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

/**
 * TFLite EfficientNetB0 모델 래퍼 (수정됨)
 * - 파이썬의 'Letterbox Resizing' 적용 (비율 유지)
 * - EfficientNet 전용 전처리 적용 (0~255 범위 유지)
 */
class TFLiteModel(context: Context, modelFileName: String = "efficientnet_b0.tflite") {

    private var interpreter: Interpreter? = null

    // 입력 이미지 크기
    private val inputSize = 224
    private val imageByteSize = inputSize * inputSize * 3 * 4 // RGB * Float(4byte)

    // 출력 임베딩 크기
    private val embeddingSize = 1280

    init {
        try {
            val modelBuffer = loadModelFile(context, modelFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            println("✅ TFLite 모델 로드 완료: $modelFileName")
        } catch (e: Exception) {
            e.printStackTrace()
            println("❌ TFLite 모델 로드 실패: ${e.message}")
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
            // 1. 파이썬과 동일한 Letterbox 전처리 적용
            val inputBuffer = preprocessImageWithLetterbox(bitmap)

            // 2. 출력 버퍼
            val outputBuffer = Array(1) { FloatArray(embeddingSize) }

            // 3. 추론
            interpreter?.run(inputBuffer, outputBuffer)

            return outputBuffer[0]

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * [중요] 파이썬 로직 이식: Letterbox Resizing & Normalization
     * 1. 비율 유지하며 리사이징 (빈 공간은 검은색)
     * 2. 픽셀 값은 0~255 그대로 전달 (EfficientNet 스펙)
     */
    private fun preprocessImageWithLetterbox(bitmap: Bitmap): ByteBuffer {
        // --- 1단계: Letterbox Resizing (비율 유지) ---
        val targetWidth = inputSize
        val targetHeight = inputSize

        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height

        // 스케일 계산 (가로/세로 중 더 많이 줄여야 하는 쪽 기준)
        val scale = kotlin.math.min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)

        val scaledWidth = (sourceWidth * scale).toInt()
        val scaledHeight = (sourceHeight * scale).toInt()

        // 리사이징된 비트맵 생성
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // 검은색 배경의 224x224 비트맵 생성
        val backgroundBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(backgroundBitmap)
        canvas.drawColor(Color.BLACK) // 배경 검은색

        // 중앙 정렬 좌표 계산
        val x = (targetWidth - scaledWidth) / 2f
        val y = (targetHeight - scaledHeight) / 2f

        // 배경 위에 리사이징된 이미지 그리기
        canvas.drawBitmap(scaledBitmap, x, y, Paint(Paint.FILTER_BITMAP_FLAG))

        // --- 2단계: ByteBuffer 변환 (0~255 값 유지) ---
        val inputBuffer = ByteBuffer.allocateDirect(imageByteSize)
        inputBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        backgroundBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // 픽셀 값 추출 및 입력
        for (pixelValue in intValues) {
            // EfficientNet은 일반적으로 0~255 범위의 float32 입력을 받습니다.
            // (모델 내부에 Rescaling Layer가 포함되어 있음)
            // 따라서 [-1, 1] 정규화를 하지 않고 R, G, B 값을 그대로 넣습니다.

            val r = ((pixelValue shr 16) and 0xFF).toFloat()
            val g = ((pixelValue shr 8) and 0xFF).toFloat()
            val b = (pixelValue and 0xFF).toFloat()

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        return inputBuffer
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}