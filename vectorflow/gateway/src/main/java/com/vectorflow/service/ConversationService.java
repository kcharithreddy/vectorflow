package com.vectorflow.service;

import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.ChatMessage;
import com.vectorflow.model.Conversation;
import com.vectorflow.model.User;
import com.vectorflow.repository.ChatMessageRepository;
import com.vectorflow.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat history: conversations owned by a user, and the messages in them.
 *
 * <p>Each turn is recorded in two steps: {@link #prepareTurn} stores the user's
 * question and returns the recent history to send to the model, and
 * {@link #saveAnswer} stores the (possibly partial) streamed answer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    /** How many previous messages are sent to the model as context. */
    static final int HISTORY_MESSAGES = 10;
    static final int MAX_HISTORY_CHARS = 4000;
    static final int MAX_TITLE = 80;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;

    /** What the model needs for one turn. */
    public record Turn(Conversation conversation, String question, List<Map<String, String>> history, boolean created) {}

    /**
     * Start or continue a conversation.
     *
     * @param regenerate drop the last answer and answer the last question again
     */
    @Transactional
    public Turn prepareTurn(User user, String conversationId, String question, boolean regenerate) {
        if (regenerate) {
            Conversation conv = requireOwned(user, conversationId);
            List<ChatMessage> recent = recentNewestFirst(conv, HISTORY_MESSAGES + 2);
            int i = 0;
            while (i < recent.size() && ChatMessage.ASSISTANT.equals(recent.get(i).getRole())) {
                messages.delete(recent.get(i++));
            }
            if (i >= recent.size()) {
                throw GatewayException.badRequest("There is no question to regenerate");
            }
            ChatMessage lastQuestion = recent.get(i);
            List<ChatMessage> before = new ArrayList<>(recent.subList(i + 1, recent.size()));
            return new Turn(conv, lastQuestion.getContent(), toHistory(before), false);
        }

        boolean created = conversationId == null || conversationId.isBlank();
        Conversation conv = created ? create(user, question) : requireOwned(user, conversationId);
        List<Map<String, String>> history = created ? List.of() : toHistory(recentNewestFirst(conv, HISTORY_MESSAGES));
        messages.save(ChatMessage.builder()
                .id(UUID.randomUUID()).conversation(conv).role(ChatMessage.USER).content(question).build());
        touch(conv);
        return new Turn(conv, question, history, created);
    }

    /** Store an assistant answer (complete, or partial if the user pressed Stop). */
    @Transactional
    public void saveAnswer(UUID conversationId, String content, List<Map<String, Object>> sources, Double confidence) {
        if (content == null || content.isBlank()) {
            return;
        }
        conversations.findById(conversationId).ifPresent(conv -> {
            messages.save(ChatMessage.builder()
                    .id(UUID.randomUUID()).conversation(conv).role(ChatMessage.ASSISTANT)
                    .content(content.strip()).sources(sources).confidence(confidence).build());
            touch(conv);
        });
    }

    @Transactional(readOnly = true)
    public List<Conversation> list(User user, int limit) {
        return conversations.findByUserOrderByUpdatedAtDesc(user, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> messages(Conversation conversation) {
        return messages.findByConversationOrderByCreatedAtAsc(conversation);
    }

    @Transactional
    public Conversation rename(User user, String conversationId, String title) {
        Conversation conv = requireOwned(user, conversationId);
        conv.setTitle(InputValidator.requireText(title, "title", MAX_TITLE));
        conv.setUpdatedAt(LocalDateTime.now());
        return conversations.save(conv);
    }

    @Transactional
    public void delete(User user, String conversationId) {
        conversations.delete(requireOwned(user, conversationId)); // messages cascade in the DB
    }

    /** Users can only ever see their own conversations; anything else looks like "not found". */
    @Transactional(readOnly = true)
    public Conversation requireOwned(User user, String conversationId) {
        UUID id = InputValidator.uuid(conversationId, "conversationId");
        return conversations.findByIdAndUser(id, user)
                .orElseThrow(() -> GatewayException.notFound("Conversation not found"));
    }

    // ------------------------------------------------------------------

    private Conversation create(User user, String question) {
        return conversations.save(Conversation.builder()
                .id(UUID.randomUUID()).user(user).title(titleFrom(question)).build());
    }

    private void touch(Conversation conv) {
        conv.setUpdatedAt(LocalDateTime.now());
        conversations.save(conv);
    }

    private List<ChatMessage> recentNewestFirst(Conversation conv, int limit) {
        return messages.findByConversationOrderByCreatedAtDesc(conv, PageRequest.of(0, limit));
    }

    /** Newest-first messages -> chronological [{role, content}] for the model. */
    static List<Map<String, String>> toHistory(List<ChatMessage> newestFirst) {
        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessage m : newestFirst) {
            String content = m.getContent();
            if (content.length() > MAX_HISTORY_CHARS) {
                content = content.substring(0, MAX_HISTORY_CHARS);
            }
            history.add(Map.of("role", m.getRole(), "content", content));
        }
        Collections.reverse(history);
        return history;
    }

    static String titleFrom(String question) {
        String t = question.strip().replaceAll("\\s+", " ");
        return t.length() <= MAX_TITLE ? t : t.substring(0, MAX_TITLE - 1).strip() + "…";
    }
}
