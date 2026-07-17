# AIris 리팩토링 & 자동화 실험 Plan

> **목표:** AIris(온디바이스 미술관 도슨트)를 "쓰는 사람"에서 "만지고 측정하고 개선하는 사람"의 결과물로 격상.
> llama.cpp → LiteRT-LM 이전 + LoRA 파인튜닝 + RAG 고도화 + 시스템 레벨 하드웨어 최적화를,
> **설계/실행/분석 멀티에이전트**로 자동화하여 재현 가능한 벤치마크로 증명한다.
>
> **타깃 환경:** Android 실기기(Galaxy S25) ↔ 노트북 USB-C 테더링, ADB 제어
> **매핑되는 삼성 MX AI JD 칸:** sLLM 파인튜닝(LoRA), AI Agent/Multi-Agent, 데이터를 끈질기게 보는 능력, 문제 정의
>
> **핵심 원칙:** 모든 항목은 "바꿨다"가 아니라 **"A vs B를 재보고 왜 B가 나은지 안다"**로 서술 가능한 흔적을 남긴다.

---

## Phase 0 — 준비 & 기준선 (Baseline Freeze)

리팩토링 전에 **현재 상태를 반드시 숫자로 박제**한다. 이게 없으면 "개선했다"를 증명할 수 없다.

- [ ] 현 llama.cpp 버전 AIris를 그대로 벤치마크
  - 지표: TTFT(Time To First Token), decode speed(tok/s), 총 응답 지연, 메모리 피크
  - 도슨트 태스크 대표 프롬프트 20~30개를 고정 세트로 확보 (이후 모든 실험의 공통 입력)
- [ ] RAG 정답셋(gold set) 구축: 작품 질의 → 기대 검색 문서 매핑 (RAG 평가용, 30~50건)
- [ ] LoRA 학습용 도메인 데이터 확보 계획: 미술/예술 도메인 QA·설명 데이터 수집 경로 정리
- [ ] 결과 저장 스키마 확정 (JSON/CSV): `run_id, engine, backend, model, lora, rag_variant, ttft, tok_s, latency, mem_peak, rag_recall, rag_precision, answer_quality, timestamp`

**산출물:** `baseline_results.json`, `prompt_set.json`, `rag_goldset.json`

---

## Phase 1 — LiteRT-LM 이전 (토대)

> 나머지 모든 Phase가 이 위에 올라간다. **먼저 끝내야 하는 단계.**
> 배경: MediaPipe LLM Inference API는 유지보수 전용 모드로 전환, 신규 최적화는 LiteRT-LM에 집중됨. 이전이 업계 권장 방향.

- [ ] 환경 세팅
  - [ ] LiteRT-LM Android 의존성 추가 (`com.google.ai.edge.litertlm:litertlm-android`)
  - [ ] ADB로 노트북 ↔ S25 연결 검증 (`adb devices`, USB-C 인식 확인)
- [ ] 모델 변환/확보
  - [ ] Gemma 계열 `.litertlm` 모델 확보 (LiteRT Community HuggingFace) 또는 기존 모델 변환(LiteRT Torch Generative API)
  - [ ] Play Asset Delivery / ADB push로 기기에 모델 배치 (`adb push ... /data/local/tmp/llm/`)
- [ ] 도슨트 추론 코드를 LiteRT-LM API로 교체 (`LlmInference` / `generateResponseAsync`)
- [ ] RAG 주입부를 새 API 인터페이스에 맞게 임시 포팅 (고도화는 Phase 4)

### ⚠️ 실무 함정 (미리 방어)
- **콜드스타트:** 모델 로드 후 첫 추론이 일부 기기에서 최대 1분. → 앱 시작 시 **silent warm-up 호출** 트리거
- **OOM:** `LlmInference` 인스턴스 2개 동시 보유 시 대부분 기기 OOM. → 단일 인스턴스 + 세션 관리
- **APK 크기:** 모델 번들 시 2.5GB+. → Play Asset Delivery로 base APK 100MB 이하 유지

### 🎯 "한 걸음 더" — 단순 포팅으로 끝내지 말 것
- [ ] **llama.cpp vs LiteRT-LM 동일 태스크 벤치마크 비교표** 생성
  - 동일 프롬프트 세트, 동일 기기에서 TTFT / decode speed / 메모리 대조
  - "왜 옮겼는가"를 데이터로 증명 → JD "데이터를 끈질기게 보는 능력" 정조준

**산출물:** `phase1_engine_comparison.md` (llama.cpp vs LiteRT-LM 대조표)

---

## Phase 2 — 시스템 레벨 하드웨어 최적화

> 기존: 양자화 비트수·top-k·temperature **파라미터 튜닝(1차원)만** 함.
> 목표: **엔진·하드웨어 레벨 최적화(다차원)**로 확장. 네 KV cache 지식이 직결되는 지점.

- [ ] **① Speculative Decoding / MTP 켜고 끄고 측정** ★KV cache 지식 직결
  - LiteRT-LM의 speculative decoding(MTP drafter) on/off 벤치마크
  - 배경: 경량 MTP 드래프터와 주 모델을 같은 HW IP(GPU)에서 실행, 공유 KV cache를 로컬 메모리서 관리 → cross-IP 동기화 지연 제거, 최대 2.2x 속도
  - 서사: "speculative decoding을 개념(MemSpec)으로 알 뿐 아니라 실제 엔진에서 튜닝해봤다"
- [ ] **② 백엔드 스위칭 벤치마크: CPU → GPU → NPU**
  - LiteRT는 Android에서 OpenCL 우선, OpenGL 폴백. 레거시 TFLite GPU 대비 1.4x, NPU 프로덕션 편입
  - 각 백엔드별 TTFT/tok_s/전력·발열(가능하면) 대조
- [ ] **③ AOT 컴파일 적용**
  - 타깃 SoC(S25) 대상 사전 컴파일 → 초기화/런타임 메모리 풋프린트 최소화, "instant-start"
  - 앱 시작 지연 before/after 측정

**산출물:** `phase2_hw_optimization.md` (spec-decoding · 백엔드 · AOT 3축 벤치 결과)

---

## Phase 3 — LoRA 파인튜닝 (갭의 핵심)

> JD 약한 칸 정조준. LiteRT-LM이 LoRA를 1급 지원(baseline과 동일 `generateResponse` 사용)해서 인프라가 공짜로 딸려옴.
> **함정:** "파인튜닝했어요"에서 멈추지 말 것. **비교 실험 설계**가 연구자 마인드의 증거.

- [ ] 미술/예술 도메인 데이터로 소형 모델(Gemma 계열) LoRA 튜닝
- [ ] **3-조건 비교 실험 설계**
  - [ ] RAG-only (현 버전)
  - [ ] LoRA-only
  - [ ] LoRA + RAG
  - 동일 프롬프트 세트로 응답 품질·지연·메모리 대조
- [ ] 관찰 로그: **"언제 파인튜닝이 이기고 언제 RAG가 이기는가"** 정리
  - (도메인 지식 내재화 vs 최신/특정 사실 주입의 트레이드오프)
- [ ] 메모리 효율 확인: 경량 LoRA를 베이스 위에 얹어 메모리 중복 없이 태스크 전환 (LiteRT-LM 기능 활용)

**산출물:** `phase3_lora_ablation.md` (3-조건 ablation 표 + 관찰)

---

## Phase 4 — RAG 고도화

> 기존: 검색 결과를 시스템 프롬프트에 그냥 삽입(2023년식).
> 목표: **측정 기반으로 개선한 리트리버 파이프라인**.

- [ ] 검색 파이프라인 고도화
  - [ ] 단순 임베딩 유사도 → 하이브리드 검색(키워드 + 시맨틱)
  - [ ] 리랭킹 단계 추가
- [ ] **평가 기반 개선 루프** (Phase 0 gold set 활용)
  - 검색 recall/precision 측정 → 청킹 전략·임베딩 모델·top-k 변경 → 재측정
  - 변경 이력을 실험 로그로 (Phase 2·3과 같은 근육)
- [ ] 엔진 네이티브 RAG/function calling 레퍼런스 참고 (LiteRT-LM 생태계 문서)

**산출물:** `phase4_rag_improvement.md` (리트리버 before/after 지표 + 변경 로그)

---

## Phase 5 — 멀티에이전트 실험 자동화 ★JD Multi-Agent 정조준

> Phase 1~4의 **모든 벤치마크를 사람이 손으로 돌리지 않고 에이전트가 오케스트레이션**.
> 재현성(연구자 마인드) + AI Agent 개발 경험(JD 담당 업무)을 동시 확보.
> OpenClaw 인프라와 연결 가능.

### 아키텍처: 3-에이전트 역할 분리

```
┌─────────────────────────────────────────────────────┐
│  노트북 (오케스트레이터 호스트)                          │
│                                                       │
│  [Designer Agent] ──> 실험 매트릭스 설계               │
│     · 다음 실험 조건 조합 결정 (engine×backend×lora×rag)│
│     · 가설 명시 ("NPU가 GPU보다 X% 빠를 것")           │
│         │                                             │
│         ▼                                             │
│  [Runner Agent]  ──> ADB로 실기기 실험 실행           │
│     · adb push/shell로 S25에서 벤치 구동               │
│     · 콜드스타트·OOM 방어(warm-up, 단일 인스턴스)      │
│     · raw 지표 회수 → 저장 스키마로 적재                │
│         │            USB-C / ADB                      │
│         ▼         ┌──────────────┐                    │
│  [Analyst Agent] │  Galaxy S25   │                    │
│     · 결과 통계·비교표 생성        │ (NPU/GPU/CPU) │    │
│     · 가설 검증/기각 판정          └──────────────┘    │
│     · 이상치·재실험 필요 플래그 → Designer로 피드백     │
└─────────────────────────────────────────────────────┘
```

### 구현 태스크
- [ ] **공통 실험 러너 하네스** (에이전트가 호출할 CLI/함수)
  - 입력: 실험 조건(JSON) → 출력: 표준 스키마 결과(JSON)
  - ADB 래퍼: `adb push` 모델/설정 → `adb shell` 벤치 실행 → 결과 pull
- [ ] **Designer Agent**: 실험 매트릭스 생성 + 가설 명시 (프롬프트/역할 정의)
- [ ] **Runner Agent**: 조건 하나씩 실기기 실행, 실패 시 재시도·방어 로직
- [ ] **Analyst Agent**: 결과 집계·통계·비교표, 가설 판정, 재실험 트리거
- [ ] 에이전트 간 메시지 프로토콜 정의 (역할 경계 명확화 — 이게 "정조준"의 핵심)
- [ ] 전체 루프 1회 무인 실행 검증 (사람 개입 없이 매트릭스 완주)

### 🎯 "한 걸음 더"
- [ ] 자동 생성 리포트: 에이전트가 전 Phase 결과를 종합해 **자동 벤치마크 리포트** 산출
- [ ] "왜 이 조합이 최적인가"를 Analyst가 자연어로 요약 → 그대로 자소서·발표 재료

**산출물:** `experiment_runner/` (하네스+3에이전트), `auto_report.md`

---

## 전체 의존 순서 & 우선순위

```
Phase 0 (baseline 박제)  ← 반드시 먼저
   │
Phase 1 (LiteRT 이전)    ← 나머지의 토대
   │
   ├─> Phase 2 (HW 최적화)  ┐
   ├─> Phase 3 (LoRA)       ├─ 병렬 가능 (엔진 위에서)
   └─> Phase 4 (RAG)        ┘
           │
Phase 5 (멀티에이전트 자동화) ← Phase 1~4의 러너가 준비되면 점진 통합
```

> **팁:** Phase 5를 마지막에 몰아서 만들지 말고, Phase 2부터 **실험 러너 하네스를 먼저 만들어** 손으로 쓰다가, 나중에 그 위에 에이전트를 얹으면 자연스럽다. (자동화는 "수동으로 잘 되는 것"을 감싸는 것이지, 처음부터 에이전트로 시작하면 디버깅 지옥.)

---

## 각 Phase → 삼성 MX AI JD 매핑 (자소서/면접 재료)

| Phase | 산출 서사 | JD 칸 |
|---|---|---|
| 1 | "엔진을 데이터 근거로 이전·검증" | 데이터 관찰력, 최신 기술 적용 |
| 2 | "spec-decoding·NPU까지 시스템 최적화" | 단말 SW, 리서치 마인드 |
| 3 | "sLLM을 도메인 LoRA 튜닝, RAG와 ablation" | **sLLM 파인튜닝(LoRA)** 직격 |
| 4 | "RAG를 측정 기반으로 고도화" | RAG, 끈질긴 데이터 관찰 |
| 5 | "멀티에이전트로 실험 자동화" | **AI Agent / Multi-Agent** 직격 |

> **관통 원칙 재확인:** 모든 Phase에서 "만들었다"가 아니라 **"측정하고 개선했다 / 비교하고 이유를 안다"**로 남길 것.
> 이것이 단순 개발자와 "연구자 마인드를 가진 개발자"를 가르는 유일한 차이이자, JD 첫 세 줄이 요구하는 실물이다.
