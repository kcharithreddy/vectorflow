package com.vectorflow.graphql;

import com.vectorflow.graphql.GraphQlTypes.DeleteResponse;
import com.vectorflow.graphql.GraphQlTypes.SourceOutput;
import com.vectorflow.model.ChatMessage;
import com.vectorflow.model.Conversation;
import com.vectorflow.model.User;
import com.vectorflow.security.GraphQlAuthGuard;
import com.vectorflow.service.ConversationService;
import com.vectorflow.service.InputValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

/** Chat history API. Every operation is scoped to the signed-in user's own conversations. */
@Controller
@RequiredArgsConstructor
public class ConversationResolver {

    private final GraphQlAuthGuard authGuard;
    private final ConversationService conversationService;

    public record ConversationOutput(String id, String title, String createdAt, String updatedAt) {
        static ConversationOutput from(Conversation c) {
            return new ConversationOutput(c.getId().toString(), c.getTitle(),
                    c.getCreatedAt().toString(), c.getUpdatedAt().toString());
        }
    }

    public record ChatMessageOutput(String id, String role, String content, List<SourceOutput> sources,
                                    Double confidence, String createdAt) {
        static ChatMessageOutput from(ChatMessage m) {
            List<SourceOutput> sources = m.getSources() == null ? List.of() : m.getSources().stream()
                    .map(ChatMessageOutput::source).toList();
            return new ChatMessageOutput(m.getId().toString(), m.getRole(), m.getContent(), sources,
                    m.getConfidence(), m.getCreatedAt().toString());
        }

        private static SourceOutput source(Map<String, Object> s) {
            Object relevance = s.get("relevance");
            return new SourceOutput(
                    String.valueOf(s.getOrDefault("document_id", "")),
                    String.valueOf(s.getOrDefault("title", "Untitled")),
                    String.valueOf(s.getOrDefault("snippet", "")),
                    relevance instanceof Number n ? n.doubleValue() : 0.0);
        }
    }

    @QueryMapping
    public List<ConversationOutput> conversations(@Argument Integer limit) {
        User user = authGuard.requireAuth();
        int n = InputValidator.range(limit, 50, 1, 200, "limit");
        return conversationService.list(user, n).stream().map(ConversationOutput::from).toList();
    }

    @QueryMapping
    public ConversationOutput conversation(@Argument String id) {
        User user = authGuard.requireAuth();
        return ConversationOutput.from(conversationService.requireOwned(user, id));
    }

    @SchemaMapping(typeName = "Conversation", field = "messages")
    public List<ChatMessageOutput> messages(ConversationOutput conversation) {
        User user = authGuard.requireAuth();
        Conversation c = conversationService.requireOwned(user, conversation.id());
        return conversationService.messages(c).stream().map(ChatMessageOutput::from).toList();
    }

    @MutationMapping
    public ConversationOutput renameConversation(@Argument String id, @Argument String title) {
        User user = authGuard.requireAuth();
        return ConversationOutput.from(conversationService.rename(user, id, title));
    }

    @MutationMapping
    public DeleteResponse deleteConversation(@Argument String id) {
        User user = authGuard.requireAuth();
        conversationService.delete(user, id);
        return new DeleteResponse(true, "Conversation deleted");
    }

}
