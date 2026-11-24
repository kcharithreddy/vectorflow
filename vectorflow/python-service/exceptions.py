"""Custom exception types for structured error handling."""

import logging

import openai

logger = logging.getLogger(__name__)


class VectorFlowError(Exception):
    """Base exception for VectorFlow operations."""

    def __init__(self, message: str, status_code: int = 500):
        self.message = message
        self.status_code = status_code
        super().__init__(message)


class IngestionError(VectorFlowError):
    """Raised when document ingestion fails."""

    def __init__(self, message: str = "Document ingestion failed"):
        super().__init__(message, status_code=500)


class SearchError(VectorFlowError):
    """Raised when vector search fails."""

    def __init__(self, message: str = "Search operation failed"):
        super().__init__(message, status_code=500)


class DocumentNotFoundError(VectorFlowError):
    """Raised when a requested document cannot be found."""

    def __init__(self, document_id: str):
        super().__init__(f"Document not found: {document_id}", status_code=404)


class LLMError(VectorFlowError):
    """Raised when the LLM/RAG pipeline fails."""

    def __init__(self, message: str = "Failed to generate answer"):
        super().__init__(message, status_code=500)


class EmbeddingError(VectorFlowError):
    """Raised when embedding generation fails."""

    def __init__(self, message: str = "Embedding generation failed"):
        super().__init__(message, status_code=500)


class InvalidRequestError(VectorFlowError):
    """Raised for invalid client input that passes schema validation."""

    def __init__(self, message: str = "Invalid request"):
        super().__init__(message, status_code=400)


class UpstreamServiceError(VectorFlowError):
    """Raised when a dependency (OpenAI, Qdrant) is unavailable or misconfigured."""

    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message, status_code=status_code)


def translate_openai_error(exc: Exception) -> VectorFlowError | None:
    """Map OpenAI SDK exceptions to client-meaningful errors (or None if not OpenAI)."""
    if isinstance(exc, openai.AuthenticationError):
        return UpstreamServiceError(
            "OpenAI authentication failed - check OPENAI_API_KEY", status_code=502
        )
    if isinstance(exc, openai.RateLimitError):
        return UpstreamServiceError(
            "OpenAI rate limit or quota exceeded - try again later", status_code=503
        )
    if isinstance(exc, (openai.APIConnectionError, openai.APITimeoutError)):
        return UpstreamServiceError("Could not reach OpenAI - try again later", status_code=503)
    if isinstance(exc, openai.BadRequestError):
        return UpstreamServiceError("OpenAI rejected the request", status_code=502)
    if isinstance(exc, openai.APIError):
        return UpstreamServiceError("OpenAI request failed", status_code=502)
    return None


def to_vectorflow_error(exc: Exception, fallback: VectorFlowError) -> VectorFlowError:
    """Return ``exc`` if already a VectorFlowError, a translated upstream error, or ``fallback``."""
    if isinstance(exc, VectorFlowError):
        return exc
    return translate_openai_error(exc) or fallback
