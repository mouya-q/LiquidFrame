package com.mouya.LiquidFrame.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val HERO_HEIGHT_FRACTION = 0.60f

private val HERO_BACKGROUND_EXTEND = 180.dp

private val BACKGROUND_FADE_DISTANCE = 389.dp

private const val BACKGROUND_PARALLAX = 0.12f

private const val WORDMARK_FADE_START_FRACTION = 0.25f
private const val WORDMARK_FADE_DISTANCE_FRACTION = 0.35f
private const val WORDMARK_FADE_SHRINK = 0.1f

private const val COLOR_INTERPOLATION_SECONDS = 12f

private const val BACKGROUND_SPEED = 0.16f

private const val WORDMARK_FLOW_PERIOD_SECONDS = 9f

private const val GRADIENT_RADIUS_FRACTION = 0.62f

private const val GRADIENT_SATURATION = 1.18f
private const val GRADIENT_BRIGHTNESS_OFFSET = 0.015f
/** Throttle for the shared animation clock, ~30 fps. */
private const val ANIMATION_FRAME_NANOS = 33_333_333L

@Composable
fun AboutPage(
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val dark = isDark()
    val heroHeight = LocalConfiguration.current.screenHeightDp.dp * HERO_HEIGHT_FRACTION
    val heroHeightPx = with(density) { heroHeight.toPx() }
    val backgroundFadeDistance = with(density) { BACKGROUND_FADE_DISTANCE.toPx() }
    val wordmarkFadeStart = heroHeightPx * WORDMARK_FADE_START_FRACTION
    val wordmarkFadeDistance = heroHeightPx * WORDMARK_FADE_DISTANCE_FRACTION
    val scrollOffset = scrollState.value.toFloat()
    val reachedListEnd = !scrollState.canScrollForward && scrollState.maxValue > 0
    val backgroundAlpha = if (reachedListEnd) 0f else 1f -
        (scrollOffset / backgroundFadeDistance).coerceIn(0f, 1f)
    val wordmarkProgress = if (reachedListEnd) 1f else
        ((scrollOffset - wordmarkFadeStart) / wordmarkFadeDistance).coerceIn(0f, 1f)
    val wordmarkAlpha = 1f - wordmarkProgress
    val wordmarkScale = 1f - wordmarkProgress * WORDMARK_FADE_SHRINK
    val cardProgress = ((scrollOffset - heroHeightPx * 0.58f) / with(density) { 360.dp.toPx() })
        .coerceIn(0f, 1f)
    val cardAlpha = (if (dark) 0.48f else 0.38f) +
        ((if (dark) 0.88f else 0.84f) - (if (dark) 0.48f else 0.38f)) * cardProgress

    val pageBackdrop = rememberLayerBackdrop()
    val animationClock = rememberAboutAnimationClock()

    Box(modifier = modifier.fillMaxSize()) {

        AboutBackground(
            dark = dark,
            animationClock = animationClock,
            backdrop = pageBackdrop,
            heroHeight = heroHeight,
            scrollOffset = scrollOffset,
            backgroundAlpha = backgroundAlpha,
        )
        CompositionLocalProvider(LocalLiquidBackdrop provides pageBackdrop) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(start = 18.dp, end = 18.dp, bottom = 132.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Spacer(Modifier.height(heroHeight + 16.dp))

                SettingSection("开发者", surfaceAlpha = cardAlpha, softBlur = true) {
                    SettingRow(
                        title = "mouya",
                        subtitle = "酷安 @muraya",
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                tint = LiquidColors.blue,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.coolapk.com/u/43580052")))
                        },
                    )
                }

                SettingSection("项目", surfaceAlpha = cardAlpha, softBlur = true) {
                    SettingRow(
                        title = "GitHub 仓库",
                        subtitle = "https://github.com/mouya-q/LiquidFrame",
                        trailing = {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = LiquidColors.blue, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mouya-q/LiquidFrame")))
                        },
                    )
                }

                SettingSection("关于", surfaceAlpha = cardAlpha, softBlur = true) {
                    SettingRow(
                        title = "模块说明",
                        subtitle = "LiquidFrame 是一个 LSPosed 模块，为小米相机水印渲染液态玻璃材质",
                    )
                    SettingDivider()
                    SettingRow(
                        title = "技术栈",
                        subtitle = "Xposed API 82 · backdrop RuntimeShader · Compose · pure-Kotlin pixel renderer",
                    )
                }
            }

            AboutHero(
                dark = dark,
                animationClock = animationClock,
                logoAlpha = wordmarkAlpha,
                logoScale = wordmarkScale,
                versionName = versionName,
                versionCode = versionCode,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .height(heroHeight)
                    .padding(horizontal = 18.dp),
            )
        }
    }
}

@Composable
private fun AboutBackground(
    dark: Boolean,
    animationClock: AboutAnimationClock,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    heroHeight: androidx.compose.ui.unit.Dp,
    scrollOffset: Float,
    backgroundAlpha: Float,
) {
    val animationTime = animationClock.time
    val colors = animatedGradientColors(animationTime, dark)
    val backdropColors = remember(colors, dark) {
        colors.map { it.copy(alpha = if (dark) 0.26f else 0.30f) }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.layerBackdrop(backdrop).fillMaxSize()) {
            drawRect(if (dark) Color(0xFF0B0B0D) else Color(0xFFF8F8FC))
            drawRect(
                brush = Brush.linearGradient(
                    colors = backdropColors,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }
        AnimatedAboutBackground(
            animationTime = animationTime,
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight + HERO_BACKGROUND_EXTEND)
                .alpha(backgroundAlpha)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    translationY = -scrollOffset * BACKGROUND_PARALLAX
                },
        )
    }
}

@Composable
private fun AboutHero(
    dark: Boolean,
    animationClock: AboutAnimationClock,
    logoAlpha: Float,
    logoScale: Float,
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    val animationTime = animationClock.time

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    alpha = logoAlpha
                    scaleX = logoScale
                    scaleY = logoScale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GradientWordmark(dark, animationTime)

            Spacer(Modifier.height(10.dp))
            Text(
                "$versionName ($versionCode)",
                fontSize = 13.sp,
                color = if (dark) Color.White.copy(alpha = 0.75f) else Color(0xFF111113).copy(alpha = 0.75f),
            )
        }
    }
}

private class AboutAnimationClock {
    var time by mutableFloatStateOf(0f)
}

@Composable
private fun rememberAboutAnimationClock(): AboutAnimationClock {
    val clock = remember { AboutAnimationClock() }
    LaunchedEffect(clock) {
        var lastFrame = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastFrame == 0L) {
                    lastFrame = now
                } else if (now - lastFrame >= ANIMATION_FRAME_NANOS) {
                    clock.time += (now - lastFrame) / 1_000_000_000f
                    lastFrame = now
                }
            }
        }
    }
    return clock
}

private val LightWordmarkGradient = arrayOf(
    0f to Color(0xFF2438C7),
    0.18f to Color(0xFF4854DF),
    0.36f to Color(0xFF8060E8),
    0.54f to Color(0xFFC06ABF),
    0.72f to Color(0xFFDF7E9E),
    0.88f to Color(0xFF777BEA),
    1f to Color(0xFF2438C7),
)

private val DarkWordmarkGradient = arrayOf(
    0f to Color(0xFF7898FF),
    0.18f to Color(0xFF8A8BFF),
    0.36f to Color(0xFFB18BFF),
    0.54f to Color(0xFFE39BDE),
    0.72f to Color(0xFFF0A9C7),
    0.88f to Color(0xFF9AA8FF),
    1f to Color(0xFF7898FF),
)

@Composable
private fun GradientWordmark(dark: Boolean, animationTime: Float) {
    val base = if (dark) Color.White else Color(0xFF24305C)
    val colors = if (dark) DarkWordmarkGradient else LightWordmarkGradient
    val phase = (animationTime / WORDMARK_FLOW_PERIOD_SECONDS) % 1f

    Text(
        text = "LiquidFrame",
        style = TextStyle(
            color = base,
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1.1).sp,
        ),
        maxLines = 1,
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val gradientWidth = size.width * 1.35f
                val startX = -phase * gradientWidth
                val flow = Brush.linearGradient(
                    colorStops = colors,
                    start = Offset(startX, 0f),
                    end = Offset(startX + gradientWidth, 0f),
                    tileMode = TileMode.Repeated,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = flow, blendMode = BlendMode.SrcIn)
                }
            },
    )
}

@Composable
private fun AnimatedAboutBackground(
    animationTime: Float,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawGradientField(animationTime, colors, size, Offset.Zero)

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White,
                    0.68f to Color.White,
                    1f to Color.Transparent,
                ),
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }
}

private fun DrawScope.drawGradientField(
    animationTime: Float,
    colors: List<Color>,
    fieldSize: androidx.compose.ui.geometry.Size,
    sampleOrigin: Offset,
) {

    val strengthened = colors.map(::strengthenGradientColor)
    val translucentPalette = strengthened.any { it.alpha < 0.8f }
    val radius = fieldSize.maxDimension * GRADIENT_RADIUS_FRACTION
    val motionTime = animationTime * BACKGROUND_SPEED

    drawRect(
        brush = Brush.linearGradient(
            colors = strengthened.map { color ->
                color.copy(
                    alpha = if (translucentPalette) {
                        color.alpha * 0.72f
                    } else {
                        0.58f
                    },
                )
            },
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
        val color = strengthened[index % strengthened.size]
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(
                        alpha = if (translucentPalette) {
                            color.alpha * 0.96f
                        } else {
                            0.88f
                        },
                    ),
                    color.copy(alpha = 0f),
                ),
                center = center - sampleOrigin,
                radius = radius,
            ),
            center = center - sampleOrigin,
            radius = radius,
        )
    }
}

private fun strengthenGradientColor(color: Color): Color {
    val average = (color.red + color.green + color.blue) / 3f
    return Color(
        red = (average + (color.red - average) * GRADIENT_SATURATION - GRADIENT_BRIGHTNESS_OFFSET).coerceIn(0f, 1f),
        green = (average + (color.green - average) * GRADIENT_SATURATION - GRADIENT_BRIGHTNESS_OFFSET).coerceIn(0f, 1f),
        blue = (average + (color.blue - average) * GRADIENT_SATURATION - GRADIENT_BRIGHTNESS_OFFSET).coerceIn(0f, 1f),
        alpha = color.alpha,
    )
}

private fun animatedGradientColors(animationTime: Float, dark: Boolean): List<Color> {
    return interpolatePalettes(if (dark) DarkPalettes else LightPalettes, animationTime)
}

private fun interpolatePalettes(palettes: List<List<Color>>, animationTime: Float): List<Color> {
    val segmentValue = animationTime / COLOR_INTERPOLATION_SECONDS
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
        lerp(start[index], end[index], progress)
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
