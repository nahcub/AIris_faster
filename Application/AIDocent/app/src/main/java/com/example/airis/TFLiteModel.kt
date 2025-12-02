package com.example.airis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/**
 * CLIP 모델용 TFLite 래퍼
 * - 모델명: art_clip_model.tflite
 * - 전처리: (Pixel - Mean) / Std
 */
class TFLiteModel(
    context: Context,
    // 🔥 [수정] 기본 모델명을 CLIP 모델로 변경
    modelFileName: String = "art_clip_model.tflite"
) {

    private var interpreter: Interpreter? = null
    private val inputSize = 224
    private val imageByteSize = inputSize * inputSize * 3 * 4
    private val embeddingSize = 128

    init {
        try {
            val modelBuffer = loadModelFile(context, modelFileName)
            val options = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(modelBuffer, options)
            println("✅ CLIP 모델 로드 완료: $modelFileName")
        } catch (e: Exception) {
            e.printStackTrace()
            println("❌ 모델 로드 실패: ${e.message}")
        }
    }

    private fun loadModelFile(context: Context, modelFileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun extractEmbedding(bitmap: Bitmap): FloatArray? {
        if (interpreter == null) return null
        try {
            val inputBuffer = preprocessImage(bitmap)
            val outputBuffer = Array(1) { FloatArray(embeddingSize) }
            interpreter?.run(inputBuffer, outputBuffer)
            return outputBuffer[0]
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * 🔥 [핵심] CLIP 전용 전처리 (Letterbox + Mean/Std 정규화)
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        // 1. Letterbox Resizing
        val targetW = inputSize
        val targetH = inputSize
        val scale = min(targetW.toFloat() / bitmap.width, targetH.toFloat() / bitmap.height)
        val scaledW = (bitmap.width * scale).toInt()
        val scaledH = (bitmap.height * scale).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val bgBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bgBitmap)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(scaledBitmap, (targetW - scaledW) / 2f, (targetH - scaledH) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))

        // 2. Normalization (CLIP Mean/Std 적용)
        val inputBuffer = ByteBuffer.allocateDirect(imageByteSize)
        inputBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)
        bgBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // CLIP 공식 상수 (RGB 순서)
        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            // 🔥 (값 - 평균) / 표준편차
            inputBuffer.putFloat((r - mean[0]) / std[0])
            inputBuffer.putFloat((g - mean[1]) / std[1])
            inputBuffer.putFloat((b - mean[2]) / std[2])
        }
        return inputBuffer
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}