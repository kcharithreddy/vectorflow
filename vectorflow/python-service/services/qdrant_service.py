"""Qdrant vector database service (native async client).

Each chunk is stored as one point.  Point ids are deterministic
(``uuid5(document_id, chunk_index)``) so re-ingesting a document with the same
id overwrites its chunks instead of duplicating them.
"""

import asyncio
import logging
import uuid
from datetime import datetime, timezone
from functools import lru_cache
from typing import Any, Dict, List, Optional, Sequence, Tuple

from qdrant_client import AsyncQdrantClient
from qdrant_client.http import models
from qdrant_client.http.models import (
    DatetimeRange,
    Distance,
    FieldCondition,
    Filter,
    MatchAny,
    MatchValue,
    PayloadSchemaType,
    MatchText,
    PointStruct,
    TextIndexParams,
    TokenizerType,
    VectorParams,
)

from config import Settings, get_settings
from services import hybrid
from services.embedding_service import Chunk

logger = logging.getLogger(__name__)

# Payload fields that get an index for fast filtering.
PAYLOAD_INDEXES = {
    "document_id": PayloadSchemaType.KEYWORD,
    "chunk_index": PayloadSchemaType.INTEGER,
    "created_at": PayloadSchemaType.DATETIME,
    "metadata.category": PayloadSchemaType.KEYWORD,
    "metadata.tags": PayloadSchemaType.KEYWORD,
    # full-text index for the keyword half of hybrid search
    "text": TextIndexParams(type="text", tokenizer=TokenizerType.WORD, lowercase=True,
                            min_token_len=2, max_token_len=40),
}

SCROLL_BATCH = 256
LEGACY_TITLE_PREFIX = "[Document: "


def chunk_point_id(document_id: str, chunk_index: int) -> str:
    return str(uuid.uuid5(uuid.UUID(document_id), str(chunk_index)))


def clean_chunk_text(text: str) -> str:
    """Strip the title prefix that older versions stored inside the chunk text."""
    if text.startswith(LEGACY_TITLE_PREFIX):
        newline = text.find("\n")
        if newline != -1:
            return text[newline + 1:]
    return text


def document_filter(document_id: str) -> Filter:
    return Filter(must=[FieldCondition(key="document_id", match=MatchValue(value=document_id))])


class QdrantService:
    """Service for interacting with the Qdrant vector database."""

    def __init__(self, settings: Optional[Settings] = None, client: Optional[AsyncQdrantClient] = None):
        settings = settings or get_settings()
        self.client = client or AsyncQdrantClient(
            host=settings.qdrant_host,
            port=settings.qdrant_port,
            api_key=settings.qdrant_api_key or None,
            https=False,
        )
        self.collection_name = settings.qdrant_collection
        self.vector_size = settings.embedding_dimensions
        self.hybrid_enabled = settings.hybrid_search
        self._ready = False
        self._ready_lock = asyncio.Lock()

    # ------------------------------------------------------------------
    # Collection bootstrap
    # ------------------------------------------------------------------

    async def ensure_collection(self) -> None:
        """Create the collection and payload indexes if needed (idempotent)."""
        if self._ready:
            return
        async with self._ready_lock:
            if self._ready:
                return
            if not await self.client.collection_exists(self.collection_name):
                await self.client.create_collection(
                    collection_name=self.collection_name,
                    vectors_config=VectorParams(size=self.vector_size, distance=Distance.COSINE),
                )
                logger.info("Created Qdrant collection '%s' (dim=%d)", self.collection_name, self.vector_size)
            else:
                info = await self.client.get_collection(self.collection_name)
                existing = self._configured_vector_size(info)
                if existing is not None and existing != self.vector_size:
                    raise RuntimeError(
                        f"Qdrant collection '{self.collection_name}' has vector size {existing}, "
                        f"but EMBEDDING_DIMENSIONS is {self.vector_size}. Use a new collection "
                        f"name (QDRANT_COLLECTION) or re-create the collection."
                    )

            for field, schema in PAYLOAD_INDEXES.items():
                try:
                    await self.client.create_payload_index(
                        collection_name=self.collection_name,
                        field_name=field,
                        field_schema=schema,
                    )
                except Exception:  # index already exists / unsupported - not fatal
                    logger.debug("Payload index for '%s' not created", field, exc_info=True)
            self._ready = True

    @staticmethod
    def _configured_vector_size(info) -> Optional[int]:
        try:
            vectors = info.config.params.vectors
            return vectors.size if hasattr(vectors, "size") else None
        except AttributeError:
            return None

    # ------------------------------------------------------------------
    # Writes
    # ------------------------------------------------------------------

    async def upsert_chunks_async(
        self,
        document_id: str,
        title: str,
        chunks: Sequence[Chunk],
        embeddings: List[List[float]],
        metadata: Optional[Dict[str, Any]] = None,
    ) -> int:
        """Insert (or replace) all chunks of a document."""
        if len(chunks) != len(embeddings):
            raise ValueError("chunks and embeddings must have the same length")
        await self.ensure_collection()

        points = self.build_points(document_id, title, chunks, embeddings, metadata)
        await self.client.upsert(collection_name=self.collection_name, points=points, wait=True)

        # Remove chunks left over from a previous, longer version of the document.
        await self.client.delete(
            collection_name=self.collection_name,
            points_selector=models.FilterSelector(
                filter=Filter(
                    must=[
                        FieldCondition(key="document_id", match=MatchValue(value=document_id)),
                        FieldCondition(key="chunk_index", range=models.Range(gte=len(chunks))),
                    ]
                )
            ),
        )
        return len(points)

    async def delete_by_document_id_async(self, document_id: str) -> int:
        """Delete all chunks for a document. Returns the number of chunks removed."""
        await self.ensure_collection()
        flt = document_filter(document_id)
        count = (await self.client.count(self.collection_name, count_filter=flt, exact=True)).count
        if count:
            await self.client.delete(
                collection_name=self.collection_name,
                points_selector=models.FilterSelector(filter=flt),
                wait=True,
            )
        return count

    # ------------------------------------------------------------------
    # Reads
    # ------------------------------------------------------------------

    async def search_async(
        self,
        query_embedding: List[float],
        limit: int = 5,
        filters: Optional[Dict[str, Any]] = None,
        min_score: Optional[float] = None,
        query_text: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """Return the chunks most relevant to the query.

        With ``query_text`` (and HYBRID_SEARCH on) this is hybrid search:
        vector candidates + keyword candidates, re-ranked by fusing the vector
        ranking with a BM25 ranking.  Each result's ``score`` stays the cosine
        similarity, so relevance thresholds keep their meaning.
        """
        await self.ensure_collection()
        query_filter = self.build_search_filter(filters)
        terms = hybrid.keywords(query_text) if (query_text and self.hybrid_enabled) else []
        if not terms:
            response = await self.client.query_points(
                collection_name=self.collection_name,
                query=query_embedding,
                limit=limit,
                query_filter=query_filter,
                score_threshold=min_score,
                with_payload=True,
            )
            return self.format_search_results(response.points)

        pool = max(limit * 4, 20)
        vector_resp, keyword_resp = await asyncio.gather(
            self.client.query_points(
                collection_name=self.collection_name,
                query=query_embedding,
                limit=pool,
                query_filter=query_filter,
                score_threshold=min_score,
                with_payload=True,
            ),
            self.client.query_points(
                collection_name=self.collection_name,
                query=query_embedding,
                limit=pool,
                query_filter=self.keyword_filter(terms, query_filter),
                # An exact keyword match counts as evidence, so it may score a bit
                # lower on meaning than a pure vector hit.
                score_threshold=(min_score / 2) if min_score else None,
                with_payload=True,
            ),
        )
        vector_hits = self.format_search_results(vector_resp.points)
        keyword_hits = self.format_search_results(keyword_resp.points)
        return self.fuse(terms, vector_hits, keyword_hits, limit)

    @staticmethod
    def keyword_filter(terms: Sequence[str], base: Optional[Filter]) -> Filter:
        """Chunks matching the user's filters AND containing at least one keyword."""
        should = [FieldCondition(key="text", match=MatchText(text=t)) for t in terms]
        return Filter(must=list(base.must or []) if base else [], should=should)

    @staticmethod
    def fuse(terms: Sequence[str], vector_hits: List[Dict[str, Any]],
             keyword_hits: List[Dict[str, Any]], limit: int) -> List[Dict[str, Any]]:
        by_id: Dict[str, Dict[str, Any]] = {}
        for hit in vector_hits + keyword_hits:
            by_id.setdefault(hit["id"], hit)
        candidates = list(by_id.values())
        bm25 = hybrid.bm25_scores(terms, [c["title"] + " " + c["text"] for c in candidates])
        keyword_rank = [c["id"] for c, s in sorted(zip(candidates, bm25), key=lambda x: -x[1]) if s > 0]
        fused = hybrid.rrf_fuse([[h["id"] for h in vector_hits], keyword_rank])
        ranked = sorted(candidates, key=lambda c: (-fused.get(c["id"], 0.0), -c["score"]))
        return ranked[:limit]

    async def get_collection_info_async(self) -> Dict[str, Any]:
        await self.ensure_collection()
        info = await self.client.get_collection(self.collection_name)
        return {
            "name": self.collection_name,
            "points_count": info.points_count,
            "indexed_vectors_count": info.indexed_vectors_count,
            "status": info.status.value if hasattr(info.status, "value") else str(info.status),
            "vector_size": self._configured_vector_size(info),
        }

    async def list_all_documents_async(
        self,
        page: int = 1,
        page_size: int = 20,
    ) -> Tuple[List[Dict[str, Any]], int]:
        """Paginated list of documents.

        Each document is represented by its first chunk (``chunk_index == 0``),
        so paging and the total count are exact and never require loading
        every chunk into memory.
        """
        await self.ensure_collection()
        first_chunks = Filter(must=[FieldCondition(key="chunk_index", match=MatchValue(value=0))])
        total = (await self.client.count(self.collection_name, count_filter=first_chunks, exact=True)).count

        skip = (page - 1) * page_size
        if skip >= total:
            return [], total

        needed = skip + page_size
        collected: List[Any] = []
        offset = None
        while len(collected) < needed:
            results, offset = await self.client.scroll(
                collection_name=self.collection_name,
                scroll_filter=first_chunks,
                limit=min(SCROLL_BATCH, needed - len(collected)),
                offset=offset,
                with_payload=["document_id", "title", "created_at", "metadata", "total_chunks"],
                with_vectors=False,
            )
            collected.extend(results)
            if offset is None or not results:
                break
        points = collected[skip:needed]

        async def chunk_count(point) -> int:
            stored = point.payload.get("total_chunks")
            if isinstance(stored, int):
                return stored
            # Documents ingested by older versions don't store total_chunks.
            doc_id = point.payload.get("document_id", "")
            return (await self.client.count(
                self.collection_name, count_filter=document_filter(doc_id), exact=True
            )).count

        counts = await asyncio.gather(*(chunk_count(p) for p in points))
        documents = [
            {
                "document_id": p.payload.get("document_id", ""),
                "title": p.payload.get("title", ""),
                "created_at": p.payload.get("created_at", ""),
                "metadata": p.payload.get("metadata") or {},
                "chunk_count": n,
            }
            for p, n in zip(points, counts)
        ]
        return documents, total

    async def get_document_chunks_async(self, document_id: str) -> List[Dict[str, Any]]:
        """Get every chunk of a document, ordered by chunk index."""
        await self.ensure_collection()
        flt = document_filter(document_id)
        chunks: List[Dict[str, Any]] = []
        offset = None
        while True:
            results, offset = await self.client.scroll(
                collection_name=self.collection_name,
                scroll_filter=flt,
                limit=SCROLL_BATCH,
                offset=offset,
                with_payload=True,
                with_vectors=False,
            )
            for point in results:
                chunks.append({
                    "chunk_id": str(point.id),
                    "chunk_index": point.payload.get("chunk_index", 0),
                    "text": clean_chunk_text(point.payload.get("text", "")),
                    "title": point.payload.get("title", ""),
                    "created_at": point.payload.get("created_at", ""),
                    "metadata": point.payload.get("metadata") or {},
                })
            if offset is None:
                break
        chunks.sort(key=lambda c: c["chunk_index"])
        return chunks

    async def health_check_async(self) -> bool:
        try:
            await self.client.get_collections()
            return True
        except Exception:
            return False

    async def close(self) -> None:
        try:
            await self.client.close()
        except Exception:
            logger.debug("Error closing Qdrant client", exc_info=True)

    # ------------------------------------------------------------------
    # Pure helpers
    # ------------------------------------------------------------------

    @staticmethod
    def build_points(
        document_id: str,
        title: str,
        chunks: Sequence[Chunk],
        embeddings: List[List[float]],
        metadata: Optional[Dict[str, Any]] = None,
    ) -> List[PointStruct]:
        created_at = datetime.now(timezone.utc).isoformat()
        total = len(chunks)
        return [
            PointStruct(
                id=chunk_point_id(document_id, i),
                vector=embedding,
                payload={
                    "text": chunk.text,
                    "document_id": document_id,
                    "title": title,
                    "chunk_index": i,
                    "total_chunks": total,
                    "token_count": chunk.token_count,
                    "created_at": created_at,
                    "metadata": metadata or {},
                },
            )
            for i, (chunk, embedding) in enumerate(zip(chunks, embeddings))
        ]

    @staticmethod
    def build_search_filter(filters: Optional[Dict[str, Any]]) -> Optional[Filter]:
        """Translate a user-facing filter dict into a Qdrant Filter."""
        if not filters:
            return None

        conditions = []
        if filters.get("category"):
            conditions.append(
                FieldCondition(key="metadata.category", match=MatchValue(value=filters["category"]))
            )
        if filters.get("tags"):
            conditions.append(FieldCondition(key="metadata.tags", match=MatchAny(any=list(filters["tags"]))))
        if filters.get("date_from") or filters.get("date_to"):
            conditions.append(
                FieldCondition(
                    key="created_at",
                    range=DatetimeRange(gte=filters.get("date_from"), lte=filters.get("date_to")),
                )
            )
        return Filter(must=conditions) if conditions else None

    @staticmethod
    def format_search_results(points) -> List[Dict[str, Any]]:
        return [
            {
                "id": str(p.id),
                "score": float(p.score),
                "document_id": p.payload.get("document_id", ""),
                "title": p.payload.get("title", ""),
                "text": clean_chunk_text(p.payload.get("text", "")),
                "chunk_index": p.payload.get("chunk_index", 0),
                "metadata": p.payload.get("metadata") or {},
            }
            for p in points
        ]


@lru_cache()
def get_qdrant_service() -> QdrantService:
    """Get or create the Qdrant service singleton."""
    return QdrantService()
