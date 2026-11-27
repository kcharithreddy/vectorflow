package com.vectorflow.web;

import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.Conversation;
import com.vectorflow.model.Role;
import com.vectorflow.model.User;
import com.vectorflow.ratelimit.RateLimiter;
import com.vectorflow.security.GraphQlAuthGuard;
import com.vectorflow.service.ConversationService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AskStreamControllerTest {

    private GraphQlAuthGuard guard;
    private VectorSearchService vectors;
    private RateLimiter limiter;
    private ConversationService conversations;
    private AskStreamController controller;
    private final User user = User.builder().id(UUID.randomUUID()).email("u@x.io").role(Role.VIEWER).build();
    private final Conversation conv = Conversation.builder().id(UUID.randomUUID()).title("What is a pod?").build();

    @BeforeEach
    void setUp() {
        guard = mock(GraphQlAuthGuard.class);
        vectors = mock(VectorSearchService.class);
        limiter = mock(RateLimiter.class);
        conversations = mock(ConversationService.class);
        controller = new AskStreamController(guard, vectors, limiter, conversations);
        when(guard.requireAuth()).thenReturn(user);
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    @Test
    void newChatEmitsConversationFirstThenRelaysAndSavesTheAnswer() {
        List<Map<String, String>> history = List.of();
        when(conversations.prepareTurn(user, null, "What is a pod?", false))
                .thenReturn(new ConversationService.Turn(conv, "What is a pod?", history, true));
        when(vectors.askStream(eq("What is a pod?"), eq(5), any(), eq(history), eq("auto"), isNull())).thenReturn(Flux.just(
                sse("sources", "{\"sources\": []}"),
                sse("delta", "{\"text\": \"A pod \"}"),
                sse("delta", "{\"text\": \"runs containers [1].\"}"),
                sse("done", "{\"confidence\": 0.9, \"sources\": [{\"title\": \"K8s\", \"relevance\": 0.5}], \"answer_type\": \"documents\", \"model\": \"gpt-4o-mini\"}")));

        var events = controller.askStream(new AskStreamController.AskStreamRequest("  What is a pod?  ", null, " devops ", List.of("k8s")))
                .collectList().block();

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("conversation", "sources", "delta", "delta", "done");
        assertThat(events.get(0).data()).contains(conv.getId().toString()).contains("\"created\":true");
        verify(vectors).askStream(eq("What is a pod?"), eq(5), eq(Map.of("category", "devops", "tags", List.of("k8s"))), eq(history), eq("auto"), isNull());
        verify(conversations, timeout(2000)).saveAnswer(eq(conv.getId()), eq("A pod runs containers [1]."),
                argThat(s -> s != null && s.size() == 1 && "K8s".equals(s.get(0).get("title"))), eq(0.9),
                eq("documents"), eq("gpt-4o-mini"));
    }

    @Test
    void stoppedStreamSavesThePartialAnswer() {
        when(conversations.prepareTurn(any(), any(), any(), anyBoolean()))
                .thenReturn(new ConversationService.Turn(conv, "q", List.of(), false));
        when(vectors.askStream(any(), anyInt(), any(), any(), any(), any())).thenReturn(Flux.concat(
                Flux.just(sse("delta", "{\"text\": \"Partial answer\"}")), Flux.never()));

        // Client reads the conversation event + one delta, then disconnects (Stop button).
        controller.askStream(new AskStreamController.AskStreamRequest("q", 3, null, null, conv.getId().toString(), false))
                .take(2).blockLast();

        verify(conversations, timeout(2000)).saveAnswer(conv.getId(), "Partial answer", null, null, null, null);
    }

    @Test
    void regenerateDoesNotRequireAQuestion() {
        when(conversations.prepareTurn(user, conv.getId().toString(), null, true))
                .thenReturn(new ConversationService.Turn(conv, "Original question", List.of(), false));
        when(vectors.askStream(any(), anyInt(), any(), any(), any(), any())).thenReturn(Flux.empty());
        controller.askStream(new AskStreamController.AskStreamRequest(null, 3, null, null, conv.getId().toString(), true))
                .collectList().block();
        verify(vectors).askStream(eq("Original question"), eq(3), isNull(), eq(List.of()), eq("auto"), isNull());
    }

    @Test
    void rejectsBadInputBeforeStreaming() {
        assertThatThrownBy(() -> controller.askStream(new AskStreamController.AskStreamRequest(" ", 3, null, null)))
                .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> controller.askStream(new AskStreamController.AskStreamRequest("q", 11, null, null)))
                .hasMessageContaining("maxSources");
        verifyNoInteractions(vectors, conversations);
    }

    @Test
    void rateLimitIsCheckedBeforeStreaming() {
        doThrow(new RateLimitExceededException("Too many questions", 30)).when(limiter).checkAsk(anyString());
        assertThatThrownBy(() -> controller.askStream(new AskStreamController.AskStreamRequest("q", 3, null, null)))
                .hasMessageContaining("Too many");
        verifyNoInteractions(vectors, conversations);
    }

    @Test
    void anonymousUsersAreRejected() {
        when(guard.requireAuth()).thenThrow(new AccessDeniedException("Authentication required. Please provide a valid JWT token."));
        assertThatThrownBy(() -> controller.askStream(new AskStreamController.AskStreamRequest("q", 3, null, null)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(vectors);
    }

    @Test
    void modeAndModelArePassedThroughAndValidated() {
        when(conversations.prepareTurn(any(), any(), any(), anyBoolean()))
                .thenReturn(new ConversationService.Turn(conv, "q", List.of(), true));
        when(vectors.askStream(any(), anyInt(), any(), any(), any(), any())).thenReturn(Flux.empty());
        controller.askStream(new AskStreamController.AskStreamRequest("q", 3, null, null, null, false, " General ", "gpt-4o"))
                .collectList().block();
        verify(vectors).askStream(eq("q"), eq(3), isNull(), eq(List.of()), eq("general"), eq("gpt-4o"));

        assertThatThrownBy(() -> controller.askStream(new AskStreamController.AskStreamRequest("q", 3, null, null, null, false, "creative", null)))
                .hasMessageContaining("mode must be one of");
        assertThatThrownBy(() -> controller.askStream(new AskStreamController.AskStreamRequest("q", 3, null, null, null, false, null, "gpt 4o; drop")))
                .hasMessageContaining("Invalid model");
    }

    @Test
    void chatOptionsRequireLoginAndComeFromTheAiService() {
        when(vectors.chatOptions()).thenReturn(Map.of("models", List.of("gpt-4o-mini", "gpt-4o"), "default_model", "gpt-4o-mini"));
        assertThat(controller.chatOptions()).containsEntry("default_model", "gpt-4o-mini");
        when(guard.requireAuth()).thenThrow(new AccessDeniedException("Authentication required"));
        assertThatThrownBy(() -> controller.chatOptions()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noFiltersMeansNull() {
        assertThat(AskStreamController.filters(new AskStreamController.AskStreamRequest("q", 3, "  ", List.of()))).isNull();
    }
}
