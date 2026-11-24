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
import java.util.Map;
import java.util.UUID;

/**
 * Metadata row for an ingested document.
 *
 * <p>The id is assigned by the gateway <em>before</em> ingestion and passed to
 * the Python service, so the Postgres row and the Qdrant chunks always share
 * the same document id. {@link Persistable} lets Spring Data insert new rows
 * directly instead of doing a merge (SELECT + INSERT).
 */
@Entity
@Table(name = "documents")
@Getter
@Setter
@ToString(exclude = "user")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document implements Persistable<UUID> {

    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "chunk_count")
    @Builder.Default
    private Integer chunkCount = 0;

    @Column(length = 50)
    @Builder.Default
    private String status = STATUS_PROCESSING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        isNew = false;
    }
}
