# `rag_sources/` — provenance & methodology (NOT part of the RAG corpus)

⚠️ **This file is documentation for us, not content for the retriever.** `build_chunks.py`
must never read this file. The three content files below (`artist_bios.jsonl`,
`movement_glossary.md`, `museum_info.md`) contain only what a docent could plausibly
know — no meta-commentary about being "fictional," "invented," or "AI-generated." If a
retrieved chunk ever surfaces text like that in a generated answer, it's a bug: either
this separation broke, or the chunker pulled from the wrong place.

See `docs/plan/rag-roadmap.md` (decision 5, §1-2) for why this corpus exists
instead of re-chunking the original artwork records.

## What's real vs. invented

- **`artist_bios.jsonl`** — real facts, paraphrased from English Wikipedia. Each line
  carries `source_url` for the exact article used.
- **`movement_glossary.md`** — real facts, paraphrased from public sources (list below).
  Not individually cited per section; treat as background material rather than an exact
  quote source.
- **`museum_info.md`** — **the museum itself is fictional** ("Northlight Museum" does not
  exist). It exists to give the RAG corpus material for the questions a visitor asks a
  docent that the artwork lookup can't answer — "몇 층에 있어요?", "이 작가 작품이 몇 개
  있어요?", "몇 시까지 해요?" — not "what is this painting" (handled by exact lookup, see
  roadmap §0). Within that fiction, **the numbers are not arbitrary**: floor assignment
  follows the same `school` × period grouping as the movement profiles, and per-artist
  work counts are the *real* counts from
  `datasets/exhibition_northern-renaissance-295.jsonl` (verified 2026-08-13). Only the
  floor/room layout, hours, and other visitor-facing details are made up.

⚠️ **Maintenance**: if the underlying 295-item selection (`exhibition_ids.json`) is ever
re-curated, the per-artist counts and floor assignments in `museum_info.md` must be
regenerated to match — they're meant to stay consistent with the dataset, not decorative.

## Sources consulted for `movement_glossary.md`

- [Northern Renaissance Art: History, Characteristics](http://www.visual-arts-cork.com/history-of-art/northern-renaissance.htm)
- [20.2: Painting in the Northern Renaissance — Humanities LibreTexts](https://human.libretexts.org/Bookshelves/Art/Art_History_(Boundless)/20:_The_Northern_Renaissance/20.02:_Painting_in_the_Northern_Renaissance)
- [Flemish painting — Wikipedia](https://en.wikipedia.org/wiki/Flemish_painting)
- [Flemish Painting in the Northern Renaissance — Lumen Learning](https://courses.lumenlearning.com/suny-fmcc-worldhistory/chapter/flemish-painting-in-the-northern-renaissance/)
- General background on Jan van Scorel, Maerten van Heemskerck, and Dutch Romanism,
  cross-checked against the artist-specific Wikipedia articles in `artist_bios.jsonl`

## Pipeline language

Everything is English per the project's pipeline-language decision
(`docs/plan/rag-roadmap.md`, decisions 3-4).
