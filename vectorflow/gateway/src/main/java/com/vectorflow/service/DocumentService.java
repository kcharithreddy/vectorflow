package com.vectorflow.service;

import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.Document;
import com.vectorflow.model.Role;
import com.vectorflow.model.User;
import com.vectorflow.model.dto.Metadata;
import com.vectorflow.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates document metadata in Postgres with vector storage in the Python service.
 *
 * <p>Ingestion flow: assign an id, record the document as {@code processing},
 * ingest it under that id, then mark it {@code completed} (or {@code failed}).
 * The Postgres row therefore always reflects what happened, and ids always
 * match between Postgres and Qdrant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final VectorSearchService vectorSearchService;
    private final DocumentRepository documentRepository;

    public record DocumentInput(String title, String content, Metadata metadata) {}

    public VectorSearchService.IngestResult ingest(User user, DocumentInput input) {
        DocumentInput clean = sanitize(input);
        Document document = documentRepository.save(newDocument(user, clean));

        try {
            var result = vectorSearchService.ingest(
                    document.getId().toString(), clean.title(), clean.content(), clean.metadata());
            applyResult(document, result.status(), result.chunk_count());
            documentRepository.save(document);
            log.info("Document ingested: id={} chunks={} user={}", document.getId(), result.chunk_count(), user.getEmail());
            return result;
        } catch (RuntimeException e) {
            document.setStatus(Document.STATUS_FAILED);
            documentRepository.save(document);
            throw e;
        }
    }

    public VectorSearchService.BulkIngestResult bulkIngest(User user, List<DocumentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw GatewayException.badRequest("At least one document is required");
        }
        if (inputs.size() > InputValidator.MAX_BULK_DOCUMENTS) {
            throw GatewayException.badRequest("At most " + InputValidator.MAX_BULK_DOCUMENTS + " documents per request");
        }

        List<DocumentInput> clean = inputs.stream().map(this::sanitize).toList();
        List<Document> documents = documentRepository.saveAll(
                clean.stream().map(in -> newDocument(user, in)).toList());

        List<VectorSearchService.BulkIngestDocument> payload = new ArrayList<>();
        for (int i = 0; i < clean.size(); i++) {
            DocumentInput in = clean.get(i);
            payload.add(new VectorSearchService.BulkIngestDocument(
                    documents.get(i).getId().toString(),
                    in.title(),
                    in.content(),
                    in.metadata() != null ? in.metadata().toMap() : Map.of()));
        }

        VectorSearchService.BulkIngestResult result;
        try {
            result = vectorSearchService.bulkIngest(payload);
        } catch (RuntimeException e) {
            documents.forEach(d -> d.setStatus(Document.STATUS_FAILED));
            documentRepository.saveAll(documents);
            throw e;
        }

        Map<String, Document> byId = new HashMap<>();
        documents.forEach(d -> byId.put(d.getId().toString(), d));
        for (var r : result.results()) {
            Document d = byId.get(r.document_id());
            if (d != null) {
                applyResult(d, r.status(), r.chunk_count());
            }
        }
        documentRepository.saveAll(documents);
        log.info("Bulk ingest by {}: {}/{} succeeded", user.getEmail(), result.successful(), result.total());
        return result;
    }

    /**
     * Delete a document. ADMINs may delete anything; EDITORs only documents they own.
     *
     * @return false if the document did not exist anywhere
     */
    public boolean delete(User user, String documentId) {
        UUID id = InputValidator.uuid(documentId, "documentId");
        Optional<Document> row = documentRepository.findById(id);

        boolean isAdmin = user.getRole() == Role.ADMIN;
        if (!isAdmin) {
            boolean owns = row.map(d -> d.getUser() != null && user.getId().equals(d.getUser().getId())).orElse(false);
            if (!owns) {
                throw GatewayException.forbidden("Editors can only delete documents they ingested");
            }
        }

        boolean deletedVectors = vectorSearchService.deleteDocument(id.toString());
        row.ifPresent(documentRepository::delete);
        log.info("Document {} deleted by {} (vectors={}, row={})", id, user.getEmail(), deletedVectors, row.isPresent());
        return deletedVectors || row.isPresent();
    }

    public Page<Document> list(User user, int page, int pageSize, boolean mineOnly) {
        PageRequest request = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return mineOnly ? documentRepository.findByUser(user, request) : documentRepository.findAllBy(request);
    }

    // ------------------------------------------------------------------

    private DocumentInput sanitize(DocumentInput input) {
        if (input == null) {
            throw GatewayException.badRequest("Document input is required");
        }
        String title = InputValidator.requireText(input.title(), "title", InputValidator.MAX_TITLE_LENGTH);
        if (input.content() == null || input.content().isBlank()) {
            throw GatewayException.badRequest("content must not be blank");
        }
        return new DocumentInput(title, input.content(), input.metadata());
    }

    private Document newDocument(User user, DocumentInput input) {
        Map<String, Object> metadata = input.metadata() != null ? input.metadata().toMap() : Map.of();
        return Document.builder()
                .id(UUID.randomUUID())
                .user(user)
                .title(input.title())
                .status(Document.STATUS_PROCESSING)
                .metadata(new HashMap<>(metadata))
                .build();
    }

    private static void applyResult(Document document, String status, Integer chunkCount) {
        boolean completed = Document.STATUS_COMPLETED.equalsIgnoreCase(status);
        document.setStatus(completed ? Document.STATUS_COMPLETED : Document.STATUS_FAILED);
        document.setChunkCount(chunkCount != null ? chunkCount : 0);
    }
}
