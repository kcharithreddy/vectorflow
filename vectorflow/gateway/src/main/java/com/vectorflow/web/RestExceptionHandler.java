package com.vectorflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vectorflow.exception.GatewayException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JSON errors ({"error": "..."}) for the REST endpoints, mirroring the GraphQL error classifications.
 *
 * <p>The body is written directly rather than through content negotiation: a client that
 * asked for {@code text/event-stream} (the streaming ask endpoint) must still receive a
 * readable 400/401/403 instead of a masked "not acceptable" error.
 */
@RestControllerAdvice(basePackageClasses = RestExceptionHandler.class)
@Slf4j
public class RestExceptionHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ExceptionHandler(GatewayException.class)
    void gateway(GatewayException e, HttpServletResponse response) throws IOException {
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
        write(response, status, e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    void accessDenied(AccessDeniedException e, HttpServletResponse response) throws IOException {
        boolean anonymous = e.getMessage() != null && e.getMessage().startsWith("Authentication required");
        write(response, anonymous ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    void tooLarge(MaxUploadSizeExceededException e, HttpServletResponse response) throws IOException {
        write(response, HttpStatus.PAYLOAD_TOO_LARGE, "File is too large (max 20 MB)");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    void missing(Exception e, HttpServletResponse response) throws IOException {
        write(response, HttpStatus.BAD_REQUEST, "No file was uploaded");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    void rateLimited(RateLimitExceededException e, HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        write(response, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    static void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(
                Map.of("error", message == null ? status.getReasonPhrase() : message)));
        response.getWriter().flush();
    }
}
