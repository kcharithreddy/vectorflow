# Evaluation

A repeatable benchmark for VectorFlow's RAG quality: **retrieval accuracy, answer correctness,
citations, honesty on off-topic questions and latency**.

## Run it

With the stack running (`docker compose up -d`) and a real `OPENAI_API_KEY` in `.env`:

```bash
python3 eval/run_eval.py
```

No installs needed (standard library only). It talks to the Python AI service on
`http://localhost:8000` and reads `INTERNAL_API_KEY` from `.env` (override with `--url` / `--api-key`).
A run costs a fraction of a cent of OpenAI usage.

Results are written to `eval/results/latest.md` (readable report) and `eval/results/latest.json` (raw data).

## How it works

1. Ingests the evaluation corpus (the 3 `sample-data/*.md` files + the 5 documents in
   `scripts/bulk_ingest_data.py`) under their own tag `vectorflow-eval`.
   **Every query is filtered to that tag, so your own documents are never read or mixed into the scores.**
2. For each of the 26 answerable questions in [`dataset.json`](dataset.json):
   - **search** → where does the expected document rank? (hit@1, hit@5, MRR)
   - **ask** → does the answer contain the expected key facts, cite a source, and cite the *right* source?
3. For 6 off-topic questions (e.g. *"What is the capital of France?"*), checks that the system declines
   instead of making something up.
4. Deletes the evaluation documents again (`--keep` to leave them).

## Metrics

| Metric | Meaning |
|---|---|
| Retrieval hit@1 / hit@5 | Expected document is the top result / in the top 5 |
| MRR | Mean reciprocal rank of the expected document (1.0 = always first) |
| Answer fact recall | Share of expected key facts (case-insensitive) found in answers |
| Fully correct answers | Answers containing every expected fact |
| Citations / correct source cited | Answer cites a numbered source / the expected document is among them |
| False refusals | Answerable questions wrongly answered with "I don't know" |
| Off-topic honesty | Off-topic questions correctly declined (hallucination check) |
| Latency p50 / p95 | End-to-end search and ask times |

## Using it to improve the system

Change one thing (chunk size, overlap, `ASK_MIN_RELEVANCE`, embedding or chat model, prompt), re-run,
and compare `latest.md` with the previous run. Add questions to `dataset.json` for your own documents:
each needs the `question`, the `expected_document` title and the `must_include` key facts.
