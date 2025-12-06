package com.example.airis

object SimilarityCalculator {

    fun findMostSimilarArtwork(
        queryVector: FloatArray,
        indexData: Map<String, FloatArray>
    ): Pair<String, Float>? {

        var maxSimilarity = -1.0f
        var bestMatchId: String? = null

        for ((id, dbVector) in indexData) {
            if (queryVector.size != dbVector.size) continue

            // L2 정규화된 벡터의 코사인 유사도 = 내적
            var dotProduct = 0.0f
            for (i in queryVector.indices) {
                dotProduct += queryVector[i] * dbVector[i]
            }

            if (dotProduct > maxSimilarity) {
                maxSimilarity = dotProduct
                bestMatchId = id
            }
        }

        return if (bestMatchId != null) Pair(bestMatchId, maxSimilarity) else null
    }
}