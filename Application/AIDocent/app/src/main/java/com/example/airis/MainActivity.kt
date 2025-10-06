package com.example.airis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true  // true = 어두운 아이콘
        }

        setContent {
            MaterialTheme {
                AppNavigation()  // 변경된 부분
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    var isConnected by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = "onboarding"
    ) {
        // 온보딩 화면
        composable("onboarding") {
            OnboardingScreen(
                onStartClick = {
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }  // 뒤로가기 방지
                    }
                }
            )
        }

        // 홈 화면
        composable("home") {
            MainScreen(
                isConnected = isConnected,  // 상태 전달
                onConnectionChange = { isConnected = it },  // 상태 변경 콜백
                onStartClick = {
                    // "눌러서 연결하기" 버튼 클릭 시
                },
                onHistoryClick = {
                    navController.navigate("myhistory")
                }
            )
        }

        // 마이 히스토리 화면
        composable("myhistory") {
            MyHistoryScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}