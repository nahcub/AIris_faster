# Android에서 **llama.cpp** 통합 가이드 (LMPlayground 기반)

생성일: 2025-11-03 02:18

이 문서는 업로드하신 프로젝트 **LMPlayground**를 실제로 분석하여 정리한, **안드로이드 앱에 llama.cpp를 통합하는 실전 가이드**입니다.
경로와 API 이름, Gradle/NDK/CMake 설정은 모두 리포의 실제 파일을 기준으로 작성했습니다.

---

## 🧩 1. 프로젝트 개요

### 트리(핵심 경로만)
```
LMPlayground/
└─ app/
   └─ src/main/cpp/
      ├─ llama.cpp/                # llama.cpp 소스(서브디렉터리로 포함)
      ├─ CMakeLists.txt            # native 빌드 정의
      ├─ LlamaCpp.h
      ├─ LlamaModel.cpp
      └─ native-lib.cpp            # JNI 브리지 구현
└─ app/build.gradle.kts            # 앱 모듈 Gradle
└─ build.gradle.kts                # 프로젝트 루트 Gradle
└─ settings.gradle.kts
└─ app/src/main/java/
   └─ com/druk/llamacpp/           # Kotlin JNI wrapper 패키지
      ├─ LlamaCpp.kt               # native 메서드 선언(loadModel 등)
      ├─ LlamaModel.kt             # 모델 핸들 / 세션 생성 등
      ├─ LlamaGenerationSession.kt # 생성 세션 JNI 래퍼
      ├─ LlamaGenerationCallback.kt
      └─ LlamaProgressCallback.kt
└─ app/src/main/java/com/druk/lmplayground/
   └─ ...                          # 샘플 UI, ViewModel, ModelInfoProvider 등
```

### 핵심 파일
- **CMake**: `app/src/main/cpp/CMakeLists.txt` — `add_subdirectory(llama.cpp)`로 소스 포함
- **JNI 브리지**: `app/src/main/cpp/native-lib.cpp`
- **llama.cpp 연계 클래스(C++)**: `LlamaModel.cpp`, `LlamaGenerationSession.cpp`, `LlamaCpp.h`
- **Kotlin JNI 래퍼**: `com.druk.llamacpp.*`
- **모델 카탈로그/프롬프트 템플릿**: `app/src/main/java/com/druk/lmplayground/models/ModelInfoProvider.kt`

---

## ⚙️ 2. 빌드 구성

### Gradle (앱 모듈) — `app/build.gradle.kts`
- **externalNativeBuild**에서 CMake 사용 및 `CMakeLists.txt` 지정
- **ABI 타겟**: `abiFilters += setOf("arm64-v8a", "x86_64")`
- (실기기 배포는 **arm64-v8a만** 남기는 것을 권장)

### Gradle / NDK / CMake 버전
- **NDK**: `ndkVersion = "27.2.12479018"`
- **CMake**: `version = "3.22.1"`
- Gradle 스크립트에서 externalNativeBuild → cmake → `path`, `version` 지정

### CMake — `app/src/main/cpp/CMakeLists.txt`
- 상단에 `add_subdirectory(llama.cpp)` — 리포에 llama.cpp 소스가 **서브디렉터리**로 포함됨
- 이후 타깃 라이브러리와 include/링킹 규칙 정의

> ⚠️ 팁: 추후 앱 크기/빌드 시간 최적화를 위해 llama.cpp를 **서브모듈(submodule)** 로 두고 필요한 파일만 포함하거나, 프리빌트 라이브러리를 사용해도 됩니다.

---

## 🔗 3. JNI 통합 방식

### 패키지 및 클래스
- Kotlin 패키지: **`com.druk.llamacpp`**
- 주요 래퍼:
  - `LlamaCpp` — **모델 로드** 등 진입점 `external fun loadModel(...)`
  - `LlamaModel` — 모델 핸들/속성/세션 생성
  - `LlamaGenerationSession` — **텍스트 생성(inference) 세션**

### 네이티브 시그니처(예시)
JNI 함수들은 `native-lib.cpp`에 구현되어 있고, 시그니처는 다음 규칙을 따릅니다.
```
Java_com_druk_llamacpp_LlamaCpp_loadModel(...)
Java_com_druk_llamacpp_LlamaModel_getModelSize(...)
Java_com_druk_llamacpp_LlamaGenerationSession_generate(...)
Java_com_druk_llamacpp_LlamaGenerationSession_destroy(...)
```
- `LlamaGenerationSession_generate`에서는 콜백 객체의 `newTokens(byte[])` **메서드를 호출**하여 스트리밍 토큰을 전달합니다.

### 호출 흐름(순서도)
Kotlin(UI) → `LlamaCpp.loadModel` → JNI → `LlamaModel.cpp`에서 `llama_model_load_from_file`  
↓  
Kotlin(UI) → `LlamaModel.createSession` → JNI → `LlamaGenerationSession.init(model)` → `llama_context_default_params()` 등 초기화  
↓  
Kotlin(UI) → `LlamaGenerationSession.generate(prompt, callback)` → JNI → 토큰 생성 루프에서 `callback.newTokens(bytes)`로 스트리밍 전달

---

## 🧠 4. 모델 로딩 과정

### 파일/경로
- 모델 파일은 **`.gguf`** 형식 사용
- JNI: `LlamaModel.cpp` 내부에서
  - `llama_model_params model_params = llama_model_default_params();`
  - `llama_model_load_from_file(modelPath.c_str(), model_params);`

### 컨텍스트 초기화
- `LlamaGenerationSession.cpp`에서
  - `llama_context_params ctx_params = llama_context_default_params();`
  - `llama_model_get_vocab(model)` 등으로 vocab 획득
  - 샘플러/배치/메시지 버퍼 준비

### 생성(inference)
- 세션의 `generate()`에서 프롬프트 토큰화 → 디코딩 루프
- JNI 측에서 **스트리밍 콜백**(`newTokens(byte[])`)으로 UI에 토큰을 전달

### 언로드/리소스 관리
- `LlamaGenerationSession_destroy(...)`에서 네이티브 객체 삭제 및 핸들 null 처리
- 모델 해제는 `llama_model_free(model)` 경로 사용

---

## 💬 5. 앱 UI ↔ Native 상호작용

### Kotlin 인터페이스
- **콜백**: `com.druk.llamacpp.LlamaGenerationCallback`
  ```kotlin
  interface LlamaGenerationCallback {
      fun newTokens(newTokens: ByteArray) // UTF-8 텍스트 토큰 스트림
  }
  ```
- **프로그레스**: `LlamaProgressCallback` — 모델 로딩 진행률(float 0.0~1.0)

### 호출 예시(개념)
```kotlin
val llama = LlamaCpp()
val model = llama.loadModel(
    path = "/storage/emulated/0/Download/YourModel.gguf",
    inputPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
    inputSuffix = "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
    antiPrompt = arrayOf("<|eot_id|>"),
    progressCallback = object : LlamaProgressCallback {
        override fun onProgress(progress: Float) { /* update UI */ }
    }
)

val session = model.createSession()
session.generate("Hello", object : LlamaGenerationCallback {
    override fun newTokens(newTokens: ByteArray) {
        val text = newTokens.toString(Charsets.UTF_8)
        // append to UI
    }
})
```

- UI는 **코루틴/Dispatchers.Main** 등으로 스레드 전환하여 안전하게 갱신하세요.

---

## 🚀 6. 내 앱에 0부터 통합하기 (체크리스트)

1) **llama.cpp 소스 포함**
- 방법 A: 현재 예시처럼 `app/src/main/cpp/llama.cpp/`로 포함하고 `add_subdirectory(llama.cpp)`
- 방법 B: git submodule로 추가 후 동일 경로(or 원하는 경로)로 연결

2) **CMake 세팅**
- `CMakeLists.txt`에 타깃 라이브러리 생성 후 llama.cpp 소스와 include 경로 연결
- 필요 시 `LLAMA_*` 옵션들(예: BLAS/Vulkan) 빌드 플래그 조정

3) **Gradle/NDK 세팅**
- `externalNativeBuild.cmake.path = "app/src/main/cpp/CMakeLists.txt"`
- `ndkVersion = "27.2.12479018"` (프로젝트 루트/앱에 명시)
- `abiFilters`는 배포 타겟 기준으로 최소화(예: `arm64-v8a`만)

4) **JNI 래퍼 추가**
- `com.druk.llamacpp` 패키지 구조와 유사하게 `external fun` 선언
- C++ 측 `native-lib.cpp`에 `Java_com_yourpkg_...` 네이밍으로 구현

5) **모델 배치**
- 개발 단계: `/sdcard/Download/*.gguf` 같이 접근 쉬운 위치 사용
- 운영 단계: 첫 실행 시 **다운로드 → 앱 전용 디렉터리**로 이동/검증 권장
- 모델별 **inputPrefix/inputSuffix/antiPrompt**를 `ModelInfoProvider`처럼 관리

6) **inference 테스트**
- 간단한 UI로 prompt 입력 → 스트리밍 출력 확인
- 로그로 `llama_perf_context_print` 결과를 확인하여 속도/토큰 처리량 점검

---

## ⚡ 7. 성능 및 최적화 팁

- **양자화(Quantization)**: `.gguf`의 `Q4_K_M`, `Q5`, `Q8` 등으로 메모리/속도 트레이드오프
- **ABI**: 실제 기기는 `arm64-v8a`만 빌드하여 APK 크기 축소
- **스레딩**: `n_threads`(스레드 수) → big.LITTLE 구조에서 최적값 실측
- **컨텍스트 재사용**: 같은 세션에서의 다회 호출은 생성 비용 감소
- **백엔드 가속**: 빌드 옵션으로 Vulkan/OpenCL/NEON/Arm Compute Library 고려
- **토큰 스트리밍**: UI에서 부분 리렌더링(append-only)로 **jank 최소화**
- **메모리**: 대용량 모델은 `llama_model_size()` 확인 후 OOM 방지를 위한 UX 설계

---

## 🧭 8. 확장 아이디어

- **온디바이스 챗 UI**: 멀티턴 대화 버퍼(`messages`)를 JNI로 유지하고, 시스템/유저/어시스턴트 역할 토큰을 `inputPrefix/suffix`로 엄밀히 처리
- **Streaming UX**: stop tokens(`antiPrompt`) 기반 중단, “생성 중지” 버튼 지원
- **프롬프트 파이프라인**: 템플릿 클래스화(예: Llama3, Qwen, Gemma 세트), 토크나이저 옵션 노출
- **모델 매니저**: 다운로드/검증/체크섬/버전 교체 UI 제공

---

## 🔚 부록: 실제 파일 레퍼런스
- JNI 브리지: `app/src/main/cpp/native-lib.cpp`
- 모델 로드: `app/src/main/cpp/LlamaModel.cpp` → `llama_model_load_from_file(...)`
- 컨텍스트: `app/src/main/cpp/LlamaGenerationSession.cpp` → `llama_context_default_params()`
- Kotlin 래퍼 패키지: `app/src/main/java/com/druk/llamacpp/`
- Gradle: `app/build.gradle.kts` (예: `abiFilters += setOf("arm64-v8a", "x86_64")`, CMake `version "3.22.1"`)
- NDK: `ndkVersion = "27.2.12479018"`

---

※ 본 가이드는 업로드된 LMPlayground 프로젝트를 기반으로 자동 생성되었습니다.
