package com.example.airis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true  // true = 어두운 아이콘
        }

        setContent {
            MaterialTheme {  // 또는 MaterialTheme
                OnboardingScreen(
                    onStartClick = {
                        // 버튼 클릭 시 동작
                        // 예: 다음 화면으로 이동
                    }
                )
            }
        }
    }
}