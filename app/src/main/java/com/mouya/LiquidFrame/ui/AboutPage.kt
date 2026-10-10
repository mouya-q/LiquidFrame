package com.mouya.LiquidFrame.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * About page with animated gradient header, version info, and links.
 *
 * The animated gradient field and hero layout are adapted from common
 * Compose patterns for glass-like about pages.
 */
@Composable
fun AboutPage(
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val dark = isSystemInDarkTheme()

    var animationTime by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    animationTime += (now - previous) / 1_000_000_000f
                }
                previous = now
            }
        }
    }

    val gradientColors = animatedGradientColors(animationTime, dark)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(
                start = 18.dp,
                end = 18.dp,
                top = 54.dp,
                bottom = 100.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Animated gradient hero header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedGradientField(
                animationTime = animationTime,
                colors = gradientColors,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF7AC4FF).copy(alpha = 0.8f),
                                    Color(0xFF0A0A0E),
                                ),
                            ),
                        ),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "LiquidFrame",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$versionName ($versionCode)",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        // Developer section
        SettingSection("开发者") {
            SettingRow(
                title = "mouya",
                subtitle = "酷安 @muraya",
                trailing = {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = LiquidColors.blue,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mouya-q"))
                    )
                },
            )
        }

        // Project links
        SettingSection("项目") {
            SettingRow(
                title = "GitHub 仓库",
                subtitle = "https://github.com/mouya-q/LiquidFrame",
                trailing = {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = LiquidColors.blue, modifier = Modifier.size(20.dp))
                },
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mouya-q/LiquidFrame"))
                    )
                },
            )
            SettingDivider()
            SettingRow(
                title = "更新日志",
                subtitle = "查看完整版本历史",
                trailing = {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = LiquidColors.blue, modifier = Modifier.size(20.dp))
                },
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mouya-q/LiquidFrame/blob/main/CHANGELOG.md"))
                    )
                },
            )
        }

        // About module
        SettingSection("关于") {
            SettingRow(
                title = "模块说明",
                subtitle = "LiquidFrame 是一个 LSPosed 模块，为小米相机水印渲染液态玻璃材质。它 hook 相机水印渲染管线，保留原始水印文字，仅替换背景为液态玻璃。",
            )
            SettingDivider()
            SettingRow(
                title = "技术栈",
                subtitle = "Xposed API 82 · Kyant0 backdrop/shapes/capsule · Compose · pure-Kotlin pixel renderer",
            )
        }
    }
}

@Composable
private fun AnimatedGradientField(
    animationTime: Float,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawGradientField(animationTime, colors, size, Offset.Zero)
    }
}

private fun DrawScope.drawGradientField(
    animationTime: Float,
    colors: List<Color>,
    fieldSize: androidx.compose.ui.geometry.Size,
    sampleOrigin: Offset,
) {
    val radius = fieldSize.maxDimension * 0.6f
    val motionTime = animationTime * 0.12f

    drawRect(
        brush = Brush.linearGradient(
            colors = colors.map { it.copy(alpha = 0.6f) },
            start = Offset(-sampleOrigin.x, -sampleOrigin.y),
            end = Offset(fieldSize.width - sampleOrigin.x, fieldSize.height - sampleOrigin.y),
        ),
    )

    val centers = listOf(
        Offset(
            fieldSize.width * (0.18f + 0.10f * sin(motionTime)),
            fieldSize.height * (0.20f + 0.08f * cos(motionTime * 0.8f)),
        ),
        Offset(
            fieldSize.width * (0.82f + 0.10f * cos(motionTime * 0.9f)),
            fieldSize.height * (0.78f + 0.10f * sin(motionTime * 0.7f)),
        ),
        Offset(
            fieldSize.width * (0.22f + 0.12f * cos(motionTime * 0.65f)),
            fieldSize.height * (0.80f + 0.08f * sin(motionTime * 0.85f)),
        ),
        Offset(
            fieldSize.width * (0.80f + 0.12f * sin(motionTime * 0.72f)),
            fieldSize.height * (0.20f + 0.08f * cos(motionTime * 0.62f)),
        ),
    )
    centers.forEachIndexed { index, center ->
        val color = colors[index]
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.8f), color.copy(alpha = 0f)),
                center = center - sampleOrigin,
                radius = radius,
            ),
            center = center - sampleOrigin,
            radius = radius,
        )
    }
}

private fun animatedGradientColors(animationTime: Float, dark: Boolean): List<Color> {
    val palettes = if (dark) DarkPalettes else LightPalettes
    val segmentValue = animationTime / 12f
    val segment = floor(segmentValue).toInt() % 4
    val rawProgress = segmentValue - floor(segmentValue)
    val progress = rawProgress * rawProgress * (3f - 2f * rawProgress)
    val start = when (segment) {
        0 -> palettes[1]; 1 -> palettes[0]; 2 -> palettes[1]; else -> palettes[2]
    }
    val end = when (segment) {
        0 -> palettes[0]; 1 -> palettes[1]; 2 -> palettes[2]; else -> palettes[1]
    }
    return start.indices.map { index ->
        androidx.compose.ui.graphics.lerp(start[index], end[index], progress)
    }
}

private val LightPalettes = listOf(
    listOf(Color(1f, 0.90f, 0.94f), Color(1f, 0.84f, 0.89f), Color(0.97f, 0.73f, 0.82f), Color(0.64f, 0.65f, 0.98f)),
    listOf(Color(0.58f, 0.74f, 1f), Color(1f, 0.90f, 0.93f), Color(0.74f, 0.76f, 1f), Color(0.97f, 0.77f, 0.84f)),
    listOf(Color(0.98f, 0.86f, 0.90f), Color(0.60f, 0.73f, 0.98f), Color(0.92f, 0.93f, 1f), Color(0.56f, 0.69f, 1f)),
)

private val DarkPalettes = listOf(
    listOf(Color(0.20f, 0.06f, 0.88f, 0.40f), Color(0.30f, 0.14f, 0.55f, 0.50f), Color(0f, 0.64f, 0.96f, 0.50f), Color(0.11f, 0.16f, 0.83f, 0.40f)),
    listOf(Color(0.07f, 0.15f, 0.79f, 0.50f), Color(0.62f, 0.21f, 0.67f, 0.50f), Color(0.06f, 0.25f, 0.84f, 0.50f), Color(0f, 0.20f, 0.78f, 0.50f)),
    listOf(Color(0.58f, 0.30f, 0.74f, 0.40f), Color(0.27f, 0.18f, 0.60f, 0.50f), Color(0.66f, 0.26f, 0.62f, 0.50f), Color(0.12f, 0.16f, 0.70f, 0.60f)),
)