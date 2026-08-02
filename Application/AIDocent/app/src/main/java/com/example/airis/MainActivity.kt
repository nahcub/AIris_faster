package com.example.airis

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.example.airis.ui.theme.AIrisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 벤치 돌리는 동안 화면이 꺼지면 측정이 끊기므로, 앱이 떠 있는 내내 화면 유지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            AIrisTheme {
                // 화면 전체 배경을 테마 색으로 칠함 (없으면 흰 윈도우 배경이 비쳐 카드만 다크로 보임)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 인텐트 엑스트라가 있으면 adb 자동 실행, 없으면 수동 모델 선택 화면.
                    // launchMode는 기본(standard) — 자동화가 `am start -S`로 강제 재시작하므로
                    // onNewIntent 처리가 필요 없다.
                    InferenceScreen(
                        autoInitialize = true,
                        autoRun = AutoRunRequest.from(intent)
                    )
                }
            }
        }
    }
}
