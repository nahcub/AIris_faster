package com.example.airis

import android.view.animation.OvershootInterpolator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onInitializationComplete: () -> Unit
) {
    // 로고 애니메이션 효과를 위한 상태 변수
    val scale = remember { Animatable(0f) }

    // 화면이 뜨자마자 실행
    LaunchedEffect(key1 = true) {
        // 1. 애니메이션 시작
        scale.animateTo(
            targetValue = 1.0f,
            animationSpec = tween(
                durationMillis = 800,
                easing = {
                    OvershootInterpolator(2f).getInterpolation(it)
                }
            )
        )

        // 2. [수정됨] 로딩 완료를 기다리지 않고 딱 1.5초 대기
        // (애니메이션 시간 포함하여 총 대기 시간이 1.5초가 되도록 로직 구성)
        // 위 애니메이션이 비동기가 아니므로(animateTo는 suspend),
        // 애니메이션(0.8초) 끝난 후 0.7초 더 기다리면 총 1.5초 얼추 맞음.
        // 혹은 더 단순하게:
        delay(700L)

        // 3. 무조건 다음 화면으로 이동
        onInitializationComplete()
    }

    // [UI] 화면 구성 (기존과 동일)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = android.R.drawable.star_big_on),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale.value)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "AI Docent",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Text(
                text = "당신만의 미술관 가이드",
                fontSize = 16.sp,
                color = Color.Gray
            )
        }
    }
}