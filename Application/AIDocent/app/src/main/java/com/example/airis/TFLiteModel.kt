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

class TFLiteModel(context: Context, modelFileName: String = "art_model.tflite") {

    private var interpreter: Interpreter? = null
    private val inputSize = 224
    private val imageByteSize = inputSize * inputSize * 3 * 4
    private val embeddingSize = 128

    init {
        try {
            val modelBuffer = loadModelFile(context, modelFileName)
            val options = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            e.printStackTrace()
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

        // 2. Buffer 변환
        val inputBuffer = ByteBuffer.allocateDirect(imageByteSize)
        inputBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)
        bgBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in intValues) {
            // [수정] Python 학습 시 / 255.0 전처리를 했으므로 여기도 똑같이 해야 함
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)          // B
        }
        return inputBuffer
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}