# LLM 적용 방식 상세 문서
---

## 개요

이 프로젝트는 **llama.cpp**를 활용하여 Android 디바이스에서 **온디바이스 LLM 추론**을 구현합니다. Qwen3 모델(0.6B 파라미터, IQ4_NL 양자화)을 사용하여 미술 작품에 대한 질문에 실시간으로 답변하는 AI 도슨트 기능을 제공합니다.

### 핵심 특징
- **완전한 오프라인 동작**: 네트워크 연결 없이 로컬에서 LLM 추론 실행
- **스트리밍 생성**: 실시간 토큰 단위 응답 생성
- **프롬프트 캐싱**: 시스템 프롬프트를 KV Cache에 저장하여 추론 속도 향상
- **JNI 기반 통합**: Kotlin/Android와 C++ llama.cpp 간 네이티브 브리지

## AI 모델
- **모델**: Qwen3-0.6B
- **양자화**: IQ4_NL (4-bit importance matrix quantization)
- **포맷**: GGUF (GPT-Generated Unified Format)
- **파라미터 수**: 약 600M


## 구현 세부사항

### 1. 모델 로딩 (Model Loading)

#### 세부 단계
1. **JNI 문자열 변환**: Java String → C char*
2. **모델 파라미터 설정**: `llama_model_default_params()` 사용
3. **모델 로드**: `llama_model_load_from_file()` 호출
4. **검증**: 모델 포인터 null 체크
5. **전역 변수 저장**: `static llama_model* model`에 저장

#### 특징
- GGUF 포맷 자동 파싱
- 양자화된 가중치 로드
- 메타데이터 및 아키텍처 정보 로드

**샘플링 전략**:
1. **Top-P (Nucleus Sampling)**: 누적 확률 0.8까지의 토큰만 고려
2. **Min-P Filtering**: 최소 확률 필터 (0.0 = 비활성화)
3. **Temperature**: 0.7 (낮을수록 deterministic, 높을수록 creative)
4. **Distribution Sampling**: 확률 분포에서 최종 토큰 선택

## 성능 최적화

### 1. 프롬프트 캐싱 (KV Cache Reuse)

#### 최적화 원리
Transformer 모델의 Self-Attention 메커니즘에서 각 토큰은 이전 모든 토큰과의 관계를 계산합니다. 이때 계산된 Key-Value 쌍을 KV Cache에 저장하면, 동일한 프롬프트에 대해 재계산할 필요가 없습니다.

### 2. 세션 기반 관리 (Session-Based Architecture)

초기화 1회 (initSession);
매 요청마다 (generateStreaming), sampler만 리셋
앱 종료 시 1회 (closeSession)
#### 성능 이점
- **Context 생성 오버헤드 제거**: 약 200-300ms 절약
- **메모리 단편화 감소**: 반복적인 할당/해제 방지
- **연속 대화 지원**: 이전 대화 맥락 유지 가능 (향후 확장 가능)

---

### 3. 멀티스레딩 최적화

#### 스레드 설정 전략
```cpp
ctx_params.n_threads = 6;          // 추론용
ctx_params.n_threads_batch = 8;    // 배치 처리용
```

#### CPU 코어 할당
- **총 코어 수**: 8개 (일반적인 Android 디바이스)
- **시스템 예약**: 2개 (OS, UI 렌더링)
- **추론 사용**: 6개 (토큰 생성 단계)
- **배치 처리**: 8개 (프롬프트 디코딩 단계, 일시적으로 모든 코어 활용)

#### 효과
- **디코딩 속도**: 병렬 처리로 2-3배 향상
- **UI 반응성**: 시스템 예약 코어로 앱 끊김 방지

---

### 4. 배치 처리 최적화

#### 배치 크기 설정
배치 크기 = 1024

#### 트레이드오프
- **큰 배치 크기**: 빠른 처리, 높은 메모리 사용, 발열 심함
- **작은 배치 크기**: 느린 처리, 낮은 메모리 사용
- **선택**: 1024 = 성능과 메모리의 균형점


## 사용 방법

### 1. 모델 준비

### 2. 앱 실행 흐름

#### Step 1: 모델 로드
사용자가 "Load Model" 버튼 클릭
**소요 시간**: 1-5초

#### Step 2: 시스템 프롬프트 캐싱
// 사용자가 "Decode System Prompt" 버튼 클릭
**소요 시간**: 4초 (1회만)

#### Step 3: 질문 입력 및 생성
사용자가 질문 입력 후 "Generate" 버튼 클릭
실시간 출력
**토큰당 시간**:  0.4초

### 4. 성능 벤치마크

#### 테스트 환경
- **디바이스**: Snapdragon8 elite (8 cores)
- **메모리**: 8GB RAM
- **모델**: Qwen3-0.6B-IQ4_NL

#### 측정 결과
| 단계 | 시간 | 속도 |
|------|------|------|
| 모델 로드 | 1.3초 | - |
| 시스템 프롬프트 디코딩 | 9초 | 102 tok/sec |
| 첫 토큰 생성 (TTFT) | 13초 | - |
| 평균 토큰 생성 | 2.5 tok/sec |