package com.example.airis

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

// 벤치마크 한 번의 결과 = 레코드 하나.
// 나중 Phase용 필드(backend/lora/rag/mem)는 지금 null로 둬서 스키마를 미리 고정한다.
data class BenchmarkRecord(
    val engine: String,
    val model: String,
    val prompt: String,
    val ttftSec: Double,
    val decodeTokPerSec: Double,
    val totalSec: Double,
    val tokenCount: Int,
    val backend: String? = null,

    // 측정 조건: 회차마다 같은 상한을 써야 tok_s 비교가 성립한다(레코드에 남겨 사후 검증 가능하게).
    val maxTokens: Int? = null,

    // 엔진레벨 계측(EngineStats). 위의 ttft/tok_s/token_count가 app레벨 '체감값'인 반면 이쪽이 ground truth.
    // 엔진이 계측을 제공하지 않으면 null.
    val promptTokens: Int? = null,
    val decodeTokens: Int? = null,
    val prefillTokPerSec: Double? = null,
    val engineTokPerSec: Double? = null,
    val engineTtftSec: Double? = null,

    val lora: String? = null,
    val ragVariant: String? = null,
    val memPeakMb: Double? = null,
    val nativeHeapMb: Double? = null,
    val tempStartC: Double? = null,
    val tempEndC: Double? = null,
    val thermalStatus: String? = null,
    val runId: String = UUID.randomUUID().toString().take(8),
    val timestamp: String = Instant.now().toString()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("run_id", runId)
        put("timestamp", timestamp)
        put("engine", engine)
        put("model", model)
        put("prompt", prompt)
        put("ttft", ttftSec)
        put("tok_s", decodeTokPerSec)
        put("latency", totalSec)
        put("token_count", tokenCount)
        put("backend", backend ?: JSONObject.NULL)
        put("max_tokens", maxTokens ?: JSONObject.NULL)
        // 엔진레벨(ground truth) — app레벨 ttft/tok_s/token_count와 나란히 두고 교차검증한다
        put("prompt_tokens", promptTokens ?: JSONObject.NULL)
        put("decode_tokens", decodeTokens ?: JSONObject.NULL)
        put("prefill_tok_s", prefillTokPerSec ?: JSONObject.NULL)
        put("engine_tok_s", engineTokPerSec ?: JSONObject.NULL)
        put("engine_ttft", engineTtftSec ?: JSONObject.NULL)
        put("lora", lora ?: JSONObject.NULL)
        put("rag_variant", ragVariant ?: JSONObject.NULL)
        put("mem_peak", memPeakMb ?: JSONObject.NULL)
        put("native_heap_mb", nativeHeapMb ?: JSONObject.NULL)
        put("temp_start_c", tempStartC ?: JSONObject.NULL)
        put("temp_end_c", tempEndC ?: JSONObject.NULL)
        put("thermal_status", thermalStatus ?: JSONObject.NULL)
    }
}

object BenchmarkLogger {
    private const val TAG = "BenchmarkLogger"

    // 벤치 1건을 기록한다 — 정량 지표는 results.jsonl, 생성된 답변 본문은 responses.jsonl.
    //
    // 두 파일에 쓰는 일을 한 함수 안에 둔 이유: 호출부가 '레코드는 남겼는데 응답은 잊는' 실수를
    // 할 수 없게 하려고. 저장 지점이 늘어나도 append 하나만 부르면 두 파일이 항상 맞는다.
    // 덕분에 두 파일의 행 수가 정확히 1:1이 된다(warmup·실패 회차는 애초에 여기 안 온다).
    //
    // 경로: /sdcard/Android/data/com.example.airis/files/benchmarks/
    fun append(context: Context, record: BenchmarkRecord, response: String) {
        val dir = File(context.getExternalFilesDir(null), "benchmarks")
        if (!dir.exists()) dir.mkdirs()

        File(dir, "results.jsonl").appendText(record.toJson().toString() + "\n")
        File(dir, "responses.jsonl").appendText(responseJson(record, response).toString() + "\n")

        Log.d(TAG, "Saved benchmark + response (run_id=${record.runId}) → ${dir.absolutePath}")
    }

    // 응답 본문 1건. run_id가 results.jsonl과 이어 붙이는 조인 키다.
    //
    // 필드 선택의 이유:
    //  - prompt는 results.jsonl에도 있지만 여기 중복해 둔다. 조인 없이 이 파일 하나만으로
    //    사람/LLM-judge에게 바로 던질 수 있어야 하고, 프롬프트는 짧아서 비용이 없다.
    //  - max_tokens는 '이 응답이 잘렸을 수 있는가'를 파일만 보고 알 수 있게 한다(self-describing).
    //    `--ei maxtokens 256` 같은 길이 통제 회차의 응답이 섞여도 필터로 갈라진다.
    //  - ⚠️ model/engine은 일부러 넣지 않는다. 품질 평가는 블라인드로 해야 하는데
    //    파일에 모델명이 박혀 있으면 judge 프롬프트를 만들 때 새어 들어간다.
    //    채점이 끝난 뒤 run_id로 results.jsonl과 조인해 붙이면 된다.
    //
    // ⚠️ 반드시 JSONObject로 직렬화할 것 — 응답엔 개행이 잔뜩 들어가는데 문자열을 그냥 붙이면
    //    'JSONL 한 줄 = 한 레코드' 규약이 깨진다(org.json이 \n으로 escape 해준다).
    private fun responseJson(record: BenchmarkRecord, response: String): JSONObject =
        JSONObject().apply {
            put("run_id", record.runId)
            put("timestamp", record.timestamp)
            put("max_tokens", record.maxTokens ?: JSONObject.NULL)
            put("prompt", record.prompt)
            put("response", response)
        }
}