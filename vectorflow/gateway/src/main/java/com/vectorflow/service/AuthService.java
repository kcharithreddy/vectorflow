package com.vectorflow.service;

import com.vectorflow.audit.AuditLog;
import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.Role;
import com.vectorflow.model.User;
import com.vectorflow.ratelimit.RateLimiter;
import com.vectorflow.repository.UserRepository;
import com.vectorflow.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RateLimiter rateLimiter;
    private final AuditLog audit;

    /** Lazily computed hash used to keep login timing uniform for unknown emails. */
    private volatile String dummyHash;

    @Transactional
    public AuthResult register(String rawEmail, String password, String clientIp) {
        String email = InputValidator.normalizeEmail(rawEmail);
        InputValidator.validatePassword(password, email);
        rateLimiter.checkRegistration(clientIp);

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw GatewayException.badRequest("A user with this email already exists");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .role(Role.VIEWER)
                .build();
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent registration for the same email.
            throw GatewayException.badRequest("A user with this email already exists");
        }
        audit.event("REGISTER", "email", user.getEmail(), "ip", clientIp);
        return new AuthResult(issueToken(user), user);
    }

    @Transactional(readOnly = true)
    public AuthResult login(String rawEmail, String password, String clientIp) {
        if (rawEmail == null || rawEmail.isBlank() || password == null) {
            throw GatewayException.unauthorized(INVALID_CREDENTIALS);
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        rateLimiter.assertLoginAllowed(email);

        Optional<User> found = userRepository.findByEmailIgnoreCase(email);
        boolean ok = found.isPresent() && passwordEncoder.matches(password, found.get().getPasswordHash());
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash()); // constant-ish time for unknown users
        }
        if (!ok) {
            rateLimiter.recordLoginFailure(email);
            audit.event("LOGIN_FAILURE", "email", email, "ip", clientIp);
            throw GatewayException.unauthorized(INVALID_CREDENTIALS);
        }

        User user = found.get();
        rateLimiter.resetLoginFailures(email);
        audit.event("LOGIN_SUCCESS", "email", user.getEmail(), "role", user.getRole(), "ip", clientIp);
        return new AuthResult(issueToken(user), user);
    }

    @Transactional
    public User assignRole(User admin, String userId, String roleName) {
        if (roleName == null) {
            throw GatewayException.badRequest("Role is required");
        }
        Role role;
        try {
            role = Role.valueOf(roleName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw GatewayException.badRequest("Invalid role: " + roleName + ". Valid roles: VIEWER, EDITOR, ADMIN");
        }

        User user = userRepository.findById(InputValidator.uuid(userId, "userId"))
                .orElseThrow(() -> GatewayException.notFound("User not found: " + userId));

        if (user.getId().equals(admin.getId()) && role != Role.ADMIN) {
            throw GatewayException.badRequest("Admins cannot remove their own ADMIN role");
        }

        Role previous = user.getRole();
        user.setRole(role);
        user = userRepository.save(user);
        audit.event("ROLE_CHANGE", "by", admin.getEmail(), "user", user.getEmail(), "from", previous, "to", role);
        return user;
    }

    private String issueToken(User user) {
        return jwtTokenProvider.generateToken(user.getEmail(), user.getRole().name());
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode("dummy-password-for-timing");
        }
        return dummyHash;
    }

    public record AuthResult(String token, User user) {}
}
