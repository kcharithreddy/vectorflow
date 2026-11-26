import uuid

from config import get_settings


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["qdrant_connected"] is True
    assert body["status"] in {"healthy", "degraded"}


def test_ingest_get_list_delete_roundtrip(client, fake_qdrant):
    r = client.post("/api/ingest", json={
        "title": "  Doc  ", "content": "x" * 50, "metadata": {"category": "c", "tags": ["t"]},
    })
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "completed"
    assert body["title"] == "Doc"
    assert body["chunk_count"] == 3
    doc_id = body["document_id"]

    r = client.get(f"/api/documents/{doc_id}")
    assert r.status_code == 200
    assert r.json()["total_chunks"] == 3

    r = client.get("/api/documents", params={"page": 1, "page_size": 10})
    assert r.json()["total_count"] == 1

    r = client.delete(f"/api/documents/{doc_id}")
    assert r.status_code == 200
    assert r.json()["deleted_chunks"] == 3

    assert client.delete(f"/api/documents/{doc_id}").status_code == 404
    assert client.get(f"/api/documents/{doc_id}").status_code == 404


def test_ingest_with_caller_supplied_id(client):
    doc_id = str(uuid.uuid4())
    r = client.post("/api/ingest", json={"title": "T", "content": "hello", "document_id": doc_id})
    assert r.json()["document_id"] == doc_id


def test_ingest_validation(client):
    assert client.post("/api/ingest", json={"title": "T", "content": "   "}).status_code == 422
    assert client.post("/api/ingest", json={"title": " ", "content": "x"}).status_code == 422
    assert client.post("/api/ingest", json={"title": "T", "content": "x", "document_id": "nope"}).status_code == 422
    assert client.post("/api/ingest", json={"title": "T", "content": "x", "metadata": None}).status_code == 200


def test_delete_rejects_non_uuid(client):
    assert client.delete("/api/documents/not-a-uuid").status_code == 400


def test_search_passes_filters_and_formats_results(client, fake_qdrant):
    fake_qdrant.search_results = [
        {"id": "p1", "score": 0.876543, "document_id": "d1", "title": "T", "text": "body " * 100,
         "metadata": {"category": "c"}},
    ]
    r = client.post("/api/search", json={
        "query": " vectors ", "limit": 3, "filters": {"category": "c", "tags": None}, "min_score": 0.2,
    })
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["query"] == "vectors"
    assert body["total_count"] == 1
    assert body["results"][0]["score"] == 0.8765
    assert body["results"][0]["snippet"].endswith("...")
    assert fake_qdrant.last_search == {"limit": 3, "filters": {"category": "c"}, "min_score": 0.2}


def test_search_limit_validation(client):
    assert client.post("/api/search", json={"query": "q", "limit": 0}).status_code == 422
    assert client.post("/api/search", json={"query": "q", "limit": 21}).status_code == 422


def test_bulk_ingest_partial_failure(client):
    r = client.post("/api/bulk-ingest", json={"documents": [
        {"title": "ok", "content": "fine"},
        {"title": "big", "content": "x" * 50},
    ]})
    assert r.status_code == 200
    assert r.json()["successful"] == 2

    get_settings().max_document_chars = 10
    try:
        r = client.post("/api/bulk-ingest", json={"documents": [
            {"title": "ok", "content": "fine"},
            {"title": "too big", "content": "x" * 50},
        ]})
    finally:
        get_settings.cache_clear()
    body = r.json()
    assert (body["total"], body["successful"], body["failed"]) == (2, 1, 1)
    assert "character limit" in body["results"][1]["message"]


def test_bulk_ingest_requires_documents(client):
    assert client.post("/api/bulk-ingest", json={"documents": []}).status_code == 422


def test_internal_api_key_enforced(client):
    get_settings().internal_api_key = "s3cret"
    try:
        assert client.post("/api/search", json={"query": "q"}).status_code == 401
        assert client.post("/api/search", json={"query": "q"},
                           headers={"X-Internal-API-Key": "wrong"}).status_code == 401
        assert client.post("/api/search", json={"query": "q"},
                           headers={"X-Internal-API-Key": "s3cret"}).status_code == 200
        assert client.get("/health").status_code == 200  # health stays open
    finally:
        get_settings.cache_clear()


def test_security_problems_and_strict_mode():
    import asyncio

    import main
    from config import Settings

    weak = Settings(internal_api_key="change-me-internal-api-key", cors_allowed_origins="*", _env_file=None)
    assert len(weak.security_problems()) == 2
    assert Settings(internal_api_key="a-long-random-value", _env_file=None).security_problems() == []

    get_settings().security_strict = True
    get_settings().internal_api_key = ""
    try:
        async def start():
            async with main.lifespan(main.app):
                pass
        import pytest
        with pytest.raises(RuntimeError, match="SECURITY_STRICT"):
            asyncio.run(start())
    finally:
        get_settings.cache_clear()
