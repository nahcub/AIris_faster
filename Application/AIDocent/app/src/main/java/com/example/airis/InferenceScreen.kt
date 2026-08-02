package com.example.airis

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "InferenceScreen"

// 이 화면이 쓰는 추론 엔진. EngineFactory(엔진 생성)와 ModelCatalog(스캔할 모델 확장자)가
// 함께 참조하므로, 엔진 교체는 여전히 이 한 줄이다.
private val ENGINE_TYPE = EngineType.LITE_RT

// loadAndInit 한 번의 결과. '어디까지 갔는지'를 UI 상태로 그대로 옮기기 위한 묶음.
private data class InitProgress(
    val modelLoaded: Boolean = false,
    val sessionInitialized: Boolean = false,
    val systemPromptDecoded: Boolean = false,
    val error: String? = null
)

@Composable
fun InferenceScreen(
    autoInitialize: Boolean = false,
    autoRun: AutoRunRequest = AutoRunRequest.MANUAL
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { EngineFactory.create(ENGINE_TYPE, context) }
    // 그림 인식 이음새. 지금은 고정 작품 하나를 반환하는 stub — 나중에 실제 인식 구현체로 교체.
    val artworkRecognizer = remember { FixedArtworkRecognizer() }

    // 기기에 push된 모델 목록과 사용자가 고른 것. 모델 파일명이 더 이상 소스에 박혀 있지 않다.
    var models by remember { mutableStateOf(ModelCatalog.scan(context, ENGINE_TYPE)) }
    var selectedModel by remember { mutableStateOf<ModelFile?>(null) }

    var modelLoaded by remember { mutableStateOf(false) }
    var sessionInitialized by remember { mutableStateOf(false) }
    var systemPromptDecoded by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("모델을 고르세요.") }
    var userInput by remember { mutableStateOf("") }
    var generatedText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generationStats by remember { mutableStateOf<String?>(null) }
    var isLoadingModel by remember { mutableStateOf(false) }

    // 화면 종료 시 세션 정리
    DisposableEffect(Unit) {
        onDispose {
            if (sessionInitialized) {
                engine.close()
                Log.d(TAG, "Session closed on screen dispose")
            }
        }
    }

    // 자동 실행 경로(adb 스크립트). 엑스트라가 없으면 아무것도 하지 않고 수동 선택 화면이 뜬다.
    // 사람이 누르는 버튼과 같은 헬퍼(loadAndInit/runSuiteAndReport)를 쓰는 게 핵심 —
    // 자동화 전용 코드 경로를 따로 두면 둘이 서서히 어긋난다.
    LaunchedEffect(autoRun) {
        if (autoRun.isManual) return@LaunchedEffect
        val name = autoRun.modelFileName ?: return@LaunchedEffect

        val model = ModelCatalog.findByName(context, ENGINE_TYPE, name)
        if (model == null) {
            statusText = "❌ 모델을 찾을 수 없습니다: $name\n\n${ModelCatalog.directoryPath(context)}"
            BenchSignal.modelNotFound(context, name)
            return@LaunchedEffect
        }

        selectedModel = model
        isLoadingModel = true
        try {
            val progress = loadAndInit(engine, artworkRecognizer, model, decodeNow = true) {
                statusText = it
            }
            modelLoaded = progress.modelLoaded
            sessionInitialized = progress.sessionInitialized
            systemPromptDecoded = progress.systemPromptDecoded

            if (!progress.systemPromptDecoded) {
                statusText = progress.error ?: "❌ 초기화 실패"
                BenchSignal.suiteFailed(context, "init_failed")
                return@LaunchedEffect
            }
            statusText = readyText(model)

            if (autoRun.runSuite) {
                isGenerating = true
                val saved = runSuiteAndReport(
                    context, engine, model, autoRun.repeats, autoRun.warmups
                ) { done, total, label ->
                    statusText = "🧪 Suite $done/$total ($label)"
                }
                statusText = "✅ Suite 완료! results.jsonl에 ${saved}건 저장됨"
            }
        } catch (e: Exception) {
            Log.e(TAG, "auto run failed", e)
            statusText = "❌ 자동 실행 오류: ${e.message}"
            BenchSignal.suiteFailed(context, e.message ?: "exception")
        } finally {
            isLoadingModel = false
            isGenerating = false
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

        if (!modelLoaded) {
            // 모델 선택 화면. 로드는 1회뿐이라 모델을 바꾸려면 앱을 재시작한다
            // (자동화도 모델마다 `am start -S`로 새로 띄우므로 측정 조건이 같아진다).
            ModelPicker(
                models = models,
                selected = selectedModel,
                enabled = !isLoadingModel,
                emptyHint = ".${ModelCatalog.extensionFor(ENGINE_TYPE)} 모델이 없습니다.\n" +
                        "adb push로 아래 경로에 올리세요:\n${ModelCatalog.directoryPath(context)}",
                onSelect = { selectedModel = it },
                onRefresh = { models = ModelCatalog.scan(context, ENGINE_TYPE) },
                onLoad = {
                    val model = selectedModel ?: return@ModelPicker
                    isLoadingModel = true
                    coroutineScope.launch {
                        try {
                            val progress = loadAndInit(
                                engine, artworkRecognizer, model, decodeNow = autoInitialize
                            ) { statusText = it }

                            modelLoaded = progress.modelLoaded
                            sessionInitialized = progress.sessionInitialized
                            systemPromptDecoded = progress.systemPromptDecoded
                            statusText = progress.error ?: when {
                                progress.systemPromptDecoded -> readyText(model)
                                else -> "📦 ${model.label}\n\n✅ Session initialized!\n\n" +
                                        "Click 'Decode System Prompt' to cache artwork info."
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "load failed", e)
                            statusText = "Error: ${e.message}"
                        } finally {
                            isLoadingModel = false
                        }
                    }
                }
            )
        } else {
            // 시스템 프롬프트를 아직 안 캐싱한 경우(수동 단계별 실행)
            if (sessionInitialized && !systemPromptDecoded) {
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
                                    statusText = selectedModel?.let { readyText(it) }
                                        ?: "✅ System prompt cached!"
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
                            val model = selectedModel ?: return@Button

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
                                        model = model.label   // 벤치 라벨 = 고른 파일명
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
                                    Log.e(TAG, "Error during generation", e)
                                    statusText = "❌ Error: ${e.message}\n\nlogcat 확인 (filter: LlamaNative or InferenceScreen)"
                                } finally {
                                    isGenerating = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isGenerating && userInput.isNotBlank() && systemPromptDecoded
                    ) {
                        Text(if (isGenerating) "⚡ Generating..." else "⚡ Generate (Fast)")
                    }

                    IconButton(
                        onClick = {
                            userInput = ""
                            generatedText = ""
                            generationStats = null
                            statusText = selectedModel?.let { readyText(it) }
                                ?: "✅ System prompt cached."
                        },
                        enabled = !isGenerating
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 자동 반복 실험: 프롬프트셋 × (warmup + 반복)을 한 번에 돌려 results.jsonl에 기록.
                // 자동 실행(adb)도 같은 runSuiteAndReport를 부른다.
                Button(
                    onClick = {
                        val model = selectedModel ?: return@Button
                        isGenerating = true
                        generatedText = ""
                        generationStats = null
                        coroutineScope.launch {
                            try {
                                val saved = runSuiteAndReport(
                                    context, engine, model, repeats = null, warmups = null
                                ) { done, total, label ->
                                    statusText = "🧪 Suite $done/$total ($label)"
                                }
                                statusText = "✅ Suite 완료! results.jsonl에 ${saved}건 저장됨"
                            } catch (e: Exception) {
                                Log.e(TAG, "Suite error", e)
                                statusText = "❌ Suite 오류: ${e.message}"
                                BenchSignal.suiteFailed(context, e.message ?: "exception")
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
                    Text(
                        "🧪 Run Suite (${BenchmarkRunner.DEFAULT_PROMPTS.size} prompts × " +
                                "${BenchmarkRunner.DEFAULT_REPEATS})"
                    )
                }
            }
        }
    }
}

// 기기에서 찾은 모델 목록을 라디오 버튼으로 고르는 화면.
// 목록이 비면 push 경로를 안내한다(새로고침으로 앱 재시작 없이 다시 스캔).
@Composable
private fun ModelPicker(
    models: List<ModelFile>,
    selected: ModelFile?,
    enabled: Boolean,
    emptyHint: String,
    onSelect: (ModelFile) -> Unit,
    onRefresh: () -> Unit,
    onLoad: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (models.isEmpty()) {
            Text(
                text = "⚠️ $emptyHint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup()
            ) {
                models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = model == selected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onSelect(model) }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = model == selected,
                            onClick = null,   // Row의 selectable이 클릭을 받는다
                            enabled = enabled
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(model.fileName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${model.sizeMb} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onRefresh, enabled = enabled) {
                Text("🔄 새로고침")
            }
            Button(
                onClick = onLoad,
                enabled = enabled && selected != null,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (enabled) "Load Model" else "Loading...")
            }
        }
    }
}

// 모델 로드 → 세션 → (선택적으로) 시스템 프롬프트 캐싱까지의 한 체인.
// 사람이 Load 버튼을 누른 경로와 adb 자동 실행 경로가 이 함수 하나를 공유한다.
private suspend fun loadAndInit(
    engine: InferenceEngine,
    recognizer: ArtworkRecognizer,
    model: ModelFile,
    decodeNow: Boolean,
    onStatus: (String) -> Unit
): InitProgress {
    // 목록을 스캔한 뒤 파일이 지워졌을 수 있으므로 로드 직전에 한 번 더 확인
    if (!model.file.exists()) {
        return InitProgress(error = "⚠️ 파일이 없습니다:\n${model.file.absolutePath}")
    }

    onStatus("📦 ${model.label}\n\nLoading model...")
    val loaded = withContext(Dispatchers.Default) { engine.loadModel(model.file.absolutePath) }
    if (!loaded) {
        return InitProgress(error = "❌ Failed to load model.\nCheck logcat for details.")
    }

    onStatus("📦 ${model.label}\n\n✅ Model loaded!\n\nInitializing session...")
    val session = withContext(Dispatchers.Default) { engine.initSession() }
    if (!session) {
        return InitProgress(
            modelLoaded = true,
            error = "❌ Failed to initialize session.\nCheck logcat for details."
        )
    }

    if (!decodeNow) return InitProgress(modelLoaded = true, sessionInitialized = true)

    onStatus("📦 ${model.label}\n\n✅ Session initialized!\n\nDecoding system prompt...")
    // 인식된(지금은 고정) 작품을 엔진에 주입 → decode가 이 값으로 시스템 프롬프트 프리필
    engine.setArtwork(recognizer.recognize())
    val decoded = withContext(Dispatchers.Default) { engine.decodeSystemPrompt() }
    if (!decoded) {
        return InitProgress(
            modelLoaded = true,
            sessionInitialized = true,
            error = "❌ Failed to decode system prompt.\nCheck logcat for details."
        )
    }

    return InitProgress(modelLoaded = true, sessionInitialized = true, systemPromptDecoded = true)
}

// Suite 실행 + 완료 신호. 버튼과 자동 실행이 공유하는 지점이라 버튼 람다 밖에 둔다.
// repeats/warmups가 null이면 BenchmarkRunner의 기본값을 쓴다(자동화가 --ei로 덮어쓸 수 있음).
private suspend fun runSuiteAndReport(
    context: Context,
    engine: InferenceEngine,
    model: ModelFile,
    repeats: Int?,
    warmups: Int?,
    onProgress: (done: Int, total: Int, label: String) -> Unit
): Int {
    val saved = BenchmarkRunner.runSuite(
        context = context,
        engine = engine,
        model = model.label,
        repeats = repeats ?: BenchmarkRunner.DEFAULT_REPEATS,
        warmups = warmups ?: BenchmarkRunner.DEFAULT_WARMUPS,
        onProgress = onProgress
    )
    BenchSignal.suiteDone(context, saved, model.label, engine.backend)
    return saved
}

// 어떤 모델로 재고 있는지가 화면에 늘 보이게 — 벤치 라벨과 같은 이름을 쓴다.
private fun readyText(model: ModelFile): String =
    "📦 ${model.label}\n\n✅ System prompt cached!\n\n⚡ Ready for fast generation!\n\nYou can now ask questions."
