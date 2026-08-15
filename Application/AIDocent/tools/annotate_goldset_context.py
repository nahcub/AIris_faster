#!/usr/bin/env python3
"""Add `context_artwork` to every row of datasets/rag_goldset.jsonl (in place).

Why this field exists
---------------------
The goldset questions are written the way a visitor actually speaks in front of a
painting -- "who taught him after van Eyck passed away?", "where was this painter
born?". Those questions have no resolvable referent as bare strings, and the first
Phase 2 run confirmed it: `artist` questions scored worst on every embedder, and the
misses were exactly the pronoun ones. No embedder can fix that, because the missing
information was never in the query.

But the app is not missing it. `ArtworkRecognizer.recognize()` returns the whole
`Artwork` record by exact key lookup before any generation happens (roadmap decision 2),
so at query time we know the title, author, school, date and description of the work
the visitor is standing in front of. Evaluating retrieval on the bare question measures
a system strictly worse than the one we are building.

`context_artwork` records which exhibition artwork the visitor is standing in front of
for that question -- i.e. what `ArtworkRecognizer` would have returned. eval_retrieval.py
then composes the search query from that record plus the question (see --query-formats),
so the eval and the app see the same information.

Assignment rules (deterministic, so rerunning this never churns the file)
------------------------------------------------------------------------
1. If the question already names an artist by surname ("is van der Weyden's stuff in the
   same room as Memling's?"), its referent is in the query string already and nothing
   needs resolving -- rule 3 applies. This is checked first on purpose: it keeps the
   derivation below to the questions that genuinely cannot be answered without context.
2. Else, if any gold chunk carries an `artist`, the visitor is in front of a work by
   that artist -- that is the scenario the question was written under. Among that
   artist's works in the exhibition, pick by longest description, then id ascending
   (the same tie-break the exhibition selection used, roadmap §1-1).
3. Otherwise (general `movement` and `museum` questions, plus rule 1's) there is no
   referent to resolve; the visitor could be standing anywhere. Assign the pinned
   Darmstadt Madonna, which is what `FixedArtworkRecognizer` returns today.

⚠️ Rule 2 derives the scenario from the gold answer, which would be circular if we then
claimed retrieval had gotten easy. It is not circular for what we actually measure:
the app knows the artist with certainty from a key lookup, never from the retrieval it
is about to run. Keep the no-context numbers in every comparison anyway -- eval_retrieval.py
prints the `q` (question-only) query format alongside the augmented ones for exactly
this reason.

⚠️ Rule 3 is the interesting half. Those questions have nothing to do with the
Darmstadt Madonna, so prepending it is *irrelevant* context. If augmentation helps
`artist` questions but drags `movement`/`museum` down, that trade-off is the finding,
not a bug -- read the per-type table, never just the overall number.

Usage:
    python tools/annotate_goldset_context.py
    python tools/annotate_goldset_context.py --dry-run
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

PINNED_ARTWORK_ID = "19873-1darmst.jpg"  # Darmstadt Madonna, FixedArtworkRecognizer's stub


def surname(author_key: str) -> str:
    """'WEYDEN, Rogier van der' -> 'weyden'. Same convention as build_chunks.py."""
    return (author_key.split(",")[0] if "," in author_key else author_key).strip().lower()


def names_an_artist(question: str, author_keys: set[str]) -> bool:
    q = question.lower()
    return any(re.search(rf"\b{re.escape(surname(k))}\b", q) for k in author_keys)


def read_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def pick_artwork(artworks: list[dict], author: str) -> dict:
    candidates = [a for a in artworks if a["author"] == author]
    if not candidates:
        sys.exit(f"ERROR: no exhibition artwork by {author!r}")
    return sorted(candidates, key=lambda a: (-len(a.get("description") or ""), a["id"]))[0]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--goldset", default="datasets/rag_goldset.jsonl")
    parser.add_argument("--chunks", default="datasets/rag_chunks.jsonl")
    parser.add_argument("--artworks", default="datasets/exhibition_northern-renaissance-295.jsonl")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    gold = read_jsonl(Path(args.goldset))
    chunks = {c["chunk_id"]: c for c in read_jsonl(Path(args.chunks))}
    artworks = read_jsonl(Path(args.artworks))
    by_id = {a["id"]: a for a in artworks}
    if PINNED_ARTWORK_ID not in by_id:
        sys.exit(f"ERROR: pinned artwork {PINNED_ARTWORK_ID} is not in {args.artworks}")

    author_keys = {a["author"] for a in artworks}
    n_derived = 0
    for g in gold:
        artists = {chunks[c].get("artist") for c in g["answer_chunks"] if chunks[c].get("artist")}
        if names_an_artist(g["question"], author_keys) or not artists:
            artwork = by_id[PINNED_ARTWORK_ID]
        else:
            if len(artists) > 1:
                sys.exit(
                    f"ERROR: {g['qid']} needs a context artwork but its gold chunks span "
                    f"multiple artists ({artists}) and the question names none of them"
                )
            artwork = pick_artwork(artworks, artists.pop())
            n_derived += 1
        g["context_artwork"] = artwork["id"]

    print(f"{n_derived} questions got an artist-derived context artwork, "
          f"{len(gold) - n_derived} fell back to the pinned {PINNED_ARTWORK_ID} "
          f"(question already names the artist, or has no artist referent)")
    for g in gold[:3]:
        a = by_id[g["context_artwork"]]
        print(f"  {g['qid']} [{g['type']}] {g['question']}  ->  {a['title']} ({a['author']})")

    if args.dry_run:
        print("(dry run: nothing written)")
        return

    with Path(args.goldset).open("w", encoding="utf-8") as f:
        for g in gold:
            f.write(json.dumps(g, ensure_ascii=False) + "\n")
    print(f"wrote {len(gold)} rows to {args.goldset}")


if __name__ == "__main__":
    main()
