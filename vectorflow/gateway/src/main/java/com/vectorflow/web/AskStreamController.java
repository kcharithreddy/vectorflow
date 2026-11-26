package com.vectorflow.web;

import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.User;
import com.vectorflow.ratelimit.RateLimiter;
import com.vectorflow.security.GraphQlAuthGuard;
import com.vectorflow.service.InputValidator;
import com.vectorflow.service.VectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming version of the {@code ask} query: answer tokens are relayed to the
 * browser as Server-Sent Events as soon as the model produces them.
 * Auth and validation happen before the stream starts, so failures there are
 * normal JSON 400/401 responses.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class AskStreamController {

    private final GraphQlAuthGuard authGuard;
    private final VectorSearchService vectorSearchService;
    private final RateLimiter rateLimiter;

    public record AskStreamRequest(String question, Integer maxSources, String category, List<String> tags) {}

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> askStream(@RequestBody AskStreamRequest body) {
        User user = authGuard.requireAuth();
        if (body == null) {
            throw GatewayException.badRequest("Request body is required");
        }
        String question = InputValidator.requireText(body.question(), "question", 4000);
        int maxSources = InputValidator.range(body.maxSources(), 5, 1, InputValidator.MAX_SOURCES, "maxSources");
        rateLimiter.checkAsk(user.getId().toString());
        log.info("Streaming ask: '{}' (maxSources={})", question, maxSources);
        return vectorSearchService.askStream(question, maxSources, filters(body));
    }

    static Map<String, Object> filters(AskStreamRequest body) {
        Map<String, Object> filters = new HashMap<>();
        if (body.category() != null && !body.category().isBlank()) {
            filters.put("category", body.category().trim());
        }
        if (body.tags() != null && !body.tags().isEmpty()) {
            filters.put("tags", body.tags());
        }
        return filters.isEmpty() ? null : filters;
    }
}
