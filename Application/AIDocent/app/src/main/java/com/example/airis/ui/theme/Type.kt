package com.example.airis.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.airis.R

val PretendardFamily = FontFamily(
    Font(R.font.pretendard_gov_thin, FontWeight.Thin),
    Font(R.font.pretendard_gov_medium, FontWeight.Medium),
    Font(R.font.pretendard_gov_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_gov_bold, FontWeight.Bold)
)

val NovaOval = Font(R.font.nova_oval_regular)

// Set of Material typography styles to start with
val Typography = Typography(
    displayMedium = TextStyle(
        fontFamily = PretendardFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp
    ),
    displaySmall = TextStyle(
        fontFamily = PretendardFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp
    )
    /*
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
     */
)