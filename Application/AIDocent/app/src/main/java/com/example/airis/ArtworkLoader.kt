package com.example.airis

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * [수정됨] JSON 구조에 맞춘 작품 데이터 클래스
 */
data class Artwork(
    val id: String,          // 파일명 (키값)
    val title: String,       // 작품명
    val author: String,      // 작가
    val type: String,        // 형식 (religious 등)
    val technique: String,   // 기법 (Oil on limewood 등)
    val school: String,      // 화파/국가 (German 등)
    val date: String,        // 제작 연도
    val description: String  // 설명 (로드하지만 팝업에는 표시 안 함)
)

class ArtworkLoader(private val context: Context) {

    // 1. 벡터 인덱스 로드 (검색용) -> Map<ID, Vector>
    // (이전과 동일하므로 생략하거나 기존 코드 유지)
    fun loadArtworkIndex(fileName: String): Map<String, FloatArray> {
        val indexMap = mutableMapOf<String, FloatArray>()
        try {
            val jsonString = readJsonFromAssets(fileName)
            val jsonObject = JSONObject(jsonString)
            val keys = jsonObject.keys()

            while (keys.hasNext()) {
                val id = keys.next()
                val vectorArray = jsonObject.getJSONArray(id)
                val vector = FloatArray(vectorArray.length()) { i ->
                    vectorArray.getDouble(i).toFloat()
                }
                indexMap[id] = vector
            }
            Log.d("ArtworkLoader", "✅ 인덱스 로드 완료: ${indexMap.size}개")
        } catch (e: Exception) {
            Log.e("ArtworkLoader", "❌ 인덱스 로드 실패", e)
        }
        return indexMap
    }

    // 2. [수정됨] 메타데이터 로드 (정보용)
    fun loadArtworkMetadata(fileName: String): Map<String, Artwork> {
        val metadataMap = mutableMapOf<String, Artwork>()
        try {
            val jsonString = readJsonFromAssets(fileName)
            val jsonObject = JSONObject(jsonString)
            val keys = jsonObject.keys()

            while (keys.hasNext()) {
                val id = keys.next()
                val infoObj = jsonObject.getJSONObject(id)

                val artwork = Artwork(
                    id = id,
                    title = infoObj.optString("title", "Unknown Title"),
                    author = infoObj.optString("author", "Unknown Author"),
                    type = infoObj.optString("type", "-"),
                    technique = infoObj.optString("technique", "-"),
                    school = infoObj.optString("school", "-"),
                    date = infoObj.optString("date", "-"),
                    description = infoObj.optString("description", "")
                )
                metadataMap[id] = artwork
            }
            Log.d("ArtworkLoader", "✅ 메타데이터 로드 완료: ${metadataMap.size}개")
        } catch (e: Exception) {
            Log.e("ArtworkLoader", "❌ 메타데이터 로드 실패", e)
        }
        return metadataMap
    }

    private fun readJsonFromAssets(fileName: String): String {
        return context.assets.open(fileName).bufferedReader().use { it.readText() }
    }
}