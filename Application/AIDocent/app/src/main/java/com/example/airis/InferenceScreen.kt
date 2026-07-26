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
import java.io.File

private const val MODEL_FILE_NAME = "qwen3_0_6b_mixed_int4.litertlm"
// 벤치 기록용 모델 라벨은 파일명에서 파생 (확장자 무관하게 마지막 점 뒤 제거)
private val MODEL_NAME = MODEL_FILE_NAME.substringBeforeLast('.')

@Composable
fun InferenceScreen(
    autoInitialize: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var modelLoaded by remember { mutableStateOf(false) }
    val engine = remember { EngineFactory.create(EngineType.LITE_RT, context) }
    // 그림 인식 이음새. 지금은 고정 작품 하나를 반환하는 stub — 나중에 실제 인식 구현체로 교체.
    val artworkRecognizer = remember { FixedArtworkRecognizer() }
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
                engine.close()
                Log.d("InferenceScreen", "Session closed on screen dispose")
            }
        }
    }

    // 자동 초기화 로직
    LaunchedEffect(autoInitialize) {
        if (autoInitialize && !modelLoaded && !isAutoInitializing) {
            isAutoInitializing = true
            try {
                val appFilesDir = context.getExternalFilesDir(null)
                val modelFile = File(appFilesDir, MODEL_FILE_NAME)
                val path = modelFile.absolutePath
                
                if (!modelFile.exists()) {
                    statusText = "⚠️ File not found!\nCopy model to:\n$path"
                    isAutoInitializing = false
                    return@LaunchedEffect
                }
                
                statusText = "Loading model..."
                // 모델 로드는 백그라운드 스레드에서 실행
                val loaded = withContext(Dispatchers.Default) {
                    engine.loadModel(path)
                }
                
                if (loaded) {
                    modelLoaded = true
                    statusText = "✅ Model loaded!\n\nInitializing session..."

                    // 세션 초기화 (컨텍스트와 샘플러 생성)
                    val sessionInit = withContext(Dispatchers.Default) {
                        engine.initSession()
                    }

                    if (sessionInit) {
                        sessionInitialized = true
                        statusText = "✅ Session initialized!\n\nDecoding system prompt..."

                        // 인식된(지금은 고정) 작품을 엔진에 주입 → decode가 이 값으로 시스템 프롬프트 프리필
                        engine.setArtwork(artworkRecognizer.recognize())

                        // 시스템 프롬프트 디코딩
                        val decoded = withContext(Dispatchers.Default) {
                            engine.decodeSystemPrompt()
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
                Log.e("InferenceScreen", "Auto initialization error", e)
            } finally {
                isAutoInitializing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()   // edge-to-edge에서 상태바·내비게이션바에 안 가리도록
            .padding(12.dp),
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        // 모델 로드 버튼들 (자동 초기화 중이면 숨김)
        if (!modelLoaded && !isAutoInitializing) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        try {
                            val appFilesDir = context.getExternalFilesDir(null)
                            val modelFile = File(appFilesDir, MODEL_FILE_NAME)
                            val path = modelFile.absolutePath
                            
                            if (!modelFile.exists()) {
                                statusText = "⚠️ File not found!\nCopy model to:\n$path"
                                return@launch
                            }
                            
                            statusText = "Loading model..."
                            // 모델 로드는 백그라운드 스레드에서 실행
                            val loaded = withContext(Dispatchers.Default) {
                                engine.loadModel(path)
                            }
                            
                            if (loaded) {
                                modelLoaded = true
                                statusText = "✅ Model loaded!\n\nInitializing session..."

                                // 세션 초기화 (컨텍스트와 샘플러 생성)
                                val sessionInit = withContext(Dispatchers.Default) {
                                    engine.initSession()
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
                                engine.setArtwork(artworkRecognizer.recognize())
                                val decoded = withContext(Dispatchers.Default) {
                                    engine.decodeSystemPrompt()
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

                            // 측정은 BenchmarkRunner.runOnce에 위임 (reset → generate → 지표).
                            // UI는 토큰 스트리밍과 결과 표시만 담당.
                            coroutineScope.launch {
                                try {
                                    val outcome = BenchmarkRunner.runOnce(
                                        context = context,
                                        engine = engine,
                                        prompt = userInput.trim(),
                                        model = MODEL_NAME
                                    ) { token ->
                                        generatedText += token
                                    }

                                    val rec = outcome.record
                                    when {
                                        rec != null -> {
                                            statusText = "✅ Response generated!"
                                            generationStats = String.format(
                                                "TTFT: %.2fs | Decode: %.1f tok/s | Total: %.2fs | Tokens: %d",
                                                rec.ttftSec, rec.decodeTokPerSec, rec.totalSec, rec.tokenCount
                                            )
                                            withContext(Dispatchers.IO) {
                                                BenchmarkLogger.append(context, rec)
                                            }
                                        }
                                        outcome.timedOut ->
                                            statusText = "⏱️ Generation timed out after 5 minutes.\n\nlogcat 확인 (filter: LlamaNative)"
                                        else ->
                                            statusText = "❌ Generation failed. logcat 확인 (filter: LlamaNative)"
                                    }
                                } catch (e: Exception) {
                                    Log.e("InferenceScreen", "Error during generation", e)
                                    statusText = "❌ Error: ${e.message}\n\nlogcat 확인 (filter: LlamaNative or InferenceScreen)"
                                } finally {
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

                Spacer(modifier = Modifier.height(12.dp))

                // 자동 반복 실험: 프롬프트셋 × (warmup + 반복)을 한 번에 돌려 results.jsonl에 기록.
                // 사람이 버튼을 여러 번 누를 필요가 없어짐 = 확장 가능한 측정의 진입점.
                Button(
                    onClick = {
                        isGenerating = true
                        generatedText = ""
                        generationStats = null
                        coroutineScope.launch {
                            try {
                                val saved = BenchmarkRunner.runSuite(
                                    context = context,
                                    engine = engine,
                                    model = MODEL_NAME,
                                    onProgress = { done, total, label ->
                                        statusText = "🧪 Suite $done/$total ($label)"
                                    }
                                )
                                statusText = "✅ Suite 완료! results.jsonl에 ${saved}건 저장됨"
                            } catch (e: Exception) {
                                Log.e("InferenceScreen", "Suite error", e)
                                statusText = "❌ Suite 오류: ${e.message}"
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Text("🧪 Run Suite (${BenchmarkRunner.DEFAULT_PROMPTS.size} prompts × 5)")
                }
        } // if (systemPromptDecoded) 블록 닫기
        } // else 블록 닫기
    }
}

