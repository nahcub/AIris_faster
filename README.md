# AIris_faster

온디바이스 미술관 도슨트 앱(AIris)의 LLM 추론(JNI + llama.cpp) 파트를 떼어낸 실험 전용 레포지토리.

원본 팀 프로젝트(카메라 기반 작품 인식, UI 등)에서 LLM/JNI 관련 코드만 분리했습니다.
목표는 llama.cpp → LiteRT-LM 이전, 시스템 레벨 하드웨어 최적화, LoRA 파인튜닝, RAG 고도화를
측정 가능한 벤치마크로 비교하는 것입니다. 자세한 계획은 [`PLAN.md`](PLAN.md) 참고.

## 구성

- `Application/AIDocent/app/src/main/cpp/` — JNI 네이티브 코드 (`native-lib.cpp`, `prompt_generate.*`) + llama.cpp 소스(로컬 전용, git 미추적)
- `Application/AIDocent/app/src/main/java/com/example/airis/NativeBridge.kt` — JNI 브릿지
- `Application/AIDocent/app/src/main/java/com/example/airis/LlamaScreen.kt` — 모델 로드/추론 테스트 화면
- `Application/AIDocent/app/src/main/assets/art_metadata.json` — 벤치마크용 프롬프트 재료(작품 설명 텍스트)

## llama.cpp 소스 준비

용량 문제로 `cpp/llama.cpp/`는 git에 커밋하지 않습니다 (`.gitignore` 처리). 빌드 전에 별도로 채워넣어야 합니다.

```
# 예시: 로컬에 이미 받아둔 llama.cpp 소스 복사
cp -r <local-llama.cpp-source> Application/AIDocent/app/src/main/cpp/llama.cpp
```
