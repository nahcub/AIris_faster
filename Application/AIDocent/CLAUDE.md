# CLAUDE.md

## 이 프로젝트가 무엇인가

온디바이스 미술관 도슨트 앱(AIris)에서 **LLM 추론 파트(JNI + llama.cpp)만 떼어낸 실험용 프로젝트**.
목표는 llama.cpp → LiteRT-LM 이전, 하드웨어 최적화, LoRA, RAG 고도화를 **측정 가능한 벤치마크로 비교**하는 것. 전체 계획은 저장소 루트의 `../../PLAN.md` 참고.

여기(`AIDocent`)가 **실제 Gradle 프로젝트 루트**다 (`rootProject.name = "AIris"`, `settings.gradle.kts`/`gradlew`/`.vscode`가 여기 있음). 상위의 `Application/`, `CapstoneProject-1/`은 각각 껍데기 폴더 / git 루트일 뿐이니 작업·빌드는 이 디렉토리 기준으로 한다.

## 실제로 의미 있는 파일 (이것만 보면 됨)

파일 수는 수천 개지만 **거의 전부 vendored llama.cpp**다. 손대야 할 파일은 아래뿐:

**Kotlin** (`app/src/main/java/com/example/airis/`)
- `MainActivity.kt` — 진입점. `LlamaScreen(autoInitialize = true)` 하나만 띄움
- `LlamaScreen.kt` — 모델 로드/세션/추론 테스트 UI + 자동 초기화 흐름 + 성능 표시. **UI는 `NativeBridge`를 직접 부르지 않고 `InferenceEngine`을 통해 호출**
- `InferenceEngine.kt` — **추론 엔진 계약서(interface)**. `loadModel/initSession/decodeSystemPrompt/generateStreaming/close`. UI가 바라보는 추상화 지점
- `LlamaCppEngine.kt` — `InferenceEngine` 구현체(어댑터). 몸통은 그냥 `NativeBridge`에 위임. **llama.cpp를 삭제하지 않고 감싸는 층**
- `EngineFactory.kt` — `EngineType`(현재 `LLAMA_CPP`, 나중 `LITE_RT`) 보고 엔진 생성. 엔진 교체는 여기 한 줄
- `NativeBridge.kt` — JNI 브릿지 (`external fun` 선언들). 이제 `LlamaCppEngine` 뒤의 내부 부품

> **왜 이렇게 나눴나**: 나중에 llama.cpp → LiteRT로 갈아끼울 때 UI(`LlamaScreen`)를 안 건드리려고 추상화. LiteRT는 `LiteRtEngine`을 `InferenceEngine` 구현체로 추가하고 `EngineFactory`에 케이스만 넣으면 됨. 배경/개념 정리는 `docs/notes/2026-07-18-inference-engine-abstraction.md`.

**C++ / JNI** (`app/src/main/cpp/`)
- `native-lib.cpp` — JNI 구현 전부. 모델 로드, 세션, 프롬프트 캐싱, **스트리밍 생성 + 성능 타이밍**
- `prompt_generate.cpp` / `.h` — 시스템/유저 프롬프트 빌드(Qwen 채팅 템플릿), 작품 정보 주입
- `CMakeLists.txt` — `airis` 공유 라이브러리 빌드, llama.cpp 링크

**설정 / 에셋**
- `app/build.gradle.kts` — **ABI는 arm64-v8a 전용**(armeabi-v7a는 llama.cpp NEON fp16 때문에 빌드 실패), C++17, NDK/CMake 설정
- `app/src/main/assets/art_metadata.json` — 벤치마크용 프롬프트 재료(작품 설명 텍스트)

**⚠️ `app/src/main/cpp/llama.cpp/`** — vendored 소스. **용량 때문에 git 미추적**(.gitignore). 빌드 전 별도로 채워야 함. 여기 파일은 읽지도 편집하지도 말 것(검색 시 노이즈의 원인).

## 런타임 흐름

`engine.loadModel(path)` → `engine.initSession()` → `engine.decodeSystemPrompt()` → `engine.generateStreaming(prompt, onToken)`
(`engine` = `EngineFactory.create(...)`가 준 `InferenceEngine`. 현재는 `LlamaCppEngine` → `NativeBridge` → JNI로 이어짐)

- 세션 파라미터(`native-lib.cpp` `initSession`): `n_ctx=1024`, `n_batch=1024`, threads 6/8, sampler = top_p 0.8 + min_p + temp 0.4
- 시스템 프롬프트는 KV 캐시에 미리 디코딩해 재사용(프롬프트 캐싱)
- 생성은 토큰 단위 콜백으로 UI에 스트리밍, stop sequence로 조기 종료
- **UTF-8 스트리밍 주의**: 토큰 하나가 멀티바이트 문자(한글 3바이트·이모지 4바이트)를 쪼갤 수 있음. `native-lib.cpp`의 `generateStreaming`은 `utf8_complete_prefix_len()`로 **완성된 UTF-8 경계까지만** `NewStringUTF`로 보내고 잘린 꼬리는 다음 토큰까지 버퍼링. 안 하면 `NewStringUTF`가 앱을 SIGABRT로 죽임

**모델 파일**: `Qwen3-0.6B-IQ4_NL.gguf` — 앱 번들이 아니라 기기의 `getExternalFilesDir(null)`에 별도로 push해야 함. 없으면 UI가 경로를 안내.

## 현재 측정 중인 성능 지표

속도(tok/s) 계열만 측정하며, logcat으로 출력:
- **Prefill**: `decodeSystemPrompt()`가 시스템 프롬프트 처리 시간/토큰수/tok/s 로깅 (태그 `LlamaNative`)
- **Generation**: `generateStreaming()`이 생성 시간/토큰수/tok/s 로깅 — 단, user prompt decode가 포함된 값
- **End-to-end**: `LlamaScreen.kt`가 `System.currentTimeMillis()`로 재서 화면에 표시 (태그 `LlamaScreen`)

아직 없음: TTFT, 순수 decode 분리, 메모리/발열/전력, 품질(perplexity 등).

## 빌드 / 실행

이 디렉토리에서:

```powershell
./gradlew.bat installDebug          # 빌드 + 설치
```

VS Code 태스크(기본 빌드, `Ctrl+Shift+B`): **"폰에서 실행"** → `scripts/run-on-device.ps1` (빌드·설치·앱 실행). 로그는 **"로그 보기 (logcat)"** 태스크 → `scripts/logcat.ps1`.

- 물리 기기 필요(USB 디버깅). `ANDROID_HOME` 미설정 시 스크립트가 표준 SDK 경로로 폴백.
- minSdk 29 / targetSdk 36.

## 작업 시 주의

- 검색/편집은 `llama.cpp/` 폴더를 제외하고 앱 소스에 한정할 것.
- JNI 함수 시그니처는 `NativeBridge.kt`의 `external fun`과 `native-lib.cpp`의 `Java_com_example_airis_NativeBridge_*`가 **정확히 일치**해야 함.
- 네이티브 코드를 바꾸면 Gradle이 CMake를 다시 돌림 — 첫 빌드는 llama.cpp 컴파일로 오래 걸림. **Kotlin만 바꾸면 재빌드 빠름**(CMake 안 돎).
- 콜백(`onToken` 등)을 위임할 땐 **참조(`onToken`)와 호출(`onToken(it)`)을 구분**할 것. `NativeBridge.generateStreaming(prompt) { onToken }`처럼 참조만 하면 토큰이 UI로 안 옴 → `generateStreaming(prompt, onToken)`으로 전달.
- 새 엔진 추가 시: `InferenceEngine` 구현 → `EngineFactory`에 케이스 추가. `LlamaScreen`은 건드리지 말 것(추상화의 목적).
