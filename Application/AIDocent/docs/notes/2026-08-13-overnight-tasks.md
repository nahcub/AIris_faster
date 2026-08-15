# 무인 배치 작업 사양서 (2026-08-13, 결정 5 반영)

토큰이 많이 드는 생성 작업을 **자리를 비운 채 돌리기 위한** 사양서.
로드맵(`../plan/rag-roadmap.md`) Phase 1에 해당한다.

⚠️ **이 문서는 자기완결적이어야 한다.** 작업을 실행하는 세션은 이 대화의 맥락을 갖고 있지 않다고 가정하고 썼다.

> **개정 이력**
> - 08-13 초판
> - 08-13 **결정 5 반영** — 코퍼스가 "작품 레코드 청크"에서 "외부 수집 배경지식"으로 바뀌면서
>   작업 B를 다시 씀. 작업 C(프로필 집계 생성)는 **삭제**(외부 수집으로 대체됨).
> - 08-14 **작업 A·B 완료.** 이 문서는 이제 사양서가 아니라 **기록**이다 — 산출물이 실제로 나왔고
>   두 검증 스크립트 모두 통과했다(아래 "결과" 절). 재실행할 일은 없지만, 데이터셋을 다시 만들 때의
>   요구사항 명세로는 그대로 유효하다.
> - 08-13 **`tools/build_chunks.py` 작성·실행 완료** — "선행 조건" 절이 더 이상 미래형이 아니다.
>   `datasets/rag_chunks.jsonl`(60청크)이 이미 존재한다. **chunk_id 형식이 아래 초안(콜론 구분)과
>   다르다** — 실제로 나온 형식(언더스코어 구분, `##` 제목 슬러그)에 맞춰 이 문서를 갱신함.

---

## 결과 (08-14)

| 작업 | 산출물 | 검증 |
|---|---|---|
| A | `datasets/docent_seeds_en.jsonl` — **100건** | ✅ meta 100% 일치 / 줄 순서 유지 / 한글 0 / 마크다운 0 / 55~115단어 |
| B | `datasets/rag_goldset.jsonl` — **40문항** | ✅ chunk_id 전건 유효 / artist 16·movement 12·museum 12 / Holbein·Cranach 겹침 0 / 질문 3~12단어 |

표본 검수(아래 체크리스트)는 A 3건 · B 5건만 봤다 — 관찰 유도 문장과 구어체 질문은 살아 있는 걸 확인했고,
**앵무새·번역투 전수 검수는 안 했다.** Phase 2에서 특정 골드셋 문항이 계속 틀리면 임베더보다 그 줄을
먼저 의심할 것.

---

## 0. 전제 — 이미 끝난 것

| 파일 | 내용 |
|---|---|
| `datasets/exhibition_northern-renaissance-295.jsonl` | 전시 295점 메타데이터 (26작가) |
| `datasets/rag_sources/artist_bios.jsonl` | **26작가 위키피디아 전기** — `author/name/source_url/text` |
| `datasets/rag_sources/movement_glossary.md` | 사조·기법 용어집 — `##` 섹션 **5개** |
| `datasets/rag_sources/museum_info.md` | 가상 미술관 안내 — `##` 섹션 **4개** |
| `datasets/docent_seeds.jsonl` | 기존 한국어 LoRA 학습셋 100건 |

**전시**: *Devotion and Likeness — Painting North of the Alps, 1450–1550* (내부 ID `northern-renaissance-295`)

**언어**: 파이프라인 전체가 **영어**다(로드맵 결정 4).

⚠️ **작품 레코드(`exhibition_*.jsonl`)는 RAG 검색 대상이 아니다**(결정 5).
눈앞의 작품 정보는 `ArtworkRecognizer`의 정확한 key lookup이 이미 제공한다.
RAG 코퍼스는 위 `rag_sources/` 3종뿐이고, `tools/build_chunks.py` 실행 결과
**`datasets/rag_chunks.jsonl`(60청크 — artist 26 / movement 5 / museum 29)이 이미 존재한다.**

---

## 작업 A — LoRA 학습셋 100건 영어 재작성 ★ 최대 비용

> **결정 5의 영향 없음.** 초판 그대로다.

### 입출력

```
IN   datasets/docent_seeds.jsonl          (한국어 100건)
OUT  datasets/docent_seeds_en.jsonl       (영어 100건, 같은 순서·같은 건수)
```

### 입력 스키마 (그대로 유지)

```json
{"messages":[{"role":"user","content":"..."},{"role":"assistant","content":"..."}],
 "meta":{"image":"19873-1darmst.jpg","category":"artwork","title":"Darmstadt Madonna",
         "author":"HOLBEIN, Hans the Younger","type":"religious","school":"German"}}
```

- `category`: `artwork` 50 / `artist` 25 / `movement` 25
- ⚠️ **`meta`는 한 글자도 바꾸지 말 것.** 작품 동일성이 이 필드로 확인된다
- ⚠️ **줄 순서 유지.** 100번째 줄은 100번째 줄로

### 절대 규칙 — 왜 "번역"이 아니라 "재작성"인가

**바꾸는 것은 언어 하나뿐이다.** 작품·질문 의도·답변 구조·정보량은 전부 그대로 간다.
그래야 한국어본과의 차이가 '언어' 하나로 통제되고, 2026-08-02에 관측된 화법 전이 실패가
언어 탓인지 아닌지 갈린다(CLAUDE.md).

그런데 **기계 번역은 안 된다.** 영어 도슨트 화법은 한국어 화법의 직역이 아니다.
"먼저 발밑의 카펫을 한번 보시겠어요?"를 직역하면 어색한 영어가 되고, 그 어색함을 LoRA가 학습한다.
→ **같은 작품·같은 질문·같은 논지를, 자연스러운 영어 도슨트 말투로 다시 쓴다.**

### 화법 요구사항 (이 학습셋의 존재 이유)

이 LoRA의 목적은 지식이 아니라 **화법**이다.

1. **관찰 유도** — 관람객의 눈을 특정 지점으로 이끄는 문장이 **최소 1회**.
   `"Look at the crumpled carpet beneath their feet."` / `"Notice how the figures differ in size."`
   ⚠️ 한국어본의 핵심 특징이고, 영어 회차 6건에서 **전이되지 않았던** 바로 그 요소다
2. **말을 거는 어조** — 현장에서 사람에게 말하는 문장. 문서 요약이 아니다
3. **산문 문단** — 마크다운 금지
4. **자연스러운 영어** — 번역투 금지

### 금지 목록 (대조군의 특징 = LoRA가 학습하면 안 되는 것)

- ❌ 마크다운: `##` 헤더, `-`/`*` 불릿, `**bold**`
- ❌ 라벨형 문장: `"Historical Implication:"`, `"Key Features:"`
- ❌ 조수 말투: `"The provided text describes…"`, `"According to the information…"`
- ❌ **메타데이터 복창(앵무새)** — description 문장을 그대로 옮기지 말 것.
  CLAUDE.md 품질 문제 #1. **자기 말로 다시 설명**해야 한다
- ❌ 같은 단어를 괄호로 되풀이하는 퇴화 패턴 (`"sculpted" (or "sculpted")`)
- ❌ 한글 문자

### 길이 목표

원본 한국어 실측: user 8~27자(중앙 16) / assistant 223~299자(중앙 251)

- **user**: **3~8 단어**. 관람객이 툭 던지는 질문. 검색어가 아니라 말이다
  (원본이 반말인 건 입력 분포를 맞추려는 의도 → 영어에서는 **캐주얼한 구어체**)
- **assistant**: **70~95 단어 / 3~5 문장**. ⚠️ 편차를 좁게 유지할 것 —
  원본이 223~299자로 매우 균일하고, **그 균일함 자체가 "짧게 답하라"는 신호로 학습된다**

### 예시 (형태 참고용 — 실제 내용은 원본 각 건을 따를 것)

```json
{"messages":[
  {"role":"user","content":"why does the carpet look wrinkled"},
  {"role":"assistant","content":"Take a moment to look down at the carpet beneath the Madonna's feet. Holbein has let it ruckle up into folds instead of lying flat, and that small choice does a lot of work: it tells your eye exactly how deep the niche is. Once you see the floor receding, the whole family kneeling around her settles into real space. It is a quiet piece of illusionism, hidden in a detail most visitors walk straight past."}],
 "meta":{ ...원본 그대로... }}
```

### 검증 (완료 조건)

```python
import json, re
K = [json.loads(l) for l in open('datasets/docent_seeds.jsonl', encoding='utf-8') if l.strip()]
E = [json.loads(l) for l in open('datasets/docent_seeds_en.jsonl', encoding='utf-8') if l.strip()]
assert len(E) == 100 == len(K), f'건수 {len(E)}'
for i, (k, e) in enumerate(zip(K, E)):
    assert e['meta'] == k['meta'], f'{i}행 meta 불일치'
    assert [m['role'] for m in e['messages']] == ['user', 'assistant'], f'{i}행 role'
    for m in e['messages']:
        assert not re.search(r'[가-힣]', m['content']), f'{i}행 한글 잔존'
    a = e['messages'][1]['content']
    assert not re.search(r'^\s*[#*-]|\*\*', a, re.M), f'{i}행 마크다운'
    w = len(a.split())
    assert 55 <= w <= 115, f'{i}행 길이 {w}단어'
print('OK: 100건 통과')
```

⚠️ **자동으로 못 잡는 것** — 아침 표본 검수:
관찰 유도 문장 유무 / 앵무새 여부(원문 대조) / 번역투 여부

---

## ✅ 선행 조건 완료 — `tools/build_chunks.py` 작성·실행됨 (08-13)

작업 B의 정답은 **`chunk_id`** 인데, 이제 `datasets/rag_chunks.jsonl`(60청크)이 존재하므로 작업 B를
바로 시작할 수 있다.

### chunk_id 규칙 (실제 `tools/build_chunks.py` 출력 그대로 — 골드셋은 반드시 이 형식을 쓸 것)

⚠️ **콜론(`:`)이 아니라 언더스코어(`_`) 구분이다.** 초판 초안은 `artist:durer-albrecht` 같은
콜론+하이픈 형식을 가정했지만, 실제로 짠 스크립트는 아래처럼 만들었고 이미 검증까지 끝났다 —
**스크립트가 아니라 이 문서 쪽을 고쳤다.**

```
artist_<author 슬러그>              예) artist_durer_albrecht                    (26개)
movement_<## 제목 슬러그>           예) movement_german_school                   (5개)
museum_<## 제목 슬러그>             예) museum_general_visitor_information       (일반 안내, 3개)
museum_location_<author 슬러그>     예) museum_location_holbein_hans_the_younger (작가별 위치, 26개)
```

슬러그 규칙 (`tools/build_chunks.py`의 `slugify()`): 유니코드 정규화로 발음 구별 부호 제거
(Ü→U, ü→u — "Dürer"가 "d_rer"로 뭉개지지 않게) → 소문자화 → 영숫자 외 문자를 `_`로 치환 →
연속 `_` 축약 → 앞뒤 `_` 제거.

⚠️ **movement/museum 일반 섹션의 슬러그는 `##` 제목을 그대로 슬러그화한 것**이라 예상보다 길 수
있다(예: `movement_northern_renaissance_period_overview_1450_1550`,
`museum_exhibition_composition_for_what_kind_of_show_is_this_questions`). 골드셋을 쓰기 전에
**`datasets/rag_chunks.jsonl`을 열어 정확한 `chunk_id` 값을 그대로 복사해 쓸 것** — 추측해서 짓지 말 것.

---

## 작업 B — RAG 골드셋 30~50문항 (결정 5 반영해 다시 씀)

### 입출력

```
IN   datasets/rag_chunks.jsonl                              (60청크, 이미 존재함)
IN   datasets/rag_sources/artist_bios.jsonl                 (내용 확인용)
IN   datasets/rag_sources/movement_glossary.md
IN   datasets/rag_sources/museum_info.md
OUT  datasets/rag_goldset.jsonl
```

### 출력 스키마

```json
{"qid":"g001",
 "question":"did this painter do anything else here?",
 "type":"artist",
 "answer_chunks":["artist_holbein_hans_the_younger","museum_location_holbein_hans_the_younger"],
 "answer_note":"홀바인 전기 + 미술관 내 위치",
 "difficulty":"easy"}
```

- `type`: **`artist` | `movement` | `museum`** — 로드맵 §2-1의 청크 타입과 같은 축
  (⚠️ 초판의 `artwork` 타입은 **없어졌다**. 작품 레코드는 검색 대상이 아니다)
- `answer_chunks`: 정답으로 인정할 **chunk_id 목록**(위 "chunk_id 규칙" 절 형식). 여러 개 가능.
  ⚠️ **`datasets/rag_chunks.jsonl`을 직접 열어 정확한 값을 복사할 것** — 특히 movement/museum
  일반 섹션은 `##` 제목을 그대로 슬러그화해서 예상보다 길다
- ⚠️ **정답이 이 60청크 안에서 확정 가능해야 한다.** 코퍼스 밖 지식이 필요한 문항은 버릴 것

### 유형 배분 (40문항 기준)

| type | 문항 | 무엇을 묻나 | 예 |
|---|---|---|---|
| `artist` | 16 | 작가의 생애·경력·특징 | "who was Cranach anyway", "was this painter famous in his time?" |
| `movement` | 12 | 시대배경·화파·기법·도상 용어 | "why so many altarpieces back then", "what does that lily mean" |
| `museum` | 12 | 관람 동선·소장 현황·안내 | "where are the German paintings", "how many Dürers do you have" |

⚠️ **type이 편중되면 타입별 검색 분리(로드맵 §2-1)를 평가할 수 없다.** 고르게 배분할 것.

⚠️ **`museum` 타입을 빼먹지 말 것.** 이게 우리 코퍼스에만 있는 정보라
"모델이 지어내는 걸 막는다"는 RAG의 가치를 가장 직접적으로 보여준다(로드맵 §0).

### ⚠️ 학습셋과의 겹침 회피 (암기 검증 방지)

LoRA가 이미 배운 주제로 문제를 내면 **검색을 잘해서 맞힌 건지 외워서 맞힌 건지 구분이 안 된다.**

**금지 규칙**

1. ❌ **HOLBEIN, Hans the Younger / CRANACH, Lucas the Elder 에 대한 `artist` 문항 금지**
   — 시드 100건에 이 두 작가 항목이 있다. 나머지 24명으로 만들 것
2. ⚠️ **`movement` 문항을 만들기 전에 시드의 movement 25건을 확인할 것.** 아래 스크립트로
   북유럽(German/Flemish/Dutch) 관련 항목을 뽑아 **같은 주제를 피한다**:

```python
import json
R = [json.loads(l) for l in open('datasets/docent_seeds.jsonl', encoding='utf-8') if l.strip()]
for r in R:
    if r['meta']['category'] == 'movement' and r['meta'].get('school') in {'German','Flemish','Dutch'}:
        print(r['meta']['school'], '|', r['meta']['title'], '|', r['messages'][0]['content'])
```

> 참고: 시드 100건은 school이 French 39 / Italian 20으로 이 전시(German·Flemish·Dutch)와
> 거의 겹치지 않는다. **운이 좋은 것이고 결과적으로 유리하다** — LoRA는 화법을 배우고
> 골드셋은 검색을 평가하므로 분리돼 있을수록 깨끗하다.

3. ✅ `museum` 타입은 **겹칠 수 없다** — 가상 미술관 정보는 시드 작성 시점에 존재하지 않았다

### 질문 문체

⚠️ **검색어가 아니라 말이어야 한다.**

```
❌ "Cranach biography German school 1500-1550"    ← 검색어. 너무 쉽고 의미 없음
✅ "was this guy well known back then?"           ← 실제 관람객 말투
```

실제 관람객이 그림 앞에서 던지는 짧은 구어체(**3~10 단어**). 작업 A의 user 문체와 같은 결로.

⚠️ **정답 청크의 문장을 질문에 그대로 넣지 말 것.** 단어가 겹치면 임베딩이 아니라 문자열 일치를
재는 셈이 되어 점수가 부풀려진다. **다른 말로 물어야** 진짜 의미 검색을 평가한다.

### 검증

```python
import json, collections, re
G = [json.loads(l) for l in open('datasets/rag_goldset.jsonl', encoding='utf-8') if l.strip()]
C = {json.loads(l)['chunk_id'] for l in open('datasets/rag_chunks.jsonl', encoding='utf-8') if l.strip()}
assert 30 <= len(G) <= 50, f'문항 수 {len(G)}'
assert len({g['qid'] for g in G}) == len(G), 'qid 중복'
BAN = {'artist_holbein_hans_the_younger', 'artist_cranach_lucas_the_elder'}
for g in G:
    assert g['type'] in {'artist', 'movement', 'museum'}, f'{g["qid"]} type'
    assert g['answer_chunks'], f'{g["qid"]} 정답 없음'
    for cid in g['answer_chunks']:
        assert cid in C, f'{g["qid"]} 존재하지 않는 chunk_id: {cid}'
    assert not re.search(r'[가-힣]', g['question']), f'{g["qid"]} 한글'
    assert 3 <= len(g['question'].split()) <= 12, f'{g["qid"]} 질문 길이'
    if g['type'] == 'artist':
        assert not (set(g['answer_chunks']) & BAN), \
            f'{g["qid"]} 시드 겹침 작가(Holbein/Cranach)'
c = collections.Counter(g['type'] for g in G)
assert len(c) == 3, f'type 누락 {c}'
assert min(c.values()) >= len(G) * 0.2, f'type 편중 {c}'
print('OK:', len(G), '문항', dict(c))
```

---

## ~~작업 C — 작가·사조 프로필 생성~~ ❌ 삭제 (결정 5)

초판에서는 작품 레코드를 집계해 작가·사조 프로필을 **만들** 계획이었다.
결정 5로 **위키피디아 등에서 직접 수집**하는 방식으로 바뀌었고, 그 수집은 08-13에 이미 완료됐다
(`datasets/rag_sources/` 3종).

→ 그 파일들을 청크로 자르는 `tools/build_chunks.py`도 08-13에 작성·실행 완료됐다
(위 "선행 조건 완료" 참조) — 이제 작업 B를 바로 시작할 수 있다.

---

## 실행 순서

```
[완료 08-13]     tools/build_chunks.py 작성 + 실행     ← 완료
                 → datasets/rag_chunks.jsonl (60청크)

[완료 08-14]     A. 학습셋 100건 영어 재작성          → docent_seeds_en.jsonl (100건)
[완료 08-14]     B. 골드셋 30~50문항                   → rag_goldset.jsonl (40문항)
[완료 08-14]     검증 스크립트 실행 → 표본 검수        ← 표본은 일부만, 위 "결과" 절 참고

[다음]           Phase 2 — 오프라인 검색 품질 게이트   ← 골드셋으로 임베더 비교 (앱 코드 0줄)

[그 뒤]          Colab: LoRA 영어 재학습 → .litertlm 재변환   ← 토큰 아니라 GPU 시간
                 (대조군 .litertlm은 재변환 불필요 — 베이스 모델은 언어 무관)
```

⚠️ **A와 B는 서로 독립**이고 이제 **둘 다 선행 조건이 없다** — 같은 밤에 함께 돌려도 된다.

---

## 아침 검수 체크리스트

**작업 A**
- [x] 검증 스크립트 통과 (100건 / meta 동일 / 한글 0 / 마크다운 0 / 길이)
- [x] 표본: **관찰 유도 문장이 있는가** — 3건 확인, 전건 있음
- [ ] 표본 10건: description 복창(앵무새)이 아닌가 — 원문과 대조 ← **미완**
- [ ] 표본 5건: 번역투가 아닌가 ← **미완**

**작업 B**
- [x] 검증 스크립트 통과 (문항 수 / chunk_id 유효 / type 배분 / 겹침 금지 / 질문 길이)
- [x] 표본: 질문이 '검색어'가 아니라 '말'인가 — 5건 확인, 전건 구어체
- [ ] 표본 10건: **정답 청크의 문장을 질문이 그대로 베끼지 않았는가** ← **미완**
- [ ] 표본 5건: 정답 청크가 실제로 그 질문의 답인가 ← **미완**

⚠️ **검증 스크립트가 통과했다고 품질이 보장되는 건 아니다.** 스크립트는 형식만 본다.
화법·앵무새·번역투·질문 자연스러움은 사람이 표본으로 확인해야 하고,
그게 이 두 산출물의 **유일한 존재 이유**다.
