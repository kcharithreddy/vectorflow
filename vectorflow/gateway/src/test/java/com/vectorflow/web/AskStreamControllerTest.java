package com.vectorflow.web;

import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.Role;
import com.vectorflow.model.User;
import com.vectorflow.security.GraphQlAuthGuard;
import com.vectorflow.service.VectorSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AskStreamControllerTest {

    private GraphQlAuthGuard guard;
    private VectorSearchService vectors;
    private AskStreamController controller;
    private com.vectorflow.ratelimit.RateLimiter limiter;

    @BeforeEach
    void setUp() {
        guard = mock(GraphQlAuthGuard.class);
        vectors = mock(VectorSearchService.class);
        limiter = mock(com.vectorflow.ratelimit.RateLimiter.class);
        controller = new AskStreamController(guard, vectors, limiter);
        when(guard.requireAuth()).thenReturn(User.builder().id(UUID.randomUUID()).role(Role.VIEWER).build());
    }

    @Test
    void viewersCanStreamAndRequestIsNormalised() {
        when(vectors.askStream(anyString(), anyInt(), any()))
                .thenReturn(Flux.just(ServerSentEvent.<String>builder().event("done").data("{}").build()));

        var events = controller.askStream(new AskStreamController.AskStreamRequest("  What is a pod?  ", null, " devops ", List.of("k8s")))
                .collectList().block();

        assertThat(events).hasSize(1);
        verify(vectors).askStream(eq("What is a pod?"), eq(5), eq(Map.of("category", "devops", "tags", List.of("k8s"))));
    }

    @Test
    void rejectsBadInputBeforeStreaming() {
        assertThatThrownBy(() -> controller.askStream(new AskStreamController.AskStreamRequest(" ", 3, null, null)))
                .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> controller.askStream(new AskStreamController.AskStreamRequest("q", 11, null, null)))
                .hasMessageContaining("maxSources");
        verifyNoInteractions(vectors);
    }

    @Test
    void anonymousUsersAreRejected() {
        when(guard.requireAuth()).thenThrow(new AccessDeniedException("Authentication required. Please provide a valid JWT token."));
        assertThatThrownBy(() -> controller.askStream(new AskStreamController.AskStreamRequest("q", 3, null, null)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(vectors);
    }

    @Test
    void rateLimitIsCheckedBeforeStreaming() {
        doThrow(new com.vectorflow.web.RateLimitExceededException("Too many questions", 30)).when(limiter).checkAsk(anyString());
        assertThatThrownBy(() -> controller.askStream(new AskStreamController.AskStreamRequest("q", 3, null, null)))
                .hasMessageContaining("Too many");
        verifyNoInteractions(vectors);
    }

    @Test
    void noFiltersMeansNull() {
        assertThat(AskStreamController.filters(new AskStreamController.AskStreamRequest("q", 3, "  ", List.of()))).isNull();
    }
}
