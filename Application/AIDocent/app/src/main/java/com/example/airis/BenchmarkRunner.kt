package com.example.airis

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// runOnce 1회의 산출물: 기록 1건(성공 시) + 생성 텍스트 + 상태 플래그.
// record == null 이면 timedOut/failed 를 보고 원인을 구분한다.
data class BenchmarkOutcome(
    val record: BenchmarkRecord?,
    val text: String,
    val timedOut: Boolean,
    val failed: Boolean
)

// 측정을 UI에서 분리한 실행 계층.
// - runOnce: '리셋 → 생성 → 지표 계산' 한 사이클. UI 버튼과 배치 러너가 공유하는 단위.
// - runSuite: 고정 프롬프트셋 × (warmup + 반복). 여러 번 실험을 위한 진입점.
object BenchmarkRunner {
    private const val TAG = "BenchmarkRunner"

    // 반복 실험용 고정 프롬프트셋. 실험 대상 질문을 바꾸려면 여기만 고치면 된다.
    val DEFAULT_PROMPTS = listOf(
        "Describe this painting.",
        "What is the historical background of this artwork?",
        "Why did the artist use this technique?"
    )

    private const val GEN_TIMEOUT_MS = 300_000L // 5분

    // 측정 1회 = reset → generateStreaming → TTFT/decode/total 계산.
    // 맨 앞의 resetToSystemPrompt()가 매 호출을 '시스템 프롬프트만 있는 깨끗한 상태'로 되돌려
    // 회차 간 독립(= 통제된 실험 조건)을 보장한다. 시스템 프롬프트 캐시는 유지되므로 재프리필은 없다.
    suspend fun runOnce(
        engine: InferenceEngine,
        prompt: String,
        model: String,
        onToken: (String) -> Unit = {}
    ): BenchmarkOutcome = withContext(Dispatchers.Default) {
        engine.resetToSystemPrompt()

        val startTime = System.currentTimeMillis()
        var tokenCount = 0
        var firstTokenTime = 0L
        val sb = StringBuilder()

        val success = withTimeoutOrNull(GEN_TIMEOUT_MS) {
            engine.generateStreaming(prompt) { token ->
                if (firstTokenTime == 0L) firstTokenTime = System.currentTimeMillis()
                sb.append(token)
                tokenCount++
                onToken(token)
            }
        }

        val endTime = System.currentTimeMillis()
        val totalSec = (endTime - startTime) / 1000.0
        val ttftSec = if (firstTokenTime > 0L) (firstTokenTime - startTime) / 1000.0 else 0.0
        val decodeSec = if (firstTokenTime > 0L) (endTime - firstTokenTime) / 1000.0 else 0.0
        // 첫 토큰은 TTFT에 포함되므로 순수 decode 구간엔 나머지(tokenCount-1)개만 계산
        val decodeTokPerSec = if (decodeSec > 0) (tokenCount - 1).coerceAtLeast(0) / decodeSec else 0.0

        val record = if (success == true) BenchmarkRecord(
            engine = engine.name,
            model = model,
            prompt = prompt,
            ttftSec = ttftSec,
            decodeTokPerSec = decodeTokPerSec,
            totalSec = totalSec,
            tokenCount = tokenCount
        ) else null

        BenchmarkOutcome(
            record = record,
            text = sb.toString(),
            timedOut = success == null,
            failed = success == false
        )
    }

    // 배치 실험 = prompts × (warmups + repeats).
    // warmups 회차는 JIT/캐시/발열 예열용이라 버리고, repeats 회차만 results.jsonl에 기록한다.
    // 반환값 = 실제로 저장된 레코드 수.
    suspend fun runSuite(
        context: Context,
        engine: InferenceEngine,
        model: String,
        prompts: List<String> = DEFAULT_PROMPTS,
        repeats: Int = 5,
        warmups: Int = 1,
        onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> }
    ): Int = withContext(Dispatchers.Default) {
        val total = prompts.size * (warmups + repeats)
        var done = 0
        var saved = 0

        for (prompt in prompts) {
            // 예열: 결과를 버린다
            repeat(warmups) {
                runOnce(engine, prompt, model)
                done++
                onProgress(done, total, "warmup")
            }
            // 본 측정: 기록한다
            repeat(repeats) { i ->
                val outcome = runOnce(engine, prompt, model)
                outcome.record?.let { rec ->
                    withContext(Dispatchers.IO) { BenchmarkLogger.append(context, rec) }
                    saved++
                }
                done++
                onProgress(done, total, "measure ${i + 1}/$repeats")
            }
        }

        Log.d(TAG, "Suite done: saved $saved records to results.jsonl")
        saved
    }
}
