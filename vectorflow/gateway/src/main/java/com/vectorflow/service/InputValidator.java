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

    public static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw GatewayException.badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw GatewayException.badRequest("Password must be at most " + MAX_PASSWORD_LENGTH + " characters");
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
