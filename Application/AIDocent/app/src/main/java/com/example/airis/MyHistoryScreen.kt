package com.example.airis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class) // TopAppBarDefaults 사용을 위해 추가
@Composable
fun MyHistoryScreen(onBackClick: () -> Unit = {}) {
    Scaffold(
        // 1. topBar '자리'에 TopAppBar '부품'을 지정합니다.
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "마이 히스토리",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black // 필요에 따라 색상 지정
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            // CameraPreviewScreen과 통일성을 위해 Material Icon 사용
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = "뒤로가기",
                        )
                    }
                },
                // CameraPreviewScreen과 동일한 배경색 적용
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFDFDFD)
                )
            )
        },
        // 전체 화면 배경색 설정
        containerColor = Color(0xFFFDFDFD)
    ) { paddingValues -> // 2. Scaffold가 제공하는 paddingValues를 받습니다.
        // 3. content 영역에 paddingValues를 적용합니다.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // TopAppBar 영역을 제외한 안전한 영역 설정
                .padding(horizontal = 20.dp), // 추가적인 좌우 여백
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 여기에 히스토리 내용이 들어갑니다.
            // 예시: Text("히스토리 목록이 여기에 표시됩니다.")
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MyHistoryPreview() {
    MaterialTheme {
        MyHistoryScreen(
            onBackClick = { }
        )
    }
}