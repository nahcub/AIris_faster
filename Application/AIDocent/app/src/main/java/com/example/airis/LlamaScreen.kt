package com.example.airis

import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

@Composable
fun LlamaScreen(
    onBackClick: () -> Unit = {},
    autoInitialize: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var modelLoaded by remember { mutableStateOf(false) }
    var sessionInitialized by remember { mutableStateOf(false) }
    var systemPromptDecoded by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready to load model.") }
    var userInput by remember { mutableStateOf("") }
    var generatedText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generationStats by remember { mutableStateOf<String?>(null) }
    var isAutoInitializing by remember { mutableStateOf(false) }

    // 화면 종료 시 세션 정리
    DisposableEffect(Unit) {
        onDispose {
            if (sessionInitialized) {
                NativeBridge.closeSession()
                Log.d("LlamaScreen", "Session closed on screen dispose")
            }
        }
    }

    // 자동 초기화 로직
    LaunchedEffect(autoInitialize) {
        if (autoInitialize && !modelLoaded && !isAutoInitializing) {
            isAutoInitializing = true
            try {
                val appFilesDir = context.getExternalFilesDir(null)
                val modelFile = File(appFilesDir, "Qwen3-0.6B-IQ4_NL.gguf")
                val path = modelFile.absolutePath
                
                if (!modelFile.exists()) {
                    statusText = "⚠️ File not found!\nCopy model to:\n$path"
                    isAutoInitializing = false
                    return@LaunchedEffect
                }
                
                statusText = "Loading model..."
                // 모델 로드는 백그라운드 스레드에서 실행
                val loaded = withContext(Dispatchers.Default) {
                    NativeBridge.loadModel(path)
                }
                
                if (loaded) {
                    modelLoaded = true
                    statusText = "✅ Model loaded!\n\nInitializing session..."

                    // 세션 초기화 (컨텍스트와 샘플러 생성)
                    val sessionInit = withContext(Dispatchers.Default) {
                        NativeBridge.initSession()
                    }

                    if (sessionInit) {
                        sessionInitialized = true
                        statusText = "✅ Session initialized!\n\nDecoding system prompt..."

                        // 시스템 프롬프트 디코딩
                        val decoded = withContext(Dispatchers.Default) {
                            NativeBridge.decodeSystemPrompt()
                        }
                        
                        if (decoded) {
                            systemPromptDecoded = true
                            statusText = "✅ System prompt cached!\n\n⚡ Ready for fast generation!\n\nYou can now ask questions."
                        } else {
                            statusText = "❌ Failed to decode system prompt.\nCheck logcat for details."
                        }
                    } else {
                        statusText = "❌ Failed to initialize session.\nCheck logcat for details."
                    }
                } else {
                    statusText = "❌ Failed to load model.\nCheck logcat for details."
                }
            } catch (e: Exception) {
                statusText = "Error: ${e.message}"
                Log.e("LlamaScreen", "Auto initialization error", e)
            } finally {
                isAutoInitializing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 상태 표시
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium
                )
                
                if (generatedText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = generatedText,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                if (generationStats != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = generationStats!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 모델 로드 버튼들 (자동 초기화 중이면 숨김)
        if (!modelLoaded && !isAutoInitializing) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        try {
                            val appFilesDir = context.getExternalFilesDir(null)
                            val modelFile = File(appFilesDir, "Qwen3-0.6B-IQ4_NL.gguf")
                            val path = modelFile.absolutePath
                            
                            if (!modelFile.exists()) {
                                statusText = "⚠️ File not found!\nCopy model to:\n$path"
                                return@launch
                            }
                            
                            statusText = "Loading model..."
                            // 모델 로드는 백그라운드 스레드에서 실행
                            val loaded = withContext(Dispatchers.Default) {
                                NativeBridge.loadModel(path)
                            }
                            
                            if (loaded) {
                                modelLoaded = true
                                statusText = "✅ Model loaded!\n\nInitializing session..."

                                // 세션 초기화 (컨텍스트와 샘플러 생성)
                                val sessionInit = withContext(Dispatchers.Default) {
                                    NativeBridge.initSession()
                                }

                                if (sessionInit) {
                                    sessionInitialized = true
                                    statusText = "✅ Session initialized!\n\nClick 'Decode System Prompt' to cache artwork info for faster generation."
                                } else {
                                    statusText = "❌ Failed to initialize session.\nCheck logcat for details."
                                }
                            } else {
                                statusText = "❌ Failed to load model.\nCheck logcat for details."
                            }
                        } catch (e: Exception) {
                            statusText = "Error: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load Model from App Storage")
            }
        } else {
            // 모델이 로드된 후 (자동 초기화 중이면 숨김)
            if (sessionInitialized && !systemPromptDecoded && !isAutoInitializing) {
                // 시스템 프롬프트 디코딩 버튼
                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                statusText = "Decoding system prompt (artwork info)..."
                                val decoded = withContext(Dispatchers.Default) {
                                    NativeBridge.decodeSystemPrompt()
                                }
                                
                                if (decoded) {
                                    systemPromptDecoded = true
                                    statusText = "✅ System prompt cached!\n\n⚡ Ready for fast generation!\n\nYou can now ask questions."
                                } else {
                                    statusText = "❌ Failed to decode system prompt.\nCheck logcat for details."
                                }
                            } catch (e: Exception) {
                                statusText = "Error: ${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Decode System Prompt (Cache Artwork Info)")
                }
                
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // 시스템 프롬프트가 디코딩된 후에만 입력 필드와 생성 버튼 표시
            if (systemPromptDecoded) {
                OutlinedTextField(
                    value = userInput,
                    onValueChange = { userInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Enter your prompt") },
                    placeholder = { Text("Type your question here...") },
                    enabled = !isGenerating,
                    singleLine = false,
                    maxLines = 4
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (userInput.isBlank()) return@Button

                            if (!sessionInitialized) {
                                statusText = "❌ Session not initialized!"
                                return@Button
                            }
                            
                            if (!systemPromptDecoded) {
                                statusText = "❌ System prompt not decoded! Click 'Decode System Prompt' first."
                                return@Button
                            }

                            isGenerating = true
                            generatedText = ""
                            statusText = "⚡ Generating response (fast mode)..."
                            generationStats = null

                            // 코루틴을 사용하여 백그라운드 스레드에서 스트리밍 생성
                            coroutineScope.launch {
                                try {
                                    Log.d("LlamaScreen", "Starting streaming generation (session-based)...")
                                    val startTime = System.currentTimeMillis()
                                    var tokenCount = 0

                                    val success = withTimeoutOrNull(300000) { // 5분 타임아웃
                                        withContext(Dispatchers.Default) {
                                            Log.d("LlamaScreen", "Calling NativeBridge.generateStreaming with session...")
                                            NativeBridge.generateStreaming(userInput.trim()) { token ->
                                                // 토큰이 생성될 때마다 UI 업데이트
                                                Log.d("LlamaScreen", "Received token: $token")
                                                generatedText += token
                                                tokenCount++
                                            }
                                        }
                                    }

                                    val endTime = System.currentTimeMillis()
                                    val elapsedSeconds = (endTime - startTime) / 1000.0
                                    val tokensPerSecond = if (elapsedSeconds > 0) tokenCount / elapsedSeconds else 0.0

                                    Log.d("LlamaScreen", "Streaming completed: ${success != null}")

                                    if (success == true) {
                                        Log.d("LlamaScreen", "Streaming generation successful")
                                        statusText = "✅ Response generated!"
                                        generationStats = String.format(
                                            "⏱️ Total time: %.2fs | Tokens: %d | Speed: %.2f tokens/sec",
                                            elapsedSeconds,
                                            tokenCount,
                                            tokensPerSecond
                                        )
                                    } else if (success == false) {
                                        Log.w("LlamaScreen", "Streaming generation failed")
                                        statusText = "❌ Generation failed. Check logcat for details (filter: LlamaNative)"
                                    } else {
                                        Log.w("LlamaScreen", "Generation timed out")
                                        statusText = "⏱️ Generation timed out after 5 minutes.\n\nCheck logcat for details (filter: LlamaNative)"
                                    }
                                } catch (e: Exception) {
                                    Log.e("LlamaScreen", "Error during generation", e)
                                    statusText = "❌ Error: ${e.message}\n\nCheck logcat for details (filter: LlamaNative or LlamaScreen)"
                                } finally {
                                    Log.d("LlamaScreen", "Finally block: setting isGenerating = false")
                                    isGenerating = false
                                }
                            }
                        },
                    modifier = Modifier.weight(1f),
                    enabled = !isGenerating && userInput.isNotBlank() && sessionInitialized && systemPromptDecoded
                ) {
                    Text(if (isGenerating) "⚡ Generating..." else "⚡ Generate (Fast)")
                }
                
                IconButton(
                    onClick = {
                        userInput = ""
                        generatedText = ""
                        generationStats = null
                        statusText = "✅ System prompt cached. ⚡ Fast mode enabled!"
                    },
                    enabled = !isGenerating
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            } // Row 블록 닫기
        } // if (systemPromptDecoded) 블록 닫기
        } // else 블록 닫기
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Text("뒤로가기")
        }
    }
}

