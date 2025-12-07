package com.example.airis

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 'object' 키워드를 쓰면 앱 전체에서 딱 하나만 존재하는 싱글톤이 됩니다.
object ArtRepository {
    var tfLiteModel: TFLiteModel? = null
    var artworkIndex: Map<String, FloatArray>? = null
    var artworkMetadata: Map<String, Artwork>? = null

    // 로딩이 끝났는지 확인하는 플래그
    var isLoaded = false

    // 초기화 함수 (앱 켤 때 한 번만 호출)
    suspend fun initialize(context: Context) {
        if (isLoaded) return // 이미 로딩됐으면 패스

        withContext(Dispatchers.IO) {
            try {
                val loader = ArtworkLoader(context)

                // 모델 로드
                tfLiteModel = TFLiteModel(context)

                // 데이터 로드
                artworkIndex = loader.loadArtworkIndex("art_index.json")
                artworkMetadata = loader.loadArtworkMetadata("art_metadata.json")

                isLoaded = true
                Log.d("ArtRepository", "전역 데이터 로드 완료!")
            } catch (e: Exception) {
                Log.e("ArtRepository", "데이터 로드 실패", e)
            }
        }
    }

    // 앱 종료 시 메모리 정리
    fun clear() {
        tfLiteModel?.close()
        tfLiteModel = null
        artworkIndex = null
        artworkMetadata = null
        isLoaded = false
    }
}