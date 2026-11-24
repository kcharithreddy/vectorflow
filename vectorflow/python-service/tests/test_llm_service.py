from types import SimpleNamespace

import httpx
import openai

from services.llm_service import NO_CONTEXT_ANSWER, LLMService
from services.text_utils import make_snippet


class FakeChat:
    def __init__(self, reject_logprobs=False):
        self.reject_logprobs = reject_logprobs
        self.calls = []

    async def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.reject_logprobs and kwargs.get("logprobs"):
            req = httpx.Request("POST", "https://api.openai.com/v1/chat/completions")
            raise openai.BadRequestError("logprobs unsupported", response=httpx.Response(400, request=req), body=None)
        logprobs = None
        if kwargs.get("logprobs"):
            logprobs = SimpleNamespace(content=[SimpleNamespace(logprob=-0.1), SimpleNamespace(logprob=-0.1)])
        return SimpleNamespace(choices=[SimpleNamespace(
            message=SimpleNamespace(content=" Answer [1] "), logprobs=logprobs)])


def make_service(settings, embedding_service, fake_qdrant, chat):
    client = SimpleNamespace(chat=SimpleNamespace(completions=chat))
    return LLMService(settings=settings, client=client,
                      embedding_service=embedding_service, qdrant_service=fake_qdrant)


async def test_ask_without_results_returns_fallback(settings, embedding_service, fake_qdrant):
    chat = FakeChat()
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    result = await svc.ask_async("anything?")
    assert result == {"answer": NO_CONTEXT_ANSWER, "sources": [], "confidence": 0.0}
    assert chat.calls == []


async def test_ask_with_results(settings, embedding_service, fake_qdrant):
    fake_qdrant.search_results = [
        {"id": "1", "score": 0.91234, "document_id": "d1", "title": "Doc", "text": "Some text", "metadata": {}},
    ]
    chat = FakeChat()
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    result = await svc.ask_async("q?", max_sources=3, filters={"category": "x"})
    assert result["answer"] == "Answer [1]"
    assert result["confidence"] == 0.9
    assert result["sources"][0] == {"document_id": "d1", "title": "Doc", "snippet": "Some text", "relevance": 0.9123}
    assert fake_qdrant.last_search == {"limit": 3, "filters": {"category": "x"}, "min_score": None}
    assert "[1] Doc" in chat.calls[0]["messages"][1]["content"]


async def test_falls_back_when_model_rejects_logprobs(settings, embedding_service, fake_qdrant):
    fake_qdrant.search_results = [{"id": "1", "score": 0.5, "document_id": "d", "title": "T", "text": "x"}]
    chat = FakeChat(reject_logprobs=True)
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    result = await svc.ask_async("q?")
    assert result["confidence"] == 0.85  # heuristic
    assert len(chat.calls) == 2 and "logprobs" not in chat.calls[1]
    # Subsequent calls skip the logprobs attempt entirely.
    await svc.ask_async("q2?")
    assert len(chat.calls) == 3


def test_heuristic_confidence_drops_with_uncertainty():
    assert LLMService.heuristic_confidence("Paris is the capital.") == 0.85
    assert LLMService.heuristic_confidence("I don't know, it's unclear") < 0.85


def test_make_snippet_word_boundary():
    text = "word " * 100
    snippet = make_snippet(text, 50)
    assert snippet.endswith("...") and len(snippet) <= 53
    assert "  " not in make_snippet("a   b\n\nc")
