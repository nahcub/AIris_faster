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
    val lora: String? = null,
    val ragVariant: String? = null,
    val memPeakMb: Double? = null,
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
        put("lora", lora ?: JSONObject.NULL)
        put("rag_variant", ragVariant ?: JSONObject.NULL)
        put("mem_peak", memPeakMb ?: JSONObject.NULL)
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
