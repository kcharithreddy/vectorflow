package com.vectorflow.graphql;

import com.vectorflow.model.Document;
import com.vectorflow.model.User;
import com.vectorflow.model.dto.Metadata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Input and output types mirroring schema.graphqls. */
public final class GraphQlTypes {

    private GraphQlTypes() {}

    // ---- inputs ----

    public record FilterInput(String category, List<String> tags) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            if (category != null && !category.isBlank()) {
                map.put("category", category.trim());
            }
            if (tags != null && !tags.isEmpty()) {
                map.put("tags", tags);
            }
            return map.isEmpty() ? null : map;
        }
    }

    public record MetadataInput(String category, List<String> tags) {
        public Metadata toMetadata() {
            return Metadata.builder().category(category).tags(tags).build();
        }
    }

    public record IngestInput(String title, String content, MetadataInput metadata) {}

    public record BulkIngestInput(List<IngestInput> documents) {}

    public record RegisterInput(String email, String password) {}

    public record LoginInput(String email, String password) {}

    public record AssignRoleInput(String userId, String role) {}

    // ---- outputs ----

    public enum DocumentStatus { PROCESSING, COMPLETED, FAILED;
        static DocumentStatus from(String value) {
            try {
                return value == null ? FAILED : valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return FAILED;
            }
        }
    }

    public record MetadataOutput(String category, List<String> tags) {
        @SuppressWarnings("unchecked")
        static MetadataOutput from(Map<String, Object> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return null;
            }
            Object category = metadata.get("category");
            Object tags = metadata.get("tags");
            return new MetadataOutput(
                    category != null && !category.toString().isEmpty() ? category.toString() : null,
                    tags instanceof List<?> list ? (List<String>) list.stream().map(String::valueOf).toList() : null);
        }
    }

    public record SearchResultOutput(String id, String documentId, String title, String snippet,
                                     Double score, MetadataOutput metadata) {}

    public record SearchResponse(List<SearchResultOutput> results, Integer totalCount, String query) {}

    public record SourceOutput(String documentId, String title, String snippet, Double relevance) {}

    public record AskResponse(String answer, List<SourceOutput> sources, Double confidence, String question) {}

    public record UserOutput(String id, String email, String role, String createdAt) {
        static UserOutput from(User user) {
            return new UserOutput(
                    user.getId().toString(),
                    user.getEmail(),
                    user.getRole().name(),
                    user.getCreatedAt() != null ? user.getCreatedAt().toString() : "");
        }
    }

    public record AuthResponse(String token, UserOutput user) {}

    public record IngestResponse(String documentId, String title, Integer chunkCount,
                                 DocumentStatus status, String message) {}

    public record BulkIngestResponse(int total, int successful, int failed, List<IngestResponse> results) {}

    public record DeleteResponse(boolean success, String message) {}

    public record AssignRoleResponse(boolean success, String message, UserOutput user) {}

    public record DocumentOutput(String id, String title, Integer chunkCount, DocumentStatus status,
                                 MetadataOutput metadata, String ownerEmail, String createdAt) {
        static DocumentOutput from(Document d) {
            return new DocumentOutput(
                    d.getId().toString(),
                    d.getTitle(),
                    d.getChunkCount() != null ? d.getChunkCount() : 0,
                    DocumentStatus.from(d.getStatus()),
                    MetadataOutput.from(d.getMetadata()),
                    d.getUser() != null ? d.getUser().getEmail() : null,
                    d.getCreatedAt() != null ? d.getCreatedAt().toString() : "");
        }
    }

    public record DocumentPage(List<DocumentOutput> items, long totalCount, int page, int pageSize, boolean hasNext) {}
}
