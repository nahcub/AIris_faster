#!/usr/bin/env python3
"""Build datasets/rag_chunks.jsonl from datasets/rag_sources/*.

Three source files, three chunk types (see docs/notes/2026-08-13-rag-roadmap.md §2-1):

  - artist_bios.jsonl     -> type "artist"    one line = one chunk
  - movement_glossary.md  -> type "movement"  split on "## " headings
  - museum_info.md        -> type "museum"    split on "## " headings, except the
                              "Artist Locations and Holdings" section, which is split
                              again per bullet line (one chunk per artist) so a query
                              like "where is Dürer" doesn't pull in all 26 artists.

Every chunk gets an `artist` field when it's about one specific artist (used for
metadata-filter + embedding hybrid search later, per roadmap §2-1). Artist identity is
resolved against the `author` keys in the curated exhibition dataset, not by string-
matching the free-text names in museum_info.md, because those names use different
Wikipedia-derived spellings (e.g. "Gossaert" vs "Gossart") than the dataset key.

Usage:
    python tools/build_chunks.py
    python tools/build_chunks.py --sources-dir datasets/rag_sources --out datasets/rag_chunks.jsonl
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

HEADING_RE = re.compile(r"^##\s+(.+?)\s*$")
BULLET_RE = re.compile(r"^-\s+\*\*(.+?)\*\*\s+—\s+(.+)$")
QUALIFIERS = ("the Elder", "the Younger")


def read_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def slugify(text: str) -> str:
    # Strip diacritics (Ü -> U, ü -> u) instead of dropping the letter outright —
    # otherwise "Dürer" collapses to "d_rer", which is both ugly and easy to typo
    # in a hand-written goldset.
    text = unicodedata.normalize("NFKD", text)
    text = "".join(c for c in text if not unicodedata.combining(c))
    text = text.lower()
    text = re.sub(r"[^a-z0-9]+", "_", text)
    return text.strip("_")


def split_markdown_sections(md_text: str) -> list[tuple[str, str]]:
    """Split on '## ' (h2) headings. Text before the first heading (the '# Title' line
    and anything else) is discarded on purpose — it's not retrievable content."""
    sections: list[tuple[str, str]] = []
    heading: str | None = None
    body: list[str] = []
    for line in md_text.splitlines():
        m = HEADING_RE.match(line)
        if m:
            if heading is not None:
                sections.append((heading, "\n".join(body).strip()))
            heading = m.group(1)
            body = []
        elif heading is not None:
            body.append(line)
    if heading is not None:
        sections.append((heading, "\n".join(body).strip()))
    return sections


def surname_and_qualifier(author_key: str) -> tuple[str, str | None]:
    surname = author_key.split(",")[0].strip() if "," in author_key else author_key.strip()
    qualifier = next((q for q in QUALIFIERS if q in author_key), None)
    return surname, qualifier


def match_author_key(display_name: str, author_keys: list[str]) -> str:
    """Resolve a free-text name (e.g. 'Jan Gossart') to the dataset's author key
    (e.g. 'GOSSART, Jan') by surname containment, disambiguating Elder/Younger pairs
    (Cranach) by qualifier. Raises if the match isn't exactly one key — a silent wrong
    match here means a chunk gets tagged with the wrong artist, which is the same
    failure mode as the roadmap's "index row order drift" risk."""
    candidates = []
    for key in author_keys:
        surname, qualifier = surname_and_qualifier(key)
        if surname.lower() not in display_name.lower():
            continue
        if qualifier and qualifier.lower() not in display_name.lower():
            continue
        candidates.append(key)
    if len(candidates) == 1:
        return candidates[0]
    if not candidates:
        raise ValueError(f"no author key matched display name {display_name!r}")
    raise ValueError(f"ambiguous match for {display_name!r}: {candidates}")


def build_artist_chunks(bios_path: Path) -> list[dict]:
    chunks = []
    for row in read_jsonl(bios_path):
        chunks.append(
            {
                "chunk_id": f"artist_{slugify(row['author'])}",
                "type": "artist",
                "artist": row["author"],
                "source_url": row.get("source_url"),
                "chunk_index": 0,
                "text": row["text"],
            }
        )
    return chunks


def build_movement_chunks(glossary_path: Path) -> list[dict]:
    sections = split_markdown_sections(glossary_path.read_text(encoding="utf-8"))
    chunks = []
    for i, (heading, body) in enumerate(sections):
        if not body:
            continue
        chunks.append(
            {
                "chunk_id": f"movement_{slugify(heading)}",
                "type": "movement",
                "chunk_index": i,
                "text": f"{heading}\n\n{body}",
            }
        )
    return chunks


def build_museum_chunks(museum_path: Path, author_keys: list[str]) -> list[dict]:
    sections = split_markdown_sections(museum_path.read_text(encoding="utf-8"))
    chunks = []
    idx = 0
    for heading, body in sections:
        if not body:
            continue
        if heading.strip().lower() == "artist locations and holdings":
            for line in body.splitlines():
                m = BULLET_RE.match(line.strip())
                if not m:
                    continue
                display_name, rest = m.group(1), m.group(2)
                author_key = match_author_key(display_name, author_keys)
                chunks.append(
                    {
                        "chunk_id": f"museum_location_{slugify(author_key)}",
                        "type": "museum",
                        "artist": author_key,
                        "chunk_index": idx,
                        "text": f"{display_name} — {rest}",
                    }
                )
                idx += 1
        else:
            chunks.append(
                {
                    "chunk_id": f"museum_{slugify(heading)}",
                    "type": "museum",
                    "chunk_index": idx,
                    "text": f"{heading}\n\n{body}",
                }
            )
            idx += 1
    return chunks


def load_author_keys(dataset_path: Path) -> list[str]:
    return sorted({row["author"] for row in read_jsonl(dataset_path)})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sources-dir", default="datasets/rag_sources")
    parser.add_argument("--dataset", default="datasets/exhibition_northern-renaissance-295.jsonl")
    parser.add_argument("--out", default="datasets/rag_chunks.jsonl")
    args = parser.parse_args()

    sources_dir = Path(args.sources_dir)
    author_keys = load_author_keys(Path(args.dataset))

    chunks: list[dict] = []
    chunks += build_artist_chunks(sources_dir / "artist_bios.jsonl")
    chunks += build_movement_chunks(sources_dir / "movement_glossary.md")
    chunks += build_museum_chunks(sources_dir / "museum_info.md", author_keys)

    # --- sanity checks: fail loud rather than silently ship a broken index ---
    ids = [c["chunk_id"] for c in chunks]
    dupes = {cid for cid in ids if ids.count(cid) > 1}
    if dupes:
        sys.exit(f"ERROR: duplicate chunk_id(s): {dupes}")

    artist_authors = {c["artist"] for c in chunks if c["type"] == "artist"}
    missing_bios = set(author_keys) - artist_authors
    if missing_bios:
        sys.exit(f"ERROR: artist_bios.jsonl is missing authors present in the dataset: {missing_bios}")

    museum_authors = {c["artist"] for c in chunks if c["type"] == "museum" and "artist" in c}
    missing_locations = set(author_keys) - museum_authors
    if missing_locations:
        sys.exit(f"ERROR: museum_info.md is missing gallery locations for: {missing_locations}")

    out_path = Path(args.out)
    with out_path.open("w", encoding="utf-8") as f:
        for c in chunks:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    by_type: dict[str, int] = {}
    for c in chunks:
        by_type[c["type"]] = by_type.get(c["type"], 0) + 1
    print(f"wrote {len(chunks)} chunks to {out_path}: {by_type}")


if __name__ == "__main__":
    main()
