package com.vectorflow.service;

import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.ChatMessage;
import com.vectorflow.model.Conversation;
import com.vectorflow.model.Role;
import com.vectorflow.model.User;
import com.vectorflow.repository.ChatMessageRepository;
import com.vectorflow.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationServiceTest {

    private ConversationRepository conversations;
    private ChatMessageRepository messages;
    private ConversationService service;
    private final User alice = User.builder().id(UUID.randomUUID()).email("alice@x.io").role(Role.VIEWER).build();
    private Conversation conv;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        messages = mock(ChatMessageRepository.class);
        service = new ConversationService(conversations, messages);
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        conv = Conversation.builder().id(UUID.randomUUID()).user(alice).title("t").build();
        when(conversations.findByIdAndUser(conv.getId(), alice)).thenReturn(Optional.of(conv));
    }

    private ChatMessage msg(String role, String content, int minutesAgo) {
        return ChatMessage.builder().id(UUID.randomUUID()).conversation(conv).role(role).content(content)
                .createdAt(LocalDateTime.now().minusMinutes(minutesAgo)).build();
    }

    @Test
    void firstQuestionCreatesConversationWithTitleAndNoHistory() {
        var turn = service.prepareTurn(alice, null, "  How   many vacation days do we get?  ", false);
        assertThat(turn.created()).isTrue();
        assertThat(turn.history()).isEmpty();
        assertThat(turn.conversation().getTitle()).isEqualTo("How many vacation days do we get?");
        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo("user");
    }

    @Test
    void followUpGetsChronologicalHistory() {
        when(messages.findByConversationOrderByCreatedAtDesc(eq(conv), any())).thenReturn(List.of(
                msg("assistant", "Twenty days [1].", 1), msg("user", "How many vacation days?", 2)));
        var turn = service.prepareTurn(alice, conv.getId().toString(), "Can I carry them over?", false);
        assertThat(turn.created()).isFalse();
        assertThat(turn.history()).containsExactly(
                Map.of("role", "user", "content", "How many vacation days?"),
                Map.of("role", "assistant", "content", "Twenty days [1]."));
    }

    @Test
    void cannotUseSomeoneElsesConversation() {
        User mallory = User.builder().id(UUID.randomUUID()).role(Role.VIEWER).build();
        when(conversations.findByIdAndUser(conv.getId(), mallory)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.prepareTurn(mallory, conv.getId().toString(), "hi", false))
                .isInstanceOf(GatewayException.class).hasMessage("Conversation not found");
        assertThatThrownBy(() -> service.delete(mallory, conv.getId().toString())).hasMessage("Conversation not found");
        verify(messages, never()).save(any());
    }

    @Test
    void regenerateDeletesLastAnswerAndReusesLastQuestion() {
        ChatMessage lastAnswer = msg("assistant", "old answer", 1);
        when(messages.findByConversationOrderByCreatedAtDesc(eq(conv), any())).thenReturn(new ArrayList<>(List.of(
                lastAnswer, msg("user", "Q2", 2), msg("assistant", "A1", 3), msg("user", "Q1", 4))));
        var turn = service.prepareTurn(alice, conv.getId().toString(), null, true);
        verify(messages).delete(lastAnswer);
        assertThat(turn.question()).isEqualTo("Q2");
        assertThat(turn.history()).extracting(m -> m.get("content")).containsExactly("Q1", "A1");
        verify(messages, never()).save(any()); // no duplicate question stored
    }

    @Test
    void answersRememberTheirTypeAndModel() {
        when(conversations.findById(conv.getId())).thenReturn(java.util.Optional.of(conv));
        service.saveAnswer(conv.getId(), " Paris. ", null, 0.8, "general", "gpt-4o");
        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("Paris.");
        assertThat(saved.getValue().getAnswerType()).isEqualTo("general");
        assertThat(saved.getValue().getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void blankAnswersAreNotSaved() {
        service.saveAnswer(conv.getId(), "   ", null, null);
        verifyNoInteractions(messages);
    }

    @Test
    void longTitlesAreTruncated() {
        assertThat(ConversationService.titleFrom("x".repeat(200))).hasSize(ConversationService.MAX_TITLE).endsWith("…");
    }
}
