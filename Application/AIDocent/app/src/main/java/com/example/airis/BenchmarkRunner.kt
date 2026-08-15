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

// 캐시 프로브 1회차의 관측값. 저장이 아니라 판정을 위한 것이라 BenchmarkRecord와 별개다.
data class ProbeRound(
    val index: Int,
    val prompt: String,
    val promptTokens: Int?,      // 이 회차에 엔진이 실제로 프리필한 토큰 수 ← 판정의 핵심
    val prefillTokPerSec: Double?,
    val ttftSec: Double,         // app레벨 체감 TTFT
    val engineTtftSec: Double?,
    val decodeTokens: Int?
)

// 측정을 UI에서 분리한 실행 계층.
// - runOnce: '리셋 → 생성 → 지표 계산' 한 사이클. UI 버튼과 배치 러너가 공유하는 단위.
// - runSuite: 고정 프롬프트셋 × (warmup + 반복). 여러 번 실험을 위한 진입점.
// - runCacheProbe: 리셋 '없이' 연속 질문. 시스템 프롬프트가 재프리필되는지 보는 진단.
object BenchmarkRunner {
    private const val TAG = "BenchmarkRunner"

    // 반복 실험용 고정 프롬프트셋. 실험 대상 질문을 바꾸려면 여기만 고치면 된다.
    //
    // 2026-08-02에 영어 → 한국어로 바꿨다. LoRA를 한국어 도슨트 문답으로 학습시켰는데
    // 영어로 물으면 '학습한 것'과 '재는 것'이 어긋난다 — 실측으로 확인된 어긋남이다:
    // 영어 질문에도 길이(4배 감소)와 형식(마크다운 → 산문)은 전이됐지만,
    // 학습 데이터의 핵심인 도슨트 화법(관찰 유도: "먼저 발밑의 카펫을 보시겠어요?")은
    // 한 번도 나타나지 않았다. 화법은 언어에 붙어 있어서 교차언어 전이가 안 된다.
    // 게다가 실사용(한국 미술관 도슨트 앱)도 한국어다.
    //
    // ⚠️ 학습셋(datasets/docent_seeds.jsonl)의 질문 문구를 그대로 쓰면 화법 평가가 아니라
    //    암기 검증이 된다. 아래 3개는 완전일치 0으로 확인한 held-out이다
    //    (학습셋엔 "이 그림에 대해 설명해줘", "이 그림에서 뭘 보면 좋을까?" 등이 있다).
    // ⚠️ 반말인 것도 의도적 — 학습셋 질문이 전부 반말이라 입력 분포를 맞춘 것이다
    //    (학습 데이터의 형태: 반말 질문 → 존댓말 도슨트 답변).
    // 시스템 프롬프트(art_metadata.json)는 영어 그대로 둔다. 학습 데이터의 meta도 영어였으니
    // '영어 근거 → 한국어 답변'이라는 조건까지 학습 때와 같아진다.
    val DEFAULT_PROMPTS = listOf(
        "이 작품 어떤 그림이야?",
        "이 그림 어떤 시대에 그려진 거야?",
        "화가가 왜 이렇게 그린 거야?"
    )

    // 배치 기본값. 장치 제작 단계라 축소해 둔 값이고, 정식 측정 땐 repeats를 5+로 올린다.
    // 상수로 뽑아둔 이유: 자동화(AutoRunRequest)가 "지정 안 하면 기본값"을 표현하려면 이름이 필요하고,
    // Suite 버튼 라벨도 이 값을 따라가야 실제 회차 수와 표시가 어긋나지 않는다.
    const val DEFAULT_REPEATS = 2
    const val DEFAULT_WARMUPS = 1

    private const val GEN_TIMEOUT_MS = 300_000L // 5분

    // 측정 1회 = reset → generateStreaming → TTFT/decode/total 계산.
    // 맨 앞의 resetToSystemPrompt()가 매 호출을 '시스템 프롬프트만 있는 깨끗한 상태'로 되돌려
    // 회차 간 독립(= 통제된 실험 조건)을 보장한다. 시스템 프롬프트 캐시는 유지되므로 재프리필은 없다.
    suspend fun runOnce(
        context: Context,
        engine: InferenceEngine,
        prompt: String,
        model: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        onToken: (String) -> Unit = {}
    ): BenchmarkOutcome = withContext(Dispatchers.Default) {
        engine.resetToSystemPrompt()

        // 생성 시작 직전 온도 스냅샷(발열 delta의 기준점)
        val tempStart = HardwareStats.batteryTempC(context)

        val startTime = System.currentTimeMillis()
        var tokenCount = 0
        var firstTokenTime = 0L
        val sb = StringBuilder()

        val success = withTimeoutOrNull(GEN_TIMEOUT_MS) {
            engine.generateStreaming(prompt, maxTokens) { token ->
                if (firstTokenTime == 0L) firstTokenTime = System.currentTimeMillis()
                sb.append(token)
                tokenCount++
                onToken(token)
            }
        }

        val endTime = System.currentTimeMillis()

        // 엔진 자체 계측을 즉시 회수한다.
        // ⚠️ 다음 회차의 resetToSystemPrompt()가 세션/대화를 갈아엎으면 값이 사라지므로 여기서 읽어야 한다.
        val stats = engine.lastStats()

        val totalSec = (endTime - startTime) / 1000.0
        val ttftSec = if (firstTokenTime > 0L) (firstTokenTime - startTime) / 1000.0 else 0.0
        val decodeSec = if (firstTokenTime > 0L) (endTime - firstTokenTime) / 1000.0 else 0.0
        // 첫 토큰은 TTFT에 포함되므로 순수 decode 구간엔 나머지(tokenCount-1)개만 계산
        val decodeTokPerSec = if (decodeSec > 0) (tokenCount - 1).coerceAtLeast(0) / decodeSec else 0.0

        // 생성 종료 후 자원 스냅샷: RAM(peak/native heap) + 발열(온도/스로틀링 단계)
        val peakRss = HardwareStats.peakRssMb()
        val nativeHeap = HardwareStats.nativeHeapMb()
        val tempEnd = HardwareStats.batteryTempC(context)
        val thermal = HardwareStats.thermalStatus(context)

        val record = if (success == true) BenchmarkRecord(
            engine = engine.name,
            model = model,
            prompt = prompt,
            ttftSec = ttftSec,
            decodeTokPerSec = decodeTokPerSec,
            totalSec = totalSec,
            tokenCount = tokenCount,
            backend = engine.backend,   // 실제 실행된 백엔드("gpu"/"cpu")를 기록
            maxTokens = maxTokens,      // 측정 조건(생성 상한)을 레코드에 남긴다

            // 엔진레벨 계측 — 없으면 전부 null
            promptTokens = stats?.prefillTokens,
            decodeTokens = stats?.decodeTokens,
            prefillTokPerSec = stats?.prefillTokPerSec,
            engineTokPerSec = stats?.decodeTokPerSec,
            engineTtftSec = stats?.ttftSec,

            memPeakMb = peakRss,
            nativeHeapMb = nativeHeap,
            tempStartC = tempStart,
            tempEndC = tempEnd,
            thermalStatus = thermal
        ) else null

        BenchmarkOutcome(
            record = record,
            text = sb.toString(),
            timedOut = success == null,
            failed = success == false
        )
    }

    // KV 캐시 재사용 진단.
    //
    // 왜 필요한가: runOnce는 회차 독립을 위해 매번 resetToSystemPrompt()로 대화를 새로 여는데,
    // LiteRtEngine의 reset은 Conversation을 close/open 하므로 시스템 프롬프트가 매번 다시
    // 프리필된다. 실제로 results.jsonl 96건에서 prompt_tokens가 질문 길이와 무관하게 늘 423~428
    // (= 시스템 프롬프트 ~420 + 질문 몇 토큰)이었다. 즉 **벤치 경로는 항상 전액을 낸다.**
    //
    // 그런데 실사용은 리셋을 하지 않는다. 그때도 매 질문이 전액을 내는지, 아니면 두 번째 질문부터는
    // 새 토큰만 프리필하는지에 따라 설계가 갈린다 — 작품 정보를 시스템 프롬프트에 넉넉히 깔아둘 수
    // 있느냐(조인 방식의 전제)가 여기서 결정된다.
    //
    // 그래서 이 함수만 **리셋 없이** 연속으로 질문한다. 판정은 promptTokens의 변화로 한다:
    //   1회차 ~420 → 2회차 수십      = 캐시 재사용 (새 토큰만 프리필)
    //   1회차 ~420 → 2회차 ~420 이상 = 재사용 없음 (매번 전부, 또는 대화 누적까지 다시)
    //
    // ⚠️ 이 회차들은 서로 독립이 아니다(앞 회차의 문답이 컨텍스트에 남는다). 따라서 results.jsonl에
    //    저장하지 않는다 — 저장하면 그 파일의 '회차 독립' 전제가 조용히 깨진다. 진단은 logcat과
    //    반환값으로만 나간다.
    suspend fun runCacheProbe(
        engine: InferenceEngine,
        prompts: List<String> = DEFAULT_PROMPTS,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ProbeRound> = withContext(Dispatchers.Default) {
        // 시작 상태만 한 번 정리한다. 이후로는 절대 리셋하지 않는 게 이 프로브의 전부다.
        engine.resetToSystemPrompt()

        val rounds = mutableListOf<ProbeRound>()
        prompts.forEachIndexed { i, prompt ->
            val startTime = System.currentTimeMillis()
            var firstTokenTime = 0L

            val success = withTimeoutOrNull(GEN_TIMEOUT_MS) {
                engine.generateStreaming(prompt, maxTokens) {
                    if (firstTokenTime == 0L) firstTokenTime = System.currentTimeMillis()
                }
            }
            // runOnce와 같은 이유로 생성 직후 즉시 회수한다.
            val stats = engine.lastStats()

            if (success != true) {
                Log.w(TAG, "cache probe round ${i + 1} failed (timeout=${success == null})")
                return@forEachIndexed
            }

            val round = ProbeRound(
                index = i + 1,
                prompt = prompt,
                promptTokens = stats?.prefillTokens,
                prefillTokPerSec = stats?.prefillTokPerSec,
                ttftSec = if (firstTokenTime > 0L) (firstTokenTime - startTime) / 1000.0 else 0.0,
                engineTtftSec = stats?.ttftSec,
                decodeTokens = stats?.decodeTokens
            )
            rounds += round
            // 판정 근거를 회차마다 그대로 남긴다 — verdict는 편의고, 증거는 이 숫자다.
            Log.i(
                BenchSignal.TAG,
                "CACHE_PROBE round=${round.index} prompt_tokens=${round.promptTokens} " +
                    "prefill_tok_s=${round.prefillTokPerSec} ttft=${round.ttftSec} " +
                    "engine_ttft=${round.engineTtftSec} decode_tokens=${round.decodeTokens}"
            )
            onProgress(i + 1, prompts.size)
        }
        rounds
    }

    // 프로브 결과 한 줄 판정. 2회차 이후의 프리필 토큰이 1회차보다 확연히 적으면 재사용된 것.
    // 계측을 못 받았으면(엔진이 null) 판정하지 않는다 — 모르는 걸 안다고 하지 않는 게 낫다.
    fun cacheVerdict(rounds: List<ProbeRound>): String {
        if (rounds.size < 2) return "UNKNOWN reason=need_2_rounds"
        val first = rounds[0].promptTokens ?: return "UNKNOWN reason=no_engine_stats"
        val rest = rounds.drop(1).mapNotNull { it.promptTokens }
        if (rest.isEmpty()) return "UNKNOWN reason=no_engine_stats"
        val maxRest = rest.max()
        return if (maxRest < first / 2) "REUSED first=$first rest=$rest"
        else "NOT_REUSED first=$first rest=$rest"
    }

    // 배치 실험 = prompts × (warmups + repeats).
    // warmups 회차는 JIT/캐시/발열 예열용이라 버리고, repeats 회차만 results.jsonl에 기록한다.
    // 반환값 = 실제로 저장된 레코드 수.
    suspend fun runSuite(
        context: Context,
        engine: InferenceEngine,
        model: String,
        prompts: List<String> = DEFAULT_PROMPTS,
        repeats: Int = DEFAULT_REPEATS,
        warmups: Int = DEFAULT_WARMUPS,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> }
    ): Int = withContext(Dispatchers.Default) {
        val total = prompts.size * (warmups + repeats)
        var done = 0
        var saved = 0

        for (prompt in prompts) {
            // 예열: 결과를 버린다
            repeat(warmups) {
                runOnce(context, engine, prompt, model, maxTokens)
                done++
                onProgress(done, total, "warmup")
            }
            // 본 측정: 기록한다
            repeat(repeats) { i ->
                val outcome = runOnce(context, engine, prompt, model, maxTokens)
                outcome.record?.let { rec ->
                    // 정량 지표(rec)와 답변 본문(outcome.text)을 같은 run_id로 함께 남긴다.
                    // 예전엔 outcome.text를 그냥 버렸다 — 품질 평가의 재료가 사라지던 자리.
                    withContext(Dispatchers.IO) {
                        BenchmarkLogger.append(context, rec, outcome.text)
                    }
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
