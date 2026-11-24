package com.vectorflow.web;

import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.Role;
import com.vectorflow.model.User;
import com.vectorflow.model.dto.Metadata;
import com.vectorflow.security.GraphQlAuthGuard;
import com.vectorflow.service.DocumentService;
import com.vectorflow.service.InputValidator;
import com.vectorflow.service.VectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * File upload (PDF, DOCX, text). GraphQL isn't a good fit for binary uploads,
 * so this is a small REST endpoint that reuses the same auth guard and
 * ingestion pipeline as the {@code ingest} mutation:
 * upload -> text extraction (Python service) -> chunk + embed + store.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentUploadController {

    private final GraphQlAuthGuard authGuard;
    private final VectorSearchService vectorSearchService;
    private final DocumentService documentService;

    public record UploadResponse(
            String documentId,
            String title,
            int chunkCount,
            String status,
            String message,
            String fileType,
            Integer pages,
            int characters
    ) {}

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tags", required = false) String tags
    ) throws IOException {
        User user = authGuard.requireAnyRole(Role.EDITOR, Role.ADMIN);
        if (file.isEmpty()) {
            throw GatewayException.badRequest("The file is empty");
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        log.info("Upload '{}' ({} bytes) by {} ({})", filename, file.getSize(), user.getEmail(), user.getRole());

        var extracted = vectorSearchService.extractText(filename, file.getBytes());

        String finalTitle = title != null && !title.isBlank() ? title : titleFromFilename(filename);
        Metadata metadata = Metadata.builder().category(category).tags(parseTags(tags)).build();
        var result = documentService.ingest(user,
                new DocumentService.DocumentInput(
                        InputValidator.requireText(finalTitle, "title", InputValidator.MAX_TITLE_LENGTH),
                        extracted.text(),
                        metadata));

        return new UploadResponse(
                result.document_id(),
                result.title(),
                result.chunk_count() != null ? result.chunk_count() : 0,
                result.status() != null ? result.status().toUpperCase() : "FAILED",
                result.message(),
                extracted.file_type(),
                extracted.pages(),
                extracted.characters() != null ? extracted.characters() : extracted.text().length());
    }

    /** "my_lecture-notes.v2.pdf" -> "my lecture notes.v2" */
    static String titleFromFilename(String filename) {
        String base = filename.replaceAll("^.*[/\\\\]", "");
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[_-]+", " ").trim();
        return base.isEmpty() ? "Untitled document" : base;
    }

    static List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(t -> !t.isEmpty()).distinct().toList();
    }
}
