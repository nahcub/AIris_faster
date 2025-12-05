/*
package com.example.airis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TFLiteModel(context: Context) {

    private var interpreter: Interpreter? = null

    // CLIP 정규화 상수
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    private val IMAGE_SIZE = 224

    init {
        try {
            val options = Interpreter.Options()
            interpreter = Interpreter(FileUtil.loadMappedFile(context, "art_clip_model.tflite"), options)
        } catch (e: Exception) {
            Log.e("TFLiteModel", "모델 초기화 실패", e)
        }
    }

    // [수정] 반환 타입 변경: Pair<벡터?, 전처리된이미지?>
    fun extractEmbedding(bitmap: Bitmap): Pair<FloatArray?, Bitmap?> {
        if (interpreter == null) return Pair(null, null)

        try {
            // 1. Padding 적용 리사이징 (AI가 보는 실제 이미지)
            val processedBitmap = resizeWithPadding(bitmap, IMAGE_SIZE)

            // 2. 입력 버퍼 준비
            val inputBuffer = ByteBuffer.allocateDirect(1 * IMAGE_SIZE * IMAGE_SIZE * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())

            val intValues = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            processedBitmap.getPixels(intValues, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            // 3. 정규화
            for (pixelValue in intValues) {
                val r = (pixelValue shr 16) and 0xFF
                val g = (pixelValue shr 8) and 0xFF
                val b = pixelValue and 0xFF

                inputBuffer.putFloat(((r / 255.0f) - MEAN[0]) / STD[0])
                inputBuffer.putFloat(((g / 255.0f) - MEAN[1]) / STD[1])
                inputBuffer.putFloat(((b / 255.0f) - MEAN[2]) / STD[2])
            }

            // 4. 추론
            val outputBuffer = Array(1) { FloatArray(128) }
            interpreter?.run(inputBuffer, outputBuffer)

            // 벡터와 '처리된 이미지'를 함께 반환하여 UI에서 확인 가능하게 함
            return Pair(outputBuffer[0], processedBitmap)

        } catch (e: Exception) {
            Log.e("TFLiteModel", "임베딩 추출 중 오류", e)
            return Pair(null, null)
        }
    }

    private fun resizeWithPadding(original: Bitmap, targetSize: Int): Bitmap {
        val width = original.width
        val height = original.height
        val scale = targetSize.toFloat() / Math.max(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        val background = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(background)
        canvas.drawColor(Color.BLACK) // 검은 여백

        val left = (targetSize - newWidth) / 2
        val top = (targetSize - newHeight) / 2
        val destRect = Rect(left, top, left + newWidth, top + newHeight)

        canvas.drawBitmap(original, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))

        return background
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
 */

package com.example.airis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class TFLiteModel(context: Context) {

    private var interpreter: Interpreter? = null

    // CLIP 정규화 상수
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    private val IMAGE_SIZE = 224

    init {
        try {
            val options = Interpreter.Options()
            interpreter = Interpreter(FileUtil.loadMappedFile(context, "art_clip_model.tflite"), options)
            Log.d("TFLiteModel", "✅ 모델 로드 성공")
        } catch (e: Exception) {
            Log.e("TFLiteModel", "❌ 모델 초기화 실패", e)
        }
    }

    fun extractEmbedding(bitmap: Bitmap): Pair<FloatArray?, Bitmap?> {
        if (interpreter == null) {
            Log.e("TFLiteModel", "❌ Interpreter가 null입니다")
            return Pair(null, null)
        }

        try {
            // 1. Padding 적용 리사이징
            val processedBitmap = resizeWithPadding(bitmap, IMAGE_SIZE)

            // 2. 입력 버퍼 준비 (C, H, W 순서)
            val inputBuffer = ByteBuffer.allocateDirect(1 * 3 * IMAGE_SIZE * IMAGE_SIZE * 4)
            inputBuffer.order(ByteOrder.nativeOrder())

            val intValues = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            processedBitmap.getPixels(intValues, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            // 🔥 [핵심 수정] 채널별로 데이터를 재배치 (H, W, C) → (C, H, W)
            // Python의 Permute((3, 1, 2))와 동일한 효과

            // R 채널 전체
            for (pixelValue in intValues) {
                val r = (pixelValue shr 16) and 0xFF
                inputBuffer.putFloat(((r / 255.0f) - MEAN[0]) / STD[0])
            }

            // G 채널 전체
            for (pixelValue in intValues) {
                val g = (pixelValue shr 8) and 0xFF
                inputBuffer.putFloat(((g / 255.0f) - MEAN[1]) / STD[1])
            }

            // B 채널 전체
            for (pixelValue in intValues) {
                val b = pixelValue and 0xFF
                inputBuffer.putFloat(((b / 255.0f) - MEAN[2]) / STD[2])
            }

            Log.d("TFLiteModel", "✅ 입력 데이터 준비 완료 (C, H, W 순서)")

            // 3. 추론
            val outputBuffer = Array(1) { FloatArray(128) }
            interpreter?.run(inputBuffer, outputBuffer)

            // 4. L2 정규화
            val rawVector = outputBuffer[0]
            val normalizedVector = normalizeL2(rawVector)

            Log.d("TFLiteModel", "✅ 임베딩 추출 완료")
            Log.d("TFLiteModel", "정규화 전 norm: ${calculateNorm(rawVector)}, 정규화 후 norm: ${calculateNorm(normalizedVector)}")

            return Pair(normalizedVector, processedBitmap)

        } catch (e: Exception) {
            Log.e("TFLiteModel", "❌ 임베딩 추출 중 오류", e)
            return Pair(null, null)
        }
    }

    /**
     * L2 정규화 (벡터의 크기를 1로 만듦)
     */
    private fun normalizeL2(vector: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (value in vector) {
            sumSquares += value * value
        }
        val norm = sqrt(sumSquares)

        if (norm < 1e-8f) {
            Log.w("TFLiteModel", "⚠️ 벡터의 norm이 거의 0입니다")
            return vector
        }

        return FloatArray(vector.size) { i -> vector[i] / norm }
    }

    /**
     * 벡터의 L2 norm 계산 (디버깅용)
     */
    private fun calculateNorm(vector: FloatArray): Float {
        var sumSquares = 0.0f
        for (value in vector) {
            sumSquares += value * value
        }
        return sqrt(sumSquares)
    }

    private fun resizeWithPadding(original: Bitmap, targetSize: Int): Bitmap {
        val width = original.width
        val height = original.height
        val scale = targetSize.toFloat() / Math.max(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        val background = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(background)
        canvas.drawColor(Color.BLACK) // 검은 여백

        val left = (targetSize - newWidth) / 2
        val top = (targetSize - newHeight) / 2
        val destRect = Rect(left, top, left + newWidth, top + newHeight)

        canvas.drawBitmap(original, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))

        return background
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}