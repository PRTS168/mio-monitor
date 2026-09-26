package com.a41probe.monitor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AppColors = lightColorScheme(
    primary = Ink.accent,
    onPrimary = Color.White,
    secondary = Ink.m,
    background = Ink.bg,
    onBackground = Ink.tx,
    surface = Ink.card,
    onSurface = Ink.tx,
    surfaceVariant = Ink.panel,
    onSurfaceVariant = Ink.tx2,
    outline = Ink.stroke,
    error = Ink.danger,
    onError = Color.White,
)

// 数字用等宽字体保持仪器感；中文正文用系统字体
val Mono = FontFamily.Monospace

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 11.sp, color = Ink.tx2),
    labelSmall = TextStyle(fontSize = 10.5.sp, letterSpacing = 1.2.sp, color = Ink.tx2),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
)

@Composable
fun A41Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
