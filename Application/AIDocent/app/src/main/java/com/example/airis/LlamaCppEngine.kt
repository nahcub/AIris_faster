//NativeBridge를 감싸는 어댑터

package com.example.airis

class LlamaCppEngine : InferenceEngine{
    // override = "계약서에 있던 함수를 내가 실제로 구현한다"는 표시(필수).
    // 몸통에서 하는 일은 딱 하나 — 기존 NativeBridge에게 그대로 떠넘기기
    override val name = "llama.cpp"

    // 이 빌드는 arm64 CPU 전용(CMakeLists에 GPU 백엔드 미링크) → 항상 "cpu"
    override val backend = "cpu"

    override fun loadModel(path: String): Boolean{
        return NativeBridge.loadModel(path)
    }

    override fun initSession():Boolean{
        return NativeBridge.initSession()
    }

    // C++ 전역에 작품 정보를 세팅 → 다음 decodeSystemPrompt()의 buildSystemPrompt()가 이 값을 읽어 프리필.
    override fun setArtwork(artwork: Artwork){
        NativeBridge.setArtworkInfo(
            artwork.title,
            artwork.author,
            artwork.type,
            artwork.technique,
            artwork.school,
            artwork.date,
            artwork.description,
        )
    }

    override fun decodeSystemPrompt(): Boolean{
        return NativeBridge.decodeSystemPrompt()
    }

    override fun resetToSystemPrompt(): Boolean{
        return NativeBridge.resetToSystemPrompt()
    }

    override fun generateStreaming(prompt: String, onToken:(String)-> Unit): Boolean{
        return NativeBridge.generateStreaming(prompt, onToken)
    }
    override fun close(){
        NativeBridge.closeSession()
    }
}