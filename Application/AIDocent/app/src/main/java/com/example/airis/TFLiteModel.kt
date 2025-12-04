package com.example.airis

import android.content.Context
import android.graphics.Bitmap
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
            // GPU 사용 가능 시: options.addDelegate(GpuDelegate())
            interpreter = Interpreter(FileUtil.loadMappedFile(context, "art_clip_model.tflite"), options)
        } catch (e: Exception) {
            Log.e("TFLiteModel", "모델 초기화 실패", e)
        }
    }

    // 이름 변경: getEmbedding -> extractEmbedding (호출부와 일치)
    // 반환 타입 변경: FloatArray -> FloatArray? (실패 시 null 처리)
    fun extractEmbedding(bitmap: Bitmap): FloatArray? {
        if (interpreter == null) return null

        try {
            // 1. 리사이즈
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)

            // 2. 입력 버퍼 (1 * 224 * 224 * 3 * 4bytes)
            val inputBuffer = ByteBuffer.allocateDirect(1 * IMAGE_SIZE * IMAGE_SIZE * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())

            val intValues = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            resizedBitmap.getPixels(intValues, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            // 3. 전처리 (Normalization)
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

            return outputBuffer[0]
        } catch (e: Exception) {
            Log.e("TFLiteModel", "임베딩 추출 중 오류", e)
            return null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}