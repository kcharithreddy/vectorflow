package com.vectorflow.service;

import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.Document;
import com.vectorflow.model.Role;
import com.vectorflow.model.User;
import com.vectorflow.model.dto.Metadata;
import com.vectorflow.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentServiceTest {

    private VectorSearchService vectors;
    private DocumentRepository repo;
    private DocumentService service;
    private User editor;

    @BeforeEach
    void setUp() {
        vectors = mock(VectorSearchService.class);
        repo = mock(DocumentRepository.class);
        when(repo.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        service = new DocumentService(vectors, repo, new com.vectorflow.audit.AuditLog());
        editor = User.builder().id(UUID.randomUUID()).email("e@x.io").role(Role.EDITOR).build();
    }

    @Test
    void ingestUsesSameIdForPostgresAndVectorStoreAndMarksCompleted() {
        when(vectors.ingest(anyString(), eq("Title"), eq("body"), any()))
                .thenAnswer(inv -> new VectorSearchService.IngestResult(inv.getArgument(0), "Title", 3, "completed", "ok"));

        var result = service.ingest(editor, new DocumentService.DocumentInput("  Title ", "body",
                Metadata.builder().category("c").tags(List.of("t", " t ", "")).build()));

        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(repo, times(2)).save(saved.capture());
        Document doc = saved.getValue();
        assertThat(doc.getId().toString()).isEqualTo(result.document_id());
        assertThat(doc.getStatus()).isEqualTo(Document.STATUS_COMPLETED);
        assertThat(doc.getChunkCount()).isEqualTo(3);
        assertThat(doc.getMetadata()).containsEntry("category", "c").containsEntry("tags", List.of("t"));
    }

    @Test
    void ingestFailureMarksDocumentFailedAndRethrows() {
        when(vectors.ingest(anyString(), anyString(), anyString(), any()))
                .thenThrow(GatewayException.upstream("boom", null));

        assertThatThrownBy(() -> service.ingest(editor, new DocumentService.DocumentInput("T", "c", null)))
                .hasMessage("boom");
        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(repo, times(2)).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Document.STATUS_FAILED);
    }

    @Test
    void ingestRejectsBlankContentBeforeCallingBackend() {
        assertThatThrownBy(() -> service.ingest(editor, new DocumentService.DocumentInput("T", "  ", null)))
                .isInstanceOf(GatewayException.class);
        verifyNoInteractions(vectors);
    }

    @Test
    void editorCannotDeleteSomeoneElsesDocument() {
        User other = User.builder().id(UUID.randomUUID()).role(Role.EDITOR).build();
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(Document.builder().id(id).user(other).title("x").build()));

        assertThatThrownBy(() -> service.delete(editor, id.toString()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("only delete documents they ingested");
        verifyNoInteractions(vectors);
    }

    @Test
    void editorDeletesOwnDocument() {
        UUID id = UUID.randomUUID();
        Document doc = Document.builder().id(id).user(editor).title("x").build();
        when(repo.findById(id)).thenReturn(Optional.of(doc));
        when(vectors.deleteDocument(id.toString())).thenReturn(true);

        assertThat(service.delete(editor, id.toString())).isTrue();
        verify(repo).delete(doc);
    }

    @Test
    void adminDeleteOfUnknownDocumentReturnsFalse() {
        User admin = User.builder().id(UUID.randomUUID()).role(Role.ADMIN).build();
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        when(vectors.deleteDocument(id.toString())).thenReturn(false);

        assertThat(service.delete(admin, id.toString())).isFalse();
    }

    @Test
    void bulkIngestEnforcesLimits() {
        assertThatThrownBy(() -> service.bulkIngest(editor, List.of())).isInstanceOf(GatewayException.class);
        var many = java.util.Collections.nCopies(101, new DocumentService.DocumentInput("t", "c", null));
        assertThatThrownBy(() -> service.bulkIngest(editor, many)).hasMessageContaining("At most 100");
    }

    @Test
    void bulkIngestAppliesPerDocumentStatus() {
        when(vectors.bulkIngest(anyList())).thenAnswer(inv -> {
            List<VectorSearchService.BulkIngestDocument> docs = inv.getArgument(0);
            return new VectorSearchService.BulkIngestResult(2, 1, 1, List.of(
                    new VectorSearchService.IngestResult(docs.get(0).document_id(), "a", 2, "completed", "ok"),
                    new VectorSearchService.IngestResult(docs.get(1).document_id(), "b", 0, "failed", "bad")));
        });

        service.bulkIngest(editor, List.of(
                new DocumentService.DocumentInput("a", "x", null),
                new DocumentService.DocumentInput("b", "y", null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo, times(2)).saveAll(captor.capture());
        List<Document> finalDocs = captor.getValue();
        assertThat(finalDocs).extracting(Document::getStatus)
                .containsExactly(Document.STATUS_COMPLETED, Document.STATUS_FAILED);
    }
}
