from types import SimpleNamespace

import httpx
import openai

from services.llm_service import NO_CONTEXT_ANSWER, LLMService
from services.text_utils import make_snippet


class FakeChat:
    def __init__(self, reject_logprobs=False, reply=" Answer [1] "):
        self.reject_logprobs = reject_logprobs
        self.reply = reply
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
            message=SimpleNamespace(content=self.reply), logprobs=logprobs)])


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
    assert fake_qdrant.last_search == {"limit": 3, "filters": {"category": "x"}, "min_score": 0.2}
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


async def test_refusal_drops_sources_and_confidence(settings, embedding_service, fake_qdrant):
    fake_qdrant.search_results = [{"id": "1", "score": 0.21, "document_id": "d", "title": "T", "text": "x"}]
    chat = FakeChat(reply="I don\u2019t have enough information to answer that question.")
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    result = await svc.ask_async("what is python?")
    assert result["sources"] == []
    assert result["confidence"] <= 0.1


def test_refusal_detection():
    assert LLMService.is_refusal("The sources do not contain information about Python.")
    assert not LLMService.is_refusal("A pod is the smallest deployable unit [1].")
    long_answer = "Pods are units [1]. " * 30 + "Some details are not enough information."
    assert not LLMService.is_refusal(long_answer)  # partial caveat inside a real answer


async def test_min_relevance_can_be_disabled(settings, embedding_service, fake_qdrant):
    settings.ask_min_relevance = 0.0
    svc = make_service(settings, embedding_service, fake_qdrant, FakeChat())
    await svc.ask_async("q?")
    assert fake_qdrant.last_search["min_score"] is None


class FakeStreamChat:
    """Streams the reply in pieces, like OpenAI's stream=True."""

    def __init__(self, pieces, logprob=-0.05):
        self.pieces = pieces
        self.logprob = logprob
        self.calls = []

    async def create(self, **kwargs):
        self.calls.append(kwargs)
        assert kwargs.get("stream") is True

        async def gen():
            yield SimpleNamespace(choices=[])  # keep-alive chunk with no choices
            for p in self.pieces:
                lp = SimpleNamespace(content=[SimpleNamespace(logprob=self.logprob)]) if kwargs.get("logprobs") else None
                yield SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content=p), logprobs=lp)])
            yield SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content=None), logprobs=None)])

        return gen()


async def collect(agen):
    return [e async for e in agen]


async def test_stream_emits_sources_deltas_then_done(settings, embedding_service, fake_qdrant):
    fake_qdrant.search_results = [{"id": "1", "score": 0.6, "document_id": "d1", "title": "Doc", "text": "Pods run containers"}]
    chat = FakeStreamChat(["A pod ", "runs containers ", "[1]."])
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    events = await collect(svc.ask_stream_async("What is a pod?"))
    kinds = [e["event"] for e in events]
    assert kinds == ["sources", "delta", "delta", "delta", "done"]
    assert "".join(e["data"]["text"] for e in events if e["event"] == "delta") == "A pod runs containers [1]."
    done = events[-1]["data"]
    assert done["sources"][0]["document_id"] == "d1"
    assert done["confidence"] == 0.95  # exp(-0.05)


async def test_stream_off_topic_skips_llm(settings, embedding_service, fake_qdrant):
    chat = FakeStreamChat(["unused"])
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    events = await collect(svc.ask_stream_async("capital of France?"))
    assert [e["event"] for e in events] == ["delta", "done"]
    assert events[-1]["data"] == {"confidence": 0.0, "sources": []}
    assert chat.calls == []


async def test_stream_refusal_hides_sources(settings, embedding_service, fake_qdrant):
    fake_qdrant.search_results = [{"id": "1", "score": 0.3, "document_id": "d", "title": "T", "text": "x"}]
    chat = FakeStreamChat(["I don't have enough ", "information to answer that."])
    svc = make_service(settings, embedding_service, fake_qdrant, chat)
    done = (await collect(svc.ask_stream_async("q?")))[-1]["data"]
    assert done["sources"] == [] and done["confidence"] <= 0.1


def test_stream_endpoint_sse_format(client):
    import main
    from services.llm_service import get_llm_service

    class StubLLM:
        async def ask_stream_async(self, question, max_sources, filters):
            yield {"event": "sources", "data": {"sources": []}}
            yield {"event": "delta", "data": {"text": "Hello"}}
            yield {"event": "done", "data": {"confidence": 0.9, "sources": []}}

    main.app.dependency_overrides[get_llm_service] = lambda: StubLLM()
    r = client.post("/api/ask/stream", json={"question": "hi"})
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/event-stream")
    assert r.text.split("\n\n")[1] == 'event: delta\ndata: {"text": "Hello"}'
    assert r.text.rstrip().endswith('"sources": []}')


def test_stream_endpoint_reports_errors_as_events(client):
    import main
    from services.llm_service import get_llm_service

    class FailingLLM:
        async def ask_stream_async(self, question, max_sources, filters):
            yield {"event": "sources", "data": {"sources": []}}
            raise RuntimeError("boom")

    main.app.dependency_overrides[get_llm_service] = lambda: FailingLLM()
    r = client.post("/api/ask/stream", json={"question": "hi"})
    assert 'event: error\ndata: {"message": "Failed to generate answer"}' in r.text


def test_stream_endpoint_validates_input(client):
    assert client.post("/api/ask/stream", json={"question": "  "}).status_code == 422
