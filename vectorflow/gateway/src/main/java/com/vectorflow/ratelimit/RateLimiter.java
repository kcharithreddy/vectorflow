package com.vectorflow.ratelimit;

import com.vectorflow.audit.AuditLog;
import com.vectorflow.web.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Fixed-window rate limiter backed by Redis (INCR + EXPIRE), so limits hold
 * across multiple gateway instances.
 *
 * <p>Fails open: if Redis is unavailable, requests are allowed (and a warning is
 * logged) rather than taking the whole API down.
 */
@Component
@Slf4j
public class RateLimiter {

    private static final String PREFIX = "vectorflow:ratelimit:";

    private final StringRedisTemplate redis;
    private final AuditLog audit;
    private final boolean enabled;
    private final int askPerMinute;
    private final int uploadsPerHour;
    private final int loginFailuresPer15Min;
    private final int registrationsPerHour;

    public RateLimiter(
            StringRedisTemplate redis,
            AuditLog audit,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.ask-per-minute:30}") int askPerMinute,
            @Value("${app.rate-limit.uploads-per-hour:60}") int uploadsPerHour,
            @Value("${app.rate-limit.login-failures-per-15-min:10}") int loginFailuresPer15Min,
            @Value("${app.rate-limit.registrations-per-hour:10}") int registrationsPerHour
    ) {
        this.redis = redis;
        this.audit = audit;
        this.enabled = enabled;
        this.askPerMinute = askPerMinute;
        this.uploadsPerHour = uploadsPerHour;
        this.loginFailuresPer15Min = loginFailuresPer15Min;
        this.registrationsPerHour = registrationsPerHour;
    }

    /** Search / ask / streaming ask: protects the OpenAI bill. */
    public void checkAsk(String userKey) {
        hit("ask:" + userKey, askPerMinute, Duration.ofMinutes(1),
                "Too many questions - please wait a moment and try again");
    }

    /** Uploads and ingestion. */
    public void checkUpload(String userKey) {
        hit("upload:" + userKey, uploadsPerHour, Duration.ofHours(1),
                "Upload limit reached - please try again later");
    }

    /** Registrations per client IP. */
    public void checkRegistration(String clientIp) {
        hit("register:" + safe(clientIp), registrationsPerHour, Duration.ofHours(1),
                "Too many accounts created from this network - please try again later");
    }

    /** Throws if this email is locked out after too many failed logins. */
    public void assertLoginAllowed(String email) {
        if (!enabled) {
            return;
        }
        String key = PREFIX + "login-fail:" + safe(email);
        try {
            String value = redis.opsForValue().get(key);
            if (value != null && Long.parseLong(value) >= loginFailuresPer15Min) {
                Long ttl = redis.getExpire(key);
                audit.event("LOGIN_LOCKED", "email", email);
                throw new RateLimitExceededException(
                        "Too many failed sign-in attempts. Try again in a few minutes.", ttl == null ? 900 : ttl);
            }
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable (allowing request): {}", e.getMessage());
        }
    }

    public void recordLoginFailure(String email) {
        increment("login-fail:" + safe(email), Duration.ofMinutes(15));
    }

    public void resetLoginFailures(String email) {
        if (!enabled) {
            return;
        }
        try {
            redis.delete(PREFIX + "login-fail:" + safe(email));
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private void hit(String key, int limit, Duration window, String message) {
        Long count = increment(key, window);
        if (count != null && count > limit) {
            Long ttl = null;
            try {
                ttl = redis.getExpire(PREFIX + key);
            } catch (RuntimeException ignored) {
                // fall back to the full window
            }
            audit.event("RATE_LIMITED", "key", key);
            throw new RateLimitExceededException(message, ttl == null || ttl < 0 ? window.toSeconds() : ttl);
        }
    }

    /** INCR the counter, starting its expiry window on first use. Returns null when disabled or Redis is down. */
    private Long increment(String key, Duration window) {
        if (!enabled) {
            return null;
        }
        try {
            Long count = redis.opsForValue().increment(PREFIX + key);
            if (count != null && count == 1L) {
                redis.expire(PREFIX + key, window);
            }
            return count;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable (allowing request): {}", e.getMessage());
            return null;
        }
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }
}
