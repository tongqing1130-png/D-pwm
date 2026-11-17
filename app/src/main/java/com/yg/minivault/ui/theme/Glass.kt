package com.yg.minivault.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃底色：深（或浅）色渐层 + 两团柔光。
 * 玻璃面板是半透明的，必须铺在这层上面才有「透光」的层次感。
 */
@Composable
fun GlassBackground() {
    val dark = LocalGlassDark.current
    val top = if (dark) Color(0xFF0A0D13) else Color(0xFFE7EFFF)
    val bottom = if (dark) Color(0xFF10141C) else Color(0xFFFBFDFF)
    val blob1 = if (dark) Color(0x1A4FC3F7) else Color(0x66BBD4FF)
    val blob2 = if (dark) Color(0x149BE7C4) else Color(0x5586E3C8)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom))),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(blob1, Color.Transparent), radius = 1100f)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(blob2, Color.Transparent), radius = 900f)),
        )
    }
}

/**
 * 玻璃面板：半透明填充 + 顶部高光 + 一圈极细描边 + 大圆角。
 * 用 [strong] 给重要卡片加一点不透明度，避免文字压在亮斑上读不清。
 */
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(26.dp),
    strong: Boolean = false,
): Modifier = composed {
    val dark = LocalGlassDark.current
    // 深色用不透明深面板：半透明白叠在深底上会发灰、字发飘
    val top = if (dark) Color(0xFF1F232C) else Color.White.copy(alpha = if (strong) 0.92f else 0.76f)
    val bottom = if (dark) Color(0xFF171A21) else Color.White.copy(alpha = if (strong) 0.72f else 0.52f)
    val stroke = if (dark) Color(0xFF414A5A) else Color.White.copy(alpha = 0.90f)
    this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .border(1.dp, stroke, shape)
}
