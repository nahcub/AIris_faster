package com.example.airis

import android.util.Log
import kotlin.math.sqrt

object SimilarityCalculator {

    private const val TAG = "SimilarityCalculator"

    /**
     * 가장 유사한 작품 1개 반환 (기존 메서드)
     */
    fun findMostSimilarArtwork(
        queryVector: FloatArray,
        indexData: Map<String, FloatArray>
    ): Pair<String, Float>? {
        val topResults = findTopNSimilarArtworks(queryVector, indexData, 5)
        return topResults.firstOrNull()
    }

    /**
     * 상위 N개 유사한 작품 반환 + 로그 출력
     */
    fun findTopNSimilarArtworks(
        queryVector: FloatArray,
        indexData: Map<String, FloatArray>,
        n: Int = 5
    ): List<Pair<String, Float>> {

        val results = mutableListOf<Pair<String, Float>>()

        for ((id, dbVector) in indexData) {
            if (queryVector.size != dbVector.size) continue

            // L2 정규화된 벡터의 코사인 유사도 = 내적
            var dotProduct = 0.0f
            for (i in queryVector.indices) {
                dotProduct += queryVector[i] * dbVector[i]
            }

            results.add(Pair(id, dotProduct))
        }

        // 유사도 내림차순 정렬
        results.sortByDescending { it.second }

        // 상위 N개 추출
        val topN = results.take(n)

        // 로그 출력
        Log.d(TAG, "=" .repeat(50))
        Log.d(TAG, "🔍 Top-$n 검색 결과")
        Log.d(TAG, "=" .repeat(50))
        topN.forEachIndexed { index, (id, score) ->
            val percentage = (score * 100).toInt()
            Log.d(TAG, "  ${index + 1}위: $id (${percentage}%, ${String.format("%.4f", score)})")
        }
        Log.d(TAG, "=" .repeat(50))

        return topN
    }
}