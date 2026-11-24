"""Concurrency smoke test: compares sequential vs parallel search latency.

Run after ingesting some data (see scripts/bulk_ingest_data.py):
    pip install httpx
    INTERNAL_API_KEY=... python test_parallel.py
"""

import asyncio
import os
import time

import httpx

BASE_URL = os.getenv("PYTHON_SERVICE_URL", "http://localhost:8000") + "/api"
HEADERS = {"X-Internal-API-Key": os.getenv("INTERNAL_API_KEY", "change-me-internal-api-key")}
QUERIES = [
    "What is Qdrant?",
    "How does FastAPI handle async requests?",
    "What is a Docker container?",
    "Explain vector embeddings",
    "How do JWT tokens work?",
]


async def search(client: httpx.AsyncClient, query: str, req_id: int) -> float:
    start = time.perf_counter()
    resp = await client.post(f"{BASE_URL}/search", json={"query": query, "limit": 3})
    elapsed = time.perf_counter() - start
    print(f"  Request {req_id} ({query[:25]}...): {elapsed:.3f}s  [HTTP {resp.status_code}]")
    return elapsed


async def main() -> None:
    async with httpx.AsyncClient(headers=HEADERS, timeout=60) as client:
        print("=== Sequential (one at a time) ===")
        seq_start = time.perf_counter()
        for i, q in enumerate(QUERIES):
            await search(client, q, i)
        seq_total = time.perf_counter() - seq_start
        print(f"  Total: {seq_total:.3f}s\n")

        print("=== Parallel (all at once) ===")
        par_start = time.perf_counter()
        await asyncio.gather(*(search(client, q, i) for i, q in enumerate(QUERIES)))
        par_total = time.perf_counter() - par_start
        print(f"  Total: {par_total:.3f}s\n")

    speedup = seq_total / par_total
    print(f"Speedup: {speedup:.1f}x")
    if speedup > 2.0:
        print("Parallelism is working!")
    elif speedup > 1.3:
        print("Some parallelism detected, but limited.")
    else:
        print("WARNING: No significant speedup - check async implementation.")


if __name__ == "__main__":
    asyncio.run(main())
