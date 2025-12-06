/*
package com.example.airis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
 * [수정됨] 인식 결과 데이터 클래스
 * - debugBitmap: AI 모델에 실제로 입력된 전처리(Padding/Resizing)된 이미지
 */
data class RecognitionResult(
    val artwork: Artwork,
    val similarity: Float,
    val debugBitmap: Bitmap? // 디버깅용 이미지 추가
)

/**
 * 카메라 프리뷰 화면
 */
@Composable
fun CameraPreviewScreen(
    onBackClick: () -> Unit = {}
) {
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

    // 시스템 준비 상태 확인 (ArtRepository 싱글톤)
    val isSystemReady = ArtRepository.isLoaded

    // UI 렌더링
    CameraPreviewContent(
        previewBitmap = previewBitmap,
        isCapturing = isCapturing,
        isProcessing = isProcessing,
        errorMessage = errorMessage,
        statusMessage = if (!isSystemReady) "데이터 로딩 중..." else statusMessage,
        recognitionResult = recognitionResult,
        showResultDialog = showResultDialog,
        onCaptureButtonClick = {
            // 1. 데이터 로딩 확인
            if (!isSystemReady) {
                errorMessage = "데이터가 아직 준비되지 않았습니다."
                return@CameraPreviewContent
            }

            if (!isCapturing && !isProcessing) {
                isCapturing = true
                errorMessage = null
                statusMessage = "사진 촬영 중..."

                coroutineScope.launch {
                    // 2. ESP32 스냅샷 촬영
                    captureSnapshot(
                        url = "http://192.168.4.1:80/snapshot",
                        onSuccess = { bitmap ->
                            Log.d(TAG, "📸 스냅샷 성공: ${bitmap.width}x${bitmap.height}")
                            previewBitmap = bitmap
                            isCapturing = false
                            isProcessing = true
                            statusMessage = "🔍 작품 분석 중..."

                            // 3. 백그라운드 AI 처리
                            coroutineScope.launch(Dispatchers.Default) {
                                try {
                                    // 모델 가져오기
                                    val model = ArtRepository.tfLiteModel
                                    if (model == null) throw Exception("AI 모델 오류")

                                    // 임베딩 추출 (Pair<FloatArray?, Bitmap?> 반환)
                                    Log.d(TAG, "🧠 임베딩 추출 시작...")
                                    val (embedding, aiInputImage) = model.extractEmbedding(bitmap)

                                    if (embedding != null) {
                                        withContext(Dispatchers.Main) {
                                            statusMessage = "📚 데이터베이스 검색 중..."
                                        }

                                        // 데이터 가져오기
                                        val indexData = ArtRepository.artworkIndex
                                        val metadata = ArtRepository.artworkMetadata

                                        if (indexData != null && metadata != null) {
                                            // 유사도 검색
                                            val match = SimilarityCalculator.findMostSimilarArtwork(
                                                queryVector = embedding,
                                                indexData = indexData
                                            )

                                            withContext(Dispatchers.Main) {
                                                if (match != null) {
                                                    val (id, score) = match
                                                    val info = metadata[id]

                                                    if (info != null) {
                                                        Log.d(TAG, "✅ 매칭 성공: ${info.title} ($score)")
                                                        recognitionResult = RecognitionResult(
                                                            artwork = info,
                                                            similarity = score,
                                                            debugBitmap = aiInputImage // 디버그 이미지 저장
                                                        )
                                                        showResultDialog = true
                                                        statusMessage = "완료"
                                                    } else {
                                                        errorMessage = "작품 정보 누락 ($id)"
                                                    }
                                                } else {
                                                    Log.d(TAG, "❌ 매칭 실패: 유사한 작품 없음")
                                                    errorMessage = "유사한 작품을 찾을 수 없습니다."
                                                    statusMessage = "작품을 촬영하세요"
                                                }
                                                isProcessing = false
                                            }
                                        } else {
                                            throw Exception("데이터베이스 로드 실패")
                                        }
                                    } else {
                                        throw Exception("이미지 분석 실패")
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
            showResultDialog = false
            recognitionResult = null
            statusMessage = "작품을 촬영하세요"
        },
        onBackClick = onBackClick
    )
}

/**
 * 카메라 프리뷰 UI 컴포저블
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
                    }

                    // 로딩/처리 중 오버레이
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

                    // 에러 메시지 오버레이
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

            // 결과 팝업 다이얼로그
            if (showResultDialog && recognitionResult != null) {
                RecognitionResultDialog(recognitionResult, onDialogDismiss)
            }
        }
    }
}

/**
 * [수정됨] 결과 팝업 다이얼로그
 * - AI가 본 이미지(debugBitmap)를 시각적으로 표시하여 오류 원인 파악 가능
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
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 상단 아이콘 및 타이틀
                Text(
                    text = if (result.similarity >= 0.6f) "🎨" else "🤔",
                    fontSize = 40.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // [추가] 디버깅용: AI 입력 이미지 확인
                if (result.debugBitmap != null) {
                    Text(
                        text = "AI가 인식한 이미지:",
                        fontSize = 12.sp,
                        color = Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Image(
                        bitmap = result.debugBitmap.asImageBitmap(),
                        contentDescription = "AI Input View",
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black), // 패딩 확인용 검은 배경
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 작품 제목
                Text(
                    text = result.artwork.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 작가
                Text(
                    text = result.artwork.author,
                    fontSize = 16.sp,
                    color = Color.DarkGray,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                // 상세 정보
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InfoRow("유사도", "${(result.similarity * 100).toInt()}%")
                    InfoRow("제작 연도", result.artwork.date)
                    InfoRow("기법", result.artwork.technique)
                    InfoRow("종류", result.artwork.type)
                    InfoRow("화파/학교", result.artwork.school)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 확인 버튼
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

@Composable
fun InfoRow(label: String, value: String) {
    if (value.isBlank() || value == "-") return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2f)
        )
    }
}

/**
 * ESP32-CAM 이미지 캡처
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
 */

package com.example.airis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
 * 인식 결과 데이터 클래스
 * - debugBitmap: AI가 실제로 처리한 이미지 (디버깅용)
 */
data class RecognitionResult(
    val artwork: Artwork,
    val similarity: Float,
    val debugBitmap: Bitmap? = null
)

/**
 * 카메라 프리뷰 화면
 * - ArtRepository(싱글톤)를 사용하여 메모리 효율성 및 속도 개선
 * - 촬영 -> 임베딩 추출 -> 유사도 검색 -> 결과 팝업
 */
@Composable
fun CameraPreviewScreen(
    onBackClick: () -> Unit = {}
) {
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

    // 시스템 준비 상태 확인 (DB 로딩 완료 여부)
    val isSystemReady = ArtRepository.isLoaded

    // UI 렌더링
    CameraPreviewContent(
        previewBitmap = previewBitmap,
        isCapturing = isCapturing,
        isProcessing = isProcessing,
        errorMessage = errorMessage,
        statusMessage = if (!isSystemReady) "데이터 로딩 중..." else statusMessage,
        recognitionResult = recognitionResult,
        showResultDialog = showResultDialog,
        onCaptureButtonClick = {
            // 1. 데이터 로딩 확인
            if (!isSystemReady) {
                errorMessage = "데이터가 아직 준비되지 않았습니다."
                return@CameraPreviewContent
            }

            if (!isCapturing && !isProcessing) {
                isCapturing = true
                errorMessage = null
                statusMessage = "사진 촬영 중..."

                coroutineScope.launch {
                    // 2. ESP32 스냅샷 촬영
                    captureSnapshot(
                        url = "http://192.168.4.1:80/snapshot",
                        onSuccess = { bitmap ->
                            Log.d(TAG, "📸 스냅샷 성공: ${bitmap.width}x${bitmap.height}")
                            previewBitmap = bitmap
                            isCapturing = false
                            isProcessing = true
                            statusMessage = "🔍 작품 분석 중..."

                            // 3. 백그라운드 AI 처리
                            coroutineScope.launch(Dispatchers.Default) {
                                try {
                                    // 모델 가져오기 (싱글톤)
                                    val model = ArtRepository.tfLiteModel
                                    if (model == null) throw Exception("AI 모델 오류")

                                    // 임베딩 추출
                                    Log.d(TAG, "🧠 임베딩 추출 시작...")

                                    // 🔥 [수정] Pair로 반환값 받기
                                    val (embedding, processedBitmap) = model.extractEmbedding(bitmap)

                                    if (embedding != null) {
                                        withContext(Dispatchers.Main) {
                                            statusMessage = "📚 데이터베이스 검색 중..."
                                        }

                                        // 데이터 가져오기 (싱글톤)
                                        val indexData = ArtRepository.artworkIndex
                                        val metadata = ArtRepository.artworkMetadata

                                        if (indexData != null && metadata != null) {
                                            // 유사도 검색 (싱글톤 Object 호출)
                                            val match = SimilarityCalculator.findMostSimilarArtwork(
                                                queryVector = embedding,
                                                indexData = indexData
                                            )

                                            withContext(Dispatchers.Main) {
                                                if (match != null) {
                                                    val (id, score) = match
                                                    // ID로 상세 정보 조회
                                                    val info = metadata[id]

                                                    if (info != null) {
                                                        Log.d(TAG, "✅ 매칭 성공: ${info.title} ($score)")
                                                        recognitionResult = RecognitionResult(
                                                            artwork = info,
                                                            similarity = score,
                                                            debugBitmap = processedBitmap  // 🔥 [추가]
                                                        )
                                                        showResultDialog = true
                                                        statusMessage = "완료"
                                                    } else {
                                                        // 인덱스엔 있는데 메타데이터가 없는 경우
                                                        errorMessage = "작품 정보 누락 ($id)"
                                                    }
                                                } else {
                                                    Log.d(TAG, "❌ 매칭 실패: 유사한 작품 없음")
                                                    errorMessage = "유사한 작품을 찾을 수 없습니다."
                                                    statusMessage = "작품을 촬영하세요"
                                                }
                                                isProcessing = false
                                            }
                                        } else {
                                            throw Exception("데이터베이스 로드 실패")
                                        }
                                    } else {
                                        throw Exception("이미지 분석 실패")
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
            showResultDialog = false
            recognitionResult = null
            statusMessage = "작품을 촬영하세요"
        },
        onBackClick = onBackClick
    )
}

/**
 * 카메라 프리뷰 UI 컴포저블
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
                title = { Text("작품 인식", fontWeight = FontWeight.Bold) },
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
                        // 대기 상태
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📷", fontSize = 48.sp, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = statusMessage,
                                color = Color.Gray,
                                fontSize = 16.sp
                            )
                        }
                    }

                    // 로딩/처리 중 오버레이
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

                    // 에러 메시지 오버레이
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

            // 결과 팝업 다이얼로그
            if (showResultDialog && recognitionResult != null) {
                RecognitionResultDialog(recognitionResult, onDialogDismiss)
            }
        }
    }
}

/**
 * 결과 팝업 다이얼로그
 * - debugBitmap이 있으면 AI가 처리한 이미지도 표시 (디버깅용)
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
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 상단 아이콘
                Text(
                    text = if (result.similarity >= 0.6f) "🎨" else "🤔",
                    fontSize = 40.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // [디버깅용] AI가 처리한 이미지 표시
                if (result.debugBitmap != null) {
                    Text(
                        text = "AI 입력 이미지:",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Image(
                        bitmap = result.debugBitmap.asImageBitmap(),
                        contentDescription = "AI Input",
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 작품 제목
                Text(
                    text = result.artwork.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 작가
                Text(
                    text = result.artwork.author,
                    fontSize = 16.sp,
                    color = Color.DarkGray,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // 상세 정보
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InfoRow("유사도", "${(result.similarity * 100).toInt()}%")
                    InfoRow("제작 연도", result.artwork.date)
                    InfoRow("기법", result.artwork.technique)
                    InfoRow("종류", result.artwork.type)
                    InfoRow("화파/학교", result.artwork.school)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 확인 버튼
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
 * 정보 행 표시용 헬퍼 컴포저블
 */
@Composable
fun InfoRow(label: String, value: String) {
    if (value.isBlank() || value == "-") return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2f)
        )
    }
}

/**
 * ESP32-CAM 이미지 캡처 (네트워크 요청)
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