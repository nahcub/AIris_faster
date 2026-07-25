# CLAUDE.md

## 이 프로젝트가 무엇인가

온디바이스 미술관 도슨트 앱(AIris)에서 **LLM 추론 파트(JNI + llama.cpp)만 떼어낸 실험용 프로젝트**.
목표는 llama.cpp → LiteRT-LM 이전, 하드웨어 최적화, LoRA, RAG 고도화를 **측정 가능한 벤치마크로 비교**하는 것. 전체 계획은 저장소 루트의 `../../PLAN.md` 참고.

여기(`AIDocent`)가 **실제 Gradle 프로젝트 루트**다 (`rootProject.name = "AIris"`, `settings.gradle.kts`/`gradlew`/`.vscode`가 여기 있음). 상위의 `Application/`, `CapstoneProject-1/`은 각각 껍데기 폴더 / git 루트일 뿐이니 작업·빌드는 이 디렉토리 기준으로 한다.

## 실제로 의미 있는 파일 (이것만 보면 됨)

파일 수는 수천 개지만 **거의 전부 vendored llama.cpp**다. 손대야 할 파일은 아래뿐:

**Kotlin** (`app/src/main/java/com/example/airis/`)
- `MainActivity.kt` — 진입점. `InferenceScreen(autoInitialize = true)` 하나만 띄움
- `InferenceScreen.kt` — 모델 로드/세션/추론 테스트 UI + 자동 초기화 흐름 + 성능 표시/기록. **UI는 `NativeBridge`를 직접 부르지 않고 `InferenceEngine`을 통해 호출**. 생성할 때 TTFT·순수 decode·total을 재서 화면에 띄우고 `BenchmarkLogger`로 JSONL에 기록. (구 `LlamaScreen.kt` — rename됨)
- `InferenceEngine.kt` — **추론 엔진 계약서(interface)**. `name`(엔진 이름, 벤치 기록용) + `loadModel/initSession/decodeSystemPrompt/generateStreaming/close`. UI가 바라보는 추상화 지점
- `LlamaCppEngine.kt` — `InferenceEngine` 구현체(어댑터). 몸통은 그냥 `NativeBridge`에 위임. **llama.cpp를 삭제하지 않고 감싸는 층**. `name`으로 `"llama.cpp"` 보고
- `LiteRtEngine.kt` — `InferenceEngine` 구현체(어댑터). **LiteRT-LM 런타임(`com.google.ai.edge.litertlm`)을 감싸는 층**. JNI 없이 순수 Kotlin(라이브러리가 이미 컴파일된 AAR). `Engine`(모델) + `Conversation`(세션) 구조. 시스템 프롬프트는 `ConversationConfig.systemInstruction`으로 주입, `resetToSystemPrompt`는 대화를 새로 열어 구현(LiteRT-LM엔 명시적 reset 없음), `sendMessageAsync`+`CountDownLatch`로 async 스트리밍을 blocking 계약에 맞춤, 토큰 텍스트는 `Message→Contents→Content.Text.text` 체인으로 추출. `Context` 필요(cacheDir). `name`으로 `"litert-lm"` 보고
- `EngineFactory.kt` — `EngineType`(`LLAMA_CPP` / `LITE_RT`) 보고 엔진 생성. `LiteRtEngine`이 `Context`를 요구해 `create(type, context)` 시그니처. 엔진 교체는 `InferenceScreen`의 `EngineFactory.create(...)` 인자 한 줄
- `NativeBridge.kt` — JNI 브릿지 (`external fun` 선언들). 이제 `LlamaCppEngine` 뒤의 내부 부품
- `BenchmarkLogger.kt` — 벤치 결과 1건 = `BenchmarkRecord`(data class, PLAN Phase 0 스키마: `run_id/timestamp/engine/model/prompt/ttft/tok_s/latency/token_count` + **자원 지표** `mem_peak/native_heap_mb/temp_start_c/temp_end_c/thermal_status`(실측), `backend/lora/rag`은 나중 Phase용 null). 기기의 `getExternalFilesDir(null)/benchmarks/results.jsonl`에 **JSONL(한 줄=한 레코드)**로 append. `org.json.JSONObject`로 escape 안전 처리. `adb pull`로 컴퓨터 회수
- `HardwareStats.kt` — 회차마다 찍는 **OS 자원 지표(RAM·발열) 읽기 헬퍼**(`object`). 엔진 무관 프로세스/기기 지표라 측정 계층(`BenchmarkRunner`)에서만 씀. `peakRssMb`(`/proc/self/status`의 `VmHWM` 파싱, 프로세스 누적 peak RSS), `nativeHeapMb`(`Debug.getNativeHeapAllocatedSize`, llama.cpp/모델 C++ 몫), `batteryTempC`(sticky `ACTION_BATTERY_CHANGED`의 `EXTRA_TEMPERATURE`), `thermalStatus`(`PowerManager.currentThermalStatus`, 스로틀링 단계). 어떤 read든 실패하면 null 반환(측정이 벤치를 안 깨게). **전력은 외부 계측 없이는 근사라 제외, CPU/SoC sysfs 온도는 앱 SELinux로 막혀 배제** — 배터리 온도+thermal status만 실측
- `BenchmarkRunner.kt` — 측정 실행 계층. `runOnce`(reset→생성→지표 계산 1사이클, 단발/Suite 공유 단위) + `runSuite`(고정 프롬프트셋 × [warmup 폐기 + repeats 기록]). `runOnce`가 `Context`를 받아 생성 **직전 온도** / **직후 RAM·온도·thermal**을 `HardwareStats`로 스냅샷해 레코드에 기입. 기본값 `warmups=1`(콜드스타트 버림) + `repeats=2`(장치 제작 단계라 축소, 정식 측정 땐 5+로)

> **왜 이렇게 나눴나**: llama.cpp ↔ LiteRT를 갈아끼울 때 UI(`InferenceScreen`)를 안 건드리려고 추상화. **LiteRT-LM 엔진(`LiteRtEngine`)이 이 추상화 위에서 실제로 추가됨** — `InferenceEngine` 구현 + `EngineFactory` 케이스 한 줄, `InferenceScreen`은 엔진 선택 인자 외엔 안 건드림(엔진이 `name`을 보고하므로 벤치 라벨도 자동으로 따라옴). `.task`(MediaPipe tasks-genai)가 아니라 **`.litertlm`(LiteRT-LM)**을 고른 이유: MediaPipe LLM API는 유지보수 전용으로 동결됐고, Qwen3-0.6B가 `.litertlm`으로만 배포돼 llama.cpp의 GGUF와 같은 모델로 공정 비교가 가능하기 때문. 배경/개념 정리는 `docs/notes/2026-07-18-inference-engine-abstraction.md`.

**C++ / JNI** (`app/src/main/cpp/`)
- `native-lib.cpp` — JNI 구현 전부. 모델 로드, 세션, 프롬프트 캐싱, **스트리밍 생성 + 성능 타이밍**
- `prompt_generate.cpp` / `.h` — 시스템/유저 프롬프트 빌드(Qwen 채팅 템플릿), 작품 정보 주입
- `CMakeLists.txt` — `airis` 공유 라이브러리 빌드, llama.cpp 링크

**설정 / 에셋**
- `app/build.gradle.kts` — **ABI는 arm64-v8a 전용**(armeabi-v7a는 llama.cpp NEON fp16 때문에 빌드 실패), C++17, NDK/CMake 설정. **LiteRT-LM 의존성** `com.google.ai.edge.litertlm:litertlm-android:0.14.0`. Kotlin 2.3부터 `kotlinOptions{jvmTarget}`이 에러라 top-level `kotlin{compilerOptions{jvmTarget=...}}` DSL 사용
- `gradle/libs.versions.toml` — **Kotlin 2.3.0** (LiteRT-LM 0.14.0이 Kotlin 2.3으로 컴파일돼 최소 2.3 요구. 옛 2.0.x면 `incompatible metadata version 2.3.0, expected 2.0.0` 에러). Compose 플러그인 버전은 Kotlin 버전을 따라감(`version.ref = kotlin`)
- `app/src/main/assets/art_metadata.json` — 벤치마크용 프롬프트 재료(작품 설명 텍스트)

**⚠️ `app/src/main/cpp/llama.cpp/`** — vendored 소스. **용량 때문에 git 미추적**(.gitignore). 빌드 전 별도로 채워야 함. 여기 파일은 읽지도 편집하지도 말 것(검색 시 노이즈의 원인).

## 런타임 흐름

`engine.loadModel(path)` → `engine.initSession()` → `engine.decodeSystemPrompt()` → `engine.generateStreaming(prompt, onToken)`
(`engine` = `EngineFactory.create(type, context)`가 준 `InferenceEngine`. `LLAMA_CPP` → `LlamaCppEngine` → `NativeBridge` → JNI, `LITE_RT` → `LiteRtEngine` → LiteRT-LM `Engine`/`Conversation`. 엔진 선택은 `InferenceScreen`의 `EngineFactory.create` 인자로. **벤치 반복 측정은 `BenchmarkRunner`가 매 회차 `resetToSystemPrompt()`로 초기화** — 두 엔진 모두 이 계약을 구현)

- 세션 파라미터(`native-lib.cpp` `initSession`): `n_ctx=1024`, `n_batch=1024`, threads 6/8, sampler = top_p 0.8 + min_p + temp 0.4
- 시스템 프롬프트는 KV 캐시에 미리 디코딩해 재사용(프롬프트 캐싱)
- 생성은 토큰 단위 콜백으로 UI에 스트리밍, stop sequence로 조기 종료
- **UTF-8 스트리밍 주의**: 토큰 하나가 멀티바이트 문자(한글 3바이트·이모지 4바이트)를 쪼갤 수 있음. `native-lib.cpp`의 `generateStreaming`은 `utf8_complete_prefix_len()`로 **완성된 UTF-8 경계까지만** `NewStringUTF`로 보내고 잘린 꼬리는 다음 토큰까지 버퍼링. 안 하면 `NewStringUTF`가 앱을 SIGABRT로 죽임

**모델 파일**: 엔진마다 포맷이 다름 — llama.cpp는 `Qwen3-0.6B-IQ4_NL.gguf`(GGUF), **LiteRT-LM은 `.litertlm`**(현재 `qwen3_0_6b_mixed_int4.litertlm`, `litert-community/Qwen3-0.6B`에서 받음). 앱 번들이 아니라 기기의 `getExternalFilesDir(null)`에 별도로 push. 없으면 UI가 경로를 안내. 파일명은 `InferenceScreen.kt`의 `MODEL_FILE_NAME` 상수 하나로 관리(모델 찾는 코드도 이 상수 참조, 벤치 라벨도 여기서 파생). **엔진과 모델 포맷을 같이 맞춰야 함**(`LITE_RT`↔`.litertlm`, `LLAMA_CPP`↔`.gguf`). ⚠️ 벤치 공정성: llama.cpp의 IQ4_NL(≈4bit)과 맞추려면 LiteRT도 **int4 변형**(`mixed_int4`) 사용 — int8/기본 변형은 정밀도가 달라 순수 엔진 비교가 안 됨.

## 현재 측정 중인 성능 지표

속도(tok/s)·지연 계열을 측정. **엔진레벨(logcat)**과 **app레벨(화면 + JSONL 저장)** 두 층으로 나뉜다:

**엔진레벨 (`native-lib.cpp`, logcat 태그 `LlamaNative`)**
- **Prefill**: `decodeSystemPrompt()`가 시스템 프롬프트 처리 시간/토큰수/tok/s 로깅
- **Generation**: `generateStreaming()`이 생성 시간/토큰수/tok/s 로깅 — 단, user prompt decode가 포함된 값

**app레벨 (`InferenceScreen.kt`, 태그 `InferenceScreen`)** — 콜백으로 토큰 도착 시각을 관측해 계산, 화면 표시 + `BenchmarkLogger`로 `results.jsonl`에 기록:
- **TTFT**: 질문 제출 → 첫 토큰 도착 (prefill 포함). 대화 체감의 핵심
- **순수 decode**: `(tokenCount-1) / (끝 - 첫토큰)` — 첫 토큰은 TTFT에 포함되므로 decode 구간에서 제외
- **Total**: 전체 체감 시간

> ⚠️ app레벨 `tokenCount`는 화면에 flush된 조각 수(UTF-8 버퍼링 영향)라 정확한 모델 토큰 수와 다를 수 있음 → **"체감값(perceived)"**으로 취급. 정확한 모델 토큰 기준 decode는 엔진레벨(`n_cur`)/`llama-bench`가 ground truth. 리포트엔 둘을 구분해서 쓸 것.

**자원레벨 (`HardwareStats.kt` → `BenchmarkRunner`가 기록, JSONL 저장)** — 인앱 API로 회차마다 읽어 같은 레코드에 통합. 엔진 무관 OS 지표:
- **RAM**: `mem_peak`(VmHWM 프로세스 누적 peak RSS, MB) + `native_heap_mb`(네이티브 힙 = 모델/런타임 C++ 몫). 엔진별 메모리 효율 비교의 재료
- **발열**: `temp_start_c`/`temp_end_c`(배터리 온도, 생성 전/후) + `thermal_status`(스로틀링 단계 `NONE~SHUTDOWN`)

> ⚠️ 자원레벨 주의: `mem_peak`(VmHWM)은 프로세스 누적 peak라 회차 간 값이 같게 나옴(모델이 메모리 대부분 차지, 정상). 배터리 온도는 반응이 느려 짧은 생성(수 초)엔 전=후로 안 움직임 — **발열 곡선은 긴 부하(연속 Suite/긴 생성)에서만** 관측됨. CPU/SoC 코어 온도까지 원하면 외부 adb 샘플러 별도.

아직 없음: 엔진레벨 순수 decode 분리, **전력**(외부 계측 필요), 품질(perplexity·groundedness·accuracy/F1·LLM-judge 등), **통계 방법론**(N회↑ → 중앙값/p90). 지금은 자원(RAM·발열)까지 자동 기록되지만 반복 수가 적은 v0 단계.

## 빌드 / 실행

이 디렉토리에서:

```powershell
./gradlew.bat installDebug          # 빌드 + 설치
```

VS Code 태스크(기본 빌드, `Ctrl+Shift+B`): **"폰에서 실행"** → `scripts/run-on-device.ps1` (빌드·설치·앱 실행). 로그는 **"로그 보기 (logcat)"** 태스크 → `scripts/logcat.ps1`.

- 물리 기기 필요(USB 디버깅). `ANDROID_HOME` 미설정 시 스크립트가 표준 SDK 경로로 폴백.
- minSdk 29 / targetSdk 36.

**벤치 결과 회수** (기기 → 컴퓨터):

```powershell
adb pull /sdcard/Android/data/com.example.airis/files/benchmarks/results.jsonl .
```

`benchmark_results/`(로컬 회수 폴더)는 데이터라 `.gitignore` 처리됨 — 커밋 대상 아님.

## 작업 시 주의

- 검색/편집은 `llama.cpp/` 폴더를 제외하고 앱 소스에 한정할 것.
- JNI 함수 시그니처는 `NativeBridge.kt`의 `external fun`과 `native-lib.cpp`의 `Java_com_example_airis_NativeBridge_*`가 **정확히 일치**해야 함.
- 네이티브 코드를 바꾸면 Gradle이 CMake를 다시 돌림 — 첫 빌드는 llama.cpp 컴파일로 오래 걸림. **Kotlin만 바꾸면 재빌드 빠름**(CMake 안 돎).
- 콜백(`onToken` 등)을 위임할 땐 **참조(`onToken`)와 호출(`onToken(it)`)을 구분**할 것. `NativeBridge.generateStreaming(prompt) { onToken }`처럼 참조만 하면 토큰이 UI로 안 옴 → `generateStreaming(prompt, onToken)`으로 전달.
- 새 엔진 추가 시: `InferenceEngine` 구현(`name` 포함) → `EngineFactory`에 케이스 추가. `InferenceScreen`은 엔진 선택 인자 외엔 건드리지 말 것(추상화의 목적).
- LiteRT-LM 라이브러리 업그레이드 시 **Kotlin 버전 호환** 먼저 확인: 라이브러리가 더 최신 Kotlin으로 빌드되면 프로젝트 Kotlin(`libs.versions.toml`)도 그 이상이어야 함(아니면 `incompatible metadata version` 에러). 라이브러리 API 이름이 헷갈릴 땐 캐시된 aar/jar를 `javap -cp <jar> <FQCN>`으로 직접 뜯어보면 정확(우리가 `Message.text` 대신 `Contents→Content.Text.text`를 이렇게 찾음).
