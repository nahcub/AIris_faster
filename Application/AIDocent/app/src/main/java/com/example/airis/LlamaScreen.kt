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
fun LlamaScreen(onBackClick: () -> Unit = {}) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var modelLoaded by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready to load model.") }
    var userInput by remember { mutableStateOf("") }
    var generatedText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    
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
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 모델 로드 버튼들
        if (!modelLoaded) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        try {
                            val appFilesDir = context.getExternalFilesDir(null)
                            val modelFile = File(appFilesDir, "Llama-3.2-3B-Instruct-Q4_K_M.gguf")
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
                                statusText = "✅ Model loaded successfully!\n\nYou can now enter a prompt below."
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
            // 모델이 로드된 후 - 입력 필드와 생성 버튼
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
                        
                        isGenerating = true
                        generatedText = ""
                        statusText = "Generating response..."
                        
                        // 코루틴을 사용하여 백그라운드 스레드에서 생성 (5분 타임아웃)
                        coroutineScope.launch {
                            try {
                                Log.d("LlamaScreen", "Starting generation...")
                                val result = withTimeoutOrNull(300000) { // 5분 타임아웃 (각 토큰당 2-3초 소요)
                                    withContext(Dispatchers.Default) {
                                        Log.d("LlamaScreen", "Calling NativeBridge.generate...")
                                        val generated = NativeBridge.generate(userInput.trim())
                                        Log.d("LlamaScreen", "Generation completed, result length: ${generated.length}")
                                        generated
                                    }
                                }
                                
                                Log.d("LlamaScreen", "Result received: ${result != null}")
                                
                                if (result != null) {
                                    Log.d("LlamaScreen", "Updating UI with result")
                                    generatedText = result
                                    statusText = "✅ Response generated!"
                                    Log.d("LlamaScreen", "UI updated successfully")
                                } else {
                                    Log.w("LlamaScreen", "Generation timed out")
                                    statusText = "⏱️ Generation timed out after 5 minutes.\n\nGeneration is slow (2-3 sec per token).\nCheck logcat for details (filter: LlamaNative)"
                                    generatedText = ""
                                }
                            } catch (e: Exception) {
                                Log.e("LlamaScreen", "Error during generation", e)
                                statusText = "❌ Error: ${e.message}\n\nCheck logcat for details (filter: LlamaNative or LlamaScreen)"
                                generatedText = ""
                            } finally {
                                Log.d("LlamaScreen", "Finally block: setting isGenerating = false")
                                isGenerating = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isGenerating && userInput.isNotBlank()
                ) {
                    Text(if (isGenerating) "Generating..." else "Generate")
                }
                
                IconButton(
                    onClick = {
                        userInput = ""
                        generatedText = ""
                        statusText = "✅ Model loaded. Ready for new prompt."
                    },
                    enabled = !isGenerating
                ) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            }
        }
        
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

