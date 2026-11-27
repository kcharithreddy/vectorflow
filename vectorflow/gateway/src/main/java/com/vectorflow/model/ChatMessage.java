package com.vectorflow.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One turn in a conversation: a user question or an assistant answer (with its sources). */
@Entity
@Table(name = "messages")
@Getter
@Setter
@ToString(exclude = "conversation")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage implements Persistable<UUID> {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> sources;

    private Double confidence;

    /** documents | general - where an assistant answer came from */
    @Column(name = "answer_type", length = 20)
    private String answerType;

    /** The model that produced an assistant answer */
    @Column(length = 60)
    private String model;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        isNew = false;
    }
}
