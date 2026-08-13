# 무인 배치 작업 사양서 (2026-08-13)

토큰이 많이 드는 생성 작업을 **자리를 비운 채 돌리기 위한** 사양서.
로드맵(`2026-08-13-rag-roadmap.md`)의 Phase 1에 해당한다.

⚠️ **이 문서는 자기완결적이어야 한다.** 작업을 실행하는 세션은 이 대화의 맥락을 갖고 있지 않다고 가정하고 썼다.
사양이 부실하면 아침에 나온 결과물을 통째로 버리게 된다.

---

## 0. 전제 — 이미 끝난 것

| 산출물 | 내용 |
|---|---|
| `datasets/exhibition_ids.json` | 전시 선정 근거(필터 조건) + ID 295개 + 표시 제목 |
| `datasets/exhibition_northern-renaissance-295.jsonl` | **작업 입력** — 295점 전체 메타데이터 (425KB) |
| `datasets/docent_seeds.jsonl` | **작업 입력** — 기존 한국어 LoRA 학습셋 100건 |
| `docs/notes/2026-08-13-rag-roadmap.md` | 결정 로그 · 아키텍처 · Phase 정의 |

**전시**: *Devotion and Likeness — Painting North of the Alps, 1450–1550*
내부 ID `northern-renaissance-295`. German 124 / Flemish 137 / Dutch 34, 26작가, 1450–1550.
type 분포: religious 192, portrait 65, mythological 19, landscape 6, historical 6, 기타 7.

**언어 결정**: 파이프라인 전체가 **영어**다(로드맵 결정 4). 코퍼스·검색·질문·답변 모두 영어.

---

## 작업 A — LoRA 학습셋 100건 영어 재작성 ★ 최대 비용

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

이 LoRA의 목적은 지식이 아니라 **화법**이다. 다음이 답변에 살아 있어야 한다:

1. **관찰 유도** — 관람객의 눈을 특정 지점으로 이끄는 문장이 **최소 1회**.
   `"Look at the crumpled carpet beneath their feet."` / `"Notice how the figures differ in size."`
   ⚠️ 한국어본의 핵심 특징이고, 영어 회차 6건에서 **전이되지 않았던** 바로 그 요소다
2. **말을 거는 어조** — 현장에서 사람에게 말하는 문장. 문서 요약이 아니다
3. **산문 문단** — 마크다운 금지(§ 아래 금지 목록)
4. **자연스러운 영어** — 번역투 금지. 원어민 도슨트가 말하듯

### 금지 목록 (대조군의 특징 = LoRA가 학습하면 안 되는 것)

- ❌ 마크다운: `##` 헤더, `-`/`*` 불릿, `**bold**`
- ❌ 라벨형 문장: `"Historical Implication:"`, `"Key Features:"`
- ❌ 조수 말투: `"The provided text describes…"`, `"According to the information…"`
- ❌ **메타데이터 복창(앵무새)** — description 문장을 그대로 옮기지 말 것.
  CLAUDE.md에 기록된 품질 문제 #1이다. **자기 말로 다시 설명**해야 한다
- ❌ 같은 단어를 괄호로 되풀이하는 퇴화 패턴 (`"sculpted" (or "sculpted")`)
- ❌ 한글 문자 (검증 스크립트가 자동 검출)

### 길이 목표

원본 한국어 실측:

| | 최소 | 중앙값 | 최대 |
|---|---|---|---|
| user | 8자 | 16자 | 27자 |
| assistant | 223자 | 251자 | 299자 |

영어 환산 목표:

- **user**: **3~8 단어**. 관람객이 툭 던지는 질문. 검색어가 아니라 말이다
  (원본이 반말인 건 입력 분포를 맞추려는 의도 → 영어에서는 **캐주얼한 구어체**로)
- **assistant**: **70~95 단어 / 3~5 문장**. ⚠️ 편차를 좁게 유지할 것 —
  원본이 223~299자로 매우 균일하다. 이 균일함 자체가 "짧게 답하라"는 신호로 학습된다

### category별 초점

| category | 건수 | 무엇을 묻고 답하나 |
|---|---|---|
| `artwork` | 50 | 이 작품 자체 — 무엇이 보이는가, 왜 그렇게 그렸는가 |
| `artist` | 25 | 그 작가의 특징·경향 |
| `movement` | 25 | 사조·시대의 성격 |

### 예시 (형태 참고용 — 실제 내용은 원본 각 건을 따를 것)

```json
{"messages":[
  {"role":"user","content":"why does the carpet look wrinkled"},
  {"role":"assistant","content":"Take a moment to look down at the carpet beneath the Madonna's feet. Holbein has let it ruckle up into folds instead of lying flat, and that small choice does a lot of work: it tells your eye exactly how deep the niche is. Once you see the floor receding, the whole family kneeling around her settles into real space. It is a quiet piece of illusionism, hidden in a detail most visitors walk straight past."}],
 "meta":{ ...원본 그대로... }}
```

### 검증 (작업 A 완료 조건)

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

⚠️ **자동으로 못 잡는 것** — 아침 검수 항목:
- 관찰 유도 문장이 실제로 있는가 (10건 표본)
- description 복창(앵무새)이 아닌가 (10건 표본, 원문과 대조)
- 영어가 번역투가 아닌가

---

## 작업 B — RAG 골드셋 30~50문항

### 입출력

```
IN   datasets/exhibition_northern-renaissance-295.jsonl
OUT  datasets/rag_goldset.jsonl
```

### 출력 스키마

```json
{"qid":"g001",
 "question":"are there other works by Holbein here?",
 "type":"artist",
 "answer_ids":["19873-1darmst.jpg","..."],
 "answer_note":"Holbein 작품 12점 전부",
 "difficulty":"easy"}
```

- `type`: `artwork` | `artist` | `movement` — 로드맵 §2-1의 청크 타입과 같은 축
- `answer_ids`: 정답으로 인정할 **작품 ID 목록**(`exhibition_*.jsonl`의 `id`). `artist`/`movement`
  문항은 해당 프로필이 커버하는 작품들을 적는다
- ⚠️ **정답이 이 295점 안에서 확정 가능해야 한다.** 코퍼스 밖 지식이 필요한 문항은 버릴 것

### 유형 배분 (40문항 기준)

| type | 문항 | 예 |
|---|---|---|
| `artwork` | 16 | "what's that skull at the bottom?", "why is she holding a book" |
| `artist` | 12 | "did this painter do anything else here?", "who was Cranach" |
| `movement` | 12 | "what was German painting like back then?", "why so many altarpieces" |

⚠️ **type 분포가 편중되면 타입별 검색 분리(로드맵 §2-1)를 평가할 수 없다.** 고르게 배분할 것.

### ⚠️ 주제 편향 방지

코퍼스가 **religious 65%(192/295)** 다. 그대로 문항을 뽑으면 골드셋도 종교화 일색이 된다.
**의도적으로 배분할 것**:

- religious 이외(portrait 65, mythological 19, landscape 6, historical 6)에서 **최소 40%**
- school도 German / Flemish / Dutch 세 축에 걸치게

### ⚠️ 학습셋과의 겹침 회피 (암기 검증 방지)

기존 시드 100건과 전시 295점의 겹침은 **아주 작지만 0은 아니다**. 실측:

| 겹침 | 대상 |
|---|---|
| 작품 | **Darmstadt Madonna** — 시드에 2건(artwork 1 + artist 1) |
| 작가 | **HOLBEIN, Hans the Younger** / **CRANACH, Lucas the Elder** |

**규칙**

1. ❌ **Darmstadt Madonna를 정답으로 하는 `artwork` 문항 금지** — 시드에 그 작품 문답이 그대로 있다
2. ❌ **Holbein / Cranach에 대한 `artist` 문항 금지** — 같은 이유
3. ✅ Holbein·Cranach의 **다른 작품**을 정답으로 하는 `artwork` 문항은 **가능**(시드에 없다)

> 참고: 시드 100건은 school이 French 39 / Italian 20으로 이 전시(German·Flemish·Dutch)와
> 거의 겹치지 않는다. **의도된 게 아니라 운이 좋은 것이고, 결과적으로 유리하다** —
> LoRA는 화법을 배우고 골드셋은 지식을 평가하므로 분리돼 있을수록 깨끗하다.

### 질문 문체

⚠️ **검색어가 아니라 말이어야 한다.** "Holbein other works exhibition"이 아니라
"are there other works by Holbein here?". 실제 관람객이 그림 앞에서 던지는 짧은 구어체.
작업 A의 user 문체와 같은 결로 맞출 것(3~10 단어).

### 검증

```python
import json, collections, re
G = [json.loads(l) for l in open('datasets/rag_goldset.jsonl', encoding='utf-8') if l.strip()]
E = {json.loads(l)['id']: json.loads(l) for l in open(
        'datasets/exhibition_northern-renaissance-295.jsonl', encoding='utf-8')}
assert 30 <= len(G) <= 50, f'문항 수 {len(G)}'
assert len({g['qid'] for g in G}) == len(G), 'qid 중복'
BAN_ART = {'HOLBEIN, Hans the Younger', 'CRANACH, Lucas the Elder'}
for g in G:
    assert g['type'] in {'artwork', 'artist', 'movement'}, g['qid']
    assert g['answer_ids'], f'{g["qid"]} 정답 없음'
    for i in g['answer_ids']:
        assert i in E, f'{g["qid"]} 코퍼스 밖 id: {i}'
    assert not re.search(r'[가-힣]', g['question']), f'{g["qid"]} 한글'
    if g['type'] == 'artwork':
        assert not any(E[i]['title'] == 'Darmstadt Madonna' for i in g['answer_ids']), \
            f'{g["qid"]} Darmstadt 금지'
    if g['type'] == 'artist':
        assert not any(E[i]['author'] in BAN_ART for i in g['answer_ids']), \
            f'{g["qid"]} 시드 겹침 작가'
c = collections.Counter(g['type'] for g in G)
assert min(c.values()) >= len(G) * 0.2, f'type 편중 {c}'
nonrel = sum(1 for g in G if any(E[i]['type'] != 'religious' for i in g['answer_ids']))
assert nonrel >= len(G) * 0.4, f'religious 편중 (비종교 {nonrel})'
print('OK:', len(G), '문항', dict(c))
```

---

## 작업 C — 작가·사조 프로필 → **무인 생성하지 말 것**

로드맵 §2-1에서 프로필은 **"통계 사실만, 서술 최소화"** 로 정했다.
LLM이 "이 작가의 특징은…"을 지어내면 **환각이 인덱스에 굳어버리고**, 모든 후속 답변이 그 위에 쌓인다.

→ 프로필은 `tools/build_profiles.py`의 **집계 스크립트**로 만든다. 토큰 0, 결정론적, 재현 가능.
`groupby(author)` / `groupby(school, 반세기)` + 건수·연도범위·type분포·대표작 나열.

⚠️ 이 스크립트는 **낮에 대화형으로** 짜는 게 맞다(코드라 검토가 필요하고 토큰이 적게 든다).

---

## 실행 순서와 의존성

```
[낮·대화형]  tools/build_profiles.py 작성        ← 토큰 적음, 검토 필요
                    │
[밤·무인]    A. 학습셋 100건 영어 재작성   ─┐
             B. 골드셋 30~50문항          ─┴─ 서로 독립. 병렬 가능
                    │
[아침]       검증 스크립트 2개 실행 → 표본 검수
                    │
[다음 날]    Colab: LoRA 영어 재학습 → .litertlm 재변환   ← 토큰 아니라 GPU 시간
             (대조군 .litertlm은 재변환 불필요 — 베이스 모델은 언어 무관)
```

**A와 B는 의존 관계가 없다.** A는 기존 시드만 보고, B는 전시 추출본만 본다.

⚠️ **B는 전시가 확정된 뒤에만 가능하다** — 이미 확정됨(295점 동결). 전시가 흔들리면 골드셋이 무효가 된다.

---

## 아침 검수 체크리스트

- [ ] 작업 A 검증 스크립트 통과 (100건 / meta 동일 / 한글 0 / 마크다운 0 / 길이)
- [ ] 작업 B 검증 스크립트 통과 (문항 수 / id 유효 / type 배분 / 종교화 편중 / 겹침 금지)
- [ ] A 표본 10건: **관찰 유도 문장이 있는가**
- [ ] A 표본 10건: description 복창(앵무새)이 아닌가 — 원문과 대조
- [ ] A 표본 5건: 번역투가 아닌가
- [ ] B 표본 10건: 질문이 '검색어'가 아니라 '말'인가
- [ ] B 표본 5건: 정답 `answer_ids`가 실제로 그 질문의 답인가

⚠️ **검증 스크립트가 통과했다고 품질이 보장되는 건 아니다.** 스크립트는 형식만 본다.
화법·앵무새·번역투는 사람이 표본으로 확인해야 하고, 그게 이 학습셋의 **유일한 존재 이유**다.
