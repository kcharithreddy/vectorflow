"""Hybrid retrieval helpers: keyword extraction, BM25 scoring and rank fusion.

Vector search finds passages with the same *meaning*; it can miss exact terms
it has never seen (product names, error codes, header names such as
``X-RateLimit-Remaining``).  Hybrid search adds keyword candidates and fuses
both rankings with Reciprocal Rank Fusion, so a passage that is strong on
either signal rises to the top.
"""

import math
import re
from collections import Counter
from typing import Dict, List, Sequence

TOKEN_RE = re.compile(r"[a-z0-9][a-z0-9_\-\.]*[a-z0-9]|[a-z0-9]")

STOPWORDS = frozenset(
    """a about above after again all also am an and any are as at be been before being below between both
    but by can could did do does doing down during each few for from further had has have having he her
    here hers him his how i if in into is it its itself just me more most my no nor not now of off on once
    only or other our out over own same she should so some such than that the their them then there these
    they this those through to too under until up very was we were what when where which while who whom
    why will with would you your yours explain tell describe give show please list many much use used using
    get gets does work works mean means""".split()
)

RRF_K = 60  # standard Reciprocal Rank Fusion constant
MAX_KEYWORDS = 5


def tokenize(text: str) -> List[str]:
    return TOKEN_RE.findall(text.lower())


def keywords(query: str, limit: int = MAX_KEYWORDS) -> List[str]:
    """Significant query terms, rarest-looking (longest) first."""
    seen: Dict[str, None] = {}
    for tok in tokenize(query):
        tok = tok.strip(".-_")
        if len(tok) >= 3 and tok not in STOPWORDS and not tok.isdigit():
            seen.setdefault(tok, None)
    return sorted(seen, key=len, reverse=True)[:limit]


def bm25_scores(query_terms: Sequence[str], documents: Sequence[str], k1: float = 1.5, b: float = 0.75) -> List[float]:
    """Okapi BM25 of each document for the query terms (IDF computed over ``documents``)."""
    docs = [tokenize(d) for d in documents]
    if not docs or not query_terms:
        return [0.0] * len(docs)
    n = len(docs)
    avgdl = (sum(len(d) for d in docs) / n) or 1.0
    freqs = [Counter(d) for d in docs]
    df = {t: sum(1 for f in freqs if t in f) for t in query_terms}
    scores = []
    for doc, f in zip(docs, freqs):
        s = 0.0
        for t in query_terms:
            tf = f.get(t, 0)
            if not tf:
                continue
            idf = math.log(1 + (n - df[t] + 0.5) / (df[t] + 0.5))
            s += idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * len(doc) / avgdl))
        scores.append(s)
    return scores


def rrf_fuse(rankings: Sequence[Sequence[str]], k: int = RRF_K) -> Dict[str, float]:
    """Reciprocal Rank Fusion: sum of 1 / (k + rank) over every ranking an id appears in."""
    fused: Dict[str, float] = {}
    for ranking in rankings:
        for rank, item in enumerate(ranking, 1):
            fused[item] = fused.get(item, 0.0) + 1.0 / (k + rank)
    return fused
