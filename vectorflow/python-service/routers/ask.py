"""RAG-based question answering router."""

import json
import logging
from typing import Any, AsyncIterator, Dict

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from exceptions import LLMError, to_vectorflow_error
from models.schemas import AskRequest, AskResponse, Source
from routers.search import filters_to_dict
from security import verify_internal_api_key
from services.llm_service import LLMService, get_llm_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["ask"], dependencies=[Depends(verify_internal_api_key)])


@router.post("/ask", response_model=AskResponse)
async def ask_question(
    request: AskRequest,
    llm_service: LLMService = Depends(get_llm_service),
) -> AskResponse:
    """
    Answer a question with Retrieval-Augmented Generation.

    1. Embed the question
    2. Retrieve the most relevant chunks from Qdrant (optionally filtered)
    3. Send the numbered sources + question to the chat model
    4. Return the answer with source citations and a confidence score
    """
    try:
        result = await llm_service.ask_async(
            question=request.question,
            max_sources=request.max_sources,
            filters=filters_to_dict(request.filters),
        )
    except Exception as exc:
        err = to_vectorflow_error(exc, LLMError())
        if err.status_code >= 500:
            logger.exception("RAG pipeline failed for question=%r", request.question)
        raise err from exc

    return AskResponse(
        answer=result["answer"],
        sources=[Source(**s) for s in result["sources"]],
        confidence=result["confidence"],
        question=request.question,
    )


def _sse(event: str, data: Dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/ask/stream")
async def ask_question_stream(
    request: AskRequest,
    llm_service: LLMService = Depends(get_llm_service),
) -> StreamingResponse:
    """
    Same as /ask, but streams the answer as Server-Sent Events:
    `sources` (retrieved passages), many `delta` (answer text), then `done`
    (confidence + the sources to show). Errors arrive as an `error` event.
    """

    async def events() -> AsyncIterator[str]:
        try:
            async for item in llm_service.ask_stream_async(
                question=request.question,
                max_sources=request.max_sources,
                filters=filters_to_dict(request.filters),
            ):
                yield _sse(item["event"], item["data"])
        except Exception as exc:
            err = to_vectorflow_error(exc, LLMError())
            if err.status_code >= 500:
                logger.exception("Streaming RAG failed for question=%r", request.question)
            yield _sse("error", {"message": err.message})

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
