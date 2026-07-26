// 작품 하나의 메타데이터 묶음.
// 두 엔진(llama.cpp / LiteRT)이 시스템 프롬프트를 만들 때 공통으로 받는 타입.
// 필드 순서는 native setArtworkInfo(JNI) 인자 순서와 맞춰 둔다(어댑터에서 그대로 넘기기 편하게).
package com.example.airis

data class Artwork(
    val title: String = "",
    val author: String = "",
    val type: String = "",
    val technique: String = "",
    val school: String = "",
    val date: String = "",
    val description: String = "",
)
