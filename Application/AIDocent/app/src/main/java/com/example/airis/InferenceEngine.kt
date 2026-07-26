//계약서
package com.example.airis

interface InferenceEngine{
    val name : String

    // 실제로 실행 중인 하드웨어 백엔드("gpu"/"cpu"). 벤치 results.jsonl의 backend 필드로 기록됨.
    // '의도'가 아니라 '실제 로드에 성공한' 백엔드를 보고해야 라벨이 진실이 된다(GPU 실패→CPU 폴백 시 "cpu").
    val backend : String

    fun loadModel(path: String): Boolean

    fun initSession(): Boolean

    // 시스템 프롬프트에 넣을 작품 정보를 주입한다. decodeSystemPrompt() 전에 호출해야 반영됨.
    // 두 엔진이 같은 Artwork를 받아 각자 방식으로 system 메시지를 만든다(동등성의 공통 입구).
    // 호출 안 하면 빈 작품(Artwork()) 기준 — 예전과 동일하게 빈 [ARTWORK INFO] 헤더만.
    fun setArtwork(artwork: Artwork)

    fun decodeSystemPrompt(): Boolean

    // 다음 생성을 '시스템 프롬프트만 있는 깨끗한 상태'에서 시작하도록 되감기.
    // 벤치마크 반복 측정의 회차 독립을 보장한다. 캐시된 시스템 프롬프트는 유지됨.
    fun resetToSystemPrompt(): Boolean

    fun generateStreaming(prompt: String, onToken: (String)-> Unit): Boolean

    fun close()
}