package com.example.airis

import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun LlamaScreen(onBackClick: () -> Unit = {}) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("Ready to load model.\n\nChoose storage location:") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        // 앱 전용 저장소 사용 버튼 (권장)
        Button(
            onClick = {
                try {
                    // 앱 전용 외부 저장소 사용 (권한 불필요)
                    val appFilesDir = context.getExternalFilesDir(null)
                    val modelFile = File(appFilesDir, "Qwen3-1.7B-Q4_K_M.gguf")
                    val path = modelFile.absolutePath
                    
                    text = "Checking file in app storage...\n" +
                           "Path: $path\n" +
                           "Exists: ${modelFile.exists()}\n" +
                           "Readable: ${if (modelFile.exists()) modelFile.canRead() else false}\n" +
                           "Size: ${if (modelFile.exists()) modelFile.length() else 0} bytes"
                    
                    if (!modelFile.exists()) {
                        text += "\n\n⚠️ File not found in app storage.\n" +
                               "Copy your model file to:\n$path\n\n" +
                               "You can copy from Downloads using a file manager."
                        return@Button
                    }
                    
                    text += "\n\nLoading model..."
                    val loaded = NativeBridge.loadModel(path)
                    
                    if (loaded) {
                        text += "\n✅ Model loaded!\nGenerating..."
                        val result = NativeBridge.generate("Hello Qwen!")
                        text = result
                    } else {
                        text += "\n\n❌ Failed to load model.\nCheck logcat for details."
                    }
                } catch (e: Exception) {
                    text = "Error: ${e.message}\n${e.stackTraceToString()}"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Load from App Storage (Recommended)")
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Public Downloads 폴더 시도 버튼
        Button(
            onClick = {
                try {
                    // Public Downloads 폴더 사용 (권한 필요)
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val modelFile = File(downloadsDir, "Qwen3-1.7B-Q4_K_M.gguf")
                    val path = modelFile.absolutePath
                    
                    val fileExists = modelFile.exists()
                    val fileReadable = modelFile.canRead()
                    
                    text = "Checking file in Downloads...\n" +
                           "Path: $path\n" +
                           "Exists: $fileExists\n" +
                           "Readable: $fileReadable\n" +
                           "Size: ${if (fileExists) modelFile.length() else 0} bytes"
                    
                    if (!fileExists) {
                        text += "\n\n❌ File does not exist!\nPlease ensure the model file is in Downloads folder."
                        return@Button
                    }
                    
                    if (!fileReadable) {
                        text += "\n\n❌ File is not readable!\n" +
                               "Android 10+ restricts access to Downloads folder.\n" +
                               "Please copy the file to app storage instead:\n" +
                               "${context.getExternalFilesDir(null)}/Qwen3-1.7B-Q4_K_M.gguf"
                        return@Button
                    }
                    
                    text += "\n\nLoading model..."
                    val loaded = NativeBridge.loadModel(path)
                    
                    if (loaded) {
                        text += "\n✅ Model loaded!\nGenerating..."
                        val result = NativeBridge.generate("Hello Qwen!")
                        text = result
                    } else {
                        text += "\n\n❌ Failed to load model.\nCheck logcat for details."
                    }
                } catch (e: Exception) {
                    text = "Error: ${e.message}\n${e.stackTraceToString()}"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Text("Try Downloads Folder")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBackClick) {
            Text("뒤로가기")
        }
    }
}

