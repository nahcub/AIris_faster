package com.example.airis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// --- 1. 상태 관리와 로직을 담당하는 Composable ---

@Composable
fun CameraPreviewScreen(
    onBackClick: () -> Unit = {}
) {
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 스트리밍 시작/중지 로직
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            streamMjpeg(
                url = "http://192.168.4.1:80/stream",
                onFrame = { bitmap ->
                    previewBitmap = bitmap
                    errorMessage = null
                },
                onError = { error ->
                    errorMessage = error
                    isStreaming = false
                }
            )
        }
    }

    // 분리된 UI Composable을 호출하여 현재 상태를 전달
    CameraPreviewContent(
        previewBitmap = previewBitmap,
        isStreaming = isStreaming,
        errorMessage = errorMessage,
        onStreamButtonClick = {
            isStreaming = !isStreaming
            if (!isStreaming) {
                previewBitmap = null
                errorMessage = null
            }
        },
        onBackClick = {
            isStreaming = false // 뒤로가기 시 스트리밍 중지
            onBackClick()
        }
    )
}


// --- 2. UI 표시에만 집중하는 Stateless Composable ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewContent(
    previewBitmap: Bitmap?,
    isStreaming: Boolean,
    errorMessage: String?,
    onStreamButtonClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "카메라 프리뷰",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = "뒤로가기",
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
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "카메라 프리뷰",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else if (errorMessage != null) {
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
                                fontSize = 16.sp
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (isStreaming) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "카메라 연결 중...",
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                            } else {
                                Text(
                                    text = "📷",
                                    fontSize = 48.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "스트리밍을 시작하세요",
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 시작/중지 버튼
                Button(
                    onClick = onStreamButtonClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isStreaming) Color(0xFFDC3545) else Color(0xFF4CAF50),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = if (isStreaming) "스트리밍 중지" else "스트리밍 시작",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// --- 3. MJPEG 스트리밍 관련 함수들 (기존 코드와 동일) ---

suspend fun streamMjpeg(
    url: String,
    onFrame: (Bitmap) -> Unit,
    onError: (String) -> Unit
) {
    withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 30000
            connection.doInput = true

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                withContext(Dispatchers.Main) {
                    onError("연결 실패: HTTP $responseCode")
                }
                return@withContext
            }

            val inputStream = connection.inputStream
            val boundary = extractBoundary(connection.contentType)

            if (boundary == null) {
                withContext(Dispatchers.Main) {
                    onError("잘못된 MJPEG 형식")
                }
                return@withContext
            }

            while (isActive) {
                val frame = readMjpegFrame(inputStream, boundary)
                if (frame != null) {
                    val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            onFrame(bitmap)
                        }
                    }
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onError("스트리밍 오류: ${e.message}")
            }
        } finally {
            connection?.disconnect()
        }
    }
}

private fun extractBoundary(contentType: String?): String? {
    if (contentType == null) return null
    val parts = contentType.split("boundary=")
    return if (parts.size == 2) parts[1].trim() else null
}

private fun readMjpegFrame(inputStream: InputStream, boundary: String): ByteArray? {
    try {
        skipToBoundary(inputStream, boundary)
        var contentLength = -1
        while (true) {
            val line = readLine(inputStream) ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength <= 0) return null
        val jpegData = ByteArray(contentLength)
        var totalRead = 0
        while (totalRead < contentLength) {
            val read = inputStream.read(jpegData, totalRead, contentLength - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        return jpegData
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}

private fun skipToBoundary(inputStream: InputStream, boundary: String) {
    val boundaryBytes = "--$boundary".toByteArray()
    val buffer = ByteArray(boundaryBytes.size)
    var pos = 0
    while (true) {
        val b = inputStream.read()
        if (b == -1) break
        buffer[pos] = b.toByte()
        pos = (pos + 1) % buffer.size
        var match = true
        for (i in boundaryBytes.indices) {
            if (buffer[(pos + i) % buffer.size] != boundaryBytes[i]) {
                match = false
                break
            }
        }
        if (match) {
            readLine(inputStream)
            return
        }
    }
}

private fun readLine(inputStream: InputStream): String? {
    val buffer = ByteArrayOutputStream()
    var prev = 0
    while (true) {
        val b = inputStream.read()
        if (b == -1) {
            return if (buffer.size() > 0) buffer.toString("UTF-8") else null
        }
        if (prev == '\r'.code && b == '\n'.code) {
            val str = buffer.toString("UTF-8")
            return str.substring(0, str.length - 1)
        }
        buffer.write(b)
        prev = b
    }
}


// --- 4. 상태별 Preview 함수들 ---

@Preview(showBackground = true, showSystemUi = true, name = "Default State")
@Composable
fun CameraPreview_Default_Preview() {
    CameraPreviewContent(
        previewBitmap = null,
        isStreaming = false,
        errorMessage = null,
        onStreamButtonClick = {},
        onBackClick = {}
    )
}

@Preview(showBackground = true, showSystemUi = true, name = "Streaming Loading")
@Composable
fun CameraPreview_Loading_Preview() {
    CameraPreviewContent(
        previewBitmap = null,
        isStreaming = true,
        errorMessage = null,
        onStreamButtonClick = {},
        onBackClick = {}
    )
}

@Preview(showBackground = true, showSystemUi = true, name = "Error State")
@Composable
fun CameraPreview_Error_Preview() {
    CameraPreviewContent(
        previewBitmap = null,
        isStreaming = false,
        errorMessage = "연결에 실패했습니다.",
        onStreamButtonClick = {},
        onBackClick = {}
    )
}

@Preview(showBackground = true, showSystemUi = true, name = "Image Preview")
@Composable
fun CameraPreview_Image_Preview() {
    val dummyBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.MAGENTA)
    }
    CameraPreviewContent(
        previewBitmap = dummyBitmap,
        isStreaming = true,
        errorMessage = null,
        onStreamButtonClick = {},
        onBackClick = {}
    )
}