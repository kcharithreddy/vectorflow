package com.vectorflow.ratelimit;

import com.vectorflow.audit.AuditLog;
import com.vectorflow.web.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RateLimiterTest {

    private final Map<String, Long> store = new HashMap<>();
    private StringRedisTemplate redis;
    private RateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenAnswer(inv -> store.merge(inv.getArgument(0), 1L, Long::sum));
        when(ops.get(anyString())).thenAnswer(inv -> {
            Long v = store.get(inv.<String>getArgument(0));
            return v == null ? null : v.toString();
        });
        when(redis.delete(anyString())).thenAnswer(inv -> store.remove(inv.<String>getArgument(0)) != null);
        when(redis.getExpire(anyString())).thenReturn(42L);
        limiter = new RateLimiter(redis, new AuditLog(), true, 3, 2, 3, 1);
    }

    @Test
    void askLimitIsPerUserAndReportsRetryAfter() {
        for (int i = 0; i < 3; i++) limiter.checkAsk("u1");
        assertThatThrownBy(() -> limiter.checkAsk("u1"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(42));
        limiter.checkAsk("u2"); // other users unaffected
    }

    @Test
    void firstHitStartsTheWindow() {
        limiter.checkUpload("u1");
        verify(redis).expire("vectorflow:ratelimit:upload:u1", Duration.ofHours(1));
        limiter.checkUpload("u1");
        verify(redis, times(1)).expire(anyString(), any(Duration.class));
    }

    @Test
    void loginLockoutAfterRepeatedFailuresAndResetOnSuccess() {
        for (int i = 0; i < 3; i++) {
            limiter.assertLoginAllowed("A@B.co");
            limiter.recordLoginFailure("A@B.co");
        }
        assertThatThrownBy(() -> limiter.assertLoginAllowed("a@b.co")).hasMessageContaining("Too many failed");
        limiter.resetLoginFailures("a@b.co");
        limiter.assertLoginAllowed("a@b.co");
    }

    @Test
    void registrationLimitPerIp() {
        limiter.checkRegistration("10.0.0.1");
        assertThatThrownBy(() -> limiter.checkRegistration("10.0.0.1")).isInstanceOf(RateLimitExceededException.class);
        limiter.checkRegistration("10.0.0.2");
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        for (int i = 0; i < 10; i++) limiter.checkAsk("u1");
        limiter.assertLoginAllowed("a@b.co");
    }

    @Test
    void disabledMeansNoRedisCalls() {
        StringRedisTemplate untouched = mock(StringRedisTemplate.class);
        RateLimiter off = new RateLimiter(untouched, new AuditLog(), false, 1, 1, 1, 1);
        for (int i = 0; i < 5; i++) off.checkAsk("u");
        verifyNoInteractions(untouched);
    }

    private static <T> T any(Class<T> type) {
        return org.mockito.ArgumentMatchers.any(type);
    }
}
