// 기기에 push된 모델 파일을 찾아주는 창구.
// EngineFactory가 '어떤 엔진을 쓸지' 고르는 창구였다면, 이건 '어떤 모델 파일을 쓸지' 고르는 창구다.
// 이게 생기기 전엔 파일명이 InferenceScreen의 MODEL_FILE_NAME 상수에 박혀 있어서
// 대조군 ↔ LoRA본을 바꿔 재려면 소스를 고치고 재빌드해야 했다.
package com.example.airis

import android.content.Context
import java.io.File

// 고를 수 있는 모델 파일 하나.
data class ModelFile(val file: File) {
    val fileName: String get() = file.name

    // 벤치 라벨(BenchmarkRecord.model)로 그대로 들어간다 — 확장자만 뗀 이름.
    val label: String get() = fileName.substringBeforeLast('.')

    val sizeMb: Long get() = file.length() / (1024 * 1024)
}

object ModelCatalog {

    // 엔진과 모델 포맷은 짝이다(LITE_RT ↔ .litertlm, LLAMA_CPP ↔ .gguf).
    // when이 exhaustive라 엔진이 늘면 컴파일러가 여기를 짚어준다.
    fun extensionFor(type: EngineType): String = when (type) {
        EngineType.LITE_RT -> "litertlm"
        EngineType.LLAMA_CPP -> "gguf"
    }

    // 앱 전용 외부 저장소(= adb push 목적지)를 훑어, 해당 엔진이 먹을 수 있는 파일만 이름순으로.
    // benchmarks/ 같은 하위 디렉토리나 확장자가 다른 파일은 걸러진다.
    fun scan(context: Context, type: EngineType): List<ModelFile> {
        val dir = context.getExternalFilesDir(null) ?: return emptyList()
        val ext = extensionFor(type)
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals(ext, ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { ModelFile(it) }
            ?: emptyList()
    }

    // 자동화 진입점: 스크립트가 인텐트로 준 파일명을 목록에서 찾는다.
    // 경로를 따로 조립하지 않고 scan()을 거치는 게 핵심 — 자동화와 UI가 같은 목록을 본다.
    fun findByName(context: Context, type: EngineType, fileName: String): ModelFile? =
        scan(context, type).find { it.fileName == fileName }

    // 모델이 하나도 없을 때 "여기에 push하세요" 안내용.
    fun directoryPath(context: Context): String =
        context.getExternalFilesDir(null)?.absolutePath ?: "(외부 저장소를 쓸 수 없음)"
}
