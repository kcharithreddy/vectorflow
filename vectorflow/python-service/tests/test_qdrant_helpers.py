from datetime import datetime, timezone

from services.embedding_service import Chunk
from services.qdrant_service import QdrantService, chunk_point_id, clean_chunk_text


DOC_ID = "6f1c1f7e-4f9e-4a39-9d55-1d2b8a7f0c11"


def test_point_ids_are_deterministic_per_document_and_index():
    assert chunk_point_id(DOC_ID, 0) == chunk_point_id(DOC_ID, 0)
    assert chunk_point_id(DOC_ID, 0) != chunk_point_id(DOC_ID, 1)


def test_build_points_payload():
    chunks = [Chunk("one", "[Document: T]\none", 3), Chunk("two", "[Document: T]\ntwo", 3)]
    points = QdrantService.build_points(DOC_ID, "T", chunks, [[0.1], [0.2]], {"category": "c"})
    assert [p.payload["chunk_index"] for p in points] == [0, 1]
    assert all(p.payload["total_chunks"] == 2 for p in points)
    assert points[0].payload["text"] == "one"  # raw text, no title prefix
    assert points[0].payload["metadata"] == {"category": "c"}
    assert points[0].payload["created_at"].endswith("+00:00")


def test_search_filter_none_when_empty():
    assert QdrantService.build_search_filter(None) is None
    assert QdrantService.build_search_filter({"category": None, "tags": []}) is None


def test_search_filter_all_conditions():
    f = QdrantService.build_search_filter({
        "category": "db",
        "tags": ["a", "b"],
        "date_from": datetime(2025, 1, 1, tzinfo=timezone.utc),
    })
    keys = [c.key for c in f.must]
    assert keys == ["metadata.category", "metadata.tags", "created_at"]


def test_clean_chunk_text_strips_legacy_prefix():
    assert clean_chunk_text("[Document: Title]\nbody") == "body"
    assert clean_chunk_text("plain body") == "plain body"
