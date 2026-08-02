// adb 스크립트가 앱을 무인 실행할 때 쓰는 진입 규약.
// 좌표 탭(adb shell input tap)은 레이아웃이 조금만 바뀌어도 깨지므로, 인텐트 엑스트라를 손잡이로 쓴다.
//
//   adb shell am start -S -n com.example.airis/.MainActivity \
//     -e model gemma-4-E2B-it-int4.litertlm -e autorun suite --ei repeats 5
//
// 엑스트라 파싱을 여기 한 곳에 가둬서 Activity/UI가 문자열 키를 흩뿌리지 않게 한다.
package com.example.airis

import android.content.Intent

data class AutoRunRequest(
    val modelFileName: String?,  // -e model <파일명>. 없으면 사람이 화면에서 고른다
    val runSuite: Boolean,       // -e autorun suite
    val repeats: Int?,           // --ei repeats N (없으면 BenchmarkRunner 기본값)
    val warmups: Int?            // --ei warmups N (0도 유효 — 예열 없이)
) {
    // 모델 지정이 없으면 자동화가 아니다 = 평범한 수동 실행.
    val isManual: Boolean get() = modelFileName == null

    companion object {
        val MANUAL = AutoRunRequest(null, false, null, null)

        fun from(intent: Intent?): AutoRunRequest {
            val model = intent?.getStringExtra("model") ?: return MANUAL
            return AutoRunRequest(
                modelFileName = model,
                runSuite = intent.getStringExtra("autorun") == "suite",
                // 엑스트라가 없으면 -1이 오므로 '지정 안 함'(null)으로 접는다.
                // repeats는 1 이상이어야 의미가 있고, warmups는 0(예열 생략)도 유효한 값이다.
                repeats = intent.getIntExtra("repeats", -1).takeIf { it > 0 },
                warmups = intent.getIntExtra("warmups", -1).takeIf { it >= 0 }
            )
        }
    }
}
