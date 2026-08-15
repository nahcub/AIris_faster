# RAG 로드맵 (2026-08-13)

참고 사례: [peterica, 온디바이스 RAG](https://peterica.tistory.com/1071) / [소스](https://github.com/peterica/peterica-edge-rag)
— Galaxy S23 Ultra, 블로그 1,000편, `multilingual-e5-small-ko-v2`(384d) ONNX INT8(113MB), SQLite에 float32 BLOB,
brute-force cosine 전수 스캔, 생성은 **Gemma 4 E2B on LiteRT-LM**.

**우리와 스택이 거의 같다**(LiteRT-LM + Gemma 4 E2B, Android, 완전 온디바이스). 그래서 이 글은
"되는가?"에 대한 존재 증명으로 쓰고, 로드맵은 **우리 코퍼스·우리 벤치 체계**에 맞춰 다시 짠다.

---

## 결정 로그

| # | 결정 | 이유 (요약) |
|---|---|---|
| 1 | **코퍼스를 전시 규모(295점 / 26작가)로 축소** | 골드셋이 의미를 갖고, 반복 실험이 하루 여러 번 가능해진다. 실제 도슨트도 전시 단위다 |
| 2 | **RAG는 "눈앞 작품 1점 밖"만 담당한다** | 눈앞 작품 정보는 `ArtworkRecognizer`가 이미 정확한 key lookup으로 제공한다. 그 자리를 근사 검색으로 대체하면 퇴보다 |
| 3 | **검색은 단일 언어** | cross-lingual은 미검증 축이다. 검색 실패와 언어 실패가 섞이면 원인 분리가 안 된다 |
| 4 | **그 언어는 영어. 파이프라인 전체를 영어로 통일** | 코퍼스가 원본 그대로라 번역 오류가 근거에 섞이지 않는다. 대가는 **LoRA 학습셋 100건 영어 재작성 + 재학습**(§5) |
| 5 | **코퍼스는 원본 작품 레코드가 아니라 외부 출처로 새로 수집한다**: (a) 작가 26명 위키피디아 전기 (b) 북유럽 르네상스 시대배경·기법 용어집 (c) 가상 미술관 안내(층/room, 작가별 소장 점수) | 결정 2의 연장 — 작품 정보는 이미 lookup되므로, RAG는 그게 못 주는 것(작가 생애·화파 배경·관람 동선)에만 쓴다. 작품 청크(원본 레코드 재청킹)는 만들지 않는다 |

---

## 0. RAG가 우리에게 무엇을 더 주는가

⚠️ **우리는 이미 retrieval을 하고 있다.** `ArtworkRecognizer.recognize()` → `Artwork` → `setArtwork()` →
`decodeSystemPrompt()`가 그 작품의 `description`을 시스템 프롬프트에 프리필한다. 이건 **정확한 key
lookup**이라 임베딩 검색보다 정확도가 높다. 이 자리를 RAG로 대체하면 손해다(결정 2).

RAG가 붙어야 할 자리는 **"눈앞의 작품 1점 밖"**:

| 관람객 질문 유형 | 지금 답할 근거 | RAG가 주는 것 | 근거 소스 |
|---|---|---|---|
| "이 그림 뭐야?" | ✅ 있음 (인식된 작품 description) | 없음 — 건드리지 말 것 | — |
| "홀바인 다른 작품은?" / "이 작가 어떤 사람이야?" | ❌ 없음 → 환각 | 작가의 생애·경력 | **작가 전기** |
| "이 시대 독일 회화는 어떤 특징이야?" | ❌ | 화파·시대 배경 | **사조 용어집** |
| "몇 층이에요?" / "이 작가 작품 몇 개 있어요?" | ❌ | 관람 동선·소장 현황 | **미술관 안내** |

즉 **우리 RAG의 정체는 "이 전시를 안내하는 도슨트가 아는, 개별 작품 설명 밖의 배경지식"** 이다.

### RAG의 가치는 "모르는 걸 알려주기"보다 "지어내는 걸 막기"다

Gemma는 사전학습으로 "르네상스가 뭔지" 정도는 이미 안다. 그런 일반 지식을 검색해 넣는 건 이득이 적다.
RAG가 실제로 값어치를 하는 건 **모델이 알 수 없는 구체적 사실** 쪽이다:

- ❌ "르네상스란?" → 모델이 이미 안다. 검색해도 이득 적음
- ✅ "이 전시에 홀바인 작품이 또 있어?" / "몇 층이야?" → **우리 코퍼스에만 있는 사실**
- ✅ "암굴의 성모는 누가 그렸어?" → 대조군이 **카라바조**라고 틀렸던 지점(CLAUDE.md 품질 문제 #2). 근거가 붙으면 잡힌다

⚠️ **RAG가 기존 문제를 악화시킬 수 있다.** CLAUDE.md에 기록된 **앵무새(parroting)** 문제 — 두 모델 다
`description`을 그대로 복창한다 — 는 컨텍스트를 더 넣으면 더 심해지는 게 자연스럽다. "RAG를 넣었더니
근거는 늘었는데 도슨트 화법은 죽었다"가 가장 유력한 실패 시나리오다. **LoRA(화법) × RAG(근거)의
상호작용**이 이 단계의 진짜 연구 질문이다.

---

## 1. 코퍼스

### 1-1. 전시 부분집합 확정 — `northern-renaissance-295`

21,382점 전체를 다루면 골드셋 30~50문항의 커버리지가 0.2%라 평가가 성립하지 않는다. 그래서 실제
도슨트처럼 **전시 단위**로 좁혔다(결정 1). `FixedArtworkRecognizer`가 반환하는 Darmstadt Madonna를
포함하도록 짰다.

전시 제목: **Devotion and Likeness** — *Painting North of the Alps, 1450–1550*
(내부 ID `northern-renaissance-295`는 벤치 레코드의 `index` 필드에 남으므로 표시 제목과 무관하게 고정)

```
school ∈ {German, Flemish, Dutch}, year ∈ [1450, 1550]
author 가 "UNKNOWN"으로 시작하면 제외
작가당 최소 8점 / 최대 12점만                → 26명 / 295점
Darmstadt Madonna는 강제 포함(pin)
동점 처리: description 긴 순 → key 오름차순
```

| 축 | 결과 |
|---|---|
| 작품 | **295** (German 124 / Flemish 137 / Dutch 34) |
| 작가 | **26명** (작가당 8~12점) |
| type 분포 | religious 192, portrait 65, mythological 19, landscape 6, historical 6, 기타 7 |

| 파일 | 역할 |
|---|---|
| `datasets/exhibition_ids.json` | 출처 기록 — 필터 조건 + 선정 ID 295개 + 표시 제목 (재현용으로 동결) |
| `datasets/exhibition_northern-renaissance-295.jsonl` | 작업용 추출본 — 선정 295점의 전체 메타데이터. 골드셋·미술관 안내 작성이 18MB 원본을 다시 필터링할 필요가 없게 함 |

⚠️ **기록해둘 선택 편향**: 동점 처리를 "description 긴 순"으로 해서 이 전시는 설명이 잘 붙은 작품 쪽으로
치우쳐 있다(중앙값 588→779자). religious가 65%라 **골드셋을 종교화 질문으로만 채우지 않도록** 유형
배분을 의식할 것.

### 1-2. 실제 코퍼스 — 결정 5: 외부 출처 수집

**작품 레코드 자체는 코퍼스에 넣지 않는다** — §0에서 이미 lookup으로 커버됨. 대신 26작가 목록을 근거로
세 가지를 **위키피디아 등 외부 출처에서 수집**했다(2026-08-13, `datasets/rag_sources/`):

| 파일 | 내용 | 상태 |
|---|---|---|
| `artist_bios.jsonl` | 26작가 각각 위키피디아 영어 요약(패러프레이즈 + 출처 URL), 150~300단어 | ✅ 완료 |
| `movement_glossary.md` | 북유럽 르네상스 시대배경 + German/Flemish/Dutch 화파 개요 + 기법·도상학 용어집, `##` 섹션 단위 | ✅ 완료 |
| `museum_info.md` | 가상 미술관("Northlight Museum") 안내 — 층/room 배정(실제 school×연대 그룹과 정합), 작가별 **실제** 소장 점수, 관람 안내(개관시간 등은 fabricated) | ✅ 완료 |

⚠️ **환각 방지 원칙**: `museum_info.md`의 층/room·소장 점수는 지어낸 것이지만 실제 295점 데이터의
school×연대 그룹, 실제 작가별 카운트와 **정합**하도록 만들었다 — 데이터셋이 재큐레이션되면 이 파일도
재생성해야 한다(파일 안에 메모해 둠). `artist_bios.jsonl`/`movement_glossary.md`는 출처를 남겨 사실
검증이 가능하다.

### 1-3. 인덱스 규모 (실측, `tools/build_chunks.py` 실행 결과)

| 소스 | 청크 수 |
|---|---|
| 작가 전기 | 26 (작가 1명 = 1청크) |
| 사조 용어집 | 5 (`##` 섹션 수) |
| 미술관 안내 | 29 (일반 안내 3 + 작가별 위치 26) |
| **합계** | **60** |

전시 스케일에서도 이미 작았는데, 코퍼스가 원본 레코드가 아니라 **손으로 수집한 배경지식**이라 21,382점
전체로 스케일업할 방법이 없다(작가 3,281명 전기를 다 쓸 수는 없다) — 그래서 §5-4의 "전체 인덱스"
아이디어는 폐기한다(§6 참고). brute-force cosine으로 충분한 건 자명하다.

---

## 2. 아키텍처 — 기존 이음새를 그대로 따른다

이 프로젝트는 이미 두 번 같은 패턴을 썼다: `InferenceEngine`(엔진 교체), `ArtworkRecognizer`(인식 교체).
**RAG도 세 번째 이음새로 넣고, `InferenceEngine`과 `LiteRtEngine`은 한 줄도 건드리지 않는다.**

```
Retriever (interface)                     ← 새로 추가할 계약
  ├─ NoopRetriever        RAG off (대조군)
  └─ EmbeddingRetriever   임베더 + 벡터스토어
        ├─ TextEmbedder (interface) → OnnxEmbedder
        └─ VectorStore  (interface) → FlatVectorStore
```

**프롬프트 조립 지점이 핵심 설계 결정이다.**

- ❌ **system 프롬프트에 넣지 말 것.** 시스템 프롬프트는 KV 캐시에 프리필해 재사용하는 구조인데,
  검색 결과는 쿼리마다 달라지므로 **캐시 재사용이 통째로 깨진다.** 두 엔진 모두 해당.
- ✅ **user 턴 앞에 붙인다.** `generateStreaming(prompt, ...)`의 `prompt` 문자열을
  `"[참고 자료]\n…\n\n[질문]\n…"` 형태로 조립. → **엔진 인터페이스 변경 0.**
  이게 TTFT 상승의 정체이자, 우리가 측정해야 할 비용이다.

**인덱스 파일 배치**: 앱 번들(assets)이 아니라 **모델과 같은 `getExternalFilesDir(null)`에 adb push**.
`ModelCatalog` 패턴을 재사용하면 인덱스를 바꿔도 재빌드가 필요 없다(`IndexCatalog`, §5-1).

### 2-1. 청킹 규칙

**타입 3종**, `datasets/rag_sources/`의 세 파일에서 각각 나온다:

```
{ chunk_id, type: "artist"|"movement"|"museum",
  artist?, source_url?, chunk_index, text }
```

- **작가 전기(`artist`)** — 항목당 1청크(150~300단어라 분할 불필요). `artist` 필드로 메타 필터 가능
- **사조 용어집(`movement`)** — `##` 섹션 경계로 분할(자연 청크, 이미 그 단위로 써 있음)
- **미술관 안내(`museum`)** — "일반 안내" 섹션과 "작가 위치" 목록은 따로 청크. 작가 위치는 작가별로
  1항목씩 잘라 `artist` 필드를 달아두면 메타 필터와 겹쳐 쓰기 좋다

⚠️ **`type`별로 검색을 분리해야 한다.** 다 섞어놓고 top-k를 뽑으면 사조 질문에 작가 전기만 올라오는
사고가 난다. 질문 유형에 따라 필터하거나 타입별로 k를 배분한다.

⚠️ **메타데이터 필터 하이브리드를 함께 쓸 것.** "홀바인 다른 작품/정보는?"류 질문은 `artist` 필드
정확 매칭이 임베딩 근사 검색보다 낫다. 26명뿐이라 매핑 테이블 방식이 현실적이다.

### 2-2. 검색 언어는 단일 — 결정 3, 4

| 계층 | 처리 |
|---|---|
| 코퍼스(문서) | **영어** (위키피디아 원문 기반, 번역 없음) |
| 검색 쿼리 | **영어** |
| 사용자 질문 · 답변 | **영어** — LoRA를 영어로 재학습 필요(§5) |

**결정 4로 언어 변수가 파이프라인에서 통째로 사라졌다.** 근거·질문·답변·검색이 전부 영어라
cross-lingual 처리가 아예 필요 없다. 이유: cross-lingual(한국어 질문 → 영어 문서)은 **미검증 축**이고,
검색 실패가 "임베딩이 나쁜 건지 언어를 못 넘은 건지" 구분이 안 된다 — CLAUDE.md의 **한 번에 한 변수**
원칙과 같다. 단일 언어면 다국어 임베더를 쓸 이유도 없다(같은 크기에서 단일 언어 전용이 더 정확·작음).

> 이 결정에 이르기까지의 상세 비교(영어 통일 vs 한국어 통일, LoRA 재학습 비용 등)는 §5-2에 남겨둔다.

---

## 3. 단계별 로드맵

각 Phase는 **다음 Phase 없이도 단독으로 검증 가능**해야 한다. 특히 Phase 2에서 실패하면 앱 코드는 손도
대지 않는다.

### Phase 0 — 범위 확정 ✅ 완료 (08-13)
결정 1~5 확정. 산출물: 이 문서.

### Phase 1 — 코퍼스 수집 + 청킹 + 골드셋 (PC/Colab, 앱 무관) ✅ 완료 (08-14)
- ✅ 전시 295점/26작가 확정 (§1-1)
- ✅ `datasets/rag_sources/` 3종 수집 완료 — 작가 전기, 사조 용어집, 미술관 안내 (§1-2).
  메타 설명(가상 미술관 고지, 출처 방법론)은 `rag_sources/README.md`로 분리 — 코퍼스 파일에 섞이면
  도슨트가 "이건 가상 데이터입니다" 같은 말을 답변에 낼 위험이 있어서다
- ✅ `tools/build_chunks.py` — `rag_sources/` 세 파일 → `datasets/rag_chunks.jsonl` (§2-1 규칙).
  실행 결과 **60청크**(artist 26 / movement 5 / museum 29). 위키피디아 표기 차이(Gossaert/Gossart,
  Matsys/Massys 등)는 `author` 키 기준 매칭으로 흡수, 26명 전원 매칭 검증 통과
- ✅ **골드셋 40문항** — `datasets/rag_goldset.jsonl`. type 배분 artist 16 / movement 12 / museum 12,
  chunk_id 전건 유효, Holbein·Cranach 겹침 0, 질문 3~12단어(구어체). 검증 스크립트
  (`2026-08-13-overnight-tasks.md` 작업 B) 통과
- ✅ **LoRA 학습셋 영어 재작성 100건** — `datasets/docent_seeds_en.jsonl`. Phase 1의 산출물은 아니지만
  결정 4(영어 통일)의 선행 작업이라 같은 밤에 함께 돌렸다. meta 100% 일치, 한글·마크다운 0,
  55~115단어. 표본에서 관찰 유도 문장 살아 있음 확인
- 산출물: `datasets/rag_chunks.jsonl`, `datasets/rag_goldset.jsonl`, `datasets/docent_seeds_en.jsonl`

⚠️ **자동 검증은 형식만 본다.** 앵무새(한국어 원문 대조)·번역투·"정답 청크가 진짜 그 질문의 답인가"는
표본 몇 건만 눈으로 봤다. Phase 2에서 특정 문항이 계속 틀리면 임베더를 의심하기 전에 **골드셋 그 줄을
먼저 다시 볼 것.**

### Phase 2 — 오프라인 검색 품질 검증 (PC/Colab) ★ 게이트 ← **지금 여기 (08-15 1차 완료)**
**여기서 recall@k가 안 나오면 앱 작업은 시작하지 않는다.**
- ✅ 임베더 3종 비교(bge-small-en-v1.5 / all-MiniLM-L6-v2 / e5-small-v2), 타입별 분해 — `tools/eval_retrieval.py`
- ✅ 임베딩 텍스트 포맷(§5-3 #1) **결정: 본문만.** 메타 헤더를 붙이면 전 모델에서 손해였다
- ✅ 메타데이터 필터 하이브리드 — 질문에 작가 이름이 안 나와서 거의 안 걸린다. **조인으로 대체**
- ⬜ 임베더 1종 확정 — **보류.** 골드셋 artist 16문항이 전부 대명사라 임베더 성능이 아니라
  '작가를 알려줬나'로만 갈린다 → movement·museum 문항 보강 후 재채점
- **결과 전문: `2026-08-15-phase2-retrieval.md`** (수치·실패한 시도 포함)

> ⚠️ **이 단계에서 아키텍처가 바뀌었다.** 작가·화파·소장위치는 작품 레코드의 `author`/`school`로
> **정확히 조인**되므로 검색 대상에서 뺀다. 게다가 KV 캐시가 재사용되는 게 실측돼서
> (기기: 425토큰 → 17토큰, TTFT 2.99초 → 0.65초) **조인은 작품당 1회 비용, 검색은 질문마다 비용**이다.
> 검색이 담당할 골드셋 문항이 40 → 21로 줄고, 가장 안 되던 artist 16문항이 통째로 빠진다.

### Phase 3 — 온디바이스 임베더 (앱, 단독 검증)
- ONNX 내보내기 + INT8 양자화(`optimum`), 토크나이저는 `onnxruntime-extensions`로 ONNX 그래프화.
  ⚠️ **토크나이저가 이 단계의 최대 난관**이다(Android에 HF tokenizers가 없다) — PC/폰 토큰화가 1비트라도
  다르면 코사인 유사도가 무의미해진다
- 런타임: **ONNX Runtime Android** (기존 `tensorflow-lite`는 향후 이미지 인식용이라 별개)
- 검증: **PC 임베딩 vs 폰 임베딩 cosine 일치도 > 0.999**
- 측정: 쿼리 1건 임베딩 지연(ms), APK/모델 용량 증가

### Phase 4 — 온디바이스 벡터스토어 (앱, 단독 검증)
- 인덱스 파일 로드 + brute-force cosine 전수 스캔(65개 청크면 자명하게 빠름). L2 정규화해두면 내적 = 코사인
- ⚠️ sqlite-vec는 ARM64 Android 바이너리가 없다 — **flat 바이너리 + 메타 JSON** 직접 구현이 기본안
- ⚠️ **벡터 행 순서와 메타 순서가 곧 연결이다.** 어긋나면 엉뚱한 근거를 **조용히** 인용한다.
  헤더에 청크 수·차원·임베더 이름을 기록해 로드 시 검증(`IndexCatalog`)
- 측정: retrieval latency(ms), 인덱스 로드 시간, `mem_peak` 증가분

### Phase 5 — 파이프라인 결합 (앱)
- `Retriever` 이음새 추가, `InferenceScreen`이 생성 직전 `retrieve(query)` → 프롬프트 조립
- **`NoopRetriever`로 RAG off를 1급 시민으로** 둘 것 — 대조군이 코드 경로를 공유해야 공정하다
- top-k와 컨텍스트 예산을 상수로: k=3 시작. ⚠️ `n_ctx=1024`는 llama.cpp 쪽 값이다.
  LiteRT-LM 경로의 실제 컨텍스트 한계를 **먼저 확인**할 것 — 시스템 프롬프트만으로 이미 ~420토큰이다

### Phase 6 — 벤치 통합 (앱 + 스크립트)
- `BenchmarkRecord.rag`(현재 null인 `rag_variant` 필드)를 채운다:
  `rag = {"on":true,"embedder":…,"k":3,"chunks":65}` 같은 self-describing 값
- 새 지표: `retrieval_ms`, `embed_ms`, `retrieved_ids`(재현·감사용)
- `AutoRunRequest`에 `-e rag on|off`, `--ei ragk N` 추가 → 재빌드 없이 A/B.
  `BenchSignal`에 실패 사유(`INDEX_NOT_FOUND`, `EMBEDDER_FAILED`) 추가 ⚠️ 없으면 스크립트 무한 대기
- ⚠️ **한 번에 한 변수**: 모델은 LoRA본으로 고정하고 `rag`만 토글
- 예상 결과(가설): `prompt_tokens` ↑↑, `ttft` ↑, **`engine_tok_s`는 변화 없음**(decode는 무관)

### Phase 7 — 품질 평가
- 재료는 이미 자동 수집된다 — `responses.jsonl`(`run_id`로 조인). ⚠️ `model`/`engine`을 뺀 것과 같은 이유로
  **`rag` 여부도 응답 파일에 넣지 말 것**(블라인드 채점)
- 축 3개: **groundedness** / **환각률**(CLAUDE.md의 "암굴의 성모=카라바조" 류 오류가 줄었는가) /
  **도슨트 화법 유지**(LoRA 효과가 컨텍스트에 파묻히지 않았는가)
- 4셀 비교가 최종 그림: `{LoRA on/off} × {RAG on/off}`

---

## 4. 주요 리스크

| 리스크 | 왜 위험한가 | 대응 |
|---|---|---|
| **토크나이저 이식** | Android에 HF tokenizers가 없다. Phase 3의 최대 난관 | `onnxruntime-extensions` ONNX 그래프화, PC 대조로 조기 검출 |
| **위키피디아 요약의 사실 오류** | 출처가 있어도 패러프레이즈 과정에서 틀릴 수 있다 | 출처 URL을 청크 메타에 남겨 추적 가능하게, 골드셋 작성 시 교차 확인 |
| **타입 혼재 검색** | 사조 질문에 작가 전기만 올라오는 사고 | `type` 필드 + 타입별 k 배분, 골드셋을 유형별로 배분해 검출 |
| **인덱스 행 순서 어긋남** | 엉뚱한 근거를 **조용히** 인용 | 헤더에 청크 수·차원·임베더명 기록, 로드 시 검증 |
| **컨텍스트 예산 초과** | 시스템 프롬프트 ~420토큰 + 검색 토큰 | k와 청크 길이로 조절, LiteRT 실제 한계 선확인 |
| **앵무새 악화 / 화법 붕괴** | LoRA 효과를 RAG가 덮을 수 있음 | 4셀 비교로 분리, 프롬프트에서 "인용이 아니라 설명" 지시 |
| **TTFT 악화** | 온디바이스 CPU에서 prefill이 이미 병목(~3.6s) | 비용을 숨기지 말고 **측정해서 보고** |
| **GPU 부재** | 활성값 fp32라 CPU 고정(2026-08-01) | RAG는 GPU와 독립. 절대 지연은 CPU 기준으로 읽을 것 |

---

## 5. 부록

### 5-1. 새로 만들 Kotlin 블럭 / 손댈 기존 파일

**새 파일** (`app/src/main/java/com/example/airis/`)

| 파일 | 역할 | 대응하는 기존 패턴 |
|---|---|---|
| `Retriever.kt` | 검색 계약(interface) + `RetrievedChunk` + `NoopRetriever` | `InferenceEngine.kt`, `ArtworkRecognizer.kt` |
| `TextEmbedder.kt` | 임베딩 계약(interface) — `embed(text): FloatArray` + `dim` | 〃 |
| `OnnxEmbedder.kt` | ONNX Runtime 세션, 토크나이저 ONNX 그래프, mean pooling + L2 정규화 | `LiteRtEngine.kt`(런타임 래핑) |
| `VectorStore.kt` | 저장소 계약(interface) — `search(vec, k, type?): List<Hit>` | |
| `FlatVectorStore.kt` | 인덱스 파일 로드 + brute-force cosine 전수 스캔 + 타입 필터 | |
| `EmbeddingRetriever.kt` | 위 둘을 조립한 `Retriever` 구현체. 타입별 k 배분 | `LlamaCppEngine.kt`(어댑터) |
| `RetrieverFactory.kt` | `RagMode`(OFF/ON) 보고 Retriever 생성 | `EngineFactory.kt` |
| `IndexCatalog.kt` | 인덱스 파일 스캔·헤더 검증 | `ModelCatalog.kt` |
| `PromptAssembler.kt` | 검색 결과 + 질문 → user 프롬프트 문자열, 컨텍스트 예산 절단. **부작용 없는 순수 함수로 둘 것** | (신규) |

**손댈 기존 파일**: `BenchmarkRunner.kt`(retriever 파라미터), `BenchmarkLogger.kt`(`rag_variant`/`retrieval_ms`/`embed_ms` 채우기), `AutoRunRequest.kt`(`-e rag`), `BenchSignal.kt`(실패 사유 추가), `InferenceScreen.kt`(retriever `remember`), `app/build.gradle.kts`(onnxruntime 의존성).

**건드리지 않을 파일** (설계가 맞는지 확인하는 척도): `InferenceEngine.kt` · `LiteRtEngine.kt` ·
`LlamaCppEngine.kt` · `EngineFactory.kt` · `NativeBridge.kt` · `MainActivity.kt` — 검색 결과는
`generateStreaming(prompt, …)`의 `prompt` 문자열 안으로만 들어가므로 엔진 계약은 바뀔 이유가 없다.

**앱 밖 작업(PC/Colab)**: `tools/build_chunks.py`(`rag_sources/` → `rag_chunks.jsonl`), 임베더 비교
노트북(Phase 2 게이트), ONNX export + INT8 양자화, `tools/build_index.py`, `tools/verify_parity.py`,
`scripts/run-benchmarks.ps1` 확장, 4셀 비교 분석 노트북.

### 5-2. 결정 4 상세 — 왜 영어로 통일했나

**해야 할 일**: `docent_seeds.jsonl` 100건 영어 재작성 → LoRA 재학습 → `.litertlm` 재변환. ⚠️ 재작성 시
작품·문답 구조는 그대로 두고 언어만 바꿀 것(기계 번역 금지 — 화법은 직역이 아니다). 대조군 `.litertlm`은
재변환 불필요(베이스 모델은 언어 무관).

배경: LoRA는 한국어 도슨트 문답 100건으로 학습했다. CLAUDE.md 2026-08-02 기록 — 영어로 재던 시절 6건에서
길이·형식·톤은 전이됐지만 **도슨트 화법(관찰 유도)이 영어 답변 어디에도 없었다.** 화법은 출력 언어에
붙어 있다. 그럼에도 영어를 고른 이유:

| | 영어로 통일 | 한국어로 통일 |
|---|---|---|
| 학습셋 100건 | ⚠️ 영어로 재작성 (며칠) | ✅ 그대로 |
| LoRA 재학습 / `.litertlm` 재변환 | ⚠️ 필요 (9.5GB 병합, RAM 병목) | ✅ 불필요 |
| 코퍼스 | ✅ 원본 그대로 | ⚠️ 295점 description 자동 번역 필요 |
| 임베더 | 영어 전용 (선택지 넓음) | `multilingual-e5-small-ko-v2` (블로그 검증됨) |

판단 기준: **이 앱은 누구에게 말하는가.** 발표·논문이 영어권이고 데모도 영어여야 한다면 재학습 비용을
내는 게 맞다고 봤다 — 코퍼스가 원본 그대로라 번역 오류가 근거에 섞이지 않는 쪽을 택함.

### 5-3. 남은 설계 결정 (코드 0줄)

1. **임베딩 대상 텍스트 포맷** — 본문만? `artist`/`type` 메타를 본문 앞에 붙일지. 임베더는 자기가 본
   문자열만 안다 — Phase 2에서 실측
2. **인덱스 저장 형식** — flat 바이너리 + 메타 JSON (SQLite BLOB보다 단순, 우리는 읽기 전용 전수 스캔뿐)
3. **k와 컨텍스트 예산** — k=3 시작, 타입별 배분
4. **인용 표기 방식** — 답변에 근거를 어떻게 드러낼지
5. **메타데이터 필터 하이브리드 범위** — `artist` 필드 정확 매칭을 어디까지 쓸지

---

## 6. 하지 않을 것

- 서버 임베딩 API — 인덱스 빌드는 Colab에서 오프라인 배치 1회면 된다. 상시 서버 불필요
- ANN/HNSW — ~65청크에서 명백히 불필요. 실측이 느릴 때만 검토
- 재랭킹(cross-encoder) — 온디바이스에서 2단계 모델은 과하다. Phase 2에서 recall이 아깝게 모자랄 때만
- 기존 작품 lookup을 임베딩 검색으로 대체 — 정확한 키가 있는데 근사 검색으로 바꾸는 건 퇴보
- 임베더 파인튜닝 — 질문-정답 쌍 수천 건이 필요하다. 기성 모델을 고르는 것까지가 Phase 2의 범위
- cross-lingual 검색 — 결정 3, 4로 배제
- **21,382점 전체를 커버하는 "스케일 증거" 인덱스** — 코퍼스가 원본 레코드 집계가 아니라 손으로 수집한
  작가 전기·용어집이 된 이상(결정 5), 3,281명 전기를 다 쓸 방법이 없다. 폐기
