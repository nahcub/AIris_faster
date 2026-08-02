//계약서
package com.example.airis

// 벤치 회차의 생성 길이 상한(모델 토큰 기준).
// 길이를 고정하지 않으면 KV 캐시가 길어질수록 decode tok/s가 떨어져서
// '프롬프트가 달라 답이 길어진 것'과 '엔진이 느린 것'이 구분되지 않는다.
const val DEFAULT_MAX_TOKENS = 256

// 엔진이 스스로 잰 계측값. app레벨(콜백 관측) 지표와 별개의 ground truth다.
// app레벨 tokenCount는 UI로 flush된 조각 수라 '체감값'인 반면, 여기 값은 모델 토큰 기준.
// ⚠️ prefillTokens의 '의미'가 엔진마다 다를 수 있다:
//    llama.cpp  — 시스템 프롬프트는 KV 캐시에 남으므로 '이번 턴 user 프롬프트'만 센다.
//    LiteRT-LM  — resetToSystemPrompt()가 대화를 새로 열기 때문에 시스템 프롬프트가 포함될 수 있다.
//    두 엔진의 prefill을 직접 비교하기 전에 실측값으로 어느 쪽인지 먼저 확인할 것.
data class EngineStats(
    val prefillTokens: Int,
    val decodeTokens: Int,
    val prefillTokPerSec: Double,
    val decodeTokPerSec: Double,
    val ttftSec: Double
)

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

    // maxTokens: 생성 상한. 벤치는 모든 회차가 같은 값을 써야 tok/s 비교가 성립한다.
    // ⚠️ 두 엔진의 '상한 정확도'가 다르다:
    //    llama.cpp — 네이티브 루프가 모델 토큰을 정확히 센다.
    //    LiteRT-LM — API에 생성 상한이 없어 콜백 횟수로 근사해 cancelProcess()로 끊는다.
    //                (콜백 1회 = 토큰 1개가 아닐 수 있음 → 실제 토큰 수는 엔진 계측으로 확인 필요)
    fun generateStreaming(prompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS, onToken: (String)-> Unit): Boolean

    // 직전 generateStreaming 1회의 엔진레벨 계측. 계측을 제공하지 않는 엔진은 null.
    // ⚠️ generateStreaming 직후, 다음 resetToSystemPrompt() 전에 읽어야 한다
    //    (세션/대화가 살아 있는 동안만 유효 — reset이 갈아엎으면 값이 사라진다).
    fun lastStats(): EngineStats? = null

    fun close()
}