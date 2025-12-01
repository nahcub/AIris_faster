/*
package com.example.airis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 카메라 프리뷰 화면 (YOLO + EfficientNet 적용)
 */
@Composable
fun CameraPreviewScreen(
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // UI 상태
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var croppedBitmap by remember { mutableStateOf<Bitmap?>(null) } // 크롭된 이미지 확인용
    var isCapturing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("작품을 촬영하세요") }

    // AI 인식 상태
    var isProcessing by remember { mutableStateOf(false) }
    var recognitionResult by remember { mutableStateOf<RecognitionResult?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }

    // 모델 및 데이터 초기화
    val tfliteModel = remember { TFLiteModel(context) }
    // [수정] YOLO 모델 파일명 (float32)
    val yoloDetector = remember { YoloDetector(context, "yolov8n_float32.tflite") }
    val artworkLoader = remember { ArtworkLoader(context) }
    val artworks = remember { artworkLoader.loadArtworks() }

    // 메모리 해제
    DisposableEffect(Unit) {
        onDispose {
            tfliteModel.close()
            yoloDetector.close() // YOLO 해제
        }
    }

    // UI 렌더링
    CameraPreviewContent(
        previewBitmap = previewBitmap,
        croppedBitmap = croppedBitmap, // UI에 크롭된 이미지 표시 옵션
        isCapturing = isCapturing,
        isProcessing = isProcessing,
        errorMessage = errorMessage,
        statusMessage = statusMessage,
        recognitionResult = recognitionResult,
        showResultDialog = showResultDialog,
        onCaptureButtonClick = {
            if (!isCapturing && !isProcessing) {
                isCapturing = true
                errorMessage = null
                statusMessage = "사진 촬영 중..."
                croppedBitmap = null

                coroutineScope.launch {
                    captureSnapshot(
                        url = "http://192.168.4.1:80/snapshot",
                        onSuccess = { bitmap ->
                            previewBitmap = bitmap
                            isCapturing = false
                            isProcessing = true
                            statusMessage = "🤖 객체 탐지 중..."

                            // 백그라운드 AI 처리
                            coroutineScope.launch(Dispatchers.Default) {
                                try {
                                    // 1. YOLOv8 객체 탐지 및 크롭
                                    val (targetBitmap, isCropped) = yoloDetector.detectAndCrop(bitmap)

                                    withContext(Dispatchers.Main) {
                                        croppedBitmap = targetBitmap // UI 업데이트 (선택 사항)
                                        statusMessage = if (isCropped) "✂️ 작품 영역 추출 완료" else "⚠️ 전체 이미지 사용"
                                    }

                                    // 2. EfficientNet 임베딩 추출 (크롭된 이미지 사용)
                                    val embedding = tfliteModel.extractEmbedding(targetBitmap)

                                    if (embedding != null) {
                                        withContext(Dispatchers.Main) { statusMessage = "🔍 데이터베이스 검색 중..." }

                                        // 3. 매칭
                                        val result = SimilarityCalculator.findMostSimilar(
                                            queryEmbedding = embedding,
                                            artworks = artworks
                                        )

                                        withContext(Dispatchers.Main) {
                                            if (result != null) {
                                                recognitionResult = RecognitionResult(
                                                    artworkId = result.artwork.title,
                                                    similarity = result.similarity
                                                )
                                                showResultDialog = true
                                            } else {
                                                errorMessage = "작품을 찾을 수 없습니다."
                                            }
                                            isProcessing = false
                                            statusMessage = "완료"
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            errorMessage = "임베딩 추출 실패"
                                            isProcessing = false
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    withContext(Dispatchers.Main) {
                                        errorMessage = "오류: ${e.message}"
                                        isProcessing = false
                                    }
                                }
                            }
                        },
                        onError = { error ->
                            errorMessage = error
                            isCapturing = false
                        }
                    )
                }
            }
        },
        onDialogDismiss = {
            showResultDialog = false
            recognitionResult = null
            statusMessage = "작품을 촬영하세요"
        },
        onBackClick = onBackClick
    )
}

/**
 * 인식 결과 데이터 클래스
 */
data class RecognitionResult(
    val artworkId: String,
    val similarity: Float
)

/**
 * 카메라 프리뷰 UI (수정됨)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewContent(
    previewBitmap: Bitmap?,
    croppedBitmap: Bitmap?,
    isCapturing: Boolean,
    isProcessing: Boolean,
    errorMessage: String?,
    statusMessage: String,
    recognitionResult: RecognitionResult?,
    showResultDialog: Boolean,
    onCaptureButtonClick: () -> Unit,
    onDialogDismiss: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("카메라 프리뷰", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(painterResource(R.drawable.ic_arrow_left), "뒤로가기")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFDFDFD))
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFFDFDFD))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 메인 프리뷰 영역
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "Preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // 기본 상태
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            //Text("📷", fontSize = 48.sp)
                            //Text("촬영 대기 중", color = Color.Gray)
                        }
                    }

                    // 처리 중 오버레이 & 상태 메시지
                    if (isProcessing || isCapturing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = statusMessage,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // 크롭된 이미지가 있으면 작게 보여줌 (디버깅용)
                                if (croppedBitmap != null) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Detection Result:", color = Color.Yellow, fontSize = 12.sp)
                                    Image(
                                        bitmap = croppedBitmap.asImageBitmap(),
                                        contentDescription = "Crop",
                                        modifier = Modifier
                                            .size(100.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.DarkGray)
                                    )
                                }
                            }
                        }
                    }

                    // 에러 표시
                    if (errorMessage != null) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(errorMessage, color = Color.Red, textAlign = TextAlign.Center)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 버튼
                Button(
                    onClick = onCaptureButtonClick,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isCapturing && !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("눌러서 촬영하기", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }

            // 결과 다이얼로그 (팝업) - [수정] 팝업 호출 위치 명확히 지정
            if (showResultDialog && recognitionResult != null) {
                RecognitionResultDialog(recognitionResult, onDialogDismiss)
            }
        }
    }
}

// ... (RecognitionResultDialog 및 captureSnapshot 함수는 기존 코드와 완전히 동일하게 유지)
@Composable
fun RecognitionResultDialog(
    result: RecognitionResult,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (result.similarity >= 0.4f) "✅" else "✅", fontSize = 50.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("인식 결과", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("ID: ${result.artworkId}", fontSize = 18.sp)
                Text(
                    "유사도: ${(result.similarity * 100).toInt()}%",
                    color = if(result.similarity > 0.7f) Color(0xFF4CAF50) else Color.Black,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("확인")
                }
            }
        }
    }
}

suspend fun captureSnapshot(
    url: String,
    onSuccess: (Bitmap) -> Unit,
    onError: (String) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                if (bitmap != null) withContext(Dispatchers.Main) { onSuccess(bitmap) }
                else withContext(Dispatchers.Main) { onError("이미지 디코딩 실패") }
            } else {
                withContext(Dispatchers.Main) { onError("연결 실패: ${connection.responseCode}") }
            }
            connection.disconnect()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError("네트워크 오류: ${e.message}") }
        }
    }
}
 */

package com.example.airis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "CameraPreview"

/**
 * 카메라 프리뷰 화면 (EfficientNet만 사용 - YOLO 제거)
 */
@Composable
fun CameraPreviewScreen(
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // UI 상태
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("작품을 촬영하세요") }

    // AI 인식 상태
    var isProcessing by remember { mutableStateOf(false) }
    var recognitionResult by remember { mutableStateOf<RecognitionResult?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }

    // 모델 및 데이터 초기화 (YOLO 제거됨)
    val tfliteModel = remember { TFLiteModel(context) }
    val artworkLoader = remember { ArtworkLoader(context) }
    val artworks = remember { artworkLoader.loadArtworks() }

    // 메모리 해제
    DisposableEffect(Unit) {
        onDispose {
            tfliteModel.close()
        }
    }

    // UI 렌더링
    CameraPreviewContent(
        previewBitmap = previewBitmap,
        isCapturing = isCapturing,
        isProcessing = isProcessing,
        errorMessage = errorMessage,
        statusMessage = statusMessage,
        recognitionResult = recognitionResult,
        showResultDialog = showResultDialog,
        onCaptureButtonClick = {
            if (!isCapturing && !isProcessing) {
                isCapturing = true
                errorMessage = null
                statusMessage = "사진 촬영 중..."

                coroutineScope.launch {
                    captureSnapshot(
                        url = "http://192.168.4.1:80/snapshot",
                        onSuccess = { bitmap ->
                            Log.d(TAG, "📸 스냅샷 성공: ${bitmap.width}x${bitmap.height}")
                            previewBitmap = bitmap
                            isCapturing = false
                            isProcessing = true
                            statusMessage = "🔍 작품 분석 중..."

                            // 백그라운드 AI 처리
                            coroutineScope.launch(Dispatchers.Default) {
                                try {
                                    // 1. EfficientNet 임베딩 추출 (원본 이미지 직접 사용)
                                    Log.d(TAG, "🧠 임베딩 추출 시작...")
                                    val embedding = tfliteModel.extractEmbedding(bitmap)

                                    if (embedding != null) {
                                        Log.d(TAG, "✅ 임베딩 추출 완료: ${embedding.size}D")
                                        withContext(Dispatchers.Main) {
                                            statusMessage = "🔍 데이터베이스 검색 중..."
                                        }

                                        // 2. 유사도 매칭
                                        val result = SimilarityCalculator.findMostSimilar(
                                            queryEmbedding = embedding,
                                            artworks = artworks
                                        )

                                        withContext(Dispatchers.Main) {
                                            if (result != null) {
                                                Log.d(TAG, "✅ 매칭 성공: ${result.artwork.title}, 유사도: ${result.similarity}")
                                                recognitionResult = RecognitionResult(
                                                    artworkId = result.artwork.title,
                                                    category = result.artwork.category,
                                                    similarity = result.similarity
                                                )
                                                showResultDialog = true
                                                statusMessage = "완료"
                                            } else {
                                                Log.d(TAG, "❌ 매칭 실패: 유사한 작품 없음")
                                                errorMessage = "작품을 찾을 수 없습니다.\n다시 촬영해주세요."
                                                statusMessage = "작품을 촬영하세요"
                                            }
                                            isProcessing = false
                                        }
                                    } else {
                                        Log.e(TAG, "❌ 임베딩 추출 실패")
                                        withContext(Dispatchers.Main) {
                                            errorMessage = "이미지 분석 실패"
                                            isProcessing = false
                                            statusMessage = "작품을 촬영하세요"
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Log.e(TAG, "❌ 오류: ${e.message}")
                                    withContext(Dispatchers.Main) {
                                        errorMessage = "오류: ${e.message}"
                                        isProcessing = false
                                        statusMessage = "작품을 촬영하세요"
                                    }
                                }
                            }
                        },
                        onError = { error ->
                            Log.e(TAG, "❌ 스냅샷 실패: $error")
                            errorMessage = error
                            isCapturing = false
                            statusMessage = "작품을 촬영하세요"
                        }
                    )
                }
            }
        },
        onDialogDismiss = {
            // 팝업만 닫고 이미지는 유지
            showResultDialog = false
            recognitionResult = null
            statusMessage = "작품을 촬영하세요"
        },
        onBackClick = onBackClick
    )
}

/**
 * 인식 결과 데이터 클래스
 */
data class RecognitionResult(
    val artworkId: String,
    val category: String,
    val similarity: Float
)

/**
 * 카메라 프리뷰 UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewContent(
    previewBitmap: Bitmap?,
    isCapturing: Boolean,
    isProcessing: Boolean,
    errorMessage: String?,
    statusMessage: String,
    recognitionResult: RecognitionResult?,
    showResultDialog: Boolean,
    onCaptureButtonClick: () -> Unit,
    onDialogDismiss: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("카메라 프리뷰", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(painterResource(R.drawable.ic_arrow_left), "뒤로가기")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFDFDFD))
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFFDFDFD))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 메인 프리뷰 영역
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF2C2C2C), RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "Preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        // 기본 상태 - 촬영 대기
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            //Text("📷", fontSize = 48.sp)
                            //Spacer(modifier = Modifier.height(8.dp))
                            //Text(
                                //text = "촬영 버튼을 눌러주세요",
                                //color = Color.Gray,
                                //fontSize = 16.sp
                            //)
                        }
                    }

                    // 처리 중 오버레이
                    if (isProcessing || isCapturing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = statusMessage,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // 에러 표시
                    if (errorMessage != null && !isProcessing && !isCapturing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("⚠️", fontSize = 40.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = errorMessage,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 촬영 버튼
                Button(
                    onClick = onCaptureButtonClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isCapturing && !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text(
                        text = if (isCapturing || isProcessing) "처리 중..." else "눌러서 촬영하기",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 결과 다이얼로그
            if (showResultDialog && recognitionResult != null) {
                RecognitionResultDialog(recognitionResult, onDialogDismiss)
            }
        }
    }
}

/**
 * 인식 결과 다이얼로그
 */
@Composable
fun RecognitionResultDialog(
    result: RecognitionResult,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 결과 아이콘
                Text(
                    text = if (result.similarity >= 0.4f) "✅" else "🔍",
                    fontSize = 50.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "인식 결과",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 작품 정보
                Text(
                    text = result.artworkId,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "카테고리: ${result.category}",
                    fontSize = 14.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 유사도 표시
                val similarityPercent = (result.similarity * 100).toInt()
                val similarityColor = if (result.similarity >= 0.4f) {
                    Color(0xFF4CAF50) // 녹색 (40% 이상)
                } else {
                    Color(0xFFFF9800) // 주황 (40% 미만)
                }

                Text(
                    text = "유사도: ${similarityPercent}%",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = similarityColor
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("확인")
                }
            }
        }
    }
}

/**
 * ESP32-CAM에서 스냅샷 캡처
 */
suspend fun captureSnapshot(
    url: String,
    onSuccess: (Bitmap) -> Unit,
    onError: (String) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                if (bitmap != null) {
                    withContext(Dispatchers.Main) { onSuccess(bitmap) }
                } else {
                    withContext(Dispatchers.Main) { onError("이미지 디코딩 실패") }
                }
            } else {
                withContext(Dispatchers.Main) { onError("연결 실패: ${connection.responseCode}") }
            }
            connection.disconnect()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError("네트워크 오류: ${e.message}") }
        }
    }
}