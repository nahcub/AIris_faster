// 자동화의 '끝났다' 신호.
// 스크립트는 언제 adb pull을 해도 되는지 알아야 하는데, 화면 픽셀로는 판단할 수 없다.
// 두 경로를 모두 남긴다:
//   - logcat  : adb logcat -m 1 -e SUITE_DONE 으로 대기
//   - 마커 파일: benchmarks/last_run.txt 를 adb shell cat 으로 폴링
//     (logcat 대기는 '앱 시작 전에 logcat을 걸어놨어야 한다'는 레이스가 있어서, 파일 폴링이 더 견고하다.
//      스크립트는 앱을 띄우기 전에 adb shell rm -f 로 지우고 시작하면 된다)
// ⚠️ 실패도 반드시 신호를 남긴다. 안 그러면 스크립트가 영원히 기다린다.
package com.example.airis

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

object BenchSignal {
    const val TAG = "AirisBench"

    fun suiteDone(context: Context, saved: Int, model: String, backend: String) =
        emit(context, "SUITE_DONE saved=$saved model=$model backend=$backend")

    fun suiteFailed(context: Context, reason: String) =
        emit(context, "SUITE_FAILED reason=$reason")

    fun modelNotFound(context: Context, name: String) =
        emit(context, "MODEL_NOT_FOUND name=$name")

    // logcat + 마커 파일에 같은 한 줄.
    // BenchmarkLogger와 같은 benchmarks/ 디렉토리를 쓴다(회수도 같은 폴더에서 끝나게).
    private fun emit(context: Context, line: String) {
        val stamped = "$line ts=${Instant.now()}"
        Log.i(TAG, stamped)
        try {
            val dir = File(context.getExternalFilesDir(null), "benchmarks")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "last_run.txt").writeText(stamped + "\n")
        } catch (e: Exception) {
            // 파일 기록이 실패해도 logcat 신호는 이미 나갔다 — 측정을 깨지 않는다.
            Log.w(TAG, "failed to write last_run.txt", e)
        }
    }
}
