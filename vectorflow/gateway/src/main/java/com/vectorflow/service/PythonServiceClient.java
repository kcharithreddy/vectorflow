package com.vectorflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.dto.Metadata;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP client for the Python AI service.
 *
 * <p>Adds the shared internal API key, applies connect/response timeouts and
 * translates downstream failures into client-friendly {@link GatewayException}s.
 */
@Service
@Slf4j
public class PythonServiceClient implements VectorSearchService {

    static final String INTERNAL_API_KEY_HEADER = "X-Internal-API-Key";

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PythonServiceClient(
            WebClient.Builder builder,
            @Value("${app.python-service.url}") String pythonServiceUrl,
            @Value("${app.python-service.api-key:}") String internalApiKey,
            @Value("${app.python-service.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${app.python-service.response-timeout:PT180S}") Duration responseTimeout
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(responseTimeout);

        WebClient.Builder configured = builder
                .baseUrl(pythonServiceUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // Large documents: allow up to 16 MB request/response bodies.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));
        if (internalApiKey != null && !internalApiKey.isBlank()) {
            configured = configured.defaultHeader(INTERNAL_API_KEY_HEADER, internalApiKey);
        } else {
            log.warn("PYTHON_SERVICE_API_KEY / INTERNAL_API_KEY is not set - calls to the Python service are unauthenticated");
        }
        this.webClient = configured.build();
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.SEARCH, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.ASK, allEntries = true)
    })
    public IngestResult ingest(String documentId, String title, String content, Metadata metadata) {
        Map<String, Object> request = new HashMap<>();
        request.put("document_id", documentId);
        request.put("title", title);
        request.put("content", content);
        request.put("metadata", metadata != null ? metadata.toMap() : Map.of());

        return call("ingest", () -> webClient.post()
                .uri("/api/ingest")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(IngestResult.class)
                .block());
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.SEARCH, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.ASK, allEntries = true)
    })
    public BulkIngestResult bulkIngest(List<BulkIngestDocument> documents) {
        return call("bulk ingest", () -> webClient.post()
                .uri("/api/bulk-ingest")
                .bodyValue(Map.of("documents", documents))
                .retrieve()
                .bodyToMono(BulkIngestResult.class)
                .block());
    }

    @Override
    @Cacheable(cacheNames = CacheNames.SEARCH,
            key = "T(com.vectorflow.service.CacheNames).key(#query, #limit, #filters, #minScore)")
    public SearchResponse search(String query, int limit, Map<String, Object> filters, Double minScore) {
        Map<String, Object> request = new HashMap<>();
        request.put("query", query);
        request.put("limit", limit);
        if (filters != null && !filters.isEmpty()) {
            request.put("filters", filters);
        }
        if (minScore != null) {
            request.put("min_score", minScore);
        }
        log.debug("Searching: query='{}' limit={} filters={}", query, limit, filters);

        return call("search", () -> webClient.post()
                .uri("/api/search")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .block());
    }

    @Override
    @Cacheable(cacheNames = CacheNames.ASK,
            key = "T(com.vectorflow.service.CacheNames).key(#question, #maxSources, #filters, null)")
    public AskResult ask(String question, int maxSources, Map<String, Object> filters) {
        Map<String, Object> request = new HashMap<>();
        request.put("question", question);
        request.put("max_sources", maxSources);
        if (filters != null && !filters.isEmpty()) {
            request.put("filters", filters);
        }
        log.debug("Asking: question='{}' maxSources={}", question, maxSources);

        return call("ask", () -> webClient.post()
                .uri("/api/ask")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AskResult.class)
                .block());
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.SEARCH, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.ASK, allEntries = true)
    })
    public boolean deleteDocument(String documentId) {
        try {
            call("delete", () -> webClient.delete()
                    .uri("/api/documents/{id}", documentId)
                    .retrieve()
                    .toBodilessEntity()
                    .block());
            return true;
        } catch (GatewayException e) {
            if (e.getCause() instanceof WebClientResponseException.NotFound) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public boolean healthCheck() {
        try {
            webClient.get().uri("/health").retrieve().toBodilessEntity().block(Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            log.warn("Python service health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------

    private <T> T call(String operation, Supplier<T> request) {
        try {
            T result = request.get();
            if (result == null) {
                throw GatewayException.upstream("AI service returned an empty response for " + operation, null);
            }
            return result;
        } catch (WebClientResponseException e) {
            String detail = extractDetail(e);
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            log.debug("Python service {} failed with {}: {}", operation, e.getStatusCode(), detail);
            if (status == HttpStatus.NOT_FOUND) {
                throw new GatewayException(detail, ErrorType.NOT_FOUND, e);
            }
            if (status == HttpStatus.BAD_REQUEST || status == HttpStatus.UNPROCESSABLE_ENTITY) {
                throw new GatewayException(detail, ErrorType.BAD_REQUEST, e);
            }
            if (status == HttpStatus.UNAUTHORIZED) {
                throw GatewayException.upstream(
                        "Gateway is not authorized to call the AI service (check INTERNAL_API_KEY)", e);
            }
            throw GatewayException.upstream(detail, e);
        } catch (WebClientRequestException e) {
            throw GatewayException.upstream("AI service is unavailable - please try again shortly", e);
        } catch (IllegalStateException e) {
            // block() timeout
            throw GatewayException.upstream("AI service timed out during " + operation, e);
        }
    }

    /** Pull FastAPI's {"detail": ...} out of an error response. */
    String extractDetail(WebClientResponseException e) {
        String fallback = "AI service error (" + e.getStatusCode().value() + ")";
        try {
            JsonNode detail = objectMapper.readTree(e.getResponseBodyAsString()).path("detail");
            if (detail.isTextual()) {
                return detail.asText();
            }
            if (detail.isArray() && !detail.isEmpty()) {
                // Pydantic validation errors: [{"loc": [...], "msg": "..."}]
                JsonNode first = detail.get(0);
                String field = first.path("loc").isArray() && !first.path("loc").isEmpty()
                        ? first.path("loc").get(first.path("loc").size() - 1).asText()
                        : "request";
                return "Invalid " + field + ": " + first.path("msg").asText("validation failed");
            }
        } catch (Exception ignored) {
            // non-JSON body
        }
        return fallback;
    }
}
