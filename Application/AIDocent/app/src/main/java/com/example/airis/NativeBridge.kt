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

    // Streaming generation with real-time callback
    external fun generateStreaming(prompt: String, onToken: (String) -> Unit): Boolean

    // Generation performance statistics
    data class GenerationStats(
        val totalTokens: Int,
        val totalTimeSeconds: Double,
        val tokensPerSecond: Double
    )
}
