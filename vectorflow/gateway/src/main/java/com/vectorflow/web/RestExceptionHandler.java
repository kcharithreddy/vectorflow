package com.vectorflow.web;

import com.vectorflow.exception.GatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

/** JSON errors ({"error": "..."}) for the REST endpoints, mirroring the GraphQL error classifications. */
@RestControllerAdvice(basePackageClasses = RestExceptionHandler.class)
@Slf4j
public class RestExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    ResponseEntity<Map<String, String>> gateway(GatewayException e) {
        HttpStatus status = switch (e.getErrorType()) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_GATEWAY;
        };
        if (status == HttpStatus.BAD_GATEWAY) {
            log.error("Upstream failure: {}", e.getMessage(), e.getCause());
        }
        return error(status, e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> accessDenied(AccessDeniedException e) {
        boolean anonymous = e.getMessage() != null && e.getMessage().startsWith("Authentication required");
        return error(anonymous ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large (max 20 MB)");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, String>> missing(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "No file was uploaded");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
