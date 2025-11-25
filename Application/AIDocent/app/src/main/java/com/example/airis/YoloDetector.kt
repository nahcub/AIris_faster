package com.example.airis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * YOLOv8 객체 탐지기
 * - 이미지에서 작품 영역(Bounding Box)을 탐지
 */
class YoloDetector(
    context: Context,
    modelFileName: String = "yolov8n_float16.tflite" // float16 또는 float32 모델 권장
) {
    private var interpreter: Interpreter? = null

    // YOLOv8 기본 입력 크기 (640x640)
    private val inputSize = 640

    // 출력 형태: [1, 84, 8400] (Batch, 4 box + 80 classes, Anchors)
    // 4개 좌표(cx, cy, w, h) + 80개 클래스 확률
    private val outputClasses = 80
    private val outputAnchors = 8400

    init {
        try {
            val modelBuffer = loadModelFile(context, modelFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            println("✅ YOLO 모델 로드 완료: $modelFileName")
        } catch (e: Exception) {
            e.printStackTrace()
            println("❌ YOLO 모델 로드 실패: ${e.message}")
        }
    }

    private fun loadModelFile(context: Context, modelFileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    /**
     * 객체 탐지 및 크롭
     * @param bitmap 원본 비트맵
     * @return 탐지된 영역으로 크롭된 비트맵 (탐지 실패 시 원본 반환)
     */
    fun detectAndCrop(bitmap: Bitmap): Pair<Bitmap, Boolean> {
        if (interpreter == null) return Pair(bitmap, false)

        try {
            // 1. 전처리 (Resize & Normalize)
            val inputBuffer = preprocessImage(bitmap)

            // 2. 출력 버퍼 준비 [1, 84, 8400]
            val outputArray = Array(1) { Array(4 + outputClasses) { FloatArray(outputAnchors) } }

            // 3. 추론
            interpreter?.run(inputBuffer, outputArray)

            // 4. 후처리 (가장 신뢰도 높은 박스 찾기)
            val bestBox = postProcess(outputArray[0], bitmap.width, bitmap.height)

            return if (bestBox != null) {
                // 크롭 수행
                val cropped = cropBitmap(bitmap, bestBox)
                println("✂️ YOLO 크롭 성공: ${bestBox.toShortString()}")
                Pair(cropped, true)
            } else {
                println("⚠️ 객체 탐지 실패: 원본 사용")
                Pair(bitmap, false)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(bitmap, false)
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixelValue in intValues) {
            // 정규화: 0~255 -> 0.0~1.0
            inputBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((pixelValue and 0xFF) / 255.0f)          // B
        }
        return inputBuffer
    }

    private fun postProcess(output: Array<FloatArray>, imgWidth: Int, imgHeight: Int): RectF? {
        var bestConf = 0.25f // 임계값 (0.25 이상만 취급)
        var bestBox: RectF? = null

        // [4 coord + 80 classes] x [8400 anchors]
        // 전치 행렬 형태이므로 루프를 돌며 확인
        for (i in 0 until outputAnchors) {
            // 클래스 점수 중 최대값 찾기 (4번 인덱스부터 끝까지)
            var maxClassScore = 0f
            for (c in 0 until outputClasses) {
                val score = output[4 + c][i]
                if (score > maxClassScore) maxClassScore = score
            }

            if (maxClassScore > bestConf) {
                bestConf = maxClassScore

                // 좌표 변환 (0~1 정규화된 값 -> 픽셀 좌표)
                val cx = output[0][i] * imgWidth // 이미지가 찌그러졌다고 가정하고 원본 비율에 매핑
                val cy = output[1][i] * imgHeight
                val w = output[2][i] * imgWidth
                val h = output[3][i] * imgHeight

                val left = max(0f, cx - w / 2)
                val top = max(0f, cy - h / 2)
                val right = left + w
                val bottom = top + h

                bestBox = RectF(left, top, right, bottom)
            }
        }

        // (참고: 여기서는 가장 높은 점수 1개만 반환하지만,
        //  실제로는 NMS(Non-Maximum Suppression)를 적용하여 겹치는 박스를 제거해야 완벽합니다.
        //  하지만 "단일 작품"을 찍는 시나리오에서는 Max Score 방식도 꽤 잘 작동합니다.)

        return bestBox
    }

    private fun cropBitmap(source: Bitmap, box: RectF): Bitmap {
        val x = box.left.toInt().coerceIn(0, source.width)
        val y = box.top.toInt().coerceIn(0, source.height)
        val width = box.width().toInt().coerceAtMost(source.width - x)
        val height = box.height().toInt().coerceAtMost(source.height - y)

        return if (width > 0 && height > 0) {
            Bitmap.createBitmap(source, x, y, width, height)
        } else {
            source
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}