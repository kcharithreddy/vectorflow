"""Document ingestion pipeline shared by the single and bulk endpoints."""

import asyncio
import logging
import uuid

from config import get_settings
from exceptions import InvalidRequestError
from models.schemas import DocumentStatus, IngestRequest, IngestResponse
from services.embedding_service import EmbeddingService
from services.qdrant_service import QdrantService

logger = logging.getLogger(__name__)


async def ingest_document(
    request: IngestRequest,
    embedding_service: EmbeddingService,
    qdrant_service: QdrantService,
) -> IngestResponse:
    """Chunk -> embed -> store. Raises on failure."""
    max_chars = get_settings().max_document_chars
    if len(request.content) > max_chars:
        raise InvalidRequestError(f"Document content exceeds the {max_chars:,} character limit")

    document_id = request.document_id or str(uuid.uuid4())

    # Tokenizing large documents is CPU-bound - keep it off the event loop.
    chunks = await asyncio.to_thread(embedding_service.chunk_text, request.content, request.title)
    if not chunks:
        raise InvalidRequestError("Document content is empty or could not be processed")

    embeddings = await embedding_service.generate_embeddings_async([c.embedding_text for c in chunks])
    chunk_count = await qdrant_service.upsert_chunks_async(
        document_id=document_id,
        title=request.title,
        chunks=chunks,
        embeddings=embeddings,
        metadata=request.metadata,
    )
    logger.info("Ingested document id=%s title=%r chunks=%d", document_id, request.title, chunk_count)

    return IngestResponse(
        document_id=document_id,
        title=request.title,
        chunk_count=chunk_count,
        status=DocumentStatus.COMPLETED,
        message=f"Successfully ingested document with {chunk_count} chunk{'s' if chunk_count != 1 else ''}",
    )
