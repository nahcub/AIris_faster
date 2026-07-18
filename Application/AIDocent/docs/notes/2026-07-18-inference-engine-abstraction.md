# 추론 엔진 추상화 + 스트리밍 버그 수정 (2026-07-18 학습 노트)

llama.cpp 하나에 직접 묶여 있던 UI를, 나중에 LiteRT로 갈아끼울 수 있도록 **추상화 계층**을 넣었다.
그 과정에서 만난 개념과 버그 2개를 정리한다.

## 1. 왜 추상화하나

- **기존:** `LlamaScreen`(UI)이 `NativeBridge`(=llama.cpp)를 직접 호출 → 특정 엔진에 꽉 묶임(tight coupling).
- **목표:** UI는 "엔진이 뭔지 모른 채" 부르고, 실제 엔진은 뒤에서 교체. llama.cpp는 **삭제하지 않고** 감싸서 유지.

```
전:  LlamaScreen ──직접──▶ NativeBridge (llama.cpp)
후:  LlamaScreen ──▶ InferenceEngine (계약서)
                         ▲            ▲
                   LlamaCppEngine   LiteRtEngine (나중)
```

## 2. 쓴 디자인 패턴 / 용어

| 용어 | 역할 | 이 프로젝트에서 |
|---|---|---|
| **인터페이스(interface)** | "이런 기능이 있어야 한다"는 계약서(몸통 없음) | `InferenceEngine` |
| **전략 패턴(Strategy)** | 하나의 계약서를 여러 방식으로 구현 | `LlamaCppEngine` / `LiteRtEngine` |
| **어댑터 패턴(Adapter)** | 기존 코드를 새 계약서 모양으로 감싸기 | `LlamaCppEngine`이 `NativeBridge`에 위임 |
| **팩토리(Factory)** | 설정 보고 알맞은 구현체 생성 | `EngineFactory.create(EngineType)` |
| **의존성 역전(DIP)** | 구체(llama.cpp)가 아니라 추상(계약서)에 의존 | 전체를 관통하는 원칙 |

## 3. Kotlin 문법 메모

- **interface ≠ class.** interface는 "요구사항 목록"(몸통 `{}` 없음), class가 그 요구사항을 실제로 구현(`override`).
- **같은 package면 import 불필요.** 같은 동네(패키지)면 이름만으로 서로 참조. 다른 패키지의 것만 `import`.
- **이름 충돌은 "패키지"가 아니라 "scope(칸)" 기준.** 같은 칸에 같은 이름+모양일 때만 충돌. 클래스/함수 안은 각자 별도 칸. 함수는 파라미터가 다르면 같은 이름 OK(오버로딩).
- **`object` = 싱글톤.** 인스턴스를 만들 필요 없이 이름 자체로 사용(`NativeBridge`). 점(`.`)은 "이 안의 멤버 꺼내쓰기".
- **`return 함수호출()`** = 그 함수가 돌려준 **값**을 반환. 함수를 반환하는 게 아님. 중간 변수 생략일 뿐.

## 4. JNI: `NativeBridge`가 어떻게 진짜 llama.cpp와 연결되나

`external fun`은 몸통이 Kotlin이 아니라 C++에 있다는 선언. 4단계로 이어진다.

1. **선언:** `external fun loadModel(...)` — 몸통 없음.
2. **이름 규칙으로 짝 맞춤:** C++의 `Java_com_example_airis_NativeBridge_loadModel(...)` 과 자동 연결. (패키지 `.` → `_`)
3. **컴파일:** CMake가 `native-lib.cpp` + `llama.cpp`를 `libairis.so`로 빌드.
4. **로드:** `object`의 `init { System.loadLibrary("airis") }`가 앱 시작 시 `.so`를 올려 다리를 연결.

## 5. 버그 ①: JNI `NewStringUTF` + 잘린 UTF-8 → 앱 강제종료(SIGABRT)

**증상:** 생성 중 `JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8` 로 앱이 죽음.
입력 바이트가 `0x20 0xf0 0x9f 0x98` — 4바이트 이모지의 **앞 3바이트만** 온 상태.

**원인:** llama.cpp는 **토큰 단위**로 뱉는데, 토큰 하나가 글자 하나와 일치하지 않음. 한글(3바이트)·이모지(4바이트)는 여러 토큰에 쪼개져 나옴. 잘린 조각을 `NewStringUTF`에 넘기면 "유효하지 않은 UTF-8"이라며 abort.
> 특히 도슨트 답변은 **한글**이라 거의 매번 재현됨.

**수정** (`native-lib.cpp`): 완성된 UTF-8 경계까지만 UI로 보내고, 잘린 꼬리 바이트는 다음 토큰까지 버퍼에 보관.
`utf8_complete_prefix_len()` 헬퍼가 "끝에 미완성 멀티바이트가 있으면 그 앞까지의 길이"를 계산.

## 6. 버그 ②: 콜백이 함수를 **호출**하지 않고 **참조**만 함

**증상:** 크래시는 없는데 화면에 텍스트가 안 뜨고 `Tokens: 0`. 네이티브 로그는 101토큰 생성 성공.

**원인** (`LlamaCppEngine.kt`):
```kotlin
// ❌ { onToken } 은 새 람다. 몸통이 onToken "참조"뿐 — 호출 안 함. 들어온 토큰(it)은 버려짐.
return NativeBridge.generateStreaming(prompt) { onToken }
```
`onToken` 은 함수를 **가리키기만** 하고, `onToken(token)` 이라야 **실행**된다.

**수정:** 함수 타입이 일치하므로 그대로 전달.
```kotlin
// ✅
return NativeBridge.generateStreaming(prompt, onToken)
```

## 핵심 교훈

- 추상화 = **계약서(interface) 한 겹**을 UI와 엔진 사이에 끼우는 것. 그러면 엔진 교체 시 UI는 안 바뀐다.
- 스트리밍 LLM에서 **토큰 ≠ 글자.** 멀티바이트 문자는 토큰 경계에서 쪼개지므로 UTF-8 경계 버퍼링이 필요.
- 콜백을 넘길 땐 **참조(`onToken`)와 호출(`onToken(it)`)을 구분**할 것.
