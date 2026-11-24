package com.vectorflow.service;

import com.sun.net.httpserver.HttpServer;
import com.vectorflow.exception.GatewayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real HTTP client against a tiny in-process server. */
class PythonServiceClientTest {

    private HttpServer server;
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst(PythonServiceClient.INTERNAL_API_KEY_HEADER));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PythonServiceClient client(String key) {
        return new PythonServiceClient(WebClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), key,
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    @Test
    void sendsInternalApiKeyAndParsesSearch() {
        body = "{\"results\":[{\"id\":\"1\",\"document_id\":\"d\",\"title\":\"T\",\"snippet\":\"s\",\"score\":0.9,"
                + "\"metadata\":{\"category\":\"c\"}}],\"total_count\":1,\"query\":\"q\"}";
        var result = client("k3y").search("q", 5, null, null);
        assertThat(seenKey.get()).isEqualTo("k3y");
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).document_id()).isEqualTo("d");
    }

    @Test
    void mapsValidationErrorsToBadRequestWithFieldName() {
        status = 422;
        body = "{\"detail\":[{\"loc\":[\"body\",\"query\"],\"msg\":\"Value error, query must not be blank\"}]}";
        assertThatThrownBy(() -> client("").search(" ", 5, null, null))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("query").contains("must not be blank");
                });
    }

    @Test
    void upstreamErrorsCarryServiceDetail() {
        status = 502;
        body = "{\"detail\":\"OpenAI authentication failed - check OPENAI_API_KEY\"}";
        assertThatThrownBy(() -> client("").ask("q", 3, null))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR);
                    assertThat(e.getMessage()).contains("OPENAI_API_KEY");
                });
    }

    @Test
    void deleteReturnsFalseOn404() {
        status = 404;
        body = "{\"detail\":\"Document not found: x\"}";
        assertThat(client("").deleteDocument("x")).isFalse();
        status = 200;
        body = "{\"message\":\"ok\"}";
        assertThat(client("").deleteDocument("x")).isTrue();
    }

    @Test
    void unreachableServiceGivesFriendlyError() {
        PythonServiceClient c = new PythonServiceClient(WebClient.builder(), "http://127.0.0.1:1", "",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThatThrownBy(() -> c.search("q", 5, null, null)).hasMessageContaining("unavailable");
        assertThat(c.healthCheck()).isFalse();
    }
}
