package com.vectorflow.service;

import com.vectorflow.audit.AuditLog;
import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.Role;
import com.vectorflow.model.User;
import com.vectorflow.ratelimit.RateLimiter;
import com.vectorflow.repository.UserRepository;
import com.vectorflow.security.JwtTokenProvider;
import com.vectorflow.web.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserRepository users;
    private RateLimiter limiter;
    private AuthService auth;
    private User alice;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        limiter = mock(RateLimiter.class);
        JwtTokenProvider jwt = new JwtTokenProvider("0123456789abcdef0123456789abcdef", 60_000);
        jwt.init();
        auth = new AuthService(users, encoder, jwt, limiter, new AuditLog());
        alice = User.builder().id(UUID.randomUUID()).email("alice@example.com")
                .passwordHash(encoder.encode("blue-river-42")).role(Role.EDITOR).build();
        when(users.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(alice));
        when(users.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());
    }

    @Test
    void successfulLoginResetsFailureCounter() {
        var result = auth.login(" Alice@Example.com ", "blue-river-42", "10.0.0.1");
        assertThat(result.token()).isNotBlank();
        verify(limiter).assertLoginAllowed("alice@example.com");
        verify(limiter).resetLoginFailures("alice@example.com");
        verify(limiter, never()).recordLoginFailure(anyString());
    }

    @Test
    void wrongPasswordAndUnknownUserLookTheSameAndAreCounted() {
        assertThatThrownBy(() -> auth.login("alice@example.com", "wrong-pass-1", "ip"))
                .isInstanceOf(GatewayException.class).hasMessage("Invalid email or password");
        assertThatThrownBy(() -> auth.login("nobody@example.com", "wrong-pass-1", "ip"))
                .isInstanceOf(GatewayException.class).hasMessage("Invalid email or password");
        verify(limiter).recordLoginFailure("alice@example.com");
        verify(limiter).recordLoginFailure("nobody@example.com");
    }

    @Test
    void lockedOutAccountIsRejectedBeforeCheckingThePassword() {
        doThrow(new RateLimitExceededException("Too many failed sign-in attempts.", 600))
                .when(limiter).assertLoginAllowed("alice@example.com");
        assertThatThrownBy(() -> auth.login("alice@example.com", "blue-river-42", "ip"))
                .isInstanceOf(RateLimitExceededException.class);
        verify(limiter, never()).resetLoginFailures(anyString());
    }

    @Test
    void registrationEnforcesPolicyAndIpLimit() {
        assertThatThrownBy(() -> auth.register("new@example.com", "password123", "ip")).hasMessageContaining("too common");
        doThrow(new RateLimitExceededException("Too many accounts", 3600)).when(limiter).checkRegistration("9.9.9.9");
        assertThatThrownBy(() -> auth.register("new@example.com", "green-lake-77", "9.9.9.9"))
                .isInstanceOf(RateLimitExceededException.class);
        verify(users, never()).saveAndFlush(any());
    }
}
