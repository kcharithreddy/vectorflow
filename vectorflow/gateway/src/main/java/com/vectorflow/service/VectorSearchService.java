package com.vectorflow.service;

import com.vectorflow.model.dto.Metadata;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Contract for vector operations delegated to the AI/ML backend.
 * Implementations may call a remote service (e.g. {@link PythonServiceClient})
 * or be stubbed for testing.
 *
 * <p>DTO component names are snake_case because they mirror the Python
 * service's JSON exactly.
 */
public interface VectorSearchService {

    /** Ingest a document under a caller-chosen id (so Postgres and Qdrant ids always match). */
    IngestResult ingest(String documentId, String title, String content, Metadata metadata);

    BulkIngestResult bulkIngest(List<BulkIngestDocument> documents);

    SearchResponse search(String query, int limit, Map<String, Object> filters, Double minScore);

    AskResult ask(String question, int maxSources, Map<String, Object> filters);

    /** @return true if the document existed in the vector store and was deleted, false if it was not found */
    boolean deleteDocument(String documentId);

    boolean healthCheck();

    // ---- DTOs shared across implementations ----

    record IngestResult(
            String document_id,
            String title,
            Integer chunk_count,
            String status,
            String message
    ) implements Serializable {}

    record SearchResponse(
            List<SearchResultDto> results,
            Integer total_count,
            String query
    ) implements Serializable {}

    record SearchResultDto(
            String id,
            String document_id,
            String title,
            String snippet,
            Double score,
            Map<String, Object> metadata
    ) implements Serializable {}

    record AskResult(
            String answer,
            List<SourceDto> sources,
            Double confidence,
            String question
    ) implements Serializable {}

    record SourceDto(
            String document_id,
            String title,
            String snippet,
            Double relevance
    ) implements Serializable {}

    record BulkIngestDocument(
            String document_id,
            String title,
            String content,
            Map<String, Object> metadata
    ) implements Serializable {}

    record BulkIngestResult(
            int total,
            int successful,
            int failed,
            List<IngestResult> results
    ) implements Serializable {}
}
