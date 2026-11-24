"""VectorFlow Python Service - FastAPI application."""

import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from config import APP_VERSION, get_settings
from exceptions import VectorFlowError
from models.schemas import HealthResponse
from routers import ask, bulk_ingest, extract, ingest, search
from services.qdrant_service import get_qdrant_service

settings = get_settings()

logging.basicConfig(
    level=settings.log_level.upper(),
    format="%(asctime)s %(levelname)-7s %(name)s - %(message)s",
)
logger = logging.getLogger("vectorflow")

STARTUP_RETRIES = 10
STARTUP_RETRY_DELAY_SECONDS = 2.0


async def _bootstrap_qdrant() -> None:
    """Make sure the collection exists, retrying while Qdrant starts up."""
    qdrant = get_qdrant_service()
    for attempt in range(1, STARTUP_RETRIES + 1):
        try:
            await qdrant.ensure_collection()
            logger.info("Qdrant collection '%s' is ready", qdrant.collection_name)
            return
        except RuntimeError:
            # Configuration problem (e.g. vector size mismatch) - retrying won't help.
            logger.exception("Qdrant collection is misconfigured")
            return
        except Exception as exc:
            logger.warning("Qdrant not ready (attempt %d/%d): %s", attempt, STARTUP_RETRIES, exc)
            await asyncio.sleep(STARTUP_RETRY_DELAY_SECONDS)
    logger.error("Could not initialise Qdrant; requests will retry lazily")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("VectorFlow Python Service %s starting", APP_VERSION)
    logger.info("Qdrant: %s:%s  collection=%s", settings.qdrant_host, settings.qdrant_port, settings.qdrant_collection)
    logger.info("Embedding model: %s (%d dims)  LLM: %s",
                settings.embedding_model, settings.embedding_dimensions, settings.llm_model)
    if not settings.openai_api_key:
        logger.warning("OPENAI_API_KEY is not set - ingest/search/ask will fail")
    if not settings.internal_api_key:
        logger.warning("INTERNAL_API_KEY is not set - /api endpoints are unauthenticated")
    await _bootstrap_qdrant()
    yield
    await get_qdrant_service().close()
    logger.info("VectorFlow Python Service shutting down")


app = FastAPI(
    title="VectorFlow Python Service",
    description="Document ingestion, embedding generation, vector search and RAG question answering",
    version=APP_VERSION,
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(ingest.router)
app.include_router(bulk_ingest.router)
app.include_router(search.router)
app.include_router(ask.router)
app.include_router(extract.router)


@app.exception_handler(VectorFlowError)
async def vectorflow_error_handler(request: Request, exc: VectorFlowError):
    """Return structured error responses for known exceptions."""
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.message})


@app.get("/", tags=["root"])
async def root():
    return {"service": "VectorFlow Python Service", "version": APP_VERSION, "docs": "/docs"}


@app.get("/health", response_model=HealthResponse, tags=["health"])
async def health_check():
    """Liveness/readiness: reports Qdrant connectivity and whether OpenAI is configured."""
    qdrant_connected = await get_qdrant_service().health_check_async()
    openai_configured = bool(settings.openai_api_key)
    return HealthResponse(
        status="healthy" if qdrant_connected and openai_configured else "degraded",
        qdrant_connected=qdrant_connected,
        openai_configured=openai_configured,
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
