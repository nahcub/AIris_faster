package com.example.airis

import android.util.Log

object NativeBridge {
    init {
        try {
            System.loadLibrary("airis")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NativeBridge", "Failed to load library", e)
            throw e
        }
    }

    external fun loadModel(path: String): Boolean

    // Session management (performance optimized)
    external fun initSession(): Boolean
    external fun closeSession()

    // 벤치마크용: KV 캐시를 시스템 프롬프트까지 되감아 회차 간 독립을 보장
    external fun resetToSystemPrompt(): Boolean

    // Prompt caching
    external fun decodeSystemPrompt(): Boolean

    // Set artwork information
    external fun setArtworkInfo(
        title: String,
        author: String,
        type: String,
        technique: String,
        school: String,
        date: String,
        description: String
    )

    // Streaming generation with real-time callback
    external fun generateStreaming(prompt: String, onToken: (String) -> Unit): Boolean

    // Generation performance statistics
    data class GenerationStats(
        val totalTokens: Int,
        val totalTimeSeconds: Double,
        val tokensPerSecond: Double
    )
}
