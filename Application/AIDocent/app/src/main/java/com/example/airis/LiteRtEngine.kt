// LiteRT-LM 런타임을 감싸는 어댑터.
// LlamaCppEngine이 NativeBridge(JNI)를 감쌌듯, 이건 Google의 LiteRT-LM(.litertlm)을 감싼다.
// .task(MediaPipe tasks-genai)와 달리, 시스템 프롬프트는 '대화를 만들 때' ConversationConfig로 넣는다.
package com.example.airis

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// LiteRT-LM은 cacheDir 등에 Context가 필요해 생성자로 받는다 (llama.cpp 엔진엔 없던 것)
class LiteRtEngine(private val context: Context) : InferenceEngine {

    private companion object {
        // 생성 완료 콜백을 기다리는 최대 시간. 상위 BenchmarkRunner의 타임아웃(5분)과 맞춤.
        const val GEN_WAIT_SEC = 300L
    }

    override val name = "litert-lm"   // ← 벤치 results.jsonl의 engine 라벨

    // 실제로 로드에 성공한 백엔드를 담는다("gpu"/"cpu"). loadModel에서 확정.
    // 로드 전엔 "unknown" — 아직 어느 백엔드로 돌지 정해지지 않음.
    private var resolvedBackend: String = "unknown"
    override val backend: String get() = resolvedBackend

    private var engine: Engine? = null            // 모델 런타임 (loadModel에서 생성)
    private var conversation: Conversation? = null // 대화 = 세션 (initSession/decode/reset에서 생성)
    private var systemPrompt: String = ""          // 대화 생성 시 주입할 시스템 프롬프트
    private var artwork: Artwork = Artwork()        // 시스템 프롬프트에 넣을 작품 정보(setArtwork로 주입)

    // 먼저 GPU로 로드 시도, 실패하면 CPU로 폴백.
    // ⚠️ GPU 경로는 매니페스트에 libOpenCL.so/libvndksupport.so 선언 + 기기가 OpenCL 노출 시에만 성공.
    //    (Galaxy S25/Adreno는 성공, Tensor G3·일부 중저가칩은 미노출 → 여기서 CPU로 자동 강등)
    //    resolvedBackend에 '실제로 성공한' 백엔드가 박혀 벤치 라벨이 진실이 된다.
    @OptIn(ExperimentalApi::class)
    override fun loadModel(path: String): Boolean {
        // ★ 엔진레벨 계측(getBenchmarkInfo) 스위치를 켠다.
        //    ExperimentalFlags는 EngineConfig가 아니라 '앱 전역 싱글톤'이고,
        //    Engine.initialize()가 이 값을 딱 한 번 읽어 네이티브에 넘긴다
        //    → 반드시 엔진 생성 '전'에 켜야 한다. 안 켜고 만든 엔진에
        //    getBenchmarkInfo()를 부르면 "INTERNAL: Benchmark is not enabled"로 던진다.
        ExperimentalFlags.enableBenchmark = true

        if (tryLoad(path, Backend.GPU(), "gpu")) return true
        if (tryLoad(path, Backend.CPU(), "cpu")) return true
        return false
    }

    // 주어진 백엔드로 엔진 생성 시도. 성공하면 resolvedBackend를 확정하고 true.
    private fun tryLoad(path: String, backendImpl: Backend, label: String): Boolean {
        return try {
            val config = EngineConfig(
                modelPath = path,
                backend = backendImpl,
                cacheDir = context.cacheDir.path    // 로드 시간 단축용 캐시
            )
            engine = Engine(config).apply { initialize() }  // ⚠️ 최대 10초 걸림 → 백그라운드에서 호출
            resolvedBackend = label
            android.util.Log.i("LiteRtEngine", "loaded with backend=$label")
            true
        } catch (e: Exception) {
            android.util.Log.w("LiteRtEngine", "backend=$label load failed, trying next", e)
            engine = null
            false
        }
    }

    override fun initSession(): Boolean {
        // 이 시점엔 아직 systemPrompt가 비어 있음 → 시스템 프롬프트 없는 대화로 시작.
        // decodeSystemPrompt에서 시스템 프롬프트를 넣어 다시 만든다.
        conversation = openConversation()
        return conversation != null
    }

    // 작품 정보 저장 → 다음 decodeSystemPrompt()의 buildSystemPrompt()가 이 값으로 본문을 만든다.
    override fun setArtwork(artwork: Artwork) {
        this.artwork = artwork
    }

    override fun decodeSystemPrompt(): Boolean {
        // llama.cpp는 KV캐시에 프리필. LiteRT-LM은 systemInstruction으로 대화를 새로 만든다.
        systemPrompt = buildSystemPrompt()   // ← 아래 TODO 참고
        conversation?.close()
        conversation = openConversation()
        return conversation != null
    }

    // 벤치 회차 독립: 대화를 새로 열어 '시스템 프롬프트만 있는 깨끗한 상태'로 되감기.
    // LiteRT-LM엔 명시적 reset이 없고, createConversation마다 상태가 독립적이라 이게 정석이다.
    override fun resetToSystemPrompt(): Boolean {
        conversation?.close()
        conversation = openConversation()
        return conversation != null
    }

    override fun generateStreaming(prompt: String, maxTokens: Int, onToken: (String) -> Unit): Boolean {
        val conv = conversation ?: return false

        // ★ async를 blocking으로: onDone 콜백이 올 때까지 latch로 막는다 (기존 계약 유지)
        val latch = CountDownLatch(1)
        var ok = true
        var emitted = 0        // 콜백 도착 횟수 (≠ 모델 토큰 수 — 아래 주석 참고)
        var capped = false     // 상한 도달로 이미 끊었는지

        conv.sendMessageAsync(prompt, object : MessageCallback {
            override fun onMessage(message: Message) {
                if (capped) return  // 취소 요청 후 늦게 도착한 조각은 버린다(길이 통제 유지)
                onToken(message.textString())  // Message → Contents → Content.Text.text 체인으로 추출
                emitted++
                // LiteRT-LM에는 llama.cpp의 n_max_gen 같은 생성 상한 설정이 없다
                // (ConversationConfig/SamplerConfig 어디에도 없음 — javap로 확인).
                // 그래서 스트림을 직접 끊어서 길이를 통제한다.
                if (emitted >= maxTokens) {
                    capped = true
                    conv.cancelProcess()
                }
            }
            override fun onDone() {
                latch.countDown()
            }
            override fun onError(throwable: Throwable) {
                // ⚠️ cancelProcess()로 끊으면 LiteRT-LM은 onDone이 아니라
                //    onError(CancellationException)로 답한다. 그건 '실패'가 아니라
                //    우리가 의도한 길이 통제의 정상 종료다.
                //    이걸 실패로 세면 runOnce가 레코드를 버려서, 상한에 안 걸린
                //    '짧은 답변만' results.jsonl에 남는 조용한 편향이 생긴다.
                if (capped) {
                    android.util.Log.d("LiteRtEngine", "generation capped at $maxTokens (cancel ack)")
                    latch.countDown()
                    return
                }
                android.util.Log.e("LiteRtEngine", "generateStreaming onError", throwable)
                ok = false
                latch.countDown()
            }
        })

        // cancelProcess() 뒤에도 onDone/onError가 반드시 온다는 보장이 없어 무한 대기를 피한다.
        // (무한 await면 Dispatchers 스레드 하나가 영영 묶인다 — 상위 withTimeoutOrNull은
        //  blocking await를 끊지 못하므로 여기서 직접 막아야 한다)
        if (!latch.await(GEN_WAIT_SEC, TimeUnit.SECONDS)) {
            android.util.Log.w("LiteRtEngine", "onDone/onError not received within ${GEN_WAIT_SEC}s")
            ok = false
        }
        return ok
    }

    // LiteRT-LM은 런타임이 직접 계측을 제공한다(Conversation.getBenchmarkInfo).
    // 덕분에 콜백 조각 수가 아니라 '실제 모델 토큰 수'와 prefill/decode 분리 속도를 그대로 얻는다.
    // ⚠️ 대화가 살아 있을 때만 유효 — resetToSystemPrompt()가 close/open 하면 값이 초기화된다.
    // ⚠️ getBenchmarkInfo()는 @ExperimentalApi — 라이브러리 업그레이드 시 사라지거나 바뀔 수 있다.
    //    깨지면 여기 한 곳만 고치면 되도록 lastStats() 안에 가둬 둔다.
    @OptIn(ExperimentalApi::class)
    override fun lastStats(): EngineStats? {
        // 계측은 '있으면 좋은 것'이지 벤치의 전제조건이 아니다.
        // (플래그가 꺼졌거나 API가 바뀌어) 실패하면 예외를 위로 던지지 말고 null로 떨어뜨려,
        // app레벨 지표(TTFT·체감 tok/s)만으로 측정이 계속되게 한다. 반환 타입의 `?`와도 일관.
        val info = try {
            conversation?.getBenchmarkInfo() ?: return null
        } catch (e: Exception) {
            android.util.Log.w("LiteRtEngine", "getBenchmarkInfo failed (benchmark disabled?)", e)
            return null
        }
        return EngineStats(
            prefillTokens = info.lastPrefillTokenCount,
            decodeTokens = info.lastDecodeTokenCount,
            prefillTokPerSec = info.lastPrefillTokensPerSecond,
            decodeTokPerSec = info.lastDecodeTokensPerSecond,
            ttftSec = info.timeToFirstTokenInSecond
        )
    }

    override fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    // 대화 생성 로직을 한 곳에 모음 (initSession & decodeSystemPrompt & resetToSystemPrompt가 공유)
    private fun openConversation(): Conversation? {
        val e = engine ?: return null
        val config = ConversationConfig(
            systemInstruction = if (systemPrompt.isNotEmpty()) Contents.of(systemPrompt) else null,
            // 벤치 공정성: greedy(argmax)로 고정 — topK=1이면 topP/temperature/seed는 무의미해진다.
            // 이전엔 topK=40/topP=0.8/temp=0.4로 llama.cpp와 '파라미터'는 맞췄지만,
            // SamplerConfig의 seed 기본값이 고정이라 LiteRT만 재현되고 llama.cpp는 매번 달라졌다.
            // greedy로 가면 시드 정책 차이 자체가 사라진다. (native-lib.cpp의 init_greedy와 짝)
            samplerConfig = SamplerConfig(
                topK = 1,
                topP = 1.0,          // LiteRT-LM은 Double
                temperature = 1.0
            )
        )
        return e.createConversation(config)
    }

    // llama.cpp 엔진과 '동등한' 시스템 프롬프트를 만든다.
    // ⚠️ 중요: llama.cpp의 buildSystemPrompt()는 <|im_start|>system … <|im_end|> Qwen 채팅
    //    템플릿 태그를 '직접' 붙인다. 하지만 LiteRT-LM은 systemInstruction에 넣은 텍스트를
    //    런타임이 알아서 모델 채팅 템플릿으로 감싼다. 그래서 여기선 태그를 빼고
    //    prompt_generate.cpp의 formatArtworkInfo() '본문만' 그대로 재현해야 이중 래핑이 안 되고
    //    두 엔진이 논리적으로 같은 system 메시지를 보게 된다.
    // setArtwork로 주입된 작품(없으면 빈 Artwork)을 본문으로 만든다 — llama.cpp와 동일한 소스.
    private fun buildSystemPrompt(): String = formatArtworkInfo(artwork)

    // prompt_generate.cpp의 formatArtworkInfo()를 Kotlin으로 1:1 미러링.
    // 비어 있지 않은 필드만 라벨과 함께 출력하는 순서·형식까지 동일하게 맞춤.
    private fun formatArtworkInfo(a: Artwork): String = buildString {
        append("[ARTWORK INFO]\n\n")
        if (a.title.isNotEmpty())       append("Title: ${a.title}\n")
        if (a.date.isNotEmpty())        append("Object Date: ${a.date}\n")
        if (a.author.isNotEmpty())      append("Artist Display Name: ${a.author}\n")
        if (a.technique.isNotEmpty())   append("Medium: ${a.technique}\n")
        if (a.type.isNotEmpty())        append("Type: ${a.type}\n")
        if (a.description.isNotEmpty()) append("Description: ${a.description}\n")
        append("\n")
    }

    // 스트리밍으로 온 Message에서 텍스트만 뽑는다.
    // Message → Contents(여러 Content) → 그중 Content.Text의 문자열들을 이어 붙임.
    private fun Message.textString(): String =
        contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
}
