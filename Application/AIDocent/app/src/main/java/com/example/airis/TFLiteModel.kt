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
    private val TAG = "TFLiteModel"

    // CLIP 정규화 상수 (Python과 동일)
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    private val IMAGE_SIZE = 224
    private val EMBEDDING_DIM = 128

    init {
        try {
            val options = Interpreter.Options()
            interpreter = Interpreter(FileUtil.loadMappedFile(context, "art_clip_model.tflite"), options)

            // 모델 입출력 형태 확인
            val inputTensor = interpreter?.getInputTensor(0)
            val outputTensor = interpreter?.getOutputTensor(0)
            Log.d(TAG, "✅ 모델 로드 성공")
            Log.d(TAG, "📥 입력 shape: ${inputTensor?.shape()?.contentToString()}")
            Log.d(TAG, "📤 출력 shape: ${outputTensor?.shape()?.contentToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 모델 초기화 실패", e)
        }
    }

    fun extractEmbedding(bitmap: Bitmap): Pair<FloatArray?, Bitmap?> {
        if (interpreter == null) {
            Log.e(TAG, "❌ Interpreter가 null입니다")
            return Pair(null, null)
        }

        try {
            // 1. 리사이징 (검은 패딩 적용) - Python과 동일
            val processedBitmap = resizeWithPadding(bitmap, IMAGE_SIZE)
            Log.d(TAG, "📐 리사이징 완료: ${processedBitmap.width}x${processedBitmap.height}")

            // 2. 픽셀 데이터 추출
            val intValues = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            processedBitmap.getPixels(intValues, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            // 3. 입력 버퍼 준비
            // 🔥 [핵심] TFLite 모델 입력: (1, 224, 224, 3) = (H, W, C) 순서
            // 모델 내부의 Permute 레이어가 (C, H, W)로 자동 변환함
            val inputBuffer = ByteBuffer.allocateDirect(1 * IMAGE_SIZE * IMAGE_SIZE * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())

            // 🔥 [수정됨] (H, W, C) 순서: 각 픽셀마다 R, G, B 순서로 입력
            for (pixelValue in intValues) {
                val r = (pixelValue shr 16) and 0xFF
                val g = (pixelValue shr 8) and 0xFF
                val b = pixelValue and 0xFF

                inputBuffer.putFloat(((r / 255.0f) - MEAN[0]) / STD[0])
                inputBuffer.putFloat(((g / 255.0f) - MEAN[1]) / STD[1])
                inputBuffer.putFloat(((b / 255.0f) - MEAN[2]) / STD[2])
            }

            // 디버깅: 입력 데이터 샘플 확인
            inputBuffer.rewind()
            val sampleValues = FloatArray(6)
            for (i in 0 until 6) {
                sampleValues[i] = inputBuffer.getFloat()
            }
            Log.d(TAG, "📊 입력 샘플 (처음 2픽셀, RGB): ${sampleValues.contentToString()}")
            inputBuffer.rewind()

            // 4. 추론
            val outputBuffer = Array(1) { FloatArray(EMBEDDING_DIM) }
            interpreter?.run(inputBuffer, outputBuffer)

            val rawVector = outputBuffer[0]

            // 5. L2 정규화 (TFLite 변환 시 이미 포함되어 있지만, 안전을 위해)
            val normalizedVector = normalizeL2(rawVector)

            // 디버깅: 출력 확인
            Log.d(TAG, "✅ 추론 완료")
            Log.d(TAG, "📊 정규화 전 norm: ${calculateNorm(rawVector)}")
            Log.d(TAG, "📊 정규화 후 norm: ${calculateNorm(normalizedVector)}")
            Log.d(TAG, "📊 벡터 샘플 (처음 5개): ${normalizedVector.take(5)}")

            return Pair(normalizedVector, processedBitmap)

        } catch (e: Exception) {
            Log.e(TAG, "❌ 임베딩 추출 중 오류", e)
            e.printStackTrace()
            return Pair(null, null)
        }
    }

    /**
     * L2 정규화
     */
    private fun normalizeL2(vector: FloatArray): FloatArray {
        val norm = calculateNorm(vector)

        if (norm < 1e-8f) {
            Log.w(TAG, "⚠️ 벡터 norm이 거의 0입니다")
            return vector
        }

        return FloatArray(vector.size) { i -> vector[i] / norm }
    }

    /**
     * L2 norm 계산
     */
    private fun calculateNorm(vector: FloatArray): Float {
        var sumSquares = 0.0f
        for (value in vector) {
            sumSquares += value * value
        }
        return sqrt(sumSquares)
    }

    /**
     * 비율 유지 리사이징 + 검은 패딩 (Python과 동일)
     */
    private fun resizeWithPadding(original: Bitmap, targetSize: Int): Bitmap {
        val width = original.width
        val height = original.height

        // 비율 계산 (Python: scale = target_size / max(h, w))
        val scale = targetSize.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        // 검은 배경 생성
        val background = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(background)
        canvas.drawColor(Color.BLACK)

        // 중앙 배치
        val left = (targetSize - newWidth) / 2
        val top = (targetSize - newHeight) / 2
        val destRect = Rect(left, top, left + newWidth, top + newHeight)

        // 고품질 리사이징
        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
        canvas.drawBitmap(original, null, destRect, paint)

        return background
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "✅ 모델 리소스 해제")
    }
}