"""Pydantic schemas for request/response models."""

import uuid
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, field_validator

from config import APP_VERSION


class DocumentStatus(str, Enum):
    """Document processing status."""
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"


# ============== Ingest Schemas ==============

class IngestRequest(BaseModel):
    """Request schema for document ingestion."""
    title: str = Field(..., min_length=1, max_length=500)
    content: str = Field(..., min_length=1)
    metadata: Dict[str, Any] = Field(default_factory=dict)
    document_id: Optional[str] = Field(
        default=None,
        description="Optional caller-supplied UUID; re-ingesting with the same id replaces the document.",
    )

    @field_validator("title")
    @classmethod
    def _strip_title(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("title must not be blank")
        return v

    @field_validator("content")
    @classmethod
    def _content_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("content must not be blank")
        return v

    @field_validator("metadata", mode="before")
    @classmethod
    def _metadata_default(cls, v: Any) -> Any:
        return {} if v is None else v

    @field_validator("document_id")
    @classmethod
    def _valid_uuid(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        try:
            return str(uuid.UUID(v))
        except ValueError as exc:
            raise ValueError("document_id must be a UUID") from exc


class IngestResponse(BaseModel):
    """Response schema for document ingestion."""
    document_id: str
    title: str
    chunk_count: int
    status: DocumentStatus
    message: str


# ============== Search Schemas ==============

class SearchFilter(BaseModel):
    """Filters for search queries."""
    category: Optional[str] = None
    tags: Optional[List[str]] = None
    date_from: Optional[datetime] = None
    date_to: Optional[datetime] = None

    def is_empty(self) -> bool:
        return not (self.category or self.tags or self.date_from or self.date_to)


class SearchRequest(BaseModel):
    """Request schema for vector search."""
    query: str = Field(..., min_length=1, max_length=8000)
    limit: int = Field(default=5, ge=1, le=20)
    filters: Optional[SearchFilter] = None
    min_score: Optional[float] = Field(default=None, ge=0.0, le=1.0)

    @field_validator("query")
    @classmethod
    def _query_not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("query must not be blank")
        return v


class SearchResult(BaseModel):
    """Individual search result."""
    id: str
    document_id: str
    title: str
    snippet: str
    score: float
    metadata: Dict[str, Any] = Field(default_factory=dict)


class SearchResponse(BaseModel):
    """Response schema for vector search."""
    results: List[SearchResult]
    total_count: int
    query: str


# ============== Ask (RAG) Schemas ==============

class AskRequest(BaseModel):
    """Request schema for RAG-based question answering."""
    question: str = Field(..., min_length=1, max_length=4000)
    max_sources: int = Field(default=5, ge=1, le=10)
    filters: Optional[SearchFilter] = None

    @field_validator("question")
    @classmethod
    def _question_not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("question must not be blank")
        return v


class Source(BaseModel):
    """Source document for an answer."""
    document_id: str
    title: str
    snippet: str
    relevance: float


class AskResponse(BaseModel):
    """Response schema for RAG-based answers."""
    answer: str
    sources: List[Source]
    confidence: float
    question: str


# ============== Document Management Schemas ==============

class DocumentInfo(BaseModel):
    """Information about an ingested document."""
    document_id: str
    title: str
    chunk_count: int
    created_at: str
    metadata: Dict[str, Any] = Field(default_factory=dict)


class DocumentListResponse(BaseModel):
    """Response schema for listing all documents."""
    documents: List[DocumentInfo]
    total_count: int
    page: int = 1
    page_size: int = 20


class DocumentChunk(BaseModel):
    """A single chunk from a document."""
    chunk_id: str
    chunk_index: int
    text: str
    created_at: str
    metadata: Dict[str, Any] = Field(default_factory=dict)


class DocumentDetailResponse(BaseModel):
    """Response with all chunks for a document."""
    document_id: str
    title: str
    chunks: List[DocumentChunk]
    total_chunks: int


class DeleteResponse(BaseModel):
    """Response for a document deletion."""
    message: str
    document_id: str
    deleted_chunks: int


# ============== Health Check ==============

class HealthResponse(BaseModel):
    """Health check response."""
    status: str
    qdrant_connected: bool
    openai_configured: bool
    version: str = APP_VERSION
