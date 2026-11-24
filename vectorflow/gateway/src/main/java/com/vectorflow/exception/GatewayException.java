package com.vectorflow.exception;

import org.springframework.graphql.execution.ErrorType;

/**
 * An error whose message is safe to show to API clients.
 * Mapped to a GraphQL error with the given classification.
 */
public class GatewayException extends RuntimeException {

    private final ErrorType errorType;

    public GatewayException(String message, ErrorType errorType) {
        super(message);
        this.errorType = errorType;
    }

    public GatewayException(String message, ErrorType errorType, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public static GatewayException badRequest(String message) {
        return new GatewayException(message, ErrorType.BAD_REQUEST);
    }

    public static GatewayException notFound(String message) {
        return new GatewayException(message, ErrorType.NOT_FOUND);
    }

    public static GatewayException unauthorized(String message) {
        return new GatewayException(message, ErrorType.UNAUTHORIZED);
    }

    public static GatewayException forbidden(String message) {
        return new GatewayException(message, ErrorType.FORBIDDEN);
    }

    public static GatewayException upstream(String message, Throwable cause) {
        return new GatewayException(message, ErrorType.INTERNAL_ERROR, cause);
    }
}
