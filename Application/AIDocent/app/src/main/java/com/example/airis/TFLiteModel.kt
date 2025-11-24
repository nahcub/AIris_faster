package com.example.airis

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite EfficientNetB0 모델 래퍼
 * - 이미지를 입력받아 1280차원 임베딩 벡터를 출력
 * - Python의 preprocess_input과 동일한 전처리 적용
 */
class TFLiteModel(context: Context, modelFileName: String = "efficientnet_b0.tflite") {

    private var interpreter: Interpreter? = null

    // 입력 이미지 크기
    private val inputSize = 224
    private val pixelSize = 3 // RGB
    private val imageByteSize = inputSize * inputSize * pixelSize * 4 // Float32 = 4 bytes

    // 출력 임베딩 크기
    private val embeddingSize = 1280

    init {
        try {
            println("🔧 TFLite 모델 초기화 시작...")
            println("   모델 파일: $modelFileName")

            // assets에서 모델 파일 로드
            val modelBuffer = loadModelFile(context, modelFileName)
            println("   ✓ 모델 파일 로드 성공 (${modelBuffer.capacity()} bytes)")

            // Interpreter 초기화
            val options = Interpreter.Options().apply {
                setNumThreads(4) // 멀티스레드 사용
            }
            interpreter = Interpreter(modelBuffer, options)
            println("   ✓ Interpreter 초기화 성공")

            println("✅ TFLite 모델 로드 완료: $modelFileName")

        } catch (e: Exception) {
            e.printStackTrace()
            println("❌ TFLite 모델 로드 실패: ${e.message}")
        }
    }

    /**
     * assets 폴더에서 모델 파일 로드
     */
    private fun loadModelFile(context: Context, modelFileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Bitmap 이미지에서 임베딩 추출
     * @param bitmap 입력 이미지
     * @return 1280차원 FloatArray (임베딩 벡터)
     */
    fun extractEmbedding(bitmap: Bitmap): FloatArray? {
        if (interpreter == null) {
            println("❌ Interpreter가 초기화되지 않았습니다.")
            return null
        }

        try {
            println("📸 임베딩 추출 시작...")

            // 1. 이미지 전처리
            val inputBuffer = preprocessImage(bitmap)

            // 2. 출력 버퍼 준비
            val outputBuffer = Array(1) { FloatArray(embeddingSize) }

            // 3. 추론 실행
            val startTime = System.currentTimeMillis()
            interpreter?.run(inputBuffer, outputBuffer)
            val endTime = System.currentTimeMillis()

            println("   ✓ 추론 완료 (${endTime - startTime}ms)")
            println("   ✓ 임베딩 차원: ${outputBuffer[0].size}D")
            println("   ✓ 임베딩 샘플: [${outputBuffer[0].take(5).joinToString(", ") { "%.4f".format(it) }}...]")

            // 4. 결과 반환
            return outputBuffer[0]

        } catch (e: Exception) {
            e.printStackTrace()
            println("❌ 임베딩 추출 실패: ${e.message}")
            return null
        }
    }

    /**
     * 이미지 전처리 (EfficientNet preprocess_input 방식)
     *
     * Python 코드와 동일:
     * ```python
     * from tensorflow.keras.applications.efficientnet import preprocess_input
     * img = preprocess_input(img)  # [0,255] → [-1,1]
     * ```
     *
     * 변환 공식: (pixel / 127.5) - 1.0
     * - [0, 255] → [-1, 1] 범위로 정규화
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        // 224x224로 리사이즈
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // ByteBuffer 생성
        val inputBuffer = ByteBuffer.allocateDirect(imageByteSize).apply {
            order(ByteOrder.nativeOrder())
        }

        // 픽셀 값을 버퍼에 추가
        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)

        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val value = intValues[pixel++]

                // RGB 값 추출
                val r = ((value shr 16) and 0xFF).toFloat()
                val g = ((value shr 8) and 0xFF).toFloat()
                val b = (value and 0xFF).toFloat()

                // ✅ EfficientNet preprocess_input 적용
                // [0, 255] → [-1, 1]
                val normalizedR = (r / 127.5f) - 1.0f
                val normalizedG = (g / 127.5f) - 1.0f
                val normalizedB = (b / 127.5f) - 1.0f

                inputBuffer.putFloat(normalizedR)
                inputBuffer.putFloat(normalizedG)
                inputBuffer.putFloat(normalizedB)

                // 디버깅: 첫 픽셀만 출력
                if (pixel == 1) {
                    println("   🎨 첫 픽셀 전처리:")
                    println("      원본 RGB: [$r, $g, $b]")
                    println("      정규화 후: [%.4f, %.4f, %.4f]".format(normalizedR, normalizedG, normalizedB))
                }
            }
        }

        return inputBuffer
    }

    /**
     * 리소스 해제
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        println("✅ TFLite 모델 리소스 해제")
    }
}