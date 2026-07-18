//엔진 골라주는 창구
package com.example.airis

enum class EngineType{
    LLAMA_CPP,
    //LITE_RT,
}

object EngineFactory{
    fun create(type: EngineType): InferenceEngine{
        return when(type){
            EngineType.LLAMA_CPP -> LlamaCppEngine()
            //EngineType.LITE_RT -> LiteRtEngine()
        }
    }
}