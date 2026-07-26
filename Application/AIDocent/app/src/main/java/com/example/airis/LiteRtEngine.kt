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
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.CountDownLatch

// LiteRT-LM은 cacheDir 등에 Context가 필요해 생성자로 받는다 (llama.cpp 엔진엔 없던 것)
class LiteRtEngine(private val context: Context) : InferenceEngine {

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
    override fun loadModel(path: String): Boolean {
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

    override fun generateStreaming(prompt: String, onToken: (String) -> Unit): Boolean {
        val conv = conversation ?: return false

        // ★ async를 blocking으로: onDone 콜백이 올 때까지 latch로 막는다 (기존 계약 유지)
        val latch = CountDownLatch(1)
        var ok = true
        conv.sendMessageAsync(prompt, object : MessageCallback {
            override fun onMessage(message: Message) {
                onToken(message.textString())  // Message → Contents → Content.Text.text 체인으로 추출
            }
            override fun onDone() {
                latch.countDown()
            }
            override fun onError(throwable: Throwable) {
                android.util.Log.e("LiteRtEngine", "generateStreaming onError", throwable)
                ok = false
                latch.countDown()
            }
        })
        latch.await()
        return ok
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
            samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.8,          // LiteRT-LM은 Double (llama.cpp 설정과 의미 맞춤)
                temperature = 0.4
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
