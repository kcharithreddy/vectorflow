"""Bulk-ingest sample data into the VectorFlow Python service.

Usage:
    python scripts/bulk_ingest_data.py                    # built-in docs + sample-data/*.md
    python scripts/bulk_ingest_data.py --no-sample-files  # built-in docs only
    python scripts/bulk_ingest_data.py --url http://localhost:8000 --api-key <INTERNAL_API_KEY>

Requires: pip install httpx

Note: this talks to the Python service directly, so the documents are not
recorded in Postgres (they won't appear in the gateway's `documents` query).
Use the gateway's `bulkIngest` mutation for fully tracked ingestion.
"""

import argparse
import asyncio
import os
import sys
from pathlib import Path
from typing import Dict, List

import httpx

REPO_ROOT = Path(__file__).resolve().parent.parent
SAMPLE_DIR = REPO_ROOT / "sample-data"

DOCUMENTS: List[Dict] = [
    {
        "title": "Python Async Programming",
        "content": "Asynchronous programming in Python uses async and await keywords for concurrent execution. Asyncio is the built-in library for writing asynchronous code. Coroutines are functions defined with async def that can be paused and resumed. The await keyword pauses execution until the awaited coroutine completes. Event loops manage the execution of asynchronous tasks. asyncio.gather() runs multiple coroutines concurrently and waits for all to complete. asyncio.create_task() schedules coroutines for concurrent execution. Async context managers use async with syntax. Async iterators use async for loops. Semaphores and locks provide synchronization primitives. Async is ideal for I/O-bound operations like API calls, database queries, and file operations. It's not beneficial for CPU-bound tasks which should use multiprocessing instead. Proper exception handling with try-except blocks is crucial in async code.",
        "metadata": {"category": "programming", "tags": ["python", "async", "concurrency"]},
    },
    {
        "title": "REST API Design Principles",
        "content": "REST stands for Representational State Transfer, an architectural style for distributed systems. RESTful APIs use HTTP methods semantically: GET for retrieval, POST for creation, PUT for full updates, PATCH for partial updates, DELETE for removal. Use proper HTTP status codes: 200 for success, 201 for created resources, 204 for successful deletion, 400 for bad requests, 401 for unauthorized, 403 for forbidden, 404 for not found, 500 for server errors. API versioning can be done via URL path (/v1/users), headers, or query parameters. Pagination for large result sets uses limit and offset parameters. Implement consistent error response formats with error codes and messages. Authentication methods include JWT tokens, OAuth 2.0, or API keys. Rate limiting prevents API abuse using headers like X-RateLimit-Limit. CORS headers allow cross-origin requests from browsers. Comprehensive documentation using OpenAPI/Swagger specifications is essential. Use HTTPS for all API endpoints to ensure security.",
        "metadata": {"category": "api-design", "tags": ["rest", "api", "http", "web"]},
    },
    {
        "title": "PostgreSQL Database Fundamentals",
        "content": "PostgreSQL is a powerful open-source relational database system with strong ACID compliance. It supports complex queries, foreign keys, triggers, views, and stored procedures. Use CREATE TABLE to define database schemas with data types like INTEGER, VARCHAR, TIMESTAMP, JSONB. Primary keys uniquely identify rows. Indexes improve query performance with CREATE INDEX commands. B-tree indexes are default, while GiST and GIN indexes support full-text search. Foreign keys maintain referential integrity between tables. Joins combine data: INNER JOIN for matching rows, LEFT JOIN for all left table rows, RIGHT JOIN for all right table rows. WHERE clauses filter query results. GROUP BY aggregates data with functions like COUNT, SUM, AVG. HAVING filters grouped results. Transactions ensure data consistency with BEGIN, COMMIT, and ROLLBACK commands. Connection pooling with tools like PgBouncer improves performance under load. VACUUM maintains database health by reclaiming storage.",
        "metadata": {"category": "database", "tags": ["postgresql", "sql", "relational"]},
    },
    {
        "title": "Redis Caching Strategies",
        "content": "Redis is an in-memory data structure store used as database, cache, and message broker. It supports data types including strings, hashes, lists, sets, sorted sets, bitmaps, and streams. SET and GET commands handle simple key-value operations. EXPIRE sets time-to-live for automatic key expiration. Cache-aside pattern loads data from database on cache miss then stores in cache. Write-through strategy updates both cache and database simultaneously. Write-behind queues writes for asynchronous database updates. Use Redis for session storage with automatic expiration. Rate limiting implements sliding window algorithms with sorted sets. Pub/sub messaging enables real-time communication between services. Redis persistence uses RDB snapshots or AOF (Append-Only File) logs for durability. Redis Cluster provides horizontal scaling through sharding. Redis Sentinel offers high availability with automatic failover. Pipeline commands to reduce round-trip latency. Use Lua scripts for atomic multi-command operations.",
        "metadata": {"category": "caching", "tags": ["redis", "cache", "performance"]},
    },
    {
        "title": "JWT Authentication Explained",
        "content": "JSON Web Tokens (JWT) are compact, URL-safe tokens for secure authentication and authorization. JWTs consist of three Base64-encoded parts separated by dots: header, payload, and signature. The header specifies the signing algorithm (HS256, RS256) and token type. The payload contains claims like user ID (sub), issued at (iat), expiration (exp), and custom claims. The signature verifies token integrity using a secret key or public/private key pair. Tokens are stateless - servers don't store session data. Clients send JWTs in the Authorization header with Bearer scheme. Use HTTPS to prevent token interception. Set appropriate expiration times: short for access tokens (15 minutes), long for refresh tokens (7 days). Refresh tokens enable obtaining new access tokens without re-authentication. Validate tokens on every protected request by verifying signature and expiration. Store tokens securely: httpOnly cookies for web apps, secure storage for mobile apps. Never store sensitive data in JWT payloads as they're readable by anyone.",
        "metadata": {"category": "security", "tags": ["jwt", "authentication", "security"]},
    },
]


def load_sample_files() -> List[Dict]:
    docs = []
    for path in sorted(SAMPLE_DIR.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        first_line = text.strip().splitlines()[0] if text.strip() else path.stem
        title = first_line.lstrip("# ").strip() or path.stem
        docs.append({
            "title": title,
            "content": text,
            "metadata": {"category": "sample-docs", "tags": [path.stem]},
        })
    return docs


async def bulk_ingest(documents: List[Dict], api_url: str, api_key: str) -> int:
    print(f"Ingesting {len(documents)} documents into {api_url} ...")
    headers = {"X-Internal-API-Key": api_key} if api_key else {}
    try:
        async with httpx.AsyncClient(timeout=300.0, headers=headers) as client:
            response = await client.post(f"{api_url}/api/bulk-ingest", json={"documents": documents})
    except httpx.ConnectError:
        print(f"Could not connect to {api_url} - is the Python service running?")
        return 1

    if response.status_code != 200:
        print(f"Error {response.status_code}: {response.text}")
        if response.status_code == 401:
            print("Hint: pass --api-key or set INTERNAL_API_KEY to the value in your .env")
        return 1

    result = response.json()
    print(f"\nDone: {result['successful']}/{result['total']} succeeded, {result['failed']} failed\n")
    for res in result["results"]:
        ok = res["status"].lower() == "completed"
        print(f"  [{'OK' if ok else 'FAIL'}] {res['title']}: {res['chunk_count']} chunks - {res['message']}")

    total_chunks = sum(r["chunk_count"] for r in result["results"])
    print(f"\nTotal chunks indexed: {total_chunks}")
    return 0 if result["failed"] == 0 else 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default=os.getenv("PYTHON_SERVICE_URL", "http://localhost:8000"))
    parser.add_argument("--api-key", default=os.getenv("INTERNAL_API_KEY", "change-me-internal-api-key"))
    parser.add_argument("--no-sample-files", action="store_true", help="skip sample-data/*.md")
    args = parser.parse_args()

    documents = list(DOCUMENTS)
    if not args.no_sample_files:
        documents += load_sample_files()
    return asyncio.run(bulk_ingest(documents, args.url.rstrip("/"), args.api_key))


if __name__ == "__main__":
    sys.exit(main())
