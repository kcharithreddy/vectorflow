"""Bulk document ingestion router."""

import asyncio
import logging
import uuid
from typing import List

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from config import get_settings
from exceptions import InvalidRequestError, VectorFlowError, translate_openai_error
from models.schemas import DocumentStatus, IngestRequest, IngestResponse
from security import verify_internal_api_key
from services.embedding_service import EmbeddingService, get_embedding_service
from services.ingestion import ingest_document
from services.qdrant_service import QdrantService, get_qdrant_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["bulk-ingestion"], dependencies=[Depends(verify_internal_api_key)])


class BulkIngestRequest(BaseModel):
    """Request schema for bulk document ingestion."""
    documents: List[IngestRequest] = Field(..., min_length=1)


class BulkIngestResponse(BaseModel):
    """Response schema for bulk ingestion."""
    total: int
    successful: int
    failed: int
    results: List[IngestResponse]


@router.post("/bulk-ingest", response_model=BulkIngestResponse)
async def bulk_ingest_documents(
    request: BulkIngestRequest,
    embedding_service: EmbeddingService = Depends(get_embedding_service),
    qdrant_service: QdrantService = Depends(get_qdrant_service),
) -> BulkIngestResponse:
    """
    Ingest many documents concurrently.

    Documents are processed in parallel with bounded concurrency
    (``BULK_INGEST_CONCURRENCY``) so large batches don't trip OpenAI rate
    limits. One failing document never fails the whole batch - each result
    reports its own status.
    """
    settings = get_settings()
    if len(request.documents) > settings.max_bulk_documents:
        raise InvalidRequestError(
            f"Too many documents: {len(request.documents)} (max {settings.max_bulk_documents})"
        )

    semaphore = asyncio.Semaphore(max(1, settings.bulk_ingest_concurrency))

    async def ingest_one(doc: IngestRequest) -> IngestResponse:
        async with semaphore:
            try:
                return await ingest_document(doc, embedding_service, qdrant_service)
            except Exception as exc:
                known = exc if isinstance(exc, VectorFlowError) else translate_openai_error(exc)
                if known is None:
                    logger.exception("Bulk ingest failed for title=%r", doc.title)
                return IngestResponse(
                    document_id=doc.document_id or str(uuid.uuid4()),
                    title=doc.title,
                    chunk_count=0,
                    status=DocumentStatus.FAILED,
                    message=known.message if known else "Document ingestion failed",
                )

    results = await asyncio.gather(*(ingest_one(doc) for doc in request.documents))
    successful = sum(1 for r in results if r.status == DocumentStatus.COMPLETED)

    return BulkIngestResponse(
        total=len(results),
        successful=successful,
        failed=len(results) - successful,
        results=list(results),
    )
