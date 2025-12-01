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
import kotlin.math.min

/**
 * YOLOv8 객체 탐지 클래스
 * - 모델: yolov8n_float16.tflite (assets 폴더)
 * - 입력: 640x640 (Normalize 0~1)
 * - 출력: [1, 84, 8400] (xc, yc, w, h, classes...)
 */
class YoloDetector(
    context: Context,
    modelFileName: String = "yolov8n_float32.tflite"
) {
    private var interpreter: Interpreter? = null

    // YOLOv8 기본 입력 해상도
    private val inputSize = 640

    // YOLOv8 출력 텐서 구조
    // [1, 84, 8400] -> 배치 1, (4개 좌표 + 80개 클래스), 8400개 앵커 박스
    private val outputRows = 84
    private val outputColumns = 8400

    init {
        try {
            val modelBuffer = loadModelFile(context, modelFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(4) // 4 스레드로 가속
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
     * 비트맵에서 객체를 탐지하고 해당 영역을 크롭하여 반환
     */
    fun detectAndCrop(bitmap: Bitmap): Pair<Bitmap, Boolean> {
        if (interpreter == null) return Pair(bitmap, false)

        try {
            // 1. 전처리 (Resize 640x640 & Normalize 0~1)
            val inputBuffer = preprocessImage(bitmap)

            // 2. 출력 버퍼 생성 [1, 84, 8400]
            val outputBuffer = Array(1) { Array(outputRows) { FloatArray(outputColumns) } }

            // 3. 추론 실행
            interpreter?.run(inputBuffer, outputBuffer)

            // 4. 후처리 (가장 확률 높은 박스 1개 찾기)
            val bestBox = parseOutput(outputBuffer[0], bitmap.width, bitmap.height)

            // 5. 크롭 실행
            return if (bestBox != null) {
                val croppedBitmap = cropBitmap(bitmap, bestBox)
                println("✂️ YOLO 크롭 성공")
                Pair(croppedBitmap, true)
            } else {
                println("⚠️ 탐지된 객체 없음 (원본 사용)")
                Pair(bitmap, false)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(bitmap, false)
        }
    }

    /**
     * 이미지 전처리: 640x640 리사이즈 및 정규화
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        // 단순 리사이즈 (비율 무시하고 640x640으로 맞춤 - YOLO는 이걸 더 선호함)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in intValues) {
            // 0~255 값을 0.0~1.0으로 정규화
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        return inputBuffer
    }

    /**
     * 모델 출력 해석 및 좌표 변환
     */
    private fun parseOutput(output: Array<FloatArray>, originalW: Int, originalH: Int): RectF? {
        var maxScore = 0.0f
        var bestBox: RectF? = null

        // 탐지 임계값 (이 점수보다 낮으면 무시)
        val threshold = 0.4f

        // 8400개의 앵커 박스 반복 확인
        for (i in 0 until outputColumns) {
            // 4번 인덱스부터 83번 인덱스까지가 클래스 확률 (총 80개)
            // 가장 높은 클래스 점수 찾기
            var classScore = 0.0f
            for (c in 4 until outputRows) {
                if (output[c][i] > classScore) {
                    classScore = output[c][i]
                }
            }

            if (classScore > maxScore) {
                maxScore = classScore

                // 좌표 추출 (640x640 기준)
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]

                // 원본 이미지 크기에 맞춰 좌표 변환
                val xFactor = originalW.toFloat() / inputSize
                val yFactor = originalH.toFloat() / inputSize

                val boxCx = cx * xFactor
                val boxCy = cy * yFactor
                val boxW = w * xFactor
                val boxH = h * yFactor

                val left = boxCx - (boxW / 2)
                val top = boxCy - (boxH / 2)
                val right = left + boxW
                val bottom = top + boxH

                bestBox = RectF(left, top, right, bottom)
            }
        }

        return if (maxScore >= threshold) bestBox else null
    }

    /**
     * 좌표 정보를 바탕으로 비트맵 자르기
     */
    private fun cropBitmap(source: Bitmap, box: RectF): Bitmap {
        // 좌표가 이미지 범위를 벗어나지 않도록 보정
        val left = max(0f, box.left).toInt()
        val top = max(0f, box.top).toInt()
        val width = min(source.width - left, box.width().toInt())
        val height = min(source.height - top, box.height().toInt())

        // 유효하지 않은 크기면 원본 반환
        if (width <= 0 || height <= 0) return source

        return Bitmap.createBitmap(source, left, top, width, height)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}