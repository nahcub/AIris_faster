//계약서
package com.example.airis

interface InferenceEngine{
    fun loadModel(path: String): Boolean

    fun initSession(): Boolean

    fun decodeSystemPrompt(): Boolean

    fun generateStreaming(prompt: String, onToken: (String)-> Unit): Boolean

    fun close()
}