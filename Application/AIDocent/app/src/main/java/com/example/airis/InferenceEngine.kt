//계약서
package com.example.airis

// 벤치 회차의 생성 길이 상한(모델 토큰 기준).
//
// 원래 256이었다. 그건 llama.cpp vs LiteRT '엔진 비교'를 공정하게 만들려던 통제였고,
// 그 축이 끝난 지금은 오히려 답변을 문장 중간에서 자르는 부작용이 크다
// (07-31 실측: 3개 프롬프트 중 2개가 상한에 걸려 잘림 → 품질 평가에 못 쓰는 응답).
//
// 지금 축(LoRA본 vs 대조군)은 아키텍처·양자화가 동일해서 decode 속도가 다를 이유가 없고,
// 실제로 139토큰 회차와 284토큰 회차의 engine_tok_s가 14.62~14.67로 같았다
// (이 길이 범위에선 KV 성장으로 인한 감속이 측정 노이즈보다 작다).
// 그래서 '통제'가 아니라 '안 걸리는 안전망' 수준으로 올려 자연 종료(EOS)까지 받는다.
//
// ⚠️ 0이나 음수를 '무제한'의 뜻으로 쓰지 말 것 — 두 엔진이 정반대로 동작한다.
//    llama.cpp는 <=0이면 1024로 폴백하지만, LiteRT는 `emitted >= maxTokens`라
//    첫 토큰에서 즉시 cancelProcess()가 걸린다. 무제한이 필요하면 큰 수를 넣을 것.
// ⚠️ 상한을 완전히 없애면 폭주 시 GEN_TIMEOUT_MS(5분)에 걸려 record가 null이 되고
//    그 회차가 통째로 사라진다. 큰 하드캡을 남겨두는 이유가 이것이다.
//    (CPU 14.7 tok/s 기준 1024토큰 ≈ 70초라 타임아웃까지 여유가 크다)
//
// 길이를 다시 고정하고 싶으면 소스가 아니라 인텐트로: `--ei maxtokens 256`.
const val DEFAULT_MAX_TOKENS = 1024

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