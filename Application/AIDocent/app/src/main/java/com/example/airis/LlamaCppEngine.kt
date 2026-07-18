//NativeBridge를 감싸는 어댑터

package com.example.airis

class LlamaCppEngine : InferenceEngine{
    // override = "계약서에 있던 함수를 내가 실제로 구현한다"는 표시(필수).
    // 몸통에서 하는 일은 딱 하나 — 기존 NativeBridge에게 그대로 떠넘기기
    override fun loadModel(path: String): Boolean{
        return NativeBridge.loadModel(path)
    }

    override fun initSession():Boolean{
        return NativeBridge.initSession()
    }
    
    override fun decodeSystemPrompt(): Boolean{
        return NativeBridge.decodeSystemPrompt()
    }

    override fun generateStreaming(prompt: String, onToken:(String)-> Unit): Boolean{
        return NativeBridge.generateStreaming(prompt, onToken)
    }
    override fun close(){
        NativeBridge.closeSession()
    }
}