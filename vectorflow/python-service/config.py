"""Configuration settings for the Python service."""

from functools import lru_cache
from typing import List

from pydantic_settings import BaseSettings, SettingsConfigDict

APP_VERSION = "1.2.0"


class Settings(BaseSettings):
    """Application settings loaded from environment variables (or a .env file)."""

    model_config = SettingsConfigDict(
        env_file=".env",
        case_sensitive=False,
        extra="ignore",  # the shared .env also holds gateway/postgres settings
    )

    # Qdrant
    qdrant_host: str = "localhost"
    qdrant_port: int = 6333
    qdrant_api_key: str = ""
    qdrant_collection: str = "documents"

    # OpenAI
    openai_api_key: str = ""
    openai_timeout_seconds: float = 60.0
    openai_max_retries: int = 3
    embedding_model: str = "text-embedding-3-small"
    embedding_dimensions: int = 1536
    embedding_batch_size: int = 100  # texts per embeddings request
    llm_model: str = "gpt-4o-mini"
    llm_temperature: float = 0.2
    llm_max_tokens: int = 700

    # Chunking (in tokens)
    chunk_size: int = 500
    chunk_overlap: int = 50

    # Ingestion limits
    max_document_chars: int = 2_000_000
    max_bulk_documents: int = 100
    max_upload_bytes: int = 20 * 1024 * 1024  # 20 MB per uploaded file
    bulk_ingest_concurrency: int = 4

    # Search
    default_search_limit: int = 5
    max_search_limit: int = 20
    # Ask: chunks scoring below this cosine similarity are treated as irrelevant
    # (text-embedding-3 scores for genuinely related text are typically 0.25-0.7).
    ask_min_relevance: float = 0.2
    # Hybrid search: fuse vector similarity with keyword (BM25) matching
    hybrid_search: bool = True

    # Answering
    # Models users may pick per question (comma-separated). LLM_MODEL is the default.
    allowed_models: str = "gpt-4o-mini,gpt-4o"
    general_max_tokens: int = 1500  # general-knowledge answers can be longer

    # Security: shared secret the gateway sends in X-Internal-API-Key.
    # Leave empty to disable (local development only).
    internal_api_key: str = ""
    # true = refuse to start without a real INTERNAL_API_KEY (use for anything public)
    security_strict: bool = False

    # CORS
    cors_allowed_origins: str = "http://localhost:3000,http://localhost:8080"

    # Logging
    log_level: str = "INFO"

    def security_problems(self) -> List[str]:
        """Settings that are unsafe for a public deployment."""
        problems = []
        if not self.internal_api_key or self.internal_api_key.startswith("change-me"):
            problems.append("INTERNAL_API_KEY is empty or the default value")
        if "*" in self.cors_origins:
            problems.append("CORS_ALLOWED_ORIGINS must not contain '*'")
        return problems

    @property
    def models(self) -> List[str]:
        """Selectable chat models; the default model is always first."""
        names = [m.strip() for m in self.allowed_models.split(",") if m.strip()]
        return [self.llm_model] + [m for m in names if m != self.llm_model]

    @property
    def cors_origins(self) -> List[str]:
        return [o.strip() for o in self.cors_allowed_origins.split(",") if o.strip()]


@lru_cache()
def get_settings() -> Settings:
    """Get cached settings instance."""
    return Settings()
