package com.vectorflow.service;

import com.vectorflow.model.dto.Metadata;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

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

    /**
     * Streams an answer as Server-Sent Events: {@code sources}, then {@code delta}s, then {@code done}.
     * Failures are delivered as an {@code error} event rather than breaking the stream.
     */
    Flux<ServerSentEvent<String>> askStream(String question, int maxSources, Map<String, Object> filters);

    /** @return true if the document existed in the vector store and was deleted, false if it was not found */
    boolean deleteDocument(String documentId);

    boolean healthCheck();

    /** Extract readable text from an uploaded file (PDF, DOCX, text formats). */
    ExtractResult extractText(String filename, byte[] data);

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

    record ExtractResult(
            String filename,
            String file_type,
            String text,
            Integer characters,
            Integer pages
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
