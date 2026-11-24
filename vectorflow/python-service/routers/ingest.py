"""Document ingestion router."""

import logging
import uuid

from fastapi import APIRouter, Depends

from exceptions import IngestionError, InvalidRequestError, DocumentNotFoundError, to_vectorflow_error
from models.schemas import DeleteResponse, IngestRequest, IngestResponse
from security import verify_internal_api_key
from services.embedding_service import EmbeddingService, get_embedding_service
from services.ingestion import ingest_document
from services.qdrant_service import QdrantService, get_qdrant_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["ingestion"], dependencies=[Depends(verify_internal_api_key)])


@router.post("/ingest", response_model=IngestResponse)
async def ingest(
    request: IngestRequest,
    embedding_service: EmbeddingService = Depends(get_embedding_service),
    qdrant_service: QdrantService = Depends(get_qdrant_service),
) -> IngestResponse:
    """
    Ingest a document: chunk, embed, and store in Qdrant.

    1. Split the document into token windows (default 500 tokens, 50 overlap)
    2. Generate embeddings for each chunk (batched)
    3. Store vectors with metadata in Qdrant
    """
    try:
        return await ingest_document(request, embedding_service, qdrant_service)
    except Exception as exc:
        err = to_vectorflow_error(exc, IngestionError())
        if err.status_code >= 500:
            logger.exception("Document ingestion failed for title=%r", request.title)
        raise err from exc


@router.delete("/documents/{document_id}", response_model=DeleteResponse)
async def delete_document(
    document_id: str,
    qdrant_service: QdrantService = Depends(get_qdrant_service),
) -> DeleteResponse:
    """Delete a document and all its chunks from Qdrant."""
    try:
        uuid.UUID(document_id)
    except ValueError:
        raise InvalidRequestError("document_id must be a UUID") from None

    try:
        deleted = await qdrant_service.delete_by_document_id_async(document_id)
    except Exception as exc:
        logger.exception("Failed to delete document id=%s", document_id)
        raise IngestionError("Failed to delete document") from exc

    if deleted == 0:
        raise DocumentNotFoundError(document_id)
    return DeleteResponse(
        message=f"Document {document_id} deleted successfully",
        document_id=document_id,
        deleted_chunks=deleted,
    )
