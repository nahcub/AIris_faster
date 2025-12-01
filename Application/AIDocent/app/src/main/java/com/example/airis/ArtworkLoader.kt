/*
package com.example.airis

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 작품 데이터 클래스
 */
data class Artwork(
    val id: String,
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Artwork

        if (id != other.id) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

/**
 * assets/artworks.json 파일을 읽어서 작품 데이터를 로드
 */
class ArtworkLoader(private val context: Context) {

    private var artworks: List<Artwork>? = null

    /**
     * JSON 파일에서 모든 작품 데이터 로드
     * @param fileName assets 폴더의 JSON 파일명
     * @return 작품 리스트
     */
    fun loadArtworks(fileName: String = "artworks.json"): List<Artwork> {
        // 이미 로드했으면 캐시된 데이터 반환
        if (artworks != null) {
            println("✅ 캐시된 작품 데이터 사용: ${artworks!!.size}개")
            return artworks!!
        }

        try {
            println("📂 JSON 파일 로드 중: $fileName")

            // 1. assets에서 JSON 파일 읽기
            val jsonString = readJsonFromAssets(fileName)

            // 2. JSON 파싱
            val jsonObject = JSONObject(jsonString)
            val artworksArray = jsonObject.getJSONArray("artworks")

            // 3. 작품 리스트 생성
            val loadedArtworks = mutableListOf<Artwork>()

            for (i in 0 until artworksArray.length()) {
                val artworkJson = artworksArray.getJSONObject(i)

                // ID 추출
                val id = artworkJson.getString("id")

                // 임베딩 배열 추출
                val embeddingArray = artworkJson.getJSONArray("embedding")
                val embedding = FloatArray(embeddingArray.length()) { index ->
                    embeddingArray.getDouble(index).toFloat()
                }

                loadedArtworks.add(Artwork(id, embedding))
            }

            artworks = loadedArtworks

            println("✅ 작품 로드 완료: ${loadedArtworks.size}개")
            println("   임베딩 차원: ${loadedArtworks.firstOrNull()?.embedding?.size ?: 0}D")

            return loadedArtworks

        } catch (e: Exception) {
            e.printStackTrace()
            println("❌ 작품 로드 실패: ${e.message}")
            return emptyList()
        }
    }

    /**
     * assets 폴더에서 JSON 파일 읽기
     */
    private fun readJsonFromAssets(fileName: String): String {
        val inputStream = context.assets.open(fileName)
        val bufferedReader = BufferedReader(InputStreamReader(inputStream))
        val stringBuilder = StringBuilder()

        bufferedReader.useLines { lines ->
            lines.forEach { stringBuilder.append(it) }
        }

        return stringBuilder.toString()
    }

    /**
     * 특정 ID의 작품 찾기
     */
    fun getArtworkById(id: String): Artwork? {
        return artworks?.find { it.id == id }
    }

    /**
     * 로드된 작품 개수
     */
    fun getCount(): Int {
        return artworks?.size ?: 0
    }

    /**
     * 캐시 초기화
     */
    fun clearCache() {
        artworks = null
        println("🗑️ 작품 캐시 초기화")
    }
}
 */

package com.example.airis

import android.content.Context
import org.json.JSONArray
/**
 * [수정됨] 작품 데이터 클래스
 * Python의 art_data.json 구조(title, category, vector)와 일치시킴
 */
data class Artwork(
    val title: String,      // 작품명 (기존 id -> title)
    val category: String,   // 카테고리 (새로 추가됨)
    val vector: FloatArray  // 벡터 (기존 embedding -> vector)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Artwork
        if (title != other.title) return false
        if (!vector.contentEquals(other.vector)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

/**
 * [수정됨] 작품 로더 클래스
 */
class ArtworkLoader(private val context: Context) {

    private var artworks: List<Artwork>? = null

    fun loadArtworks(fileName: String = "art_data.json"): List<Artwork> {
        if (artworks != null) return artworks!!

        try {
            val jsonString = readJsonFromAssets(fileName)

            // Python은 리스트 [...] 형태로 저장하므로 JSONArray로 바로 시작
            val jsonArray = JSONArray(jsonString)
            val loadedArtworks = mutableListOf<Artwork>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)

                // Python 코드의 키 값(title, vector)과 일치시킴
                val title = obj.getString("title")
                val category = obj.getString("category")

                val vectorArray = obj.getJSONArray("vector")
                val vector = FloatArray(vectorArray.length()) { index ->
                    vectorArray.getDouble(index).toFloat()
                }

                loadedArtworks.add(Artwork(title, category, vector))
            }

            artworks = loadedArtworks
            println("✅ 작품 DB 로드 완료: ${loadedArtworks.size}개")
            return loadedArtworks

        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun readJsonFromAssets(fileName: String): String {
        return context.assets.open(fileName).bufferedReader().use { it.readText() }
    }
}