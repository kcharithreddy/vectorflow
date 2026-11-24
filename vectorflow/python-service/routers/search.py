"""Vector search and document browsing router."""

import logging
from typing import Any, Dict, Optional

from fastapi import APIRouter, Depends, Query

from exceptions import DocumentNotFoundError, SearchError, to_vectorflow_error
from models.schemas import (
    DocumentChunk,
    DocumentDetailResponse,
    DocumentInfo,
    DocumentListResponse,
    SearchFilter,
    SearchRequest,
    SearchResponse,
    SearchResult,
)
from security import verify_internal_api_key
from services.embedding_service import EmbeddingService, get_embedding_service
from services.qdrant_service import QdrantService, get_qdrant_service
from services.text_utils import make_snippet

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["search"], dependencies=[Depends(verify_internal_api_key)])


def filters_to_dict(filters: Optional[SearchFilter]) -> Optional[Dict[str, Any]]:
    if filters is None or filters.is_empty():
        return None
    return filters.model_dump(exclude_none=True)


@router.post("/search", response_model=SearchResponse)
async def search_documents(
    request: SearchRequest,
    embedding_service: EmbeddingService = Depends(get_embedding_service),
    qdrant_service: QdrantService = Depends(get_qdrant_service),
) -> SearchResponse:
    """
    Semantic similarity search.

    Embeds the query, searches Qdrant (with optional category / tag / date
    filters and a minimum score) and returns ranked results with snippets.
    """
    try:
        query_embedding = await embedding_service.generate_embedding_async(request.query)
        results = await qdrant_service.search_async(
            query_embedding=query_embedding,
            limit=request.limit,
            filters=filters_to_dict(request.filters),
            min_score=request.min_score,
        )
    except Exception as exc:
        err = to_vectorflow_error(exc, SearchError())
        if err.status_code >= 500:
            logger.exception("Search failed for query=%r", request.query)
        raise err from exc

    search_results = [
        SearchResult(
            id=r["id"],
            document_id=r["document_id"],
            title=r["title"],
            snippet=make_snippet(r.get("text", "")),
            score=round(r["score"], 4),
            metadata=r.get("metadata", {}),
        )
        for r in results
    ]
    return SearchResponse(results=search_results, total_count=len(search_results), query=request.query)


@router.get("/collection/info")
async def get_collection_info(qdrant_service: QdrantService = Depends(get_qdrant_service)):
    """Get statistics about the vector collection."""
    try:
        return await qdrant_service.get_collection_info_async()
    except Exception as exc:
        logger.exception("Failed to get collection info")
        raise SearchError("Failed to retrieve collection info") from exc


@router.get("/documents", response_model=DocumentListResponse)
async def list_documents(
    page: int = Query(default=1, ge=1, description="Page number (1-based)"),
    page_size: int = Query(default=20, ge=1, le=100, description="Items per page"),
    qdrant_service: QdrantService = Depends(get_qdrant_service),
):
    """Paginated list of ingested documents (title, chunk count, metadata)."""
    try:
        docs, total_count = await qdrant_service.list_all_documents_async(page=page, page_size=page_size)
    except Exception as exc:
        logger.exception("Failed to list documents")
        raise SearchError("Failed to list documents") from exc

    return DocumentListResponse(
        documents=[DocumentInfo(**doc) for doc in docs],
        total_count=total_count,
        page=page,
        page_size=page_size,
    )


@router.get("/documents/{document_id}", response_model=DocumentDetailResponse)
async def get_document_by_id(
    document_id: str,
    qdrant_service: QdrantService = Depends(get_qdrant_service),
):
    """Return every chunk of a document, in order."""
    try:
        chunks = await qdrant_service.get_document_chunks_async(document_id)
    except Exception as exc:
        logger.exception("Failed to get document id=%s", document_id)
        raise SearchError("Failed to retrieve document") from exc

    if not chunks:
        raise DocumentNotFoundError(document_id)

    chunk_models = [
        DocumentChunk(
            chunk_id=c["chunk_id"],
            chunk_index=c["chunk_index"],
            text=c["text"],
            created_at=c["created_at"],
            metadata=c["metadata"],
        )
        for c in chunks
    ]
    return DocumentDetailResponse(
        document_id=document_id,
        title=chunks[0].get("title", ""),
        chunks=chunk_models,
        total_chunks=len(chunk_models),
    )
