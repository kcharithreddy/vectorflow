"""Embedding generation and text chunking.

Chunking is token-based (tiktoken ``cl100k_base``, the tokenizer used by the
OpenAI embedding models) with a configurable overlap.  Embeddings are
requested in batches so very large documents never exceed the per-request
input limits of the embeddings API.
"""

import asyncio
import logging
from dataclasses import dataclass
from functools import lru_cache
from typing import List, Optional, Protocol, Sequence

from openai import AsyncOpenAI

from config import Settings, get_settings

logger = logging.getLogger(__name__)

TOKENIZER_ENCODING = "cl100k_base"


class Tokenizer(Protocol):
    def encode(self, text: str) -> List[int]: ...
    def decode(self, tokens: Sequence[int]) -> str: ...


@dataclass(frozen=True)
class Chunk:
    """A piece of a document.

    ``text`` is the raw chunk content (what users see); ``embedding_text`` is
    what gets embedded - the content prefixed with the document title so the
    vector carries document-level context.
    """
    text: str
    embedding_text: str
    token_count: int


def title_prefix(title: str) -> str:
    return f"[Document: {title}]\n" if title else ""


class EmbeddingService:
    """Service for generating embeddings and chunking text."""

    def __init__(
        self,
        settings: Optional[Settings] = None,
        client: Optional[AsyncOpenAI] = None,
        tokenizer: Optional[Tokenizer] = None,
    ):
        settings = settings or get_settings()
        if settings.chunk_size <= 0:
            raise ValueError("chunk_size must be positive")
        if not 0 <= settings.chunk_overlap < settings.chunk_size:
            raise ValueError("chunk_overlap must be >= 0 and smaller than chunk_size")

        self.client = client or AsyncOpenAI(
            api_key=settings.openai_api_key or "missing-api-key",
            timeout=settings.openai_timeout_seconds,
            max_retries=settings.openai_max_retries,
        )
        self.model = settings.embedding_model
        self.dimensions = settings.embedding_dimensions
        self.batch_size = max(1, settings.embedding_batch_size)
        self.chunk_size = settings.chunk_size
        self.chunk_overlap = settings.chunk_overlap
        self._tokenizer = tokenizer

    @property
    def tokenizer(self) -> Tokenizer:
        # Loaded lazily: the first load may download the BPE file.
        if self._tokenizer is None:
            import tiktoken
            self._tokenizer = tiktoken.get_encoding(TOKENIZER_ENCODING)
        return self._tokenizer

    # ------------------------------------------------------------------
    # Embeddings
    # ------------------------------------------------------------------

    def _embedding_kwargs(self) -> dict:
        kwargs = {"model": self.model}
        # Only the text-embedding-3 family supports custom dimensions.
        if self.model.startswith("text-embedding-3"):
            kwargs["dimensions"] = self.dimensions
        return kwargs

    async def generate_embedding_async(self, text: str) -> List[float]:
        """Generate an embedding for a single text."""
        response = await self.client.embeddings.create(input=text, **self._embedding_kwargs())
        return response.data[0].embedding

    async def generate_embeddings_async(self, texts: List[str]) -> List[List[float]]:
        """Generate embeddings for many texts, batching requests to stay within API limits."""
        if not texts:
            return []
        batches = [texts[i:i + self.batch_size] for i in range(0, len(texts), self.batch_size)]

        async def embed_batch(batch: List[str]) -> List[List[float]]:
            response = await self.client.embeddings.create(input=batch, **self._embedding_kwargs())
            # The API may return items out of order; sort by index to be safe.
            return [item.embedding for item in sorted(response.data, key=lambda d: d.index)]

        results = await asyncio.gather(*(embed_batch(b) for b in batches))
        return [emb for batch in results for emb in batch]

    # ------------------------------------------------------------------
    # Chunking (CPU-only; wrap in asyncio.to_thread for big documents)
    # ------------------------------------------------------------------

    def count_tokens(self, text: str) -> int:
        return len(self.tokenizer.encode(text))

    def chunk_text(self, text: str, title: str = "") -> List[Chunk]:
        """Split text into overlapping token windows.

        Every chunk (including a single-chunk document) gets the same title
        prefix in its embedding text, and the final window always ends at the
        end of the document - no trailing chunk made purely of overlap.
        """
        text = text.strip()
        if not text:
            return []

        prefix = title_prefix(title)
        tokens = self.tokenizer.encode(text)

        if len(tokens) <= self.chunk_size:
            return [Chunk(text=text, embedding_text=prefix + text, token_count=len(tokens))]

        chunks: List[Chunk] = []
        step = self.chunk_size - self.chunk_overlap
        start = 0
        while True:
            end = min(start + self.chunk_size, len(tokens))
            piece = self.tokenizer.decode(tokens[start:end]).strip()
            if piece:
                chunks.append(Chunk(text=piece, embedding_text=prefix + piece, token_count=end - start))
            if end >= len(tokens):
                break
            start += step
        return chunks


@lru_cache()
def get_embedding_service() -> EmbeddingService:
    """Get or create the embedding service singleton."""
    return EmbeddingService()
