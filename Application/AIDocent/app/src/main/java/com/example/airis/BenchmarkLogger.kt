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

    // 기기의 앱 전용 외부 저장소에 JSONL로 한 줄씩 append.
    // 경로: /sdcard/Android/data/com.example.airis/files/benchmarks/results.jsonl
    fun append(context: Context, record: BenchmarkRecord) {
        val dir = File(context.getExternalFilesDir(null), "benchmarks")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "results.jsonl")
        file.appendText(record.toJson().toString() + "\n")
        Log.d(TAG, "Saved benchmark → ${file.absolutePath}")
    }
}