"""Answer modes (auto / documents / general), model allowlist and hybrid search."""

import uuid

import pytest
from qdrant_client import AsyncQdrantClient

from services import hybrid
from services.embedding_service import Chunk
from services.qdrant_service import QdrantService
from tests.test_llm_service import FakeChat, FakeStreamChat, collect, make_service

DOC = {"id": "1", "score": 0.6, "document_id": "d1", "title": "Leave Policy", "text": "Twenty days of vacation."}


# ---------------------------------------------------------------- modes

async def test_general_mode_skips_retrieval_and_uses_general_prompt(settings, embedding_service, fake_qdrant):
    fake_qdrant.search_results = [DOC]
    chat = FakeChat(reply="Paris is the capital of France.")
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    result = await svc.ask_async("Capital of France?", mode="general")
    assert fake_qdrant.last_search is None                       # no retrieval at all
    assert chat.calls[0]["messages"][0]["content"].startswith("You are VectorFlow, a knowledgeable")
    assert chat.calls[0]["messages"][-1] == {"role": "user", "content": "Capital of France?"}
    assert chat.calls[0]["max_tokens"] == settings.general_max_tokens
    assert result["answer_type"] == "general" and result["sources"] == []


async def test_auto_mode_without_relevant_documents_answers_generally(settings, embedding_service, fake_qdrant):
    chat = FakeChat(reply="Paris.")
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    result = await svc.ask_async("Capital of France?", mode="auto")
    assert fake_qdrant.last_search is not None                   # it did look in the documents
    assert result["answer"] == "Paris." and result["answer_type"] == "general"


async def test_auto_mode_with_cited_documents_is_grounded(settings, embedding_service, fake_qdrant):
    fake_qdrant.search_results = [DOC]
    chat = FakeChat(reply="Employees get twenty days [1].")
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    result = await svc.ask_async("Vacation days?", mode="auto")
    assert chat.calls[0]["messages"][0]["content"] == svc.AUTO_PROMPT
    assert result["answer_type"] == "documents" and result["sources"][0]["document_id"] == "d1"


async def test_auto_mode_uncited_answer_is_labelled_general(settings, embedding_service, fake_qdrant):
    fake_qdrant.search_results = [DOC]   # retrieved, but the model found them irrelevant
    chat = FakeChat(reply="Python was created by Guido van Rossum.")
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    result = await svc.ask_async("Who created Python?", mode="auto")
    assert result["answer_type"] == "general" and result["sources"] == []


async def test_documents_mode_is_the_default_and_stays_strict(settings, embedding_service, fake_qdrant):
    chat = FakeChat()
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    result = await svc.ask_async("Capital of France?")
    assert chat.calls == [] and result["answer_type"] == "documents" and result["sources"] == []


async def test_stream_general_mode(settings, embedding_service, fake_qdrant):
    chat = FakeStreamChat(["Hello ", "there!"])
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    events = await collect(svc.ask_stream_async("Hi", mode="general", model="gpt-4o"))
    assert [e["event"] for e in events] == ["delta", "delta", "done"]
    assert events[-1]["data"]["answer_type"] == "general"
    assert events[-1]["data"]["model"] == "gpt-4o"
    assert chat.calls[0]["model"] == "gpt-4o"


async def test_stream_auto_mode_with_documents(settings, embedding_service, fake_qdrant):
    fake_qdrant.search_results = [DOC]
    svc = make_service(settings, embedding_service, fake_qdrant, FakeStreamChat(["Twenty days ", "[1]."]))
    events = await collect(svc.ask_stream_async("Vacation?", mode="auto"))
    assert events[0]["event"] == "sources"
    assert events[-1]["data"]["answer_type"] == "documents" and events[-1]["data"]["sources"]


async def test_unknown_model_or_mode_is_rejected(settings, embedding_service, fake_qdrant):
    svc = make_service(settings, embedding_service, fake_qdrant, FakeChat())
    with pytest.raises(ValueError):
        await svc.ask_async("q", model="gpt-5-ultra")
    with pytest.raises(ValueError):
        await svc.ask_async("q", mode="creative")


def test_models_endpoint_and_validation(client):
    r = client.get("/api/models")
    assert r.status_code == 200
    body = r.json()
    assert body["default_model"] == body["models"][0]
    assert body["modes"] == ["auto", "documents", "general"]
    assert client.post("/api/ask", json={"question": "q", "model": "not-allowed"}).status_code == 400
    assert client.post("/api/ask/stream", json={"question": "q", "model": "not-allowed"}).status_code == 400
    assert client.post("/api/ask", json={"question": "q", "mode": "creative"}).status_code == 422


def test_search_router_passes_query_text_for_hybrid(client, fake_qdrant):
    client.post("/api/search", json={"query": "PgBouncer pooling"})
    assert fake_qdrant.last_query_text == "PgBouncer pooling"


# ---------------------------------------------------------------- hybrid helpers

def test_keywords_drop_stopwords_and_keep_exact_terms():
    assert hybrid.keywords("What is the X-RateLimit-Remaining header?") == ["x-ratelimit-remaining", "header"]
    assert "pgbouncer" in hybrid.keywords("How do we use PgBouncer?")
    assert hybrid.keywords("what is it?") == []


def test_bm25_prefers_documents_with_rare_terms():
    docs = ["pgbouncer pools postgres connections", "postgres stores data", "redis caches data"]
    scores = hybrid.bm25_scores(["pgbouncer", "postgres"], docs)
    assert scores[0] > scores[1] > scores[2] == 0.0


def test_rrf_rewards_agreement():
    fused = hybrid.rrf_fuse([["a", "b", "c"], ["b", "a"]])
    assert fused["a"] == fused["b"] > fused["c"]


# ---------------------------------------------------------------- hybrid search against in-memory Qdrant

@pytest.fixture
async def qdrant(settings):
    svc = QdrantService(settings=settings, client=AsyncQdrantClient(location=":memory:"))
    yield svc
    await svc.close()


async def test_hybrid_search_finds_exact_term_that_vector_search_ranks_low(qdrant):
    doc = str(uuid.uuid4())
    texts = ["Connection pooling basics for databases.",
             "Caching strategies with Redis.",
             "We put PgBouncer in front of Postgres."]
    vectors = [[1.0, 0.0, 0.0], [0.9, 0.1, 0.0], [0.3, 1.0, 0.0]]   # the PgBouncer chunk is semantically far
    await qdrant.upsert_chunks_async(doc, "Infra", [Chunk(t, t, 1) for t in texts], vectors)
    query = [1.0, 0.05, 0.0]

    vector_only = await qdrant.search_async(query, limit=1)
    assert "PgBouncer" not in vector_only[0]["text"]

    hybrid_hits = await qdrant.search_async(query, limit=2, query_text="Do we use PgBouncer?")
    assert any("PgBouncer" in h["text"] for h in hybrid_hits)
    assert all(0.0 <= h["score"] <= 1.0 for h in hybrid_hits)    # scores remain cosine similarities


async def test_hybrid_search_respects_filters(qdrant):
    a, b = str(uuid.uuid4()), str(uuid.uuid4())
    await qdrant.upsert_chunks_async(a, "A", [Chunk("PgBouncer config", "PgBouncer config", 1)], [[1.0, 0.0, 0.0]], {"category": "ops"})
    await qdrant.upsert_chunks_async(b, "B", [Chunk("PgBouncer notes", "PgBouncer notes", 1)], [[1.0, 0.0, 0.0]], {"category": "hr"})
    hits = await qdrant.search_async([1.0, 0.0, 0.0], limit=5, filters={"category": "hr"}, query_text="pgbouncer")
    assert {h["document_id"] for h in hits} == {b}


async def test_hybrid_can_be_disabled(settings):
    settings.hybrid_search = False
    svc = QdrantService(settings=settings, client=AsyncQdrantClient(location=":memory:"))
    doc = str(uuid.uuid4())
    await svc.upsert_chunks_async(doc, "T", [Chunk("alpha", "alpha", 1), Chunk("PgBouncer", "PgBouncer", 1)],
                                  [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]])
    hits = await svc.search_async([1.0, 0.0, 0.0], limit=1, query_text="PgBouncer")
    assert hits[0]["text"] == "alpha"
    await svc.close()
