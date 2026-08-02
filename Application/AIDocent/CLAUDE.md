# CLAUDE.md

## 이 프로젝트가 무엇인가

온디바이스 미술관 도슨트 앱(AIris)에서 **LLM 추론 파트만 떼어낸 실험용 프로젝트**.
개선안(엔진 이전, 하드웨어 최적화, LoRA, RAG)을 **측정 가능한 벤치마크로 비교**하는 게 목표다. 전체 계획은 저장소 루트의 `../../PLAN.md` 참고.

### 지금 어디까지 왔나 (2026-07-31)

**현재 주력은 LiteRT-LM + Gemma 4 E2B + 도슨트 LoRA다.** 앱은 `EngineType.LITE_RT`로 고정돼 있고(`InferenceScreen.kt`). 모델 파일은 더 이상 고정이 아니라 **앱 실행 시 선택 화면에서 고른다**(`ModelCatalog`, 아래 "모델 파일" 절) — 기기엔 `gemma-4-E2B-it-docent-lora-int4.litertlm`(LoRA본)과 `gemma-4-E2B-it-int4.litertlm`(대조군)이 함께 올라가 있다.

| 축 | 상태 |
|---|---|
| llama.cpp → LiteRT-LM 이전 | **완료.** 추상화(`InferenceEngine`) 위에서 두 엔진 공존, 측정도 끝남 |
| 측정 신뢰도 (계측·조건 통제) | **완료.** 두 엔진 모두 엔진레벨 계측(`lastStats()`) 제공 → app레벨 체감값과 한 레코드에서 교차검증. 생성 상한(`maxTokens=256`)·greedy 샘플링으로 회차 조건 통제 (아래 "현재 측정 중인 성능 지표" 절) |
| 모델 선택 UI + adb 자동화 | **완료(2026-07-31).** `ModelCatalog`/`AutoRunRequest`/`BenchSignal` 추가, 기기에서 수동 선택 화면·자동 Suite 실행 둘 다 검증됨(아래 "모델 파일" 절) |
| 하드웨어 백엔드 | **원인 규명 완료(2026-08-01), 해법은 업스트림에 막힘.** 회귀가 아니라 **모델 파일이 바뀐 것**이었다 — 우리가 변환한 `.litertlm`은 활성값이 fp32(`dynamic_wi4_afp32`의 `afp32`)라 GPU 델리게이트가 그래프를 거의 못 먹고 `INTERNAL`로 실패, CPU로 강등된다. fp16으로 뽑으면 되는데 `--experimental_use_fp16`은 litert-torch 버그로 변환이 깨지고(가중치는 fp32 강제 로드, 캐시만 fp16 → attention에서 dtype 충돌), `--experimental_use_mixed_precision`이 남은 미검증 후보다. **당분간 CPU로 진행이 합리적** — LoRA 품질 비교는 대조군·LoRA본 둘 다 CPU라 이미 공정하다. 자세한 근거는 `docs/notes/2026-08-01-gpu-fallback-activation-type.md` |
| LoRA | **진행 중.** 도슨트 화법 LoRA를 Colab에서 학습 → `.litertlm` int4 변환 (아래 Colab 절) |
| RAG | 미착수 |

⚠️ **llama.cpp / Qwen3-0.6B는 이제 과거 축이다.** 코드(`LlamaCppEngine`, `native-lib.cpp`, vendored llama.cpp)는 **지우지 않고 남겨뒀지만** 새 작업은 여기서 일어나지 않는다. 남긴 이유는 (1) 이미 뽑은 벤치 수치의 재현 가능성, (2) `InferenceEngine` 추상화가 실제로 두 구현을 견딘다는 증거. 사용자가 명시하지 않는 한 **llama.cpp/GGUF/Qwen 쪽으로 제안을 끌고 가지 말 것.**

여기(`AIDocent`)가 **실제 Gradle 프로젝트 루트**다 (`rootProject.name = "AIris"`, `settings.gradle.kts`/`gradlew`/`.vscode`가 여기 있음). 상위의 `Application/`, `CapstoneProject-1/`은 각각 껍데기 폴더 / git 루트일 뿐이니 작업·빌드는 이 디렉토리 기준으로 한다. **단 LoRA 노트북(`LoRA.ipynb`)만은 git 루트(`CapstoneProject-1/`)에 있다.**

## 실제로 의미 있는 파일 (이것만 보면 됨)

파일 수는 수천 개지만 **거의 전부 vendored llama.cpp**다. 손대야 할 파일은 아래뿐:

**Kotlin** (`app/src/main/java/com/example/airis/`)
- `MainActivity.kt` — 진입점. `InferenceScreen(autoInitialize = true, autoRun = AutoRunRequest.from(intent))` 하나만 띄움. 인텐트를 넘기는 이유는 adb 자동 실행(아래 `AutoRunRequest.kt`)
- `InferenceScreen.kt` — **모델 선택** + 로드/세션/추론 테스트 UI + 성능 표시/기록. **UI는 `NativeBridge`를 직접 부르지 않고 `InferenceEngine`을 통해 호출**. 생성할 때 TTFT·순수 decode·total을 재서 화면에 띄우고 `BenchmarkLogger`로 JSONL에 기록. (구 `LlamaScreen.kt` — rename됨)
  - 모델 파일명은 **더 이상 소스에 없다**(구 `MODEL_FILE_NAME` 상수 삭제). 앱을 켜면 `ModelCatalog.scan`이 찾은 목록에서 고르고, 고른 `ModelFile.label`이 벤치 `model` 필드가 된다
  - 엔진 선택은 파일 상단 `private val ENGINE_TYPE` 한 줄 — `EngineFactory.create`와 `ModelCatalog`(스캔 확장자)가 함께 참조
  - **수동 버튼과 adb 자동 실행이 같은 헬퍼를 공유**: `loadAndInit`(로드→세션→시스템 프롬프트 체인) / `runSuiteAndReport`(Suite + 완료 신호). 자동화 전용 코드 경로를 따로 두면 둘이 서서히 어긋나므로, 새 동작을 넣을 땐 이 두 함수 안에 넣을 것
  - **모델 교체는 앱 재시작**이 전제(로드는 1회). 자동화도 모델마다 `am start -S`로 새로 띄우므로 측정 조건이 같아진다
- `ModelCatalog.kt` — **모델 파일을 찾아주는 창구.** `EngineFactory`가 '어떤 엔진'을 고르는 창구였다면 이건 '어떤 모델 파일'. `scan(context, type)`이 `getExternalFilesDir(null)`(= adb push 목적지)에서 엔진에 맞는 확장자만 골라 목록화, `findByName`은 자동화가 준 파일명을 **같은 목록에서** 찾는다(경로를 따로 조립하지 않게). `extensionFor`가 엔진↔포맷 짝(`LITE_RT`↔`litertlm`, `LLAMA_CPP`↔`gguf`)을 코드로 못박는 지점 — `when`이 exhaustive라 엔진이 늘면 컴파일러가 짚어줌
- `AutoRunRequest.kt` — **adb 무인 실행 진입 규약.** 인텐트 엑스트라(`-e model` / `-e autorun suite` / `--ei repeats` / `--ei warmups`) 파싱을 한 곳에 가둔 것. 좌표 탭(`input tap`)은 레이아웃 바뀌면 깨지므로 손잡이는 인텐트다. 엑스트라 없으면 `MANUAL` → 평소의 수동 선택 화면
- `BenchSignal.kt` — **자동화의 완료 신호.** logcat(`AirisBench` 태그 `SUITE_DONE ...`)과 마커 파일(`benchmarks/last_run.txt`) 양쪽에 같은 한 줄. 스크립트는 파일 폴링이 더 견고하다(logcat 대기는 '앱 시작 전에 걸어놨어야 한다'는 레이스가 있음). ⚠️ `SUITE_FAILED`/`MODEL_NOT_FOUND` 실패 신호를 **반드시** 함께 남길 것 — 안 그러면 스크립트가 영원히 기다린다
- `InferenceEngine.kt` — **추론 엔진 계약서(interface)**. `name`(엔진 이름, 벤치 기록용) + `backend`(실제 실행 하드웨어 `"gpu"/"cpu"`, 벤치 기록용) + `loadModel/initSession/setArtwork/decodeSystemPrompt/resetToSystemPrompt/generateStreaming/close`. UI가 바라보는 추상화 지점. **`backend`는 '의도'가 아니라 '실제 로드에 성공한' 값을 보고**해야 라벨이 진실이 됨(GPU 실패→CPU 폴백 시 `"cpu"`). **`setArtwork(artwork: Artwork)`는 `decodeSystemPrompt()` 전에 호출** — 두 엔진이 같은 `Artwork`를 받아 각자 방식으로 system 메시지를 만드는 공통 입구(동등성 보장). 안 부르면 빈 `Artwork()` 기준. `resetToSystemPrompt()`는 벤치 회차 독립을 위해 '시스템 프롬프트만 있는 깨끗한 상태'로 되감기. **`generateStreaming(prompt, maxTokens, onToken)`의 `maxTokens`는 벤치 조건 통제용 생성 상한**(기본 `DEFAULT_MAX_TOKENS = 256`, 같은 파일의 top-level const). **`lastStats(): EngineStats?`는 직전 생성 1회의 엔진레벨 계측**(모델 토큰 기준 ground truth) — 계측을 못 주는 엔진은 기본 구현이 null을 반환하므로 새 엔진이 안 깨진다. 자세한 주의사항은 아래 "현재 측정 중인 성능 지표" 절
- `LlamaCppEngine.kt` — **(휴면) 과거 축.** `InferenceEngine` 구현체(어댑터), 몸통은 `NativeBridge`에 위임. `name`으로 `"llama.cpp"`, `backend`로 `"cpu"` 보고(arm64 CPU 전용 빌드). `lastStats()`는 `NativeBridge.lastGenerationStats()`가 준 `DoubleArray`를 `EngineStats`로 옮긴다(길이 5 미만이면 null). 지금 앱은 이 경로를 타지 않는다 — 재현·대조용으로만 남겨둠
- `LiteRtEngine.kt` — `InferenceEngine` 구현체(어댑터). **LiteRT-LM 런타임(`com.google.ai.edge.litertlm`)을 감싸는 층**. JNI 없이 순수 Kotlin(라이브러리가 이미 컴파일된 AAR). `Engine`(모델) + `Conversation`(세션) 구조. 시스템 프롬프트는 `ConversationConfig.systemInstruction`으로 주입, `resetToSystemPrompt`는 대화를 새로 열어 구현(LiteRT-LM엔 명시적 reset 없음). **작품 본문은 `formatArtworkInfo(artwork)`가 `prompt_generate.cpp`의 `formatArtworkInfo()`를 Kotlin으로 1:1 미러링** — ⚠️ 단 llama.cpp는 `<|im_start|>system … <|im_end|>` Qwen 템플릿 태그를 직접 붙이지만 LiteRT-LM은 `systemInstruction` 텍스트를 런타임이 알아서 채팅 템플릿으로 감싸므로, 여기선 태그를 빼고 '본문만' 재현해야 이중 래핑 없이 두 엔진이 논리적으로 같은 system 메시지를 봄, `sendMessageAsync`+`CountDownLatch`로 async 스트리밍을 blocking 계약에 맞춤, ⚠️ **`maxTokens` 상한은 `cancelProcess()`로 스트림을 끊어 구현하는데 LiteRT-LM이 그 응답을 `onDone`이 아니라 `onError(CancellationException)`로 준다** — `capped` 플래그로 구분해 정상 종료로 처리해야 한다(안 하면 `runOnce`가 레코드를 버려서 *상한에 안 걸린 짧은 답변만* `results.jsonl`에 남는 조용한 편향이 생김. 2026-07-31에 발견·수정), 토큰 텍스트는 `Message→Contents→Content.Text.text` 체인으로 추출. `Context` 필요(cacheDir). `name`으로 `"litert-lm"` 보고. **엔진레벨 계측은 `lastStats()`가 `Conversation.getBenchmarkInfo()`(@ExperimentalApi)를 감싼 것** — ⚠️ `loadModel`이 엔진을 만들기 **전에** `ExperimentalFlags.enableBenchmark = true`를 켜야 한다(앱 전역 싱글톤을 `Engine.initialize()`가 한 번만 읽음). 실패해도 예외를 위로 안 던지고 null로 떨어뜨려 app레벨 측정은 계속되게 함. **백엔드 선택은 `loadModel`이 GPU 먼저 시도 → 예외 시 CPU 폴백(`tryLoad` 헬퍼), 실제 성공한 백엔드를 `resolvedBackend`에 담아 `backend`로 보고**(logcat `loaded with backend=...`). ⚠️ **GPU 성공에는 조건이 세 개**고 하나라도 빠지면 조용히 CPU로 강등된다: (1) 매니페스트에 `libOpenCL.so`/`libvndksupport.so` `<uses-native-library>` 선언, (2) 기기가 OpenCL 노출(S25/Adreno는 노출, Tensor G3·일부 중저가칩은 미노출), (3) **모델 파일의 활성값이 fp16**(`.litertlm` 섹션 태그 `prefer_activation_type`). S25에서 GPU 성공 기록(TTFT ~0.15s, tok/s가 CPU 대비 ~2배)은 **Google 공식 `litert-community` 파일 기준**이고, 우리가 fp32로 변환한 파일은 같은 폰에서 실패한다 — 3번이 빠져서다(2026-08-01 실측, `docs/notes/2026-08-01-gpu-fallback-activation-type.md`). Backend는 `Backend.GPU()/CPU()/NPU()`(litertlm의 nested class)
- `EngineFactory.kt` — `EngineType`(`LLAMA_CPP` / `LITE_RT`) 보고 엔진 생성. `LiteRtEngine`이 `Context`를 요구해 `create(type, context)` 시그니처. 엔진 교체는 `InferenceScreen`의 `EngineFactory.create(...)` 인자 한 줄. **현재 `LITE_RT`로 고정**
- `NativeBridge.kt` — JNI 브릿지 (`external fun` 선언들). 이제 `LlamaCppEngine` 뒤의 내부 부품. ⚠️ `generateStreaming(prompt, maxTokens, onToken)`의 **인자 순서가 `native-lib.cpp`의 `(jstring, jint, jobject)`와 정확히 일치**해야 함. `lastGenerationStats(): DoubleArray?`는 `[prefillTokens, decodeTokens, prefillTokPerSec, decodeTokPerSec, ttftSec]` 레이아웃(아직 생성 전이면 null). 작품 주입은 `setArtworkInfo(title, author, type, technique, school, date, description)`으로 C++ 전역에 세팅(다음 `decodeSystemPrompt()`가 읽어 프리필)
- `Artwork.kt` — 작품 하나의 메타데이터 `data class`(`title/author/type/technique/school/date/description`). **두 엔진이 시스템 프롬프트를 만들 때 공통으로 받는 타입**. 필드 순서는 native `setArtworkInfo`(JNI) 인자 순서와 맞춰 둠(어댑터에서 그대로 넘기기 편하게)
- `ArtworkRecognizer.kt` — **작품 인식 이음새(interface)**. `InferenceEngine`이 '엔진'을 갈아끼우는 추상화였듯, 이건 '작품을 알아내는 방법'을 갈아끼우는 추상화. 지금은 실제 인식 없이 고정 작품 하나(Darmstadt Madonna, `art_metadata.json` 첫 항목)를 반환하는 stub `FixedArtworkRecognizer`뿐. 나중에 카메라/이미지 기반 `recognize(image)` 구현체를 추가하면 호출부(`InferenceScreen`)는 recognizer 교체 한 줄 외엔 안 건드림
- `BenchmarkLogger.kt` — 벤치 결과 1건 = `BenchmarkRecord`(data class, PLAN Phase 0 스키마: `run_id/timestamp/engine/model/prompt/ttft/tok_s/latency/token_count` + **자원 지표** `mem_peak/native_heap_mb/temp_start_c/temp_end_c/thermal_status`(실측) + `backend`(실측 `"gpu"/"cpu"`, `BenchmarkRunner`가 `engine.backend`로 주입), `lora/rag`은 나중 Phase용 null) + **측정 조건** `max_tokens` + **엔진레벨 계측(ground truth)** `prompt_tokens/decode_tokens/prefill_tok_s/engine_tok_s/engine_ttft`(엔진이 못 주면 null). 엔진레벨을 app레벨(`ttft/tok_s/token_count`) 옆에 나란히 두는 이유는 **교차검증** — 체감값과 실측 토큰 수가 얼마나 벌어지는지 같은 줄에서 볼 수 있어야 한다. ⚠️ backend 로깅 이전(~2026-07-26)에 쌓인 레코드는 `backend:null`이라 timestamp로 수동 라벨 필요. 기기의 `getExternalFilesDir(null)/benchmarks/results.jsonl`에 **JSONL(한 줄=한 레코드)**로 append. `org.json.JSONObject`로 escape 안전 처리. `adb pull`로 컴퓨터 회수
- `HardwareStats.kt` — 회차마다 찍는 **OS 자원 지표(RAM·발열) 읽기 헬퍼**(`object`). 엔진 무관 프로세스/기기 지표라 측정 계층(`BenchmarkRunner`)에서만 씀. `peakRssMb`(`/proc/self/status`의 `VmHWM` 파싱, 프로세스 누적 peak RSS), `nativeHeapMb`(`Debug.getNativeHeapAllocatedSize`, llama.cpp/모델 C++ 몫), `batteryTempC`(sticky `ACTION_BATTERY_CHANGED`의 `EXTRA_TEMPERATURE`), `thermalStatus`(`PowerManager.currentThermalStatus`, 스로틀링 단계). 어떤 read든 실패하면 null 반환(측정이 벤치를 안 깨게). **전력은 외부 계측 없이는 근사라 제외, CPU/SoC sysfs 온도는 앱 SELinux로 막혀 배제** — 배터리 온도+thermal status만 실측
- `BenchmarkRunner.kt` — 측정 실행 계층. `runOnce`(reset→생성→지표 계산 1사이클, 단발/Suite 공유 단위) + `runSuite`(고정 프롬프트셋 × [warmup 폐기 + repeats 기록]). `runOnce`가 `Context`를 받아 생성 **직전 온도** / **직후 RAM·온도·thermal**을 `HardwareStats`로 스냅샷하고 `engine.backend`(gpu/cpu)를 레코드에 기입. ⚠️ **엔진레벨 계측(`engine.lastStats()`)은 생성 직후 즉시 회수**한다 — 다음 회차의 `resetToSystemPrompt()`가 세션/대화를 갈아엎으면 값이 사라지기 때문. 기본값 `warmups=1`(콜드스타트 버림) + `repeats=2`(장치 제작 단계라 축소, 정식 측정 땐 5+로) — `DEFAULT_WARMUPS`/`DEFAULT_REPEATS` 상수라 자동화가 `--ei repeats N`으로 덮어쓸 수 있고 Suite 버튼 라벨도 이 값을 따라감

> **왜 이렇게 나눴나**: 엔진을 갈아끼울 때 UI(`InferenceScreen`)를 안 건드리려고 추상화. **`LiteRtEngine`이 이 추상화 위에서 실제로 추가되면서 검증됨** — `InferenceEngine` 구현 + `EngineFactory` 케이스 한 줄, `InferenceScreen`은 엔진 선택 인자 외엔 안 건드림(엔진이 `name`을 보고하므로 벤치 라벨도 자동으로 따라옴). `.task`(MediaPipe tasks-genai)가 아니라 **`.litertlm`(LiteRT-LM)**을 고른 이유: MediaPipe LLM API는 유지보수 전용으로 동결됐고, LiteRT-LM은 CPU/GPU/NPU를 한 포맷으로 커버한다. 배경/개념 정리는 `docs/notes/2026-07-18-inference-engine-abstraction.md`.

**C++ / JNI** (`app/src/main/cpp/`) — **llama.cpp 경로 전용이라 현재 휴면.** 지금 앱은 여기를 안 탄다
- `native-lib.cpp` — JNI 구현 전부. 모델 로드, 세션, 프롬프트 캐싱, **스트리밍 생성 + 성능 타이밍**. 생성 상한은 Kotlin이 넘긴 `max_tokens`(0 이하면 1024로 폴백), prefill/decode 구간을 나눠 재서 `last_*` static에 담아 `lastGenerationStats()`로 넘김 — **LiteRT-LM의 `getBenchmarkInfo()`와 같은 항목을 맞춰 둔 것**
- `prompt_generate.cpp` / `.h` — 시스템/유저 프롬프트 빌드(Qwen 채팅 템플릿), 작품 정보 주입. `formatArtworkInfo()`가 `LiteRtEngine`의 Kotlin 미러링 원본이라 **작품 본문 포맷을 바꿀 땐 양쪽을 같이** 고쳐야 함
- `CMakeLists.txt` — `airis` 공유 라이브러리 빌드, llama.cpp 링크

**설정 / 에셋**
- `app/build.gradle.kts` — **ABI는 arm64-v8a 전용**(armeabi-v7a는 llama.cpp NEON fp16 때문에 빌드 실패), C++17, NDK/CMake 설정. **LiteRT-LM 의존성** `com.google.ai.edge.litertlm:litertlm-android:0.14.0`. 그 외 의존성: **TensorFlow Lite**(`tensorflow-lite:2.13.0` + `tensorflow-lite-support:0.4.4`, `androidResources{noCompress += "tflite"}`)는 향후 이미지 기반 그림 인식(`ArtworkRecognizer` 실제 구현체)용, `navigation-compose`, `kotlinx-coroutines-android`. Kotlin 2.3부터 `kotlinOptions{jvmTarget}`이 에러라 top-level `kotlin{compilerOptions{jvmTarget=...}}` DSL 사용(`JVM_11`)
- `gradle/libs.versions.toml` — **Kotlin 2.3.0** (LiteRT-LM 0.14.0이 Kotlin 2.3으로 컴파일돼 최소 2.3 요구. 옛 2.0.x면 `incompatible metadata version 2.3.0, expected 2.0.0` 에러). Compose 플러그인 버전은 Kotlin 버전을 따라감(`version.ref = kotlin`)
- `app/src/main/AndroidManifest.xml` — **LiteRT-LM GPU 추론의 전제조건**: `<application>` 안에 `<uses-native-library android:name="libvndksupport.so" android:required="false"/>` + `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` 두 줄이 있어야 함. Android 12+는 벤더 비공개 `.so`(`/vendor/lib64/libOpenCL.so`)를 이 선언 없이는 `dlopen` 못 함 → 없으면 GPU가 `"Can not find OpenCL library"`로 실패. `required="false"`라 OpenCL 없는 기기에서도 설치는 됨(그런 기기는 `LiteRtEngine`이 CPU로 폴백). **이건 기기 정책 장벽이 아니라 앱 매니페스트 문제였음** — S25는 선언만 넣으면 GPU 됨(Google Edge Gallery가 되는 이유도 이 선언을 갖고 있어서)
- `app/src/main/assets/art_metadata.json` — 작품 메타데이터 21,382점. 시스템 프롬프트 재료이자 LoRA 데이터셋의 근거 자료

**데이터 (git 미추적, 코드 아님)**
- `datasets/docent_seeds.jsonl` — **LoRA 학습 데이터 100건.** `art_metadata.json`의 실제 작품 기록에 근거한 관찰 유도형 도슨트 문답(artwork 50 / artist 25 / movement 25). Colab에선 Drive(`MyDrive/airis_refactoring/`)에서 읽는다
- `results.jsonl` — 기기에서 `adb pull`로 회수한 벤치 레코드 누적본

**⚠️ `app/src/main/cpp/llama.cpp/`** — vendored 소스. **용량 때문에 git 미추적**(.gitignore). 빌드 전 별도로 채워야 함. 여기 파일은 읽지도 편집하지도 말 것(검색 시 노이즈의 원인).

## 런타임 흐름

(모델 선택) → `engine.loadModel(path)` → `engine.initSession()` → `engine.setArtwork(artwork)` → `engine.decodeSystemPrompt()` → `engine.generateStreaming(prompt, onToken)`
(맨 앞의 모델 선택 = 사람이 선택 화면에서 고르거나 `AutoRunRequest.modelFileName`으로 지정. 가운데 로드~decode 체인은 `InferenceScreen.loadAndInit`에 한 번만 구현돼 있고 두 경로가 공유)
(작품은 `ArtworkRecognizer.recognize()`가 준 `Artwork`. `setArtwork`로 주입 → `decodeSystemPrompt`가 그 값으로 system 메시지를 프리필)
(`engine` = `EngineFactory.create(type, context)`가 준 `InferenceEngine`. `LLAMA_CPP` → `LlamaCppEngine` → `NativeBridge` → JNI, `LITE_RT` → `LiteRtEngine` → LiteRT-LM `Engine`/`Conversation`. 엔진 선택은 `InferenceScreen`의 `EngineFactory.create` 인자로. **벤치 반복 측정은 `BenchmarkRunner`가 매 회차 `resetToSystemPrompt()`로 초기화** — 두 엔진 모두 이 계약을 구현)

- 세션 파라미터(`native-lib.cpp` `initSession`): `n_ctx=1024`, `n_batch=1024`, threads 6/8, **sampler = greedy**(`llama_sampler_init_greedy`, 벤치 공정성 — LiteRT의 `topK=1`과 짝. 옛 top_p 0.8 + min_p + temp 0.4에서 바뀜)
- 시스템 프롬프트는 KV 캐시에 미리 디코딩해 재사용(프롬프트 캐싱)
- 생성은 토큰 단위 콜백으로 UI에 스트리밍, stop sequence로 조기 종료
- **UTF-8 스트리밍 주의**: 토큰 하나가 멀티바이트 문자(한글 3바이트·이모지 4바이트)를 쪼갤 수 있음. `native-lib.cpp`의 `generateStreaming`은 `utf8_complete_prefix_len()`로 **완성된 UTF-8 경계까지만** `NewStringUTF`로 보내고 잘린 꼬리는 다음 토큰까지 버퍼링. 안 하면 `NewStringUTF`가 앱을 SIGABRT로 죽임

**모델 파일**: 도슨트 LoRA본 `gemma-4-E2B-it-docent-lora-int4.litertlm`과 그 대조군 `gemma-4-E2B-it-int4.litertlm`(같은 `dynamic_wi4_afp32` 레시피, 만드는 법은 아래 Colab 절). 앱 번들이 아니라 기기의 `getExternalFilesDir(null)`에 별도로 push한다.

**어떤 모델로 잴지는 소스가 아니라 앱에서 고른다.** `ModelCatalog`가 그 디렉토리를 스캔해 `.litertlm` 목록을 만들고, 앱을 켜면 선택 화면이 뜬다(모델이 하나여도 자동 로드하지 않음 — 무엇을 재는지 항상 명시적으로). 고른 파일명이 벤치 `model` 필드가 되므로 `results.jsonl`에서 자동으로 구분된다. **여러 모델을 비교할 땐 둘 다 push해 두고 골라 쓰면 되고, 재빌드는 필요 없다.** 모델을 바꾸려면 앱 재시작(로드는 1회).

**2026-07-31 기기 검증 완료.** 수동 경로: 선택 화면에 `.litertlm` 4개(용량 포함)가 뜨고 `.gguf`(`Qwen3-0.6B-IQ4_NL.gguf`)는 정확히 제외됨. 자동 경로: `adb shell am start -S ... -e model <name> -e autorun suite`로 대조군·LoRA본 각각 재빌드 없이 Suite 실행 → `SUITE_DONE` 신호와 함께 `results.jsonl`에 정상 기록. 없는 파일명으로는 `MODEL_NOT_FOUND` 신호가 즉시 남아 스크립트가 무한 대기하지 않음을 확인. (같은 날 발견한 별개 버그: `LiteRtEngine`이 `maxTokens` 상한 도달을 실패로 오판정해 긴 답변 회차가 누락되던 문제도 함께 수정 — 위 `LiteRtEngine.kt` 항목의 ⚠️ 참고.)

**엔진과 모델 포맷은 같이 맞춰야 함**(`LITE_RT`↔`.litertlm`, `LLAMA_CPP`↔`.gguf`). 과거 축 재현이 필요하면 llama.cpp는 `Qwen3-0.6B-IQ4_NL.gguf`, LiteRT의 Qwen 짝은 `qwen3_0_6b_mixed_int4.litertlm`(`litert-community/Qwen3-0.6B`)이었다.

⚠️ **모델이 바뀌면 자원 지표의 자릿수가 바뀐다.** Qwen3-0.6B는 수백 MB, Gemma 4 E2B는 GB급이다. `results.jsonl`의 `mem_peak`을 한 축에 그리면 안 되고 `model` 필드로 갈라서 볼 것.

## Colab 파트 (LoRA 학습 · 모델 변환)

**이 프로젝트는 Android 스튜디오 안에서만 끝나지 않는다.** 모델을 만드는 절반은 Colab에서 돈다.

노트북: **`CapstoneProject-1/LoRA.ipynb`** (git 루트, 이 디렉토리가 아님). ⚠️ **작업 중인 실물은 Colab에 있고 로컬 `.ipynb`는 뒤처져 있을 수 있다** — 노트북 내용을 봐야 할 땐 로컬 파일 대신 **colab-mcp로 붙어서** 읽을 것(`/colab-connect` 스킬). 로컬 파일을 근거로 "이 셀이 이렇다"고 말하면 틀리기 쉽다.

파이프라인:

```
unsloth/gemma-4-E2B-it  ──FastModel + LoRA(r=16, 텍스트 전용)──▶  어댑터
        │                    학습셋: docent_seeds.jsonl 100건, 3 epoch
        │                    train_on_responses_only (응답 토큰에만 손실)
        ▼ save_pretrained_merged
16bit HF 체크포인트 (~9.5 GB)
        ▼ litert-torch export_hf --quantization_recipe=dynamic_wi4_afp32
                                 --keep_temporary_files=True       ← 중간 .tflite 보존
                                 --externalize_embedder
                                 --jinja_chat_template_override=litert-community/gemma-4-E2B-it-litert-lm
.litertlm (~2.5 GB) ──▶ Drive ──▶ 컴퓨터 ──▶ adb push ──▶ 기기
```

⚠️ 이 파이프라인의 산출물은 **CPU 전용**이다(활성값 fp32). GPU를 켜려면 활성값이 fp16이어야 하는데 그 경로가 아직 안 뚫렸다 — 아래 "활성값 정밀도" 절 참고. **`--experimental_use_fp16=True`는 넣지 말 것(변환이 깨진다).**

**대조군 원칙 (7-B 셀).** LoRA 효과를 재려면 **LoRA만 다르고 나머지가 전부 같은** 짝이 필요하다. 그래서 원본 `unsloth/gemma-4-E2B-it`도 **동일한 레시피·동일한 플래그로** 직접 변환해 대조군을 만든다. 공식 `litert-community/gemma-4-E2B-it-litert-lm`을 대조군으로 쓰면 안 된다 — 그건 Google의 **2/4/8 혼합 양자화**라 우리 int4와 조건이 달라서 *LoRA 효과 + 양자화 차이*가 섞인다. 그 혼합 레시피는 재현 불가능하다(어느 레이어에 몇 비트인지 비공개, 관련 issue·discussion 모두 미응답). 대조군 셀은 `RUN_BASELINE` 플래그 + Drive 존재 확인으로 이중 가드가 걸려 있어 **한 번 만들면 재실행 비용이 0**이다.

**양자화 레시피(가중치)**: `litert-torch export_hf`의 기본값은 `dynamic_wi8_afp32`(**int8**)라 그냥 두면 안 된다. 패키지가 제공하는 이름은 `dynamic_wi{2,4,8}_afp32` 및 각 `_blockwise` 변형. 커스텀 JSON 경로를 주면 레이어별 혼합 정밀도도 표현 가능(AI Edge Quantizer 레시피 스키마).

**활성값 정밀도 — GPU의 전제조건이지만 아직 못 켠다.** 레시피 이름의 `afp32`가 활성값 fp32를 뜻하고 **`afp16` 레시피는 존재하지 않는다.** 활성값은 레시피가 아니라 export 플래그 소관인데, 두 후보 모두 현재로선 막혀 있다:

- ❌ **`--experimental_use_fp16=True` — 쓰지 말 것. 변환이 반드시 깨진다.** (2026-08-01 실측) 이 플래그는 KV 캐시 dtype(`cache.py`)과 `inputs_embeds`의 `.half()`(`exportable_module.py`), 포장 시 `prefer_activation_type` 태그(`litert_lm_builder.py`)를 바꾸지만 **모델 가중치는 안 건드린다.** 그런데 `export_lib.load_model`이 체크포인트 dtype을 무시하고 `torch_dtype=torch.float32`로 **강제 로드**하므로 `query`(q_proj 출력)는 항상 fp32, `key`(KV 캐시)는 fp16이 되고, `attention.py`의 `bmm_fn(query, key)`에서 `RuntimeError: expected scalar type torch.float32 but found torch.float16`으로 죽는다. `attention.py`엔 이 플래그에 대한 분기가 없어 q/k가 같은 dtype이라고 가정한다. **사용자가 켤 수 있는 조합으로는 해결 불가능한 업스트림 버그다.** ⚠️ 병합 체크포인트를 fp16으로 저장하는 우회도 소용없다 — `torch_dtype=torch.float32`가 덮어쓴다.
- ❓ **`--experimental_use_mixed_precision=True` — 미검증 후보.** 이건 torch export가 아니라 **TFLite 변환 이후**에 `mu_pass_lib.apply_mixed_precision(lrt_model)`로 거는 패스라, 위 트레이싱 에러를 아예 안 지나간다. 다만 두 플래그 다 문서·주석이 한 줄도 없어서 GPU가 요구하는 fp16 활성값을 만들어주는지는 해봐야 안다.

업스트림 상태도 미해결이다([#875](https://github.com/google-ai-edge/litert-torch/issues/875) fp16 변환, [#683](https://github.com/google-ai-edge/litert-torch/issues/683) 활성값 FP16/INT16 — 둘 다 open). ⚠️ 가중치 레시피(`dynamic_wi4_afp32`)는 무슨 일이 있어도 그대로 둘 것: 대조군과 조건이 맞아야 한다.

**`--keep_temporary_files=True`를 켜 둘 것.** 기본값 `False`면 `work_dir = tempfile.mkdtemp(dir=output_dir)`라 변환이 끝나는 순간 중간 `.tflite`가 통째로 지워진다. 남겨두면 **재변환 없이 태그만 바꿔 재포장**하는 실험이 가능해진다(`litertlm_builder_cli tflite_model --prefer_activation_type fp16 --backend_constraint gpu`). ⚠️ 단 **그래프가 실제로 fp16/mixed일 때만 정당하다** — fp32 그래프에 태그만 덧붙이면 런타임에 거짓 라벨을 다는 것이고 GPU가 먹는 텐서 수도 안 늘어난다. 참고로 태그 부착은 `experimental_use_fp16`에 묶여 있어, mixed_precision만 켜면 태그가 안 붙을 수 있다(그때가 이 재포장이 필요한 상황).

**산출물 검증은 peek으로.** 플래그가 조용히 무시되는 실패를 막으려면 컨테이너를 직접 열어 태그를 확인한다 — `litert_lm_builder.litertlm_peek.peek_litertlm_file(path, dump_files_dir, out)`. ⚠️ `litert-torch`는 `uv tool`의 별도 venv에 깔리므로 **그 venv의 python으로** 호출할 것. `dump_files_dir`를 주면 섹션을 파일로 덤프한다(tflite 추출 가능).

**파일명에 `-fp16` 접미사를 붙여 fp32본과 분리할 것.** 이유 두 가지: (1) 7-B의 'Drive에 이미 있으면 스킵' 가드가 fp32본을 보고 fp16 변환을 건너뛴다, (2) 파일명이 벤치 `model` 필드가 되므로 덮어쓰면 `results.jsonl`의 기존 fp32 레코드가 무엇으로 잰 건지 알 수 없어진다.

**RAM이 병목이다.** 변환은 GPU를 거의 안 쓰고, `litert-torch`가 `uv tool`의 **별도 프로세스**라 커널 메모리를 나눠 쓰는 게 아니라 *추가로* 요구한다. 9.5 GB 체크포인트에 기본 런타임(12 GB, 학습 커널이 이미 점유)이면 OOM으로 죽는다 — **로그 끝의 `^C`가 그 증상이다**(변환 에러가 아님). 대응은 High-RAM 런타임 또는 `--experimental_lightweight_conversion`. 런타임 유형을 바꾸면 새 VM이라 `/content`가 비므로, 변환 셀들은 Drive에서 체크포인트를 다시 내려받아 **단독 실행되게** 짜여 있다(학습 재실행 불필요 — 변환은 메모리의 `model` 객체가 아니라 디스크의 safetensors를 읽는다).

**변환 실패는 조용하다.** 실패해도 출력 폴더에 빈 tmp 디렉토리가 남아서 다음 셀이 그걸 Drive에 복사하면 성공처럼 보인다. 그래서 변환·회수 셀 모두 `glob("**/*.litertlm")`로 산출물 존재를 확인하고 없으면 `raise`한다. 이 가드를 지우지 말 것.

## 현재 측정 중인 성능 지표

속도(tok/s)·지연 계열을 측정. **엔진레벨**과 **app레벨** 두 층을 **한 레코드에 나란히** 기록해 서로 교차검증한다:

**엔진레벨 (`InferenceEngine.lastStats()` → `EngineStats`)** — **두 엔진 모두 제공한다**(예전엔 llama.cpp에만 있었음). 모델 토큰 기준의 ground truth이고, `BenchmarkRunner`가 회차마다 회수해 `prompt_tokens/decode_tokens/prefill_tok_s/engine_tok_s/engine_ttft`로 저장한다
- llama.cpp — `native-lib.cpp`가 prefill/decode 구간을 나눠 재고 `lastGenerationStats()`(DoubleArray)로 넘김. `[prefillTokens, decodeTokens, prefillTokPerSec, decodeTokPerSec, ttftSec]` 레이아웃은 `NativeBridge.kt` 주석과 **정확히 일치**해야 함
- LiteRT-LM — `Conversation.getBenchmarkInfo()`. ⚠️ **`ExperimentalFlags.enableBenchmark = true`를 엔진 생성 *전에* 켜야** 한다(앱 전역 싱글톤을 `Engine.initialize()`가 한 번만 읽음). 안 켜고 부르면 `INTERNAL: Benchmark is not enabled`
- ⚠️ **`prefillTokens`의 의미가 두 엔진에서 다를 수 있다**: llama.cpp는 시스템 프롬프트가 KV 캐시에 남아 '이번 턴 user 프롬프트'만 세지만, LiteRT-LM은 `resetToSystemPrompt()`가 대화를 새로 열어 시스템 프롬프트가 포함될 수 있다. 직접 비교 전에 실측값으로 어느 쪽인지 확인할 것
- ⚠️ `lastStats()`는 **`generateStreaming` 직후, 다음 `resetToSystemPrompt()` 전에** 읽어야 한다(대화가 살아 있는 동안만 유효)

**app레벨 (`InferenceScreen.kt`, 태그 `InferenceScreen`)** — 콜백으로 토큰 도착 시각을 관측해 계산, 화면 표시 + `BenchmarkLogger`로 `results.jsonl`에 기록:
- **TTFT**: 질문 제출 → 첫 토큰 도착 (prefill 포함). 대화 체감의 핵심
- **순수 decode**: `(tokenCount-1) / (끝 - 첫토큰)` — 첫 토큰은 TTFT에 포함되므로 decode 구간에서 제외
- **Total**: 전체 체감 시간

> ⚠️ app레벨 `tokenCount`는 화면에 flush된 조각 수(UTF-8 버퍼링 영향)라 정확한 모델 토큰 수와 다를 수 있음 → **"체감값(perceived)"**으로 취급. 리포트에 "tok/s"라고만 쓰지 말고 체감값임을 명시할 것. 정확한 수치가 필요하면 같은 레코드의 엔진레벨 필드(`decode_tokens`/`engine_tok_s`)를 쓸 것.

**측정 조건 통제 (두 엔진 공통)** — 조건이 다르면 위 숫자를 나란히 놓을 수 없다:
- **생성 상한 `DEFAULT_MAX_TOKENS = 256`**(`InferenceEngine.kt`). 길이를 고정 안 하면 KV 캐시가 길어질수록 decode가 느려져서 '답이 길어진 것'과 '엔진이 느린 것'이 안 갈린다. 레코드의 `max_tokens` 필드로 사후 검증 가능. ⚠️ 상한 정확도는 다르다 — llama.cpp는 네이티브 루프가 모델 토큰을 정확히 세고, LiteRT-LM은 API에 상한이 없어 **콜백 횟수로 근사해 `cancelProcess()`로 끊는다**(콜백 1회 ≠ 토큰 1개)
- **greedy 샘플링 고정**: llama.cpp `llama_sampler_init_greedy()` ↔ LiteRT `SamplerConfig(topK=1)`. 확률적 샘플링은 회차마다 길이가 달라져 노이즈가 되고, 특히 llama.cpp의 `LLAMA_DEFAULT_SEED`는 '매번 새 시드'라 재현조차 안 됐다. **둘 중 한쪽만 바꾸면 공정성이 깨지니 항상 짝으로** 고칠 것

**자원레벨 (`HardwareStats.kt` → `BenchmarkRunner`가 기록, JSONL 저장)** — 인앱 API로 회차마다 읽어 같은 레코드에 통합. 엔진 무관 OS 지표:
- **RAM**: `mem_peak`(VmHWM 프로세스 누적 peak RSS, MB) + `native_heap_mb`(네이티브 힙 = 모델/런타임 C++ 몫). 엔진별 메모리 효율 비교의 재료
- **발열**: `temp_start_c`/`temp_end_c`(배터리 온도, 생성 전/후) + `thermal_status`(스로틀링 단계 `NONE~SHUTDOWN`)

> ⚠️ 자원레벨 주의: `mem_peak`(VmHWM)은 프로세스 누적 peak라 회차 간 값이 같게 나옴(모델이 메모리 대부분 차지, 정상). 배터리 온도는 반응이 느려 짧은 생성(수 초)엔 전=후로 안 움직임 — **발열 곡선은 긴 부하(연속 Suite/긴 생성)에서만** 관측됨. CPU/SoC 코어 온도까지 원하면 외부 adb 샘플러 별도.

아직 없음: **전력**(외부 계측 필요), **통계 방법론**(N회↑ → 중앙값/p90). 자원(RAM·발열)·엔진레벨 계측까지 자동 기록되지만 반복 수가 적은 v0 단계 — 반복 수는 이제 재빌드 없이 `--ei repeats N`으로 올릴 수 있다.

⚠️ **잠금 화면/화면 꺼짐 상태에서 잰 값은 쓰지 말 것.** adb 자동 실행은 잠긴 기기에서도 돌아가지만, CPU 거버너가 내려앉아 같은 모델·같은 프롬프트가 2.4 tok/s와 16.5 tok/s로 갈렸다(2026-07-31 실측). 정식 측정은 화면을 켜고 잠금 해제한 상태에서(`FLAG_KEEP_SCREEN_ON`이 앱이 떠 있는 동안 꺼짐을 막아준다).

**LoRA 단계에서 새로 필요해진 것: 품질 측정.** 속도·자원 지표만으로는 LoRA를 평가할 수 없다 — LoRA는 *빠르게* 만드는 게 아니라 *도슨트답게* 만드는 작업이라, 오히려 속도는 대조군과 같게 나오는 게 정상이다(가중치가 병합돼 구조가 동일). 그러니 **대조군 vs LoRA본의 답변 품질**을 별도로 재야 하며, `BenchmarkRecord`의 `lora` 필드가 그 자리다(현재 null). 방법론은 미정 — LLM-judge, 도슨트 화법 준수율, groundedness 등이 후보.

## 빌드 / 실행

이 디렉토리에서:

```powershell
./gradlew.bat installDebug          # 빌드 + 설치
```

VS Code 태스크(기본 빌드, `Ctrl+Shift+B`): **"폰에서 실행"** → `scripts/run-on-device.ps1` (빌드·설치·앱 실행). 로그는 **"로그 보기 (logcat)"** 태스크 → `scripts/logcat.ps1`.

- 물리 기기 필요(USB 디버깅). `ANDROID_HOME` 미설정 시 스크립트가 표준 SDK 경로로 폴백.
- minSdk 29 / targetSdk 36.

**모델 push** (컴퓨터 → 기기). Drive에서 내려받은 `.litertlm`을:

```powershell
adb push gemma-4-E2B-it-docent-lora-int4.litertlm /sdcard/Android/data/com.example.airis/files/
```

대조군과 LoRA본을 **다른 파일명으로 둘 다 올려두면** 앱 선택 화면에 둘 다 뜬다. 재빌드 없이 골라 재고, 벤치 레코드의 `model` 필드가 자동으로 따라오므로 나중에 `results.jsonl`에서 구분된다. (앱이 켜져 있는 채로 push했으면 선택 화면의 `🔄 새로고침`)

**adb 무인 실행** (사람이 화면을 안 봐도 되는 측정). 좌표 탭이 아니라 인텐트 엑스트라가 손잡이다:

```powershell
adb shell am start -S -n com.example.airis/.MainActivity -e model gemma-4-E2B-it-int4.litertlm -e autorun suite --ei repeats 5
```

`-S`가 프로세스를 강제 재시작하므로 모델마다 조건이 같다. 끝나면 `BenchSignal`이 신호를 남긴다 — **스크립트는 앱을 띄우기 전에 `adb shell rm -f .../benchmarks/last_run.txt`로 지우고, 그 파일이 다시 생길 때까지 폴링**하면 된다:

```powershell
adb shell cat /sdcard/Android/data/com.example.airis/files/benchmarks/last_run.txt
```

`SUITE_DONE saved=N model=... backend=...` / 실패면 `SUITE_FAILED reason=...` 또는 `MODEL_NOT_FOUND name=...`(같은 줄이 logcat `AirisBench` 태그에도 나옴). ⚠️ 기기 화면이 잠겨 있으면 `am start`가 막힌다.

**벤치 결과 회수** (기기 → 컴퓨터):

```powershell
adb pull /sdcard/Android/data/com.example.airis/files/benchmarks/results.jsonl .
```

`benchmark_results/`(로컬 회수 폴더)는 데이터라 `.gitignore` 처리됨 — 커밋 대상 아님.

## 작업 시 주의

- **방향을 과거로 끌고 가지 말 것.** 지금 축은 LiteRT-LM + Gemma 4 E2B + LoRA다. 사용자가 명시적으로 요청하지 않는 한 llama.cpp·GGUF·Qwen3-0.6B를 해법으로 제안하지 말 것 — 그쪽 코드가 남아 있는 건 재현용이지 현역이어서가 아니다.
- **노트북은 colab-mcp로 볼 것.** `LoRA.ipynb`의 로컬 사본은 Colab의 실물보다 뒤처져 있을 수 있다. 셀 내용을 근거로 판단해야 할 땐 `/colab-connect`로 붙어서 `get_cells`로 읽을 것. (연결 툴은 60초만 대기하고, 토큰·포트가 서버 프로세스마다 새로 발급돼 스테일 탭이 흔한 실패 원인이다.)
- 검색/편집은 `llama.cpp/` 폴더를 제외하고 앱 소스에 한정할 것.
- JNI 함수 시그니처는 `NativeBridge.kt`의 `external fun`과 `native-lib.cpp`의 `Java_com_example_airis_NativeBridge_*`가 **정확히 일치**해야 함.
- 네이티브 코드를 바꾸면 Gradle이 CMake를 다시 돌림 — 첫 빌드는 llama.cpp 컴파일로 오래 걸림. **Kotlin만 바꾸면 재빌드 빠름**(CMake 안 돎).
- 콜백(`onToken` 등)을 위임할 땐 **참조(`onToken`)와 호출(`onToken(it)`)을 구분**할 것. `NativeBridge.generateStreaming(prompt) { onToken }`처럼 참조만 하면 토큰이 UI로 안 옴 → `generateStreaming(prompt, onToken)`으로 전달.
- **자동화 경로를 따로 만들지 말 것.** adb 자동 실행과 사람이 누르는 버튼은 `loadAndInit`/`runSuiteAndReport`를 공유한다. 자동 실행에만 있는 코드 경로를 새로 파면 둘이 서서히 어긋나서, 무인 측정과 손 측정 결과가 달라진다.
- **자동 실행에 새 실패 지점을 만들면 `BenchSignal`도 같이 손볼 것.** 신호를 안 남기는 실패는 스크립트 입장에서 '영원한 대기'다.
- 새 엔진 추가 시: `InferenceEngine` 구현(`name` 포함) → `EngineFactory`에 케이스 추가 → `ModelCatalog.extensionFor`에 모델 포맷 추가(`when`이 exhaustive라 컴파일러가 짚어줌). `InferenceScreen`은 엔진 선택 인자 외엔 건드리지 말 것(추상화의 목적).
- LiteRT-LM 라이브러리 업그레이드 시 **Kotlin 버전 호환** 먼저 확인: 라이브러리가 더 최신 Kotlin으로 빌드되면 프로젝트 Kotlin(`libs.versions.toml`)도 그 이상이어야 함(아니면 `incompatible metadata version` 에러). 라이브러리 API 이름이 헷갈릴 땐 캐시된 aar/jar를 `javap -cp <jar> <FQCN>`으로 직접 뜯어보면 정확(우리가 `Message.text` 대신 `Contents→Content.Text.text`를 이렇게 찾음).
- **벤치 비교는 한 번에 한 변수만.** 엔진·모델·양자화 레시피·**활성값 정밀도**·백엔드·프롬프트 중 둘 이상이 동시에 달라진 두 레코드를 비교하면 원인 분리가 안 된다. 특히 양자화는 라벨(`int4`)이 같아도 스킴이 다를 수 있으니(2/4/8 혼합 vs `dynamic_wi4_afp32`) 출처를 확인할 것.
  - ⚠️ **fp32 회차와 fp16 회차를 같은 축에 놓지 말 것** — 활성값 정밀도가 바뀌면 백엔드(cpu→gpu)까지 딸려 바뀌어 변수가 둘이다. `model` 필드의 `-fp16` 접미사로 갈라 볼 것.
  - ⚠️ **07-26/27의 `gemma-4-E2B-it`(gpu) 레코드를 07-31 우리 변환본(cpu)과 나란히 놓지 말 것** — 그건 Google 공식 파일이라 backend·양자화·파일 구성(vision/audio 인코더, `mtp_drafter` 포함)이 동시에 다르다. LoRA vs 대조군(둘 다 우리 변환, 같은 조건) 비교만 유효하다.
