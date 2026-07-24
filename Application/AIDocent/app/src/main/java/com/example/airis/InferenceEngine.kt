//계약서
package com.example.airis

interface InferenceEngine{
    val name : String
    
    fun loadModel(path: String): Boolean

    fun initSession(): Boolean

    fun decodeSystemPrompt(): Boolean

    // 다음 생성을 '시스템 프롬프트만 있는 깨끗한 상태'에서 시작하도록 되감기.
    // 벤치마크 반복 측정의 회차 독립을 보장한다. 캐시된 시스템 프롬프트는 유지됨.
    fun resetToSystemPrompt(): Boolean

    fun generateStreaming(prompt: String, onToken: (String)-> Unit): Boolean

    fun close()
}