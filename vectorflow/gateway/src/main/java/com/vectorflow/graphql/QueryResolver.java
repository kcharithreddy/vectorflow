package com.vectorflow.graphql;

import com.vectorflow.graphql.GraphQlTypes.*;
import com.vectorflow.exception.GatewayException;
import com.vectorflow.model.Document;
import com.vectorflow.model.User;
import com.vectorflow.security.GraphQlAuthGuard;
import com.vectorflow.service.DocumentService;
import com.vectorflow.service.InputValidator;
import com.vectorflow.service.VectorSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
@Slf4j
public class QueryResolver {

    private final VectorSearchService vectorSearchService;
    private final DocumentService documentService;
    private final GraphQlAuthGuard authGuard;

    /** Semantic search over all ingested documents. */
    @QueryMapping
    public SearchResponse search(
            @Argument String query,
            @Argument Integer limit,
            @Argument FilterInput filters,
            @Argument Double minScore
    ) {
        authGuard.requireAuth();
        String q = InputValidator.requireText(query, "query", 8000);
        int l = InputValidator.range(limit, 5, 1, InputValidator.MAX_SEARCH_LIMIT, "limit");
        if (minScore != null && (minScore < 0 || minScore > 1)) {
            throw GatewayException.badRequest("minScore must be between 0 and 1");
        }
        log.info("GraphQL search: '{}' (limit={})", q, l);

        var result = vectorSearchService.search(q, l, filters != null ? filters.toMap() : null, minScore);
        List<SearchResultOutput> results = result.results().stream()
                .map(r -> new SearchResultOutput(
                        r.id(), r.document_id(), r.title(), r.snippet(), r.score(),
                        MetadataOutput.from(r.metadata())))
                .toList();
        return new SearchResponse(results, result.total_count(), result.query());
    }

    /** Ask a question and get an AI-generated answer grounded in the documents. */
    @QueryMapping
    public AskResponse ask(
            @Argument String question,
            @Argument Integer maxSources,
            @Argument FilterInput filters
    ) {
        authGuard.requireAuth();
        String q = InputValidator.requireText(question, "question", 4000);
        int n = InputValidator.range(maxSources, 5, 1, InputValidator.MAX_SOURCES, "maxSources");
        log.info("GraphQL ask: '{}' (maxSources={})", q, n);

        var result = vectorSearchService.ask(q, n, filters != null ? filters.toMap() : null);
        List<SourceOutput> sources = result.sources().stream()
                .map(s -> new SourceOutput(s.document_id(), s.title(), s.snippet(), s.relevance()))
                .toList();
        return new AskResponse(result.answer(), sources, result.confidence(), result.question());
    }

    /** Paginated document list (newest first). Pass mine: true to see only your own uploads. */
    @QueryMapping
    public DocumentPage documents(
            @Argument Integer page,
            @Argument Integer pageSize,
            @Argument Boolean mine
    ) {
        User user = authGuard.requireAuth();
        int p = InputValidator.range(page, 1, 1, Integer.MAX_VALUE, "page");
        int size = InputValidator.range(pageSize, 20, 1, 100, "pageSize");

        Page<Document> result = documentService.list(user, p, size, Boolean.TRUE.equals(mine));
        return new DocumentPage(
                result.getContent().stream().map(DocumentOutput::from).toList(),
                result.getTotalElements(),
                p,
                size,
                result.hasNext());
    }

    /** Current user, or null for anonymous requests. */
    @QueryMapping
    public UserOutput me() {
        User user = authGuard.optionalAuth();
        return user == null ? null : UserOutput.from(user);
    }
}
