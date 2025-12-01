/*
package com.example.airis

import kotlin.math.sqrt

/**
 * 유사도 계산 결과
 */
data class SimilarityResult(
    val artwork: Artwork,
    val similarity: Float
)

/**
 * 코사인 유사도 계산 및 작품 검색
 *
 * 코사인 유사도를 사용하여 임베딩 벡터 간 유사도 계산
 */
object SimilarityCalculator {

    /**
     * 두 벡터 간의 코사인 유사도 계산
     *
     * 공식: cosine_similarity = (A · B) / (||A|| * ||B||)
     *
     * @param vector1 첫 번째 벡터
     * @param vector2 두 번째 벡터
     * @return 코사인 유사도 (-1.0 ~ 1.0, 1에 가까울수록 유사)
     */
    fun cosineSimilarity(vector1: FloatArray, vector2: FloatArray): Float {
        if (vector1.size != vector2.size) {
            throw IllegalArgumentException("벡터 크기가 다릅니다: ${vector1.size} vs ${vector2.size}")
        }

        // 1. 내적 (dot product) 계산
        var dotProduct = 0.0f
        for (i in vector1.indices) {
            dotProduct += vector1[i] * vector2[i]
        }

        // 2. 각 벡터의 크기(norm) 계산
        var norm1 = 0.0f
        var norm2 = 0.0f
        for (i in vector1.indices) {
            norm1 += vector1[i] * vector1[i]
            norm2 += vector2[i] * vector2[i]
        }
        norm1 = sqrt(norm1)
        norm2 = sqrt(norm2)

        // 3. 코사인 유사도 계산
        if (norm1 == 0.0f || norm2 == 0.0f) {
            return 0.0f
        }

        return dotProduct / (norm1 * norm2)
    }

    /**
     * 모든 작품과 비교하여 가장 유사한 작품 찾기
     *
     * @param queryEmbedding 검색할 이미지의 임베딩
     * @param artworks 비교할 작품 리스트
     * @return 가장 유사한 작품과 유사도, 작품이 없으면 null
     */
    fun findMostSimilar(
        queryEmbedding: FloatArray,
        artworks: List<Artwork>
    ): SimilarityResult? {
        if (artworks.isEmpty()) {
            println("❌ 비교할 작품이 없습니다.")
            return null
        }

        println("🔍 작품 검색 시작 (총 ${artworks.size}개 작품)")

        var bestMatch: Artwork? = null
        var bestSimilarity = -1.0f

        // 모든 작품과 유사도 계산
        for (artwork in artworks) {
            val similarity = cosineSimilarity(queryEmbedding, artwork.embedding)

            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestMatch = artwork
            }
        }

        return if (bestMatch != null) {
            println("✅ 가장 유사한 작품 발견:")
            println("   ID: ${bestMatch.id}")
            println("   유사도: ${"%.6f".format(bestSimilarity)}")
            SimilarityResult(bestMatch, bestSimilarity)
        } else {
            println("❌ 유사한 작품을 찾지 못했습니다.")
            null
        }
    }

    /**
     * 모든 작품과 비교하여 상위 N개 찾기
     *
     * @param queryEmbedding 검색할 이미지의 임베딩
     * @param artworks 비교할 작품 리스트
     * @param topK 상위 몇 개를 반환할지 (기본 5개)
     * @return 유사도 높은 순으로 정렬된 결과 리스트
     */
    fun findTopK(
        queryEmbedding: FloatArray,
        artworks: List<Artwork>,
        topK: Int = 5
    ): List<SimilarityResult> {
        if (artworks.isEmpty()) {
            println("❌ 비교할 작품이 없습니다.")
            return emptyList()
        }

        println("🔍 Top-$topK 작품 검색 시작 (총 ${artworks.size}개 작품)")

        // 모든 작품과 유사도 계산
        val results = artworks.map { artwork ->
            val similarity = cosineSimilarity(queryEmbedding, artwork.embedding)
            SimilarityResult(artwork, similarity)
        }

        // 유사도 높은 순으로 정렬하여 상위 K개 반환
        val topResults = results.sortedByDescending { it.similarity }.take(topK)

        println("✅ Top-$topK 작품 검색 완료:")
        topResults.forEachIndexed { index, result ->
            println("   #${index + 1} ID: ${result.artwork.id}, 유사도: ${"%.6f".format(result.similarity)}")
        }

        return topResults
    }

    /**
     * 임계값 이상의 유사도를 가진 작품 필터링
     *
     * @param queryEmbedding 검색할 이미지의 임베딩
     * @param artworks 비교할 작품 리스트
     * @param threshold 유사도 임계값 (기본 0.7)
     * @return 임계값 이상의 작품 리스트 (유사도 높은 순)
     */
    fun findAboveThreshold(
        queryEmbedding: FloatArray,
        artworks: List<Artwork>,
        threshold: Float = 0.7f
    ): List<SimilarityResult> {
        if (artworks.isEmpty()) {
            println("❌ 비교할 작품이 없습니다.")
            return emptyList()
        }

        println("🔍 임계값($threshold) 이상 작품 검색 시작")

        // 모든 작품과 유사도 계산
        val results = artworks.mapNotNull { artwork ->
            val similarity = cosineSimilarity(queryEmbedding, artwork.embedding)
            if (similarity >= threshold) {
                SimilarityResult(artwork, similarity)
            } else {
                null
            }
        }

        // 유사도 높은 순으로 정렬
        val sortedResults = results.sortedByDescending { it.similarity }

        println("✅ 임계값 이상 작품: ${sortedResults.size}개")
        sortedResults.take(5).forEachIndexed { index, result ->
            println("   #${index + 1} ID: ${result.artwork.id}, 유사도: ${"%.6f".format(result.similarity)}")
        }
        if (sortedResults.size > 5) {
            println("   ... 외 ${sortedResults.size - 5}개")
        }

        return sortedResults
    }
}
 */

package com.example.airis

import kotlin.math.sqrt

data class SimilarityResult(
    val artwork: Artwork,
    val similarity: Float
)

object SimilarityCalculator {

    fun cosineSimilarity(vector1: FloatArray, vector2: FloatArray): Float {
        var dotProduct = 0.0f
        var norm1 = 0.0f
        var norm2 = 0.0f

        for (i in vector1.indices) {
            dotProduct += vector1[i] * vector2[i]
            norm1 += vector1[i] * vector1[i]
            norm2 += vector2[i] * vector2[i]
        }

        val length = (sqrt(norm1) * sqrt(norm2))
        return if (length == 0.0f) 0.0f else dotProduct / length
    }

    fun findMostSimilar(queryEmbedding: FloatArray, artworks: List<Artwork>): SimilarityResult? {
        if (artworks.isEmpty()) return null

        var bestMatch: Artwork? = null
        var bestSimilarity = -1.0f

        for (artwork in artworks) {
            // [수정] artwork.embedding -> artwork.vector
            val similarity = cosineSimilarity(queryEmbedding, artwork.vector)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestMatch = artwork
            }
        }

        // [추가] 너무 낮은 유사도는 오답 처리 (0.6 이상만 인정)
        return if (bestMatch != null && bestSimilarity > 0.4f) {
            SimilarityResult(bestMatch, bestSimilarity)
        } else {
            null
        }
    }
}