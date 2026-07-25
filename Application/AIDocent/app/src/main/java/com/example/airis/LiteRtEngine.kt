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

    private var engine: Engine? = null            // 모델 런타임 (loadModel에서 생성)
    private var conversation: Conversation? = null // 대화 = 세션 (initSession/decode/reset에서 생성)
    private var systemPrompt: String = ""          // 대화 생성 시 주입할 시스템 프롬프트

    override fun loadModel(path: String): Boolean {
        return try {
            val config = EngineConfig(
                modelPath = path,
                backend = Backend.CPU(),            // arm64 CPU. ⚠️ Backend.GPU()는 이 기기(SM-S931N)에서
                                                    // 생성 시 "Can not find OpenCL library" 예외로 실패함
                                                    // (LiteRT-LM 0.14.0 GPU 경로가 OpenCL 요구, 기기 미노출).
                cacheDir = context.cacheDir.path    // 로드 시간 단축용 캐시
            )
            engine = Engine(config).apply { initialize() }  // ⚠️ 최대 10초 걸림 → 백그라운드에서 호출
            true
        } catch (e: Exception) {
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

    // TODO: 지금은 배선 검증이라 빈 문자열. 나중에 prompt_generate.cpp가 만들던 도슨트
    //       시스템 프롬프트를 Kotlin에서 art_metadata.json 읽어 재현하면 됨.
    private fun buildSystemPrompt(): String = ""

    // 스트리밍으로 온 Message에서 텍스트만 뽑는다.
    // Message → Contents(여러 Content) → 그중 Content.Text의 문자열들을 이어 붙임.
    private fun Message.textString(): String =
        contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
}
