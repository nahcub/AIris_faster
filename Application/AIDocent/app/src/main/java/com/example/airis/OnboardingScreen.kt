package com.example.airis

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.airis.ui.theme.PretendardFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.airis.ui.theme.PretendardFamily
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun OnboardingScreen(onStartClick: () -> Unit, onLlamaTestClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFDFDFD))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 타이틀
            Column(
                modifier = Modifier
                    .padding(top = 96.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "예술을 더 가까이",
                    fontSize = 24.sp,
                    //style = MaterialTheme.typography.displaySmall
                    fontWeight = FontWeight.SemiBold,
                    //fontFamily = PretendardFamily
                )

                Spacer(modifier = Modifier.height(5.dp))

                Text(
                    text = "AI가 들려주는 작품 이야기",
                    fontSize = 24.sp,
                    //style = MaterialTheme.typography.displayMedium
                    fontWeight = FontWeight.Bold,
                    //fontFamily = PretendardFamily
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 이미지 영역 - 애니메이션 적용
            Box(
                modifier = Modifier.size(300.dp, 250.dp),
                contentAlignment = Alignment.Center
            ) {
                // 첫 번째 이미지 - 부유 애니메이션 (왼쪽 위)
                FloatingImage(
                    imageRes = R.drawable.onboarding_paint_1,
                    contentDescription = "온보딩 아트워크_1",
                    size = 196.dp,
                    frequencyX = 0.0008f,
                    frequencyY = 0.001f,
                    amplitudeX = 2.5f,
                    amplitudeY = 4.5f,
                    phaseX = 0f,
                    phaseY = 0f,
                    baseOffsetX = (-20).dp,
                    baseOffsetY = (-40).dp
                )

                // 두 번째 이미지 - 부유 애니메이션 (왼쪽)
                FloatingImage(
                    imageRes = R.drawable.onboarding_paint_2,
                    contentDescription = "온보딩 아트워크_2",
                    size = 116.dp,
                    frequencyX = 0.0008f,
                    frequencyY = 0.001f,
                    amplitudeX = 2f,
                    amplitudeY = 3f,
                    phaseX = 2.2f,
                    phaseY = 1.5f,
                    baseOffsetX = 60.dp,
                    baseOffsetY = 20.dp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 하단 영역
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_with_text),
                    contentDescription = "로고",
                    modifier = Modifier.size(104.dp, 25.dp)
                )
            }

            Column (
                modifier = Modifier
                    .padding(
                        top = 24.dp,
                        bottom = 40.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onStartClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE0E0E0),
                        contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = "시작하기",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = onLlamaTestClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE0E0E0),
                        contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = "LLM 테스트하기",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun FloatingImage(
    imageRes: Int,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    frequencyX: Float,
    frequencyY: Float,
    amplitudeX: Float,
    amplitudeY: Float,
    phaseX: Float,
    phaseY: Float,
    baseOffsetX: androidx.compose.ui.unit.Dp = 0.dp,
    baseOffsetY: androidx.compose.ui.unit.Dp = 0.dp
) {
    // 무한 애니메이션
    val infiniteTransition = rememberInfiniteTransition(label = "floating")

    // X축 애니메이션
    val offsetX by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offsetX"
    )

    // Y축 애니메이션
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offsetY"
    )

    // 실제 오프셋 계산
    val actualOffsetX = (sin(Math.toRadians(offsetX.toDouble() + phaseX * 57.3)) * amplitudeX).toFloat()
    val actualOffsetY = (cos(Math.toRadians(offsetY.toDouble() + phaseY * 57.3)) * amplitudeY).toFloat()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(size)
                .offset {
                    IntOffset(
                        x = (baseOffsetX + actualOffsetX.dp).roundToPx(),
                        y = (baseOffsetY + actualOffsetY.dp).roundToPx()
                    )
                }
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun OnboardingScreenPreview() {
    MaterialTheme {
        OnboardingScreen(
            onStartClick = { },
            onLlamaTestClick = { }
        )
    }
}