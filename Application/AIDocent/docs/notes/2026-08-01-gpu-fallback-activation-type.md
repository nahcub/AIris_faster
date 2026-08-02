# LiteRT-LM GPU 강등의 원인: 활성값 정밀도(activation type)

2026-08-01 · Galaxy S25(SM-S931N) logcat 실측 + `litert-torch` 소스 확인

## 결론 한 줄

우리가 Colab에서 변환한 `.litertlm`은 **활성값이 fp32**(`dynamic_wi4_afp32`의 `afp32`)라 GPU 델리게이트가
그래프를 거의 못 먹고 실패, CPU로 강등된다. 기기·매니페스트·앱 코드 문제가 아니고 **회귀도 아니다** —
모델 파일이 바뀐 것이다.

**⚠️ 해법은 아직 없다.** fp16으로 재변환하면 될 것 같지만 `--experimental_use_fp16`은 litert-torch 버그로
변환이 깨진다(아래 "시도 1 — 실패" 참고). 남은 후보는 `--experimental_use_mixed_precision` 하나이고 미검증.
**당분간 CPU로 진행하는 게 합리적이다** — 맨 아래 "우선순위" 참고.

## 무엇이 아니었나 (지워야 할 오해 3개)

- ❌ **기기/OpenCL 문제 아님.** 로그에 `Created OpenCL device...` / `Created LiteRT GpuEnvironment.`가 정상으로
  찍히고, 매니페스트 선언도 먹고 있다(`nativeloader: ... uses_libraries=libOpenCL.so`)
- ❌ **`LiteRtEngine.tryLoad` 버그 아님.** GPU 시도 → 예외 → CPU 폴백은 설계대로 동작한 것이다
- ❌ **"dynamic shape(-1) → Dynamic executor가 CPU만 지원" 아님.** 조사 초기 가설이었으나 기각.
  로그에 `only supports CPU backend` 메시지가 없고 GPU 델리게이트는 오히려 초기화까지 성공한다.
  (`llm_litert_compiled_model_executor.cc:1978`을 GitHub `main` 줄 번호로 해석해 생긴 착오 — 0.14.0과
  줄 번호가 다르다. 실제 1978번은 `CompiledModel::Create` 호출부이고 에러는 거기서 전파된 것:
  `└ litert_compiled_model.h:1487`)

## 증거: 같은 폰·같은 앱에서 두 파일을 나란히 로드

| | 우리 변환본 `gemma-4-E2B-it-int4` | 공식 `gemma-4-E2B-it` |
|---|---|---|
| `activation_data_type` (engine settings) | **Not set** | **FLOAT16** |
| 섹션 태그 `section_prefer_activation_type` | **없음** | **`fp16`** (4개 섹션 전부) |
| 시그니처 | 로그에 안 나옴 | `decode` / `prefill_1024` / `prefill_128` / `verify` |
| GPU 델리게이트가 흡수한 외부 텐서 | **4개** (델리게이트 커널 1개) | **31~32개** × 커널 4개 |
| 로드 결과 | `INTERNAL` → **CPU 강등** | **GPU 성공** |

`results.jsonl`의 과거 기록도 같은 방향이다 — Google이 배포한 파일(`qwen3_0_6b_mixed_int4`,
`gemma-4-E2B-it`)은 **둘 다** `backend:gpu`, 우리가 변환한 파일은 **둘 다** `backend:cpu`.

**"외부 텐서 4개"가 핵심 증상이다.** OpenCL 델리게이트가 우리 그래프에서 거의 아무것도 못 먹었다는 뜻
(공식은 31~32개씩 4덩어리). 껍데기만 GPU로 올라가고 그 다음 CompiledModel 생성에서 터진다.
**이건 그래프 레벨 성질이지 메타데이터 문제가 아니다** — 아래 "재포장은 성립하지 않는다"의 근거.

## 왜 그렇게 됐나

레시피 이름에 이미 적혀 있었다:

```
dynamic_wi4_afp32
        │     └── a(ctivation) fp32   ← 활성값 fp32
        └──────── w(eight) i(nt)4     ← 가중치 int4
```

가중치는 int4로 잘 줄였지만 활성값이 fp32다. GPU(OpenCL) LLM 커널이 도는 공식 파일은 fp16이었다.
(⚠️ "GPU 커널이 fp16을 *요구*한다"는 인과까지 증명된 건 아니다. fp16 재변환 후에도 `backend=cpu`면
이 전제부터 다시 의심할 것.)

## 시도 1 — `--experimental_use_fp16=True` : **실패(업스트림 버그). 쓰지 말 것**

플래그 자체는 실재하고 전달도 된다(`--help`엔 없지만 `export_hf(**kwargs)` → `ExportableModuleConfig`의
dataclass 필드. 변환 로그 `print_summary()`에 `experimental_use_fp16: True`로 찍힌다).
**그런데 변환이 반드시 깨진다:**

```
RuntimeError: expected scalar type torch.float32 but found torch.float16
  litert_torch/.../core/attention.py:76   logits = bmm_fn(query, key)
```

원인 사슬:

| | dtype | 왜 |
|---|---|---|
| `query` (q_proj 출력) | **fp32** | `export_lib.load_model`이 `torch_dtype=torch.float32`로 **강제 로드** — 체크포인트 dtype 무시 |
| `key` (KV 캐시) | **fp16** | `cache.py`가 플래그 보고 fp16으로 만듦 |

`experimental_use_fp16`은 **입력·캐시만 바꾸고 모델 가중치는 안 건드린다.** 그런데 가중치는 fp32로 강제
로드되므로 둘이 attention의 `bmm`에서 처음 만나는 순간 충돌한다. `attention.py`엔 이 플래그에 대한
분기가 없고 q/k가 같은 dtype이라고 가정한다.

⚠️ **병합 체크포인트를 fp16으로 저장하는 우회는 소용없다** — `torch_dtype=torch.float32`가 덮어쓴다.
사용자가 켤 수 있는 어떤 조합으로도 해결되지 않는 **업스트림 버그**다.

## 시도 2 — `--experimental_use_mixed_precision=True` : 미검증 후보

`ExportableModuleConfig`의 또 다른 필드(로그에 `experimental_use_mixed_precision: False`로 같이 찍힌다).
**동작 층이 다르다** — torch export가 아니라 **TFLite 변환 이후** `mu_pass_lib.apply_mixed_precision(lrt_model)`
로 거는 패스라, 시도 1이 죽은 트레이싱 단계를 아예 안 지나간다.

```
--experimental_use_mixed_precision=True   ← fp16은 끄고 이것만
--keep_temporary_files=True
--quantization_recipe=dynamic_wi4_afp32   (그대로)
```

성공하면 peek으로 `prefer_activation_type` 확인 → 없으면 보존된 중간 `.tflite`를 재포장
(`litertlm_builder_cli tflite_model --prefer_activation_type fp16 --backend_constraint gpu`).
태그 부착 코드가 `experimental_use_fp16`에 묶여 있어 **안 붙을 가능성이 크다.**
이 재포장은 정당하다 — 그래프가 실제로 mixed precision이므로 거짓 라벨이 아니다.

⚠️ 두 플래그 모두 **문서도 주석도 한 줄 없다.** `apply_mixed_precision`이 GPU가 요구하는 fp16 활성값을
만들어주는지는 해봐야 안다. 업스트림도 미해결이다
([#875](https://github.com/google-ai-edge/litert-torch/issues/875) fp16 변환,
[#683](https://github.com/google-ai-edge/litert-torch/issues/683) 활성값 FP16/INT16 — 둘 다 open).

## 이건 Gemma 문제가 아니라 **변환 도구(litert-torch) 문제다**

깨지는 지점이 전부 모델 무관한 공용 코드다 — `export_lib.load_model`의 fp32 강제 로드, `core/cache.py`,
`core/attention.py`. Gemma 4 고유 코드가 아니다. 실측도 같은 방향이다: `results.jsonl`에서 **Google이
배포한 파일은 아키텍처가 달라도(Qwen3-0.6B, Gemma 4 E2B) 둘 다 GPU**, **우리가 export_hf로 변환한
파일은 둘 다 CPU**. 경계선은 "어떤 모델이냐"가 아니라 **"누가 변환했느냐"**다.

⚠️ **그래서 LoRA와 GPU는 현재 툴체인에서 양립하지 않는다.** LoRA본은 정의상 우리가 직접 변환해야 하고,
직접 변환하면 활성값이 fp32라 CPU로 떨어진다. 공식 litert-community 파일을 쓰면 GPU는 되지만 그건
LoRA가 안 들어간 모델이다. 모델을 바꿔도(예: Qwen3) 이 구조는 그대로다.

### ⚠️ "태그만 다시 붙이는 재포장"은 성립하지 않는다

`prefer_activation_type`이 File Builder(포장) 단계 파라미터인 건 맞다
(`litert_lm_builder.LitertLmFileBuilder.add_tflite_model(..., prefer_activation_type=...)`,
CLI는 `litertlm_builder_cli tflite_model --prefer_activation_type {fp16|fp32|fp32_fp16}`).

하지만 그걸 켜는 손잡이인 `--experimental_use_fp16`은 **세 곳**을 동시에 바꾼다:

| 위치 | 효과 |
|---|---|
| `core/cache.py:346` | KV 캐시 dtype → `torch.float16` |
| `core/exportable_module.py:120` | `inputs_embeds` → `.half()` |
| `core/litert_lm_builder.py:389` | 포장 시 `prefer_activation_type='fp32_fp16'` 부착 |

앞의 둘이 **export되는 그래프 자체**를 바꾼다. fp32로 뽑은 기존 `.litertlm`에 태그만 덧입히면 런타임에
거짓 라벨을 붙이는 꼴이고, OpenCL이 먹는 텐서 수도 늘지 않는다. → **변환을 다시 돌려야 한다.**

### 양자화 레시피는 그대로 둔다

패키지가 주는 이름은 `dynamic_wi{2,4,8}_afp32`와 각 `_blockwise` 변형뿐 — **`afp16` 레시피는 없다.**
활성값 정밀도는 레시피가 아니라 export 플래그로 켠다. 가중치 int4(`dynamic_wi4_afp32`)는 유지해야
대조군과 조건이 맞는다.

### `keep_temporary_files` — 이번 삽질의 부산물

`export_hf`의 기본값은 `False`이고, 그때 `work_dir = tempfile.mkdtemp(dir=output_dir)`라 변환이 끝나면
중간 `.tflite`가 통째로 지워진다. 그래서 태그 하나 고치려는데도 남은 게 없었다(Drive엔 완성된 `.litertlm`
2개, 각 2438 MB뿐). `--keep_temporary_files=True`면 `work_dir = output_dir`가 되어 중간 `.tflite`가 남고,
**같은 그래프에서 태그만 바꿔보는 재포장이 그때는 가능해진다.**

## 산출물 검증 (플래그가 조용히 무시되는 실패 방지)

플래그를 넣었다고 태그가 붙었다고 믿으면 안 된다. 컨테이너를 직접 연다:

```python
# litert-torch는 uv tool의 별도 venv에 깔린다 — 그 venv의 python으로 호출할 것
VENV_PY = "/root/.local/share/uv/tools/litert-torch-nightly/bin/python"
code = ("import sys\n"
        "from litert_lm_builder import litertlm_peek\n"
        "litertlm_peek.peek_litertlm_file(sys.argv[1], None, sys.stdout)\n")
# 출력에서 prefer_activation_type / backend_constraint / model_type 줄을 본다
```

`peek_litertlm_file(litertlm_path, dump_files_dir, output_stream, jinja_prompt_template_path=None)` —
`dump_files_dir`를 주면 섹션 내용을 파일로 덤프한다(tflite 추출 가능).

⚠️ **존재 여부가 아니라 값을 찍을 것.** `fp32_fp16`이 나올 텐데 그 자체는 실패가 아니지만 공식과 다른
값이므로 로그에 남겨둬야 폰에서 실패했을 때 바로 다음 수순으로 갈 수 있다(아래 "미확인" 참고).

## LoRA.ipynb 7-A / 7-B 반영 내용

- `USE_FP16` / `BASE_USE_FP16` 스위치 → `--experimental_use_fp16=True`.
  7-B의 실험군–대조군 일치 검사에 이 값 포함(한쪽만 켜면 `raise`)
  ⚠️ **이 스위치는 켜면 변환이 깨진다**(위 "시도 1"). `False`로 두거나,
  `--experimental_use_mixed_precision`을 켜는 스위치로 갈아탈 것
- `--keep_temporary_files=True` 양쪽 모두
- 변환 직후 `peek`으로 `prefer_activation_type` 검증, 없으면 `raise`
  (peek 실행 자체가 실패한 경우는 경고만 — 모델 불량과 구분)
- 산출물 파일명에 `-fp16` 접미사:
  `gemma-4-E2B-it-docent-lora-int4-fp16.litertlm` / `gemma-4-E2B-it-int4-fp16.litertlm`,
  Drive 폴더도 `gemma_4_{docent,base}_litertlm_fp16`으로 분리
- Drive 회수는 `.litertlm`만(중간 `.tflite`는 `COPY_INTERMEDIATES=True`일 때만)
- 맨 뒤에 `[유틸] .litertlm 컨테이너 들여다보기` 셀 추가

### 이름을 나눠야 하는 이유 두 가지

1. **7-B의 '이미 있으면 스킵' 가드가 오작동한다.** fp32 대조군이 이미 Drive에 있으므로 폴더명을 안 나누면
   fp16을 만들지도 않고 "이미 있음"으로 넘어간다
2. **기존 측정 데이터의 출처를 잃는다.** fp32본 2개는 이미 폰에서 측정을 마쳤다. 덮어쓰면 `results.jsonl`의
   기존 레코드가 어떤 파일로 잰 것인지 알 수 없어진다. 파일명이 벤치 `model` 필드가 되므로 접미사를 붙이면
   사후에 자동으로 갈린다

## 폰 검증 (최종 판정은 여기서)

```powershell
adb logcat -c
adb shell am start -S -n com.example.airis/.MainActivity -e model <파일명>.litertlm
adb logcat -d | Select-String "activation_data_type|external tensors|loaded with backend"
```

| | 성공 | 실패(현 상태) |
|---|---|---|
| `activation_data_type` | `FLOAT16` | `Not set` |
| 외부 텐서 | `Total 31~32 ...` × 여러 번 | `Total 4 ...` 1번 |
| 최종 | `loaded with backend=gpu` | `backend=cpu` |

## 아직 확인 안 된 것 / 다음 용의자

1. **`--experimental_use_mixed_precision`이 fp16 활성값을 만들어주는가.** 위 "시도 2". 현재 유일하게 남은 경로
2. **`fp32_fp16` vs `fp16`.** `export_hf`의 태그 부착 코드는 `fp32_fp16`(혼합)으로 하드코딩인데, GPU가 실제로
   도는 공식 파일은 **순수 `fp16`**이었다(위 표, logcat 실측). 둘이 같은 결과인지는 실측 전엔 모른다.
   → 보존된 중간 `.tflite`로 순수 `fp16` 태그를 시험. **재변환 없이 재포장만으로 된다**
3. **prefill 시그니처.** 공식 파일엔 `prefill_128` / `prefill_1024`가 있다. 우리도 `prefill_lengths`
   **기본값이 `[128]`**이라 `prefill_128`은 이미 나온다(2026-08-01 변환 로그 확인) — 차이는 `prefill_1024`
   하나뿐이라 우선순위가 낮아졌다. 델리게이트 커널 4개 vs 1개와 관련이 있을 수는 있다

## 우선순위에 대한 판단

**GPU는 급하지 않다.** 지금 단계의 목표는 LoRA 화법 품질 비교이고, 대조군과 LoRA본이 **둘 다 CPU**라 비교
자체는 이미 공정하다. GPU는 속도 축이라 품질 평가와 직교한다(LoRA는 가중치가 병합돼 구조가 같으므로
속도가 대조군과 같게 나오는 게 정상이다). fp16 재변환이 한 라운드 안에 안 풀리면 CPU로 LoRA 평가를 먼저
끝내는 편이 낫다.
