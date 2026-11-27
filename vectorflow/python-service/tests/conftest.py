"""Shared fixtures: in-memory fakes for OpenAI and Qdrant so tests run offline."""

import os
from types import SimpleNamespace
from typing import List, Sequence

import pytest

# Make sure a developer's real .env never leaks into tests.
os.environ.setdefault("OPENAI_API_KEY", "test-key")
os.environ["INTERNAL_API_KEY"] = ""

from config import Settings, get_settings  # noqa: E402
from services.embedding_service import EmbeddingService  # noqa: E402


class CharTokenizer:
    """Deterministic 1-char-per-token tokenizer (no BPE download needed)."""

    def encode(self, text: str) -> List[int]:
        return [ord(c) for c in text]

    def decode(self, tokens: Sequence[int]) -> str:
        return "".join(chr(t) for t in tokens)


class FakeEmbeddings:
    def __init__(self):
        self.calls: List[list] = []

    async def create(self, input, model, **kwargs):
        texts = [input] if isinstance(input, str) else list(input)
        self.calls.append(texts)
        data = [
            SimpleNamespace(index=i, embedding=[float(len(t)), 1.0, 0.0])
            for i, t in enumerate(texts)
        ]
        return SimpleNamespace(data=list(reversed(data)))  # out of order on purpose


class FakeOpenAI:
    def __init__(self):
        self.embeddings = FakeEmbeddings()


@pytest.fixture
def settings() -> Settings:
    return Settings(
        openai_api_key="test-key",
        chunk_size=20,
        chunk_overlap=5,
        embedding_batch_size=2,
        embedding_dimensions=3,
        internal_api_key="",
        _env_file=None,
    )


@pytest.fixture
def embedding_service(settings) -> EmbeddingService:
    return EmbeddingService(settings=settings, client=FakeOpenAI(), tokenizer=CharTokenizer())


class FakeQdrantService:
    """Minimal in-memory stand-in for QdrantService used by router tests."""

    collection_name = "test"

    def __init__(self):
        self.docs = {}
        self.search_results = []
        self.last_search = None

    async def upsert_chunks_async(self, document_id, title, chunks, embeddings, metadata=None):
        self.docs[document_id] = {"title": title, "chunks": list(chunks), "metadata": metadata or {}}
        return len(chunks)

    async def delete_by_document_id_async(self, document_id):
        doc = self.docs.pop(document_id, None)
        return len(doc["chunks"]) if doc else 0

    async def search_async(self, query_embedding, limit=5, filters=None, min_score=None, query_text=None):
        self.last_search = {"limit": limit, "filters": filters, "min_score": min_score}
        self.last_query_text = query_text
        return self.search_results[:limit]

    async def get_document_chunks_async(self, document_id):
        doc = self.docs.get(document_id)
        if not doc:
            return []
        return [
            {"chunk_id": f"{document_id}-{i}", "chunk_index": i, "text": c.text,
             "title": doc["title"], "created_at": "2026-01-01T00:00:00+00:00", "metadata": doc["metadata"]}
            for i, c in enumerate(doc["chunks"])
        ]

    async def list_all_documents_async(self, page=1, page_size=20):
        items = [
            {"document_id": k, "title": v["title"], "created_at": "", "metadata": v["metadata"],
             "chunk_count": len(v["chunks"])}
            for k, v in self.docs.items()
        ]
        start = (page - 1) * page_size
        return items[start:start + page_size], len(items)

    async def health_check_async(self):
        return True

    async def ensure_collection(self):
        return None

    async def close(self):
        return None


@pytest.fixture
def fake_qdrant() -> FakeQdrantService:
    return FakeQdrantService()


@pytest.fixture
def client(embedding_service, fake_qdrant, monkeypatch):
    from fastapi.testclient import TestClient

    import main
    from services.embedding_service import get_embedding_service
    from services.qdrant_service import get_qdrant_service

    monkeypatch.setattr(main, "get_qdrant_service", lambda: fake_qdrant)
    main.app.dependency_overrides[get_embedding_service] = lambda: embedding_service
    main.app.dependency_overrides[get_qdrant_service] = lambda: fake_qdrant
    with TestClient(main.app) as c:
        yield c
    main.app.dependency_overrides.clear()
    get_settings.cache_clear()
