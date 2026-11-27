package com.vectorflow.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.User;
import com.vectorflow.ratelimit.RateLimiter;
import com.vectorflow.security.GraphQlAuthGuard;
import com.vectorflow.service.ConversationService;
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
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streaming chat: answer tokens are relayed to the browser as Server-Sent Events
 * as soon as the model produces them.
 *
 * <p>Events: {@code conversation} (id + title, first), {@code sources}, many
 * {@code delta}, then {@code done}; failures arrive as {@code error}. The turn is
 * stored in the user's chat history; if the client stops early, the partial
 * answer is saved. Auth, validation and rate limits run before the stream starts,
 * so failures there are normal JSON 400/401/404/429 responses.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class AskStreamController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GraphQlAuthGuard authGuard;
    private final VectorSearchService vectorSearchService;
    private final RateLimiter rateLimiter;
    private final ConversationService conversationService;

    public record AskStreamRequest(String question, Integer maxSources, String category, List<String> tags,
                                   String conversationId, Boolean regenerate) {
        public AskStreamRequest(String question, Integer maxSources, String category, List<String> tags) {
            this(question, maxSources, category, tags, null, false);
        }
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> askStream(@RequestBody AskStreamRequest body) {
        User user = authGuard.requireAuth();
        if (body == null) {
            throw GatewayException.badRequest("Request body is required");
        }
        boolean regenerate = Boolean.TRUE.equals(body.regenerate());
        String question = regenerate ? null : InputValidator.requireText(body.question(), "question", 4000);
        int maxSources = InputValidator.range(body.maxSources(), 5, 1, InputValidator.MAX_SOURCES, "maxSources");
        rateLimiter.checkAsk(user.getId().toString());

        var turn = conversationService.prepareTurn(user, body.conversationId(), question, regenerate);
        UUID conversationId = turn.conversation().getId();
        log.info("Chat turn in {} (history={}, regenerate={})", conversationId, turn.history().size(), regenerate);

        StringBuilder answer = new StringBuilder();
        AtomicReference<JsonNode> done = new AtomicReference<>();

        Flux<ServerSentEvent<String>> header = Flux.just(event("conversation", Map.of(
                "id", conversationId.toString(),
                "title", turn.conversation().getTitle(),
                "created", turn.created())));

        Flux<ServerSentEvent<String>> answerStream = vectorSearchService
                .askStream(turn.question(), maxSources, filters(body), turn.history())
                .doOnNext(e -> {
                    try {
                        if ("delta".equals(e.event())) {
                            answer.append(JSON.readTree(e.data()).path("text").asText(""));
                        } else if ("done".equals(e.event())) {
                            done.set(JSON.readTree(e.data()));
                        }
                    } catch (Exception ex) {
                        log.debug("Unparseable stream event: {}", ex.getMessage());
                    }
                })
                // Persist when the stream ends for any reason (completed, error, or client pressed Stop).
                .doFinally(signal -> Schedulers.boundedElastic().schedule(() -> save(conversationId, answer, done.get())));

        return header.concatWith(answerStream);
    }

    private void save(UUID conversationId, StringBuilder answer, JsonNode done) {
        try {
            List<Map<String, Object>> sources = null;
            Double confidence = null;
            if (done != null) {
                sources = JSON.convertValue(done.path("sources"), new TypeReference<>() {});
                confidence = done.path("confidence").isNumber() ? done.path("confidence").asDouble() : null;
            }
            conversationService.saveAnswer(conversationId, answer.toString(), sources, confidence);
        } catch (Exception e) {
            log.error("Failed to save answer for conversation {}", conversationId, e);
        }
    }

    static ServerSentEvent<String> event(String name, Map<String, Object> data) {
        try {
            return ServerSentEvent.<String>builder().event(name).data(JSON.writeValueAsString(data)).build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
