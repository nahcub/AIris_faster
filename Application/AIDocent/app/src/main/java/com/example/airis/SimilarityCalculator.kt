package com.example.airis

import kotlin.math.sqrt

// 'object'로 변경하여 별도 인스턴스 생성 없이 호출 가능하게 함
object SimilarityCalculator {

    // queryVector: 추출된 특징, indexData: ArtRepository의 artworkIndex (Map)
    fun findMostSimilarArtwork(
        queryVector: FloatArray,
        indexData: Map<String, FloatArray>
    ): Pair<String, Float>? {

        var maxSimilarity = -1.0f
        var bestMatchId: String? = null

        // Query 벡터 Norm 계산 (최적화)
        var normA = 0.0f
        for (v in queryVector) normA += v * v
        val sqrtNormA = sqrt(normA)

        // DB 순회
        for ((id, vector) in indexData) {
            if (queryVector.size != vector.size) continue

            var dotProduct = 0.0f
            var normB = 0.0f

            for (i in queryVector.indices) {
                dotProduct += queryVector[i] * vector[i]
                normB += vector[i] * vector[i]
            }

            val denominator = sqrtNormA * sqrt(normB)
            val similarity = if (denominator > 0) dotProduct / denominator else 0.0f

            if (similarity > maxSimilarity) {
                maxSimilarity = similarity
                bestMatchId = id
            }
        }

        return if (bestMatchId != null) Pair(bestMatchId, maxSimilarity) else null
    }
}