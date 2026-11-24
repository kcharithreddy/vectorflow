"""Exercise QdrantService against qdrant-client's in-memory engine (no server needed)."""

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from qdrant_client import AsyncQdrantClient

from services.embedding_service import Chunk
from services.qdrant_service import QdrantService


@pytest.fixture
async def qdrant(settings):
    svc = QdrantService(settings=settings, client=AsyncQdrantClient(location=":memory:"))
    yield svc
    await svc.close()


def chunks(n, prefix="c"):
    return [Chunk(f"{prefix}{i}", f"{prefix}{i}", 1) for i in range(n)]


def vecs(n, base=1.0):
    return [[base, float(i + 1), 0.5] for i in range(n)]


async def ingest(qdrant, n, metadata=None, title="T"):
    doc_id = str(uuid.uuid4())
    await qdrant.upsert_chunks_async(doc_id, title, chunks(n), vecs(n), metadata)
    return doc_id


async def test_upsert_and_fetch_chunks_in_order(qdrant):
    doc_id = await ingest(qdrant, 300)  # more than one scroll batch
    got = await qdrant.get_document_chunks_async(doc_id)
    assert len(got) == 300
    assert [c["chunk_index"] for c in got] == list(range(300))


async def test_reingest_replaces_and_trims_old_chunks(qdrant):
    doc_id = str(uuid.uuid4())
    await qdrant.upsert_chunks_async(doc_id, "T", chunks(5), vecs(5))
    await qdrant.upsert_chunks_async(doc_id, "T2", chunks(2, "new"), vecs(2))
    got = await qdrant.get_document_chunks_async(doc_id)
    assert [c["text"] for c in got] == ["new0", "new1"]


async def test_list_documents_pagination_is_exact(qdrant):
    ids = [await ingest(qdrant, n, title=f"doc{n}") for n in (1, 2, 3, 4, 5)]
    seen = []
    for page in (1, 2, 3):
        docs, total = await qdrant.list_all_documents_async(page=page, page_size=2)
        assert total == 5
        seen.extend(docs)
    assert sorted(d["document_id"] for d in seen) == sorted(ids)
    assert {d["title"]: d["chunk_count"] for d in seen} == {f"doc{n}": n for n in (1, 2, 3, 4, 5)}
    docs, total = await qdrant.list_all_documents_async(page=4, page_size=2)
    assert docs == [] and total == 5


async def test_search_with_filters_and_threshold(qdrant):
    db_doc = await ingest(qdrant, 2, {"category": "db", "tags": ["sql"]})
    await ingest(qdrant, 2, {"category": "web", "tags": ["http"]})

    results = await qdrant.search_async([1.0, 1.0, 0.5], limit=10, filters={"category": "db"})
    assert results and {r["document_id"] for r in results} == {db_doc}

    results = await qdrant.search_async([1.0, 1.0, 0.5], limit=10, filters={"tags": ["http", "nope"]})
    assert len(results) == 2 and all(r["metadata"]["category"] == "web" for r in results)

    future = datetime.now(timezone.utc) + timedelta(days=1)
    assert await qdrant.search_async([1.0, 1.0, 0.5], filters={"date_from": future}) == []

    everything = await qdrant.search_async([1.0, 1.0, 0.5], limit=10)
    strict = await qdrant.search_async([1.0, 1.0, 0.5], limit=10, min_score=0.999)
    assert 0 < len(strict) < len(everything)
    assert all(r["score"] >= 0.999 for r in strict)


async def test_delete_returns_count(qdrant):
    doc_id = await ingest(qdrant, 3)
    assert await qdrant.delete_by_document_id_async(doc_id) == 3
    assert await qdrant.delete_by_document_id_async(doc_id) == 0
    assert await qdrant.get_document_chunks_async(doc_id) == []


async def test_collection_info_and_dimension_check(qdrant, settings):
    await ingest(qdrant, 1)
    info = await qdrant.get_collection_info_async()
    assert info["points_count"] == 1 and info["vector_size"] == 3

    settings.embedding_dimensions = 4
    other = QdrantService(settings=settings, client=qdrant.client)
    with pytest.raises(RuntimeError, match="vector size"):
        await other.ensure_collection()
