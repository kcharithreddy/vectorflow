#!/usr/bin/env python3
"""VectorFlow RAG evaluation.

Measures retrieval accuracy, answer quality, honesty on off-topic questions and
latency against a running Python AI service, using a fixed question set
(eval/dataset.json) over a known corpus.

The evaluation documents are ingested with their own tag ("vectorflow-eval")
and every query is filtered to that tag, so your own documents are never
read, changed or mixed into the scores. They are deleted again at the end
(use --keep to leave them).

Usage (from the project folder, with the stack running):
    python3 eval/run_eval.py
    python3 eval/run_eval.py --url http://localhost:8000 --api-key <INTERNAL_API_KEY>

Standard library only - no pip install needed. Costs a fraction of a cent of
OpenAI usage per run (about 35 embeddings + 32 short answers).
"""

import argparse
import ast
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EVAL_DIR = Path(__file__).resolve().parent
EVAL_TAG = "vectorflow-eval"
EVAL_FILTER = {"tags": [EVAL_TAG]}
NAMESPACE = uuid.UUID("6b1e4c3a-2f0d-4c8e-9a51-7d2f9e0b4a11")
TOP_K = 5


# --------------------------------------------------------------------------
# HTTP helpers (stdlib only)
# --------------------------------------------------------------------------

class Api:
    def __init__(self, base_url: str, api_key: str):
        self.base = base_url.rstrip("/")
        self.headers = {"Content-Type": "application/json"}
        if api_key:
            self.headers["X-Internal-API-Key"] = api_key

    def call(self, method: str, path: str, body=None, timeout=120):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=self.headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                text = resp.read().decode()
                return resp.status, (json.loads(text) if text else None)
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            try:
                detail = json.loads(detail).get("detail", detail)
            except Exception:
                pass
            return e.code, {"detail": detail}
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            return 0, {"detail": str(getattr(e, "reason", e))}


# --------------------------------------------------------------------------
# Corpus
# --------------------------------------------------------------------------

def load_corpus():
    """Sample-data markdown files + the documents defined in scripts/bulk_ingest_data.py."""
    docs = []
    for path in sorted((ROOT / "sample-data").glob("*.md")):
        text = path.read_text(encoding="utf-8")
        title = text.strip().splitlines()[0].lstrip("# ").strip()
        docs.append({"title": title, "content": text})

    # Read DOCUMENTS without importing the script (it needs httpx).
    tree = ast.parse((ROOT / "scripts" / "bulk_ingest_data.py").read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.AnnAssign) and getattr(node.target, "id", "") == "DOCUMENTS":
            for d in ast.literal_eval(node.value):
                docs.append({"title": d["title"], "content": d["content"]})
        elif isinstance(node, ast.Assign) and any(getattr(t, "id", "") == "DOCUMENTS" for t in node.targets):
            for d in ast.literal_eval(node.value):
                docs.append({"title": d["title"], "content": d["content"]})
    return docs


def eval_doc_id(title: str) -> str:
    return str(uuid.uuid5(NAMESPACE, title))


# --------------------------------------------------------------------------
# Scoring helpers
# --------------------------------------------------------------------------

REFUSAL_MARKERS = (
    "couldn't find any relevant information", "could not find any relevant information",
    "don't have enough information", "do not have enough information", "not enough information",
    "don't contain", "do not contain", "doesn't contain", "does not contain",
    "no information about", "cannot answer", "can't answer", "unable to answer",
)


def is_refusal(answer: str) -> bool:
    text = answer.lower().replace("’", "'")
    return any(m in text for m in REFUSAL_MARKERS)


def percentile(values, p):
    if not values:
        return 0.0
    values = sorted(values)
    k = (len(values) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (k - lo)


def unique_titles(results):
    seen = []
    for r in results:
        if r["title"] not in seen:
            seen.append(r["title"])
    return seen


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def read_env_key() -> str:
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("INTERNAL_API_KEY="):
                return line.split("=", 1)[1].strip()
    return "change-me-internal-api-key"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default=os.getenv("PYTHON_SERVICE_URL", "http://localhost:8000"))
    parser.add_argument("--api-key", default=os.getenv("INTERNAL_API_KEY") or read_env_key())
    parser.add_argument("--keep", action="store_true", help="leave the evaluation documents in the index")
    parser.add_argument("--out", default=str(EVAL_DIR / "results"), help="folder for the report files")
    args = parser.parse_args()

    api = Api(args.url, args.api_key)
    dataset = json.loads((EVAL_DIR / "dataset.json").read_text(encoding="utf-8"))

    status, health = api.call("GET", "/health", timeout=10)
    if status != 200:
        print(f"Cannot reach the Python service at {args.url} (HTTP {status}). Is the stack running?")
        return 1
    if not health.get("openai_configured"):
        print("The Python service has no OPENAI_API_KEY configured - set it in .env first.")
        return 1

    corpus = load_corpus()
    titles = {d["title"] for d in corpus}
    missing = {q["expected_document"] for q in dataset["answerable"]} - titles
    if missing:
        print(f"Dataset refers to documents that aren't in the corpus: {missing}")
        return 1

    # ---- ingest -----------------------------------------------------------
    print(f"Ingesting {len(corpus)} evaluation documents (tag '{EVAL_TAG}')...")
    chunks_total = 0
    for doc in corpus:
        status, res = api.call("POST", "/api/ingest", {
            "title": doc["title"], "content": doc["content"], "document_id": eval_doc_id(doc["title"]),
            "metadata": {"category": "evaluation", "tags": [EVAL_TAG]},
        })
        if status != 200:
            print(f"  ingest failed for {doc['title']!r}: HTTP {status} {res}")
            return 1
        chunks_total += res["chunk_count"]
    print(f"  {chunks_total} chunks indexed\n")

    rows, search_ms, ask_ms = [], [], []
    try:
        # ---- answerable questions ----------------------------------------
        for i, item in enumerate(dataset["answerable"], 1):
            q, expected = item["question"], item["expected_document"]

            t0 = time.perf_counter()
            _, s = api.call("POST", "/api/search", {"query": q, "limit": TOP_K, "filters": EVAL_FILTER})
            search_ms.append((time.perf_counter() - t0) * 1000)
            ranked = unique_titles(s.get("results", []))
            rank = ranked.index(expected) + 1 if expected in ranked else None

            t0 = time.perf_counter()
            status, a = api.call("POST", "/api/ask", {"question": q, "max_sources": TOP_K, "filters": EVAL_FILTER})
            ask_ms.append((time.perf_counter() - t0) * 1000)
            if status != 200:
                print(f"  ask failed: HTTP {status} {a}")
                return 1
            answer = a["answer"]
            found = [k for k in item["must_include"] if k.lower() in answer.lower()]
            cited = bool(re.search(r"\[\d+\]", answer)) and bool(a["sources"])
            correct_source = any(src["title"] == expected for src in a["sources"])

            rows.append({
                "type": "answerable", "question": q, "expected_document": expected,
                "retrieved": ranked, "rank": rank,
                "answer": answer, "confidence": a["confidence"],
                "fact_recall": len(found) / len(item["must_include"]), "facts_missing": [k for k in item["must_include"] if k not in found],
                "cited": cited, "correct_source_cited": correct_source, "refused": is_refusal(answer),
            })
            mark = "✓" if rank == 1 and len(found) == len(item["must_include"]) else "·"
            print(f"  {mark} [{i:2}/{len(dataset['answerable'])}] rank={rank or '-'} facts={len(found)}/{len(item['must_include'])}  {q}")

        # ---- unanswerable questions ---------------------------------------
        print()
        for item in dataset["unanswerable"]:
            q = item["question"]
            t0 = time.perf_counter()
            _, a = api.call("POST", "/api/ask", {"question": q, "max_sources": TOP_K, "filters": EVAL_FILTER})
            ask_ms.append((time.perf_counter() - t0) * 1000)
            honest = is_refusal(a["answer"]) or not a["sources"]
            rows.append({"type": "unanswerable", "question": q, "answer": a["answer"],
                         "confidence": a["confidence"], "honest": honest})
            print(f"  {'✓' if honest else '✗'} [off-topic] {q}  ->  {a['answer'][:70]}")
    finally:
        if not args.keep:
            for doc in corpus:
                api.call("DELETE", f"/api/documents/{eval_doc_id(doc['title'])}")

    # ---- metrics ------------------------------------------------------------
    ans = [r for r in rows if r["type"] == "answerable"]
    off = [r for r in rows if r["type"] == "unanswerable"]
    n = len(ans)
    metrics = {
        "retrieval_hit_at_1": sum(r["rank"] == 1 for r in ans) / n,
        "retrieval_hit_at_5": sum(r["rank"] is not None for r in ans) / n,
        "retrieval_mrr": sum(1 / r["rank"] for r in ans if r["rank"]) / n,
        "answer_fact_recall": sum(r["fact_recall"] for r in ans) / n,
        "answer_exact_facts": sum(r["fact_recall"] == 1 for r in ans) / n,
        "answers_with_citation": sum(r["cited"] for r in ans) / n,
        "correct_source_cited": sum(r["correct_source_cited"] for r in ans) / n,
        "false_refusals": sum(r["refused"] for r in ans) / n,
        "offtopic_honesty": sum(r["honest"] for r in off) / len(off) if off else 0.0,
        "avg_confidence_answerable": statistics.mean(r["confidence"] for r in ans),
        "avg_confidence_offtopic": statistics.mean(r["confidence"] for r in off) if off else 0.0,
        "search_latency_p50_ms": percentile(search_ms, 0.5),
        "ask_latency_p50_ms": percentile(ask_ms, 0.5),
        "ask_latency_p95_ms": percentile(ask_ms, 0.95),
    }

    report = render_report(metrics, rows, len(corpus), chunks_total, args.url)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "latest.md").write_text(report, encoding="utf-8")
    (out / "latest.json").write_text(json.dumps({"metrics": metrics, "rows": rows,
                                                 "generated_at": datetime.now(timezone.utc).isoformat()}, indent=2),
                                     encoding="utf-8")
    print("\n" + report.split("## Per-question results")[0])
    print(f"Full report: {out / 'latest.md'}")
    return 0


def pct(x):
    return f"{x * 100:.0f}%"


def render_report(m, rows, n_docs, n_chunks, url) -> str:
    ans = [r for r in rows if r["type"] == "answerable"]
    off = [r for r in rows if r["type"] == "unanswerable"]
    lines = [
        "# VectorFlow evaluation report",
        "",
        f"Generated {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')} · corpus: {n_docs} documents, "
        f"{n_chunks} chunks · {len(ans)} answerable + {len(off)} off-topic questions · top-k = {TOP_K}",
        "",
        "## Summary",
        "",
        "| Metric | Score | What it means |",
        "|---|---|---|",
        f"| Retrieval hit@1 | **{pct(m['retrieval_hit_at_1'])}** | the right document was the top result |",
        f"| Retrieval hit@5 | **{pct(m['retrieval_hit_at_5'])}** | the right document was in the top 5 |",
        f"| Retrieval MRR | **{m['retrieval_mrr']:.2f}** | mean reciprocal rank (1.0 = always first) |",
        f"| Answer fact recall | **{pct(m['answer_fact_recall'])}** | share of expected key facts present in answers |",
        f"| Fully correct answers | **{pct(m['answer_exact_facts'])}** | answers containing every expected fact |",
        f"| Answers with citations | **{pct(m['answers_with_citation'])}** | answers that cite a numbered source |",
        f"| Correct source cited | **{pct(m['correct_source_cited'])}** | the expected document is among the answer's sources |",
        f"| False refusals | **{pct(m['false_refusals'])}** | answerable questions wrongly answered with \"I don't know\" |",
        f"| Off-topic honesty | **{pct(m['offtopic_honesty'])}** | off-topic questions correctly declined (no hallucination) |",
        f"| Avg confidence | {m['avg_confidence_answerable']:.2f} answerable / {m['avg_confidence_offtopic']:.2f} off-topic | higher for real answers = well calibrated |",
        f"| Latency | search p50 {m['search_latency_p50_ms']:.0f} ms · ask p50 {m['ask_latency_p50_ms']:.0f} ms · ask p95 {m['ask_latency_p95_ms']:.0f} ms | end-to-end via {url} |",
        "",
        "## Per-question results",
        "",
        "| # | Question | Rank | Facts | Cited | Answer |",
        "|---|---|---|---|---|---|",
    ]
    for i, r in enumerate(ans, 1):
        answer = r["answer"].replace("\n", " ").replace("|", "\\|")
        missing = f" (missing: {', '.join(r['facts_missing'])})" if r["facts_missing"] else ""
        lines.append(f"| {i} | {r['question']} | {r['rank'] or '–'} | {pct(r['fact_recall'])}{missing} | "
                     f"{'yes' if r['cited'] else 'no'} | {answer[:160]} |")
    lines += ["", "### Off-topic questions", "", "| Question | Declined | Answer |", "|---|---|---|"]
    for r in off:
        answer = r["answer"].replace("\n", " ").replace("|", "\\|")
        lines.append(f"| {r['question']} | {'yes' if r['honest'] else '**no**'} | {answer[:160]} |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    sys.exit(main())
