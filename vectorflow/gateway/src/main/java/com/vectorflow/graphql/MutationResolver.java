package com.vectorflow.graphql;

import com.vectorflow.graphql.GraphQlTypes.*;
import com.vectorflow.config.GraphQlSecurityConfig;
import com.vectorflow.model.Role;
import com.vectorflow.model.User;
import com.vectorflow.ratelimit.RateLimiter;
import com.vectorflow.security.GraphQlAuthGuard;
import com.vectorflow.service.AuthService;
import com.vectorflow.service.DocumentService;
import com.vectorflow.service.DocumentService.DocumentInput;
import com.vectorflow.service.VectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
@Slf4j
public class MutationResolver {

    private final DocumentService documentService;
    private final GraphQlAuthGuard authGuard;
    private final AuthService authService;
    private final RateLimiter rateLimiter;

    @MutationMapping
    public IngestResponse ingest(@Argument IngestInput input) {
        User user = authGuard.requireAnyRole(Role.EDITOR, Role.ADMIN);
        rateLimiter.checkUpload(user.getId().toString());
        log.info("GraphQL ingest '{}' by {} ({})", input.title(), user.getEmail(), user.getRole());
        return toResponse(documentService.ingest(user, toDocumentInput(input)));
    }

    @MutationMapping
    public BulkIngestResponse bulkIngest(@Argument BulkIngestInput input) {
        User user = authGuard.requireAnyRole(Role.EDITOR, Role.ADMIN);
        rateLimiter.checkUpload(user.getId().toString());
        List<DocumentInput> docs = input.documents() == null ? List.of()
                : input.documents().stream().map(MutationResolver::toDocumentInput).toList();
        log.info("GraphQL bulk ingest of {} documents by {} ({})", docs.size(), user.getEmail(), user.getRole());

        var result = documentService.bulkIngest(user, docs);
        return new BulkIngestResponse(
                result.total(),
                result.successful(),
                result.failed(),
                result.results().stream().map(MutationResolver::toResponse).toList());
    }

    @MutationMapping
    public DeleteResponse deleteDocument(@Argument String documentId) {
        User user = authGuard.requireAnyRole(Role.EDITOR, Role.ADMIN);
        log.info("GraphQL delete document {} by {} ({})", documentId, user.getEmail(), user.getRole());

        boolean deleted = documentService.delete(user, documentId);
        return deleted
                ? new DeleteResponse(true, "Document deleted successfully")
                : new DeleteResponse(false, "Document not found");
    }

    @MutationMapping
    public AuthResponse register(@Argument RegisterInput input,
                                 @ContextValue(name = GraphQlSecurityConfig.CLIENT_IP, required = false) String clientIp) {
        var result = authService.register(input.email(), input.password(), clientIp);
        return new AuthResponse(result.token(), UserOutput.from(result.user()));
    }

    @MutationMapping
    public AuthResponse login(@Argument LoginInput input,
                              @ContextValue(name = GraphQlSecurityConfig.CLIENT_IP, required = false) String clientIp) {
        var result = authService.login(input.email(), input.password(), clientIp);
        return new AuthResponse(result.token(), UserOutput.from(result.user()));
    }

    @MutationMapping
    public AssignRoleResponse assignRole(@Argument AssignRoleInput input) {
        User admin = authGuard.requireRole(Role.ADMIN);
        User updated = authService.assignRole(admin, input.userId(), input.role());
        return new AssignRoleResponse(
                true,
                "Role updated to " + updated.getRole().name() + " for user " + updated.getEmail(),
                UserOutput.from(updated));
    }

    // ------------------------------------------------------------------

    private static DocumentInput toDocumentInput(IngestInput input) {
        return new DocumentInput(
                input.title(),
                input.content(),
                input.metadata() != null ? input.metadata().toMetadata() : null);
    }

    private static IngestResponse toResponse(VectorSearchService.IngestResult r) {
        return new IngestResponse(
                r.document_id(), r.title(), r.chunk_count(), DocumentStatus.from(r.status()), r.message());
    }
}
