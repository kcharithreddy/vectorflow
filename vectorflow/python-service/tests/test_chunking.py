import pytest

from config import Settings
from services.embedding_service import EmbeddingService
from tests.conftest import CharTokenizer, FakeOpenAI


def test_short_text_is_single_chunk_with_title_prefix(embedding_service):
    chunks = embedding_service.chunk_text("hello world", title="Greeting")
    assert len(chunks) == 1
    assert chunks[0].text == "hello world"
    assert chunks[0].embedding_text == "[Document: Greeting]\nhello world"


def test_blank_text_produces_no_chunks(embedding_service):
    assert embedding_service.chunk_text("   \n\t ") == []


def test_chunks_overlap_and_cover_whole_text(embedding_service):
    text = "".join(chr(ord("a") + i % 26) for i in range(53))
    chunks = embedding_service.chunk_text(text)
    # size 20, overlap 5 -> windows start at 0, 15, 30, 45(end=53)
    assert [c.token_count for c in chunks] == [20, 20, 20, 8]
    assert chunks[0].text[-5:] == chunks[1].text[:5]
    assert chunks[-1].text.endswith(text[-8:])


def test_no_trailing_chunk_made_only_of_overlap(embedding_service):
    # 35 tokens: windows [0:20], [15:35] -> the second already reaches the end.
    chunks = embedding_service.chunk_text("x" * 35)
    assert [c.token_count for c in chunks] == [20, 20]


@pytest.mark.parametrize("overlap", [20, 25, -1])
def test_invalid_overlap_rejected(overlap):
    s = Settings(chunk_size=20, chunk_overlap=overlap, _env_file=None)
    with pytest.raises(ValueError):
        EmbeddingService(settings=s, client=FakeOpenAI(), tokenizer=CharTokenizer())


async def test_embeddings_are_batched_and_ordered(embedding_service):
    texts = ["a", "bb", "ccc", "dddd", "eeeee"]
    vectors = await embedding_service.generate_embeddings_async(texts)
    assert [v[0] for v in vectors] == [1.0, 2.0, 3.0, 4.0, 5.0]
    assert [len(c) for c in embedding_service.client.embeddings.calls] == [2, 2, 1]


async def test_empty_embedding_request_makes_no_api_call(embedding_service):
    assert await embedding_service.generate_embeddings_async([]) == []
    assert embedding_service.client.embeddings.calls == []
