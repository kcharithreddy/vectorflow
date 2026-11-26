package com.vectorflow.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

    @Test
    void trustsForwardedForOnlyFromOurProxy() {
        // via nginx (private address): last X-Forwarded-For entry is the real client
        assertThat(ClientIp.resolve("172.18.0.7", "6.6.6.6, 203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(ClientIp.resolve("127.0.0.1", "203.0.113.9")).isEqualTo("203.0.113.9");
        // direct from the internet: header is ignored, so it can't be spoofed
        assertThat(ClientIp.resolve("198.51.100.4", "1.2.3.4")).isEqualTo("198.51.100.4");
        assertThat(ClientIp.resolve("198.51.100.4", null)).isEqualTo("198.51.100.4");
        assertThat(ClientIp.resolve(null, null)).isEqualTo("unknown");
        assertThat(ClientIp.isTrustedProxy("evil.example.com")).isFalse();
    }
}
