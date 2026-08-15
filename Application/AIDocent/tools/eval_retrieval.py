#!/usr/bin/env python3
"""Phase 2 gate: score candidate embedders on datasets/rag_goldset.jsonl.

See docs/notes/2026-08-13-rag-roadmap.md §3 Phase 2. This runs entirely on the PC —
no app code, no device. If recall@k isn't good enough here, Phase 3 never starts.

What gets varied (one axis at a time, per CLAUDE.md's "one variable at a time"):

  model     which embedder (see MODELS below)
  format    what text we embed for a passage: body only, or with a metadata header
            (roadmap §5-3 #1 — the embedder only knows the string it was handed)
  query     how much of the recognized artwork we prepend to the question (QUERY_FORMATS).
            The app knows the whole `Artwork` record before it retrieves, and visitor
            questions lean on it ("who taught HIM?"), so the bare question understates
            what we can actually do. But the description alone is ~780 chars against a
            6-word question — prepend all of it and the query stops being about the
            question. That is why this is an axis and not a fixed choice.
  strategy  how we search:
              vector        plain cosine over all 60 chunks
              type_oracle   restrict to the gold question's type — an UPPER BOUND,
                            not a shippable strategy: at runtime nobody tells us the
                            question's type. Read it as "how much headroom would a
                            perfect type router buy?" (roadmap §2-1)
              artist_hybrid vector search, but chunks whose `artist` surname appears
                            in the question are pulled to the front (roadmap §2-1's
                            metadata-filter hybrid)

Pooling and L2 normalization are implemented here rather than pulled in from
sentence-transformers on purpose: Phase 3 has to reproduce this exact arithmetic in
Kotlin/ONNX, and the parity check (cosine > 0.999) is only meaningful if we know
precisely what the PC side did.

Metrics are recall@1/3/5 and MRR@10, reported overall and per question type. Two of
the 40 questions have two gold chunks, so recall@k is |gold ∩ top-k| / |gold| — not
just "was anything relevant in there".

Usage:
    python tools/eval_retrieval.py                        # all models × formats, vector search
    python tools/eval_retrieval.py --models e5-small-v2 --strategies vector type_oracle
    python tools/eval_retrieval.py --out-json results.json --out-md table.md
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import numpy as np
import torch
from transformers import AutoModel, AutoTokenizer

# Candidate embedders. English-only on purpose (roadmap decisions 3 and 4 removed the
# language variable, so a multilingual model would only cost accuracy per parameter).
# All are 384-dim except e5-base, which is here as a "does a bigger model even help?"
# reference — it is 3x the download and we would rather not ship it.
MODELS = {
    "e5-small-v2": {
        "model_id": "intfloat/e5-small-v2",
        "pooling": "mean",
        "query_prefix": "query: ",
        "passage_prefix": "passage: ",
    },
    "bge-small-en-v1.5": {
        "model_id": "BAAI/bge-small-en-v1.5",
        "pooling": "cls",
        "query_prefix": "Represent this sentence for searching relevant passages: ",
        "passage_prefix": "",
    },
    "all-MiniLM-L6-v2": {
        "model_id": "sentence-transformers/all-MiniLM-L6-v2",
        "pooling": "mean",
        "query_prefix": "",
        "passage_prefix": "",
    },
    "e5-base-v2": {
        "model_id": "intfloat/e5-base-v2",
        "pooling": "mean",
        "query_prefix": "query: ",
        "passage_prefix": "passage: ",
    },
}

DEFAULT_MODELS = ["e5-small-v2", "bge-small-en-v1.5", "all-MiniLM-L6-v2"]
FORMATS = ["body", "meta"]
# How much of the recognized artwork goes into the search query. `q` is the honest
# floor (what a system with no artwork context could do) and stays in every comparison.
QUERY_FORMATS = ["q", "q_author", "q_meta", "q_full"]
STRATEGIES = ["vector", "type_oracle", "artist_hybrid"]
KS = (1, 3, 5)
MRR_AT = 10


def read_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def passage_text(chunk: dict, fmt: str) -> str:
    """Turn a chunk into the string we actually embed."""
    if fmt == "body":
        return chunk["text"]
    if fmt == "meta":
        # A short header naming the chunk's type and subject. The hypothesis is that
        # "museum" chunks in particular are hard to reach from visitor phrasing ("what
        # floor?") because the body never says the word "museum" or the artist's name.
        header = chunk["type"]
        if chunk.get("artist"):
            header += f" | {chunk['artist']}"
        return f"{header}\n\n{chunk['text']}"
    raise ValueError(f"unknown format {fmt!r}")


def query_text(question: str, artwork: dict | None, qfmt: str) -> str:
    """Compose the search query from the question and the recognized artwork.

    Mirrors what the app has on hand: `ArtworkRecognizer` hands `InferenceScreen` an
    `Artwork` (title/author/type/technique/school/date/description) before generation.
    `q_full` uses the same fields in the same order as `formatArtworkInfo()` on both
    engines, so if this format wins we can build the query from the object we already
    have rather than inventing a second representation."""
    if qfmt == "q" or artwork is None:
        return question
    if qfmt == "q_author":
        return f"{artwork['author']}. {question}"
    if qfmt == "q_meta":
        return f"{artwork['title']}, {artwork['author']}, {artwork['school']}, {artwork['date']}. {question}"
    if qfmt == "q_full":
        return (
            f"Title: {artwork['title']}\nAuthor: {artwork['author']}\nType: {artwork['type']}\n"
            f"Technique: {artwork['technique']}\nSchool: {artwork['school']}\nDate: {artwork['date']}\n"
            f"Description: {artwork['description']}\n\n{question}"
        )
    raise ValueError(f"unknown query format {qfmt!r}")


def surname(author_key: str) -> str:
    """'HOLBEIN, Hans the Younger' -> 'holbein'. Matches build_chunks.py's convention
    of treating the part before the comma as the surname."""
    return (author_key.split(",")[0] if "," in author_key else author_key).strip().lower()


class Embedder:
    def __init__(self, spec: dict, device: str, max_length: int = 512):
        self.spec = spec
        self.device = device
        self.max_length = max_length
        self.tokenizer = AutoTokenizer.from_pretrained(spec["model_id"])
        self.model = AutoModel.from_pretrained(spec["model_id"]).to(device).eval()

    @torch.no_grad()
    def encode(self, texts: list[str], prefix: str = "", batch_size: int = 16) -> np.ndarray:
        out = []
        for i in range(0, len(texts), batch_size):
            batch = [prefix + t for t in texts[i : i + batch_size]]
            enc = self.tokenizer(
                batch,
                padding=True,
                truncation=True,
                max_length=self.max_length,
                return_tensors="pt",
            ).to(self.device)
            hidden = self.model(**enc).last_hidden_state
            if self.spec["pooling"] == "cls":
                vecs = hidden[:, 0]
            elif self.spec["pooling"] == "mean":
                # Mask before averaging — padding tokens are not content, and including
                # them makes a vector depend on what else was in its batch.
                mask = enc["attention_mask"].unsqueeze(-1).float()
                vecs = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-9)
            else:
                raise ValueError(f"unknown pooling {self.spec['pooling']!r}")
            # L2 normalize so a dot product IS the cosine — this is what lets the
            # on-device store (Phase 4) skip the norms entirely.
            vecs = torch.nn.functional.normalize(vecs, p=2, dim=1)
            out.append(vecs.cpu().numpy().astype(np.float32))
        return np.vstack(out)


def rank_chunks(
    scores: np.ndarray,
    chunks: list[dict],
    question: str,
    gold_type: str,
    strategy: str,
) -> list[str]:
    """Return chunk_ids best-first under the given strategy."""
    order = np.argsort(-scores)
    ranked = [chunks[i] for i in order]

    if strategy == "type_oracle":
        ranked = [c for c in ranked if c["type"] == gold_type]
    elif strategy == "artist_hybrid":
        q = question.lower()
        # Exact surname match beats approximate vector search when it fires at all —
        # there are only 26 artists, so this is a lookup table, not NLP.
        hit = [c for c in ranked if c.get("artist") and re.search(rf"\b{re.escape(surname(c['artist']))}\b", q)]
        rest = [c for c in ranked if c not in hit]
        ranked = hit + rest
    elif strategy != "vector":
        raise ValueError(f"unknown strategy {strategy!r}")

    return [c["chunk_id"] for c in ranked]


def score_run(ranked_ids: list[str], gold: list[str]) -> dict:
    gold_set = set(gold)
    row = {}
    for k in KS:
        found = len(gold_set & set(ranked_ids[:k]))
        row[f"recall@{k}"] = found / len(gold_set)
    rr = 0.0
    for rank, cid in enumerate(ranked_ids[:MRR_AT], start=1):
        if cid in gold_set:
            rr = 1.0 / rank
            break
    row[f"mrr@{MRR_AT}"] = rr
    return row


def aggregate(rows: list[dict]) -> dict:
    keys = [f"recall@{k}" for k in KS] + [f"mrr@{MRR_AT}"]
    return {k: (sum(r[k] for r in rows) / len(rows) if rows else 0.0) for k in keys}


def evaluate(
    embedder: Embedder,
    chunks: list[dict],
    gold: list[dict],
    fmt: str,
    qfmt: str,
    strategies: list[str],
    artworks: dict[str, dict],
    doc_vecs: np.ndarray,
) -> dict:
    spec = embedder.spec
    queries = [query_text(g["question"], artworks.get(g.get("context_artwork")), qfmt) for g in gold]
    q_vecs = embedder.encode(queries, prefix=spec["query_prefix"])
    sims = q_vecs @ doc_vecs.T  # both L2-normalized -> cosine

    results = {}
    for strategy in strategies:
        per_question = []
        for i, g in enumerate(gold):
            # Surname matching stays on the raw question: if it saw the augmented query
            # it would fire on the context artist for every single question, which is a
            # different mechanism (context boosting) wearing this strategy's name.
            ranked_ids = rank_chunks(sims[i], chunks, g["question"], g["type"], strategy)
            row = score_run(ranked_ids, g["answer_chunks"])
            row["qid"] = g["qid"]
            row["type"] = g["type"]
            row["top5"] = ranked_ids[:5]
            per_question.append(row)
        by_type = {}
        for t in sorted({g["type"] for g in gold}):
            by_type[t] = aggregate([r for r in per_question if r["type"] == t])
        results[strategy] = {
            "overall": aggregate(per_question),
            "by_type": by_type,
            "per_question": per_question,
        }
    return results


def fmt_row(label: str, m: dict) -> str:
    cells = " | ".join(f"{m[f'recall@{k}']:.3f}" for k in KS)
    return f"| {label} | {cells} | {m[f'mrr@{MRR_AT}']:.3f} |"


def render_markdown(report: dict) -> str:
    header = f"| run | {' | '.join(f'R@{k}' for k in KS)} | MRR@{MRR_AT} |"
    sep = "|---" * (len(KS) + 2) + "|"
    lines = ["## Overall", "", header, sep]
    for run in report["runs"]:
        for strategy, res in run["results"].items():
            lines.append(fmt_row(f"{run['model']} / {run['format']} / {run['query_format']} / {strategy}", res["overall"]))

    lines += ["", "## By question type", ""]
    for run in report["runs"]:
        for strategy, res in run["results"].items():
            lines += [
                f"**{run['model']} / {run['format']} / {run['query_format']} / {strategy}**",
                "",
                header,
                sep,
            ]
            for t, m in res["by_type"].items():
                lines.append(fmt_row(t, m))
            lines.append("")
    return "\n".join(lines)


def render_failures(report: dict, limit: int) -> str:
    """The questions nobody retrieved. These are the ones to eyeball: a stubborn miss is
    as likely to be a bad goldset row as a bad embedder (overnight-tasks.md, 결과 절)."""
    misses: dict[str, int] = {}
    for run in report["runs"]:
        for res in run["results"].values():
            for r in res["per_question"]:
                if r["recall@5"] == 0.0:
                    misses[r["qid"]] = misses.get(r["qid"], 0) + 1
    total_runs = sum(len(run["results"]) for run in report["runs"])
    lines = [f"## Missed at k=5 (out of {total_runs} runs)", ""]
    for qid, n in sorted(misses.items(), key=lambda kv: -kv[1])[:limit]:
        lines.append(f"- {qid}: missed in {n}/{total_runs}")
    if not misses:
        lines.append("- (none)")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--chunks", default="datasets/rag_chunks.jsonl")
    parser.add_argument("--goldset", default="datasets/rag_goldset.jsonl")
    parser.add_argument("--artworks", default="datasets/exhibition_northern-renaissance-295.jsonl")
    parser.add_argument("--models", nargs="+", default=DEFAULT_MODELS, choices=list(MODELS))
    parser.add_argument("--formats", nargs="+", default=FORMATS, choices=FORMATS)
    parser.add_argument("--query-formats", nargs="+", default=["q"], choices=QUERY_FORMATS)
    parser.add_argument("--strategies", nargs="+", default=["vector"], choices=STRATEGIES)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--out-json", help="write the full report (incl. per-question top-5) here")
    parser.add_argument("--out-md", help="write the markdown tables here")
    parser.add_argument("--show-misses", type=int, default=10)
    args = parser.parse_args()

    chunks = read_jsonl(Path(args.chunks))
    gold = read_jsonl(Path(args.goldset))
    artworks = {a["id"]: a for a in read_jsonl(Path(args.artworks))}

    needs_context = [qf for qf in args.query_formats if qf != "q"]
    if needs_context:
        missing = [g["qid"] for g in gold if g.get("context_artwork") not in artworks]
        if missing:
            sys.exit(
                f"ERROR: --query-formats {needs_context} need `context_artwork` on every "
                f"question; missing or unknown on: {missing[:5]}... "
                f"(run tools/annotate_goldset_context.py)"
            )

    # Fail loud: scoring against chunk_ids that don't exist would quietly report 0.0
    # and look like an embedder problem.
    known = {c["chunk_id"] for c in chunks}
    for g in gold:
        unknown = set(g["answer_chunks"]) - known
        if unknown:
            sys.exit(f"ERROR: {g['qid']} references unknown chunk_id(s): {unknown}")

    print(f"{len(chunks)} chunks, {len(gold)} questions, device={args.device}", file=sys.stderr)

    report = {
        "chunks": len(chunks),
        "questions": len(gold),
        "ks": list(KS),
        "runs": [],
    }
    for model_name in args.models:
        print(f"loading {model_name} ...", file=sys.stderr)
        embedder = Embedder(MODELS[model_name], args.device)
        for fmt in args.formats:
            # Passages don't depend on the query format — encode them once per (model,
            # format) instead of once per cell of the matrix.
            doc_vecs = embedder.encode(
                [passage_text(c, fmt) for c in chunks], prefix=embedder.spec["passage_prefix"]
            )
            for qfmt in args.query_formats:
                print(f"  {model_name} / {fmt} / {qfmt}", file=sys.stderr)
                results = evaluate(
                    embedder, chunks, gold, fmt, qfmt, args.strategies, artworks, doc_vecs
                )
                report["runs"].append(
                    {"model": model_name, "format": fmt, "query_format": qfmt, "results": results}
                )
        del embedder

    md = render_markdown(report) + "\n\n" + render_failures(report, args.show_misses)
    print(md)

    if args.out_md:
        Path(args.out_md).write_text(md + "\n", encoding="utf-8")
    if args.out_json:
        Path(args.out_json).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
