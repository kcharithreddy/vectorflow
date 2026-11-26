package com.vectorflow.service;

import com.vectorflow.exception.GatewayException;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validation helpers for GraphQL inputs; failures become BAD_REQUEST errors. */
public final class InputValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 72; // BCrypt only uses the first 72 bytes
    public static final int MAX_TITLE_LENGTH = 500;
    public static final int MAX_SEARCH_LIMIT = 20;
    public static final int MAX_SOURCES = 10;
    public static final int MAX_BULK_DOCUMENTS = 100;

    private InputValidator() {}

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw GatewayException.badRequest("Email is required");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !EMAIL.matcher(normalized).matches()) {
            throw GatewayException.badRequest("Email address is not valid");
        }
        return normalized;
    }

    /** Very common passwords that are rejected outright (a small sample of breach lists). */
    private static final java.util.Set<String> COMMON_PASSWORDS = java.util.Set.of(
            "password", "password1", "password123", "12345678", "123456789", "1234567890", "qwerty123",
            "qwertyuiop", "iloveyou", "11111111", "00000000", "abc12345", "letmein1", "welcome1",
            "admin123", "passw0rd", "football1", "monkey123", "dragon123", "sunshine1");

    public static void validatePassword(String password) {
        validatePassword(password, null);
    }

    /**
     * Password policy: 8-72 characters, at least one letter and one digit, not a
     * well-known password, and not containing the user's email name.
     */
    public static void validatePassword(String password, String email) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw GatewayException.badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw GatewayException.badRequest("Password must be at most " + MAX_PASSWORD_LENGTH + " characters");
        }
        if (!password.matches(".*\\p{L}.*") || !password.matches(".*\\d.*")) {
            throw GatewayException.badRequest("Password must contain at least one letter and one number");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON_PASSWORDS.contains(lower)) {
            throw GatewayException.badRequest("This password is too common - please choose another");
        }
        if (email != null) {
            String name = email.split("@")[0].toLowerCase(Locale.ROOT);
            if (name.length() >= 4 && lower.contains(name)) {
                throw GatewayException.badRequest("Password must not contain your email name");
            }
        }
    }

    public static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw GatewayException.badRequest(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (maxLength > 0 && trimmed.length() > maxLength) {
            throw GatewayException.badRequest(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    public static int range(Integer value, int defaultValue, int min, int max, String field) {
        int v = value == null ? defaultValue : value;
        if (v < min || v > max) {
            throw GatewayException.badRequest(field + " must be between " + min + " and " + max);
        }
        return v;
    }

    public static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException e) {
            throw GatewayException.badRequest(field + " must be a valid UUID");
        }
    }
}
