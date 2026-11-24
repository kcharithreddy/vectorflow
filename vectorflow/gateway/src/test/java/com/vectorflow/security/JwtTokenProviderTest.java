package com.vectorflow.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static JwtTokenProvider provider(String secret, long ttlMs) {
        JwtTokenProvider p = new JwtTokenProvider(secret, ttlMs);
        p.init();
        return p;
    }

    @Test
    void roundTrip() {
        JwtTokenProvider p = provider("0123456789abcdef0123456789abcdef", 60_000);
        String token = p.generateToken("a@b.co", "EDITOR");
        assertThat(p.validateToken(token)).isTrue();
        assertThat(p.getEmailFromToken(token)).isEqualTo("a@b.co");
        assertThat(p.parse(token).orElseThrow().get("role", String.class)).isEqualTo("EDITOR");
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> provider("too-short", 1000)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        String token = provider("0123456789abcdef0123456789abcdef", 60_000).generateToken("a@b.co", "ADMIN");
        assertThat(provider("fedcba9876543210fedcba9876543210", 60_000).validateToken(token)).isFalse();
    }

    @Test
    void rejectsExpiredAndGarbageTokens() {
        JwtTokenProvider p = provider("0123456789abcdef0123456789abcdef", -1_000);
        assertThat(p.validateToken(p.generateToken("a@b.co", "VIEWER"))).isFalse();
        assertThat(p.validateToken("garbage")).isFalse();
        assertThat(p.getEmailFromToken("garbage")).isNull();
    }
}
