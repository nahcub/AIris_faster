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
 * 카메라 프리뷰 화면
 * - 사진 촬영
 * - TFLite 모델로 임베딩 추출
 * - 작품 검색 및 결과 표시
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

    // AI 인식 상태
    var isProcessing by remember { mutableStateOf(false) }
    var recognitionResult by remember { mutableStateOf<RecognitionResult?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }

    // 모델 및 데이터 초기화
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
        recognitionResult = recognitionResult,
        showResultDialog = showResultDialog,
        onCaptureButtonClick = {
            isCapturing = true
            errorMessage = null

            coroutineScope.launch {
                captureSnapshot(
                    url = "http://192.168.4.1:80/snapshot",
                    onSuccess = { bitmap ->
                        previewBitmap = bitmap
                        isCapturing = false

                        // 캡처 성공 후 AI 인식 시작
                        isProcessing = true

                        coroutineScope.launch(Dispatchers.Default) {
                            try {
                                println("🤖 AI 인식 시작...")

                                // 1. 임베딩 추출
                                val embedding = tfliteModel.extractEmbedding(bitmap)

                                if (embedding != null) {
                                    // 2. 가장 유사한 작품 찾기
                                    val result = SimilarityCalculator.findMostSimilar(
                                        queryEmbedding = embedding,
                                        artworks = artworks
                                    )

                                    // 3. 결과 저장 및 팝업 표시
                                    withContext(Dispatchers.Main) {
                                        if (result != null) {
                                            recognitionResult = RecognitionResult(
                                                artworkId = result.artwork.id,
                                                similarity = result.similarity
                                            )
                                            showResultDialog = true
                                            println("✅ AI 인식 완료: ${result.artwork.id} (${result.similarity})")
                                        } else {
                                            errorMessage = "작품을 찾을 수 없습니다."
                                            println("❌ 작품 검색 실패")
                                        }
                                        isProcessing = false
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        errorMessage = "임베딩 추출 실패"
                                        isProcessing = false
                                        println("❌ 임베딩 추출 실패")
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                withContext(Dispatchers.Main) {
                                    errorMessage = "인식 오류: ${e.message}"
                                    isProcessing = false
                                    println("❌ AI 인식 오류: ${e.message}")
                                }
                            }
                        }
                    },
                    onError = { error ->
                        errorMessage = error
                        isCapturing = false
                        println("❌ 촬영 실패: $error")
                    }
                )
            }
        },
        onDialogDismiss = {
            showResultDialog = false
            recognitionResult = null
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
 * 카메라 프리뷰 UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewContent(
    previewBitmap: Bitmap?,
    isCapturing: Boolean,
    isProcessing: Boolean,
    errorMessage: String?,
    recognitionResult: RecognitionResult?,
    showResultDialog: Boolean,
    onCaptureButtonClick: () -> Unit,
    onDialogDismiss: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "작품 인식",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = "뒤로가기"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFDFDFD)
                )
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
                // 카메라 프리뷰 영역
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            color = Color(0xFF2C2C2C),
                            shape = RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        previewBitmap != null -> {
                            // 촬영된 이미지 표시
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "카메라 프리뷰",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Crop
                            )

                            // AI 처리 중 오버레이
                            if (isProcessing) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .clip(RoundedCornerShape(16.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            strokeWidth = 4.dp,
                                            modifier = Modifier.size(60.dp)
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "🤖 AI 작품 인식 중...",
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                        errorMessage != null -> {
                            // 에러 메시지
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "⚠️",
                                    fontSize = 48.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = errorMessage,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        isCapturing -> {
                            // 촬영 중
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "📸 사진 촬영 중...",
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                            }
                        }
                        else -> {
                            // 초기 상태
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "🎨",
                                    fontSize = 64.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "작품을 촬영하세요",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "AI가 작품을 자동으로 인식합니다",
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 사진 촬영 버튼
                Button(
                    onClick = onCaptureButtonClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50),
                        contentColor = Color.White,
                        disabledContainerColor = Color.Gray,
                        disabledContentColor = Color.White
                    ),
                    enabled = !isCapturing && !isProcessing
                ) {
                    Text(
                        text = when {
                            isCapturing -> "촬영 중..."
                            isProcessing -> "인식 중..."
                            else -> "📷 사진 촬영"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 인식 결과 팝업
            if (showResultDialog && recognitionResult != null) {
                RecognitionResultDialog(
                    result = recognitionResult,
                    onDismiss = onDialogDismiss
                )
            }
        }
    }
}

/**
 * 인식 결과 팝업 Dialog
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
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 아이콘
                Text(
                    text = when {
                        result.similarity > 0.7f -> "✅"
                        result.similarity > 0.5f -> "🎯"
                        else -> "🤔"
                    },
                    fontSize = 64.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 제목
                Text(
                    text = when {
                        result.similarity > 0.7f -> "작품을 찾았습니다!"
                        result.similarity > 0.5f -> "유사한 작품을 찾았습니다"
                        else -> "낮은 유사도"
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 작품 ID
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF5F5F5)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "작품 ID",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = result.artworkId,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 유사도
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            result.similarity > 0.7f -> Color(0xFFE8F5E9)
                            result.similarity > 0.5f -> Color(0xFFFFF3E0)
                            else -> Color(0xFFFFEBEE)
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "유사도",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${(result.similarity * 100).toInt()}%",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                result.similarity > 0.7f -> Color(0xFF4CAF50)
                                result.similarity > 0.5f -> Color(0xFFFFA726)
                                else -> Color(0xFFEF5350)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 확인 버튼
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text(
                        text = "확인",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 스냅샷 캡처 함수
 * ESP32-CAM에서 이미지 가져오기
 */
suspend fun captureSnapshot(
    url: String,
    onSuccess: (Bitmap) -> Unit,
    onError: (String) -> Unit
) {
    withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            println("📸 스냅샷 요청: $url")

            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doInput = true

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                withContext(Dispatchers.Main) {
                    onError("연결 실패: HTTP $responseCode")
                }
                return@withContext
            }

            val inputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)

            if (bitmap != null) {
                println("✅ 스냅샷 캡처 성공: ${bitmap.width}x${bitmap.height}")
                withContext(Dispatchers.Main) {
                    onSuccess(bitmap)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onError("이미지 디코딩 실패")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onError("캡처 오류: ${e.message}")
            }
        } finally {
            connection?.disconnect()
        }
    }
}