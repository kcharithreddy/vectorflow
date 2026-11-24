package com.vectorflow.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

@Component
@Slf4j
public class JwtTokenProvider {

    static final String DEFAULT_SECRET = "vectorflow-super-secret-key-change-in-production";
    private static final int MIN_SECRET_BYTES = 32; // 256 bits, required for HS256

    private final String jwtSecret;
    private final long jwtExpirationMs;
    private SecretKey key;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.jwt.expiration}") long jwtExpirationMs
    ) {
        this.jwtSecret = jwtSecret;
        this.jwtExpirationMs = jwtExpirationMs;
    }

    @PostConstruct
    public void init() {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes long. "
                            + "Generate one with: openssl rand -base64 48");
        }
        if (DEFAULT_SECRET.equals(jwtSecret)) {
            log.warn("Using the default JWT secret - set JWT_SECRET before deploying anywhere public!");
        }
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /** Generate a signed token containing the user's email (subject) and role. */
    public String generateToken(String email, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + jwtExpirationMs))
                .signWith(key)
                .compact();
    }

    /** Parse and verify a token; empty if it is malformed, tampered with or expired. */
    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected JWT: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean validateToken(String token) {
        return parse(token).isPresent();
    }

    public String getEmailFromToken(String token) {
        return parse(token).map(Claims::getSubject).orElse(null);
    }

    public long getExpirationMs() {
        return jwtExpirationMs;
    }
}
