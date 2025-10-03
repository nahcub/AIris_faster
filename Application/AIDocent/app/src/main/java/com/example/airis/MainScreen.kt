package com.example.airis

import android.graphics.drawable.Icon
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen(onStartClick: () -> Unit) {
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 80.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.main_glasses),
                    contentDescription = "안경",
                    modifier = Modifier.size(192.dp, 192.dp)
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(color = Color(0xFFD9D9D9), shape = RoundedCornerShape(size = 32.dp))
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_current_location),
                    contentDescription = "위치 아이콘",
                    modifier = Modifier.size(24.dp),
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "The Metropolitan Museum of Art",
                    fontSize = 14.sp,
                    //style = MaterialTheme.typography.displaySmall
                    fontWeight = FontWeight.Medium,
                    //fontFamily = PretendardFamily
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    Modifier
                        .width(55.dp)
                        .height(25.dp)
                        .background(color = Color(0xFFF0F0F0), shape = RoundedCornerShape(size = 4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "변경하기",
                        fontSize = 12.sp,
                        //style = MaterialTheme.typography.displaySmall
                        fontWeight = FontWeight.Medium,
                        //fontFamily = PretendardFamily
                    )
                }
            }

            Column (modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                        .background(color = Color(0xFFF0F0F0), shape = RoundedCornerShape(size = 16.dp))
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                        .background(color = Color(0xFFF0F0F0), shape = RoundedCornerShape(size = 16.dp))
                )
            }

            Column (modifier = Modifier
                .padding(top = 24.dp)
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
                        text = "눌러서 연결하기",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = "© 2025 AIris. All Rights Reserved.",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
    MaterialTheme {
        MainScreen(
            onStartClick = { }
        )
    }
}