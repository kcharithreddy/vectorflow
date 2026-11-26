package com.vectorflow.web;

import com.vectorflow.model.Role;
import com.vectorflow.model.User;
import com.vectorflow.security.GraphQlAuthGuard;
import com.vectorflow.service.DocumentService;
import com.vectorflow.service.VectorSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentUploadControllerTest {

    private GraphQlAuthGuard guard;
    private VectorSearchService vectors;
    private DocumentService documents;
    private DocumentUploadController controller;
    private final User editor = User.builder().id(UUID.randomUUID()).email("e@x.io").role(Role.EDITOR).build();

    @BeforeEach
    void setUp() {
        guard = mock(GraphQlAuthGuard.class);
        vectors = mock(VectorSearchService.class);
        documents = mock(DocumentService.class);
        controller = new DocumentUploadController(guard, vectors, documents, mock(com.vectorflow.ratelimit.RateLimiter.class));
    }

    @Test
    void uploadExtractsThenIngestsWithFilenameTitleAndMetadata() throws Exception {
        when(guard.requireAnyRole(Role.EDITOR, Role.ADMIN)).thenReturn(editor);
        when(vectors.extractText(eq("lecture_notes-week1.pdf"), any()))
                .thenReturn(new VectorSearchService.ExtractResult("lecture_notes-week1.pdf", "pdf", "Some text", 9, 3));
        when(documents.ingest(eq(editor), any())).thenReturn(
                new VectorSearchService.IngestResult("doc-1", "lecture notes week1", 1, "completed", "ok"));

        var res = controller.upload(new MockMultipartFile("file", "lecture_notes-week1.pdf", "application/pdf", new byte[]{1, 2}),
                null, "school", "ml, week1, ml");

        ArgumentCaptor<DocumentService.DocumentInput> input = ArgumentCaptor.forClass(DocumentService.DocumentInput.class);
        verify(documents).ingest(eq(editor), input.capture());
        assertThat(input.getValue().title()).isEqualTo("lecture notes week1");
        assertThat(input.getValue().content()).isEqualTo("Some text");
        assertThat(input.getValue().metadata().getCategory()).isEqualTo("school");
        assertThat(input.getValue().metadata().getTags()).containsExactly("ml", "week1");
        assertThat(res.status()).isEqualTo("COMPLETED");
        assertThat(res.pages()).isEqualTo(3);
        assertThat(res.fileType()).isEqualTo("pdf");
    }

    @Test
    void viewersCannotUploadAndNothingIsExtracted() {
        when(guard.requireAnyRole(Role.EDITOR, Role.ADMIN)).thenThrow(new AccessDeniedException("Access denied"));
        assertThatThrownBy(() -> controller.upload(new MockMultipartFile("file", "a.pdf", null, new byte[]{1}), null, null, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(vectors, documents);
    }

    @Test
    void emptyFileRejected() {
        when(guard.requireAnyRole(Role.EDITOR, Role.ADMIN)).thenReturn(editor);
        assertThatThrownBy(() -> controller.upload(new MockMultipartFile("file", "a.pdf", null, new byte[0]), null, null, null))
                .hasMessageContaining("empty");
    }

    @Test
    void helpers() {
        assertThat(DocumentUploadController.titleFromFilename("C:\\\\docs\\\\my_resume-2026.docx")).isEqualTo("my resume 2026");
        assertThat(DocumentUploadController.titleFromFilename(".pdf")).isEqualTo(".pdf");
        assertThat(DocumentUploadController.titleFromFilename("___.txt")).isEqualTo("Untitled document");
        assertThat(DocumentUploadController.parseTags(" a, ,b,a ")).isEqualTo(List.of("a", "b"));
        assertThat(DocumentUploadController.parseTags(null)).isEmpty();
    }
}
