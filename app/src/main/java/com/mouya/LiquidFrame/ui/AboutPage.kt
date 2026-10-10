package com.mouya.LiquidFrame.ui

/*
 * About page: animated gradient field with scroll parallax, a hero header whose
 * icon fades and scales out as the user scrolls, and the developer / project
 * link sections.
 *
 * The gradient field is the hero's own animated backdrop: a linear gradient base
 * plus four moving radial centers on a 12-second palette interpolation cycle.
 * The field's colors are saturation-strengthened before drawing so the palette
 * survives the translucent composite. The whole field fades to transparent
 * through a vertical gradient mask and scrolls at 0.12x to create depth.
 *
 * The header capsule samples the gradient field itself as its backdrop — the
 * same material the module applies in the camera, so the icon is a live sample
 * of what the module does.
 */

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Hero region height, as a fraction of the screen height. */
private const val HERO_HEIGHT_FRACTION = 0.60f

/** Extra height below the hero the background keeps painting into. */
private val HERO_BACKGROUND_EXTEND = 180.dp

/** Scroll distance over which the background fades out completely. */
private val BACKGROUND_FADE_DISTANCE = 389.dp

/** Parallax factor: the background scrolls at this fraction of the content. */
private const val BACKGROUND_PARALLAX = 0.12f

/**
 * Logo fade window, as fractions of the hero height. The icon starts fading at
 * 0.25 and is fully gone at 0.60 of the hero scrolled past.
 */
private const val LOGO_FADE_START_FRACTION = 0.25f
private const val LOGO_FADE_DISTANCE_FRACTION = 0.35f

/** The hero logo also shrinks slightly as it fades. */
private const val LOGO_FADE_SHRINK = 0.1f

/** Palette interpolation period, seconds. */
private const val COLOR_INTERPOLATION_SECONDS = 12f

/** Motion speed of the radial centers. */
private const val BACKGROUND_SPEED = 0.12f

/** Radial gradient radius, as a fraction of the field's max dimension. */
private const val GRADIENT_RADIUS_FRACTION = 0.62f

/** Saturation multiplier and brightness lift applied to every palette color. */
private const val GRADIENT_SATURATION = 1.18f
private const val GRADIENT_BRIGHTNESS_OFFSET = 0.015f

@Composable
fun AboutPage(
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val density = LocalDensity.current
    val dark = isDark()

    val heroHeight = LocalConfiguration.current.screenHeightDp.dp * HERO_HEIGHT_FRACTION
    val heroHeightPx = with(density) { heroHeight.toPx() }
    val backgroundFadeDistance = with(density) { BACKGROUND_FADE_DISTANCE.toPx() }
    val logoFadeStart = heroHeightPx * LOGO_FADE_START_FRACTION
    val logoFadeDistance = heroHeightPx * LOGO_FADE_DISTANCE_FRACTION

    val scrollOffset by remember(listState, heroHeightPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                heroHeightPx
            } else {
                listState.firstVisibleItemScrollOffset.toFloat()
            }
        }
    }
    val reachedListEnd by remember(listState) {
        derivedStateOf {
            !listState.canScrollForward &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        }
    }
    val backgroundAlpha = if (reachedListEnd) {
        0f
    } else {
        1f - (scrollOffset / backgroundFadeDistance).coerceIn(0f, 1f)
    }
    val logoProgress = if (reachedListEnd) {
        1f
    } else {
        ((scrollOffset - logoFadeStart) / logoFadeDistance).coerceIn(0f, 1f)
    }
    val logoAlpha = 1f - logoProgress
    val logoScale = 1f - logoProgress * LOGO_FADE_SHRINK

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

    Box(modifier = modifier.fillMaxSize()) {
        // Animated background: hero-height field, parallax scrolling, fading out
        // through the first scroll distance.
        AnimatedAboutBackground(
            animationTime = animationTime,
            colors = gradientColors,
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight + HERO_BACKGROUND_EXTEND)
                .alpha(backgroundAlpha)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    translationY = -scrollOffset * BACKGROUND_PARALLAX
                },
        )

        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 0.dp,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(heroHeight + 16.dp))
                }
            }

            item {
                SettingSection("开发者") {
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
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mouya-q"))
                            )
                        },
                    )
                }
            }

            item {
                SettingSection("项目") {
                    SettingRow(
                        title = "GitHub 仓库",
                        subtitle = "https://github.com/mouya-q/LiquidFrame",
                        trailing = {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = LiquidColors.blue, modifier = Modifier.size(20.dp))
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
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = LiquidColors.blue, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mouya-q/LiquidFrame/blob/main/CHANGELOG.md"))
                            )
                        },
                    )
                }
            }

            item {
                SettingSection("关于") {
                    SettingRow(
                        title = "模块说明",
                        subtitle = "LiquidFrame 是一个 LSPosed 模块，为小米相机水印渲染液态玻璃材质。它 hook 相机水印渲染管线，保留原始水印文字，仅替换背景为液态玻璃。",
                    )
                    SettingDivider()
                    SettingRow(
                        title = "技术栈",
                        subtitle = "Xposed API 82 · backdrop RuntimeShader · Compose · pure-Kotlin pixel renderer",
                    )
                }
            }
        }

        // Hero: the glass capsule over the gradient field. Fades and shrinks as
        // the content scrolls past it.
        AboutHero(
            animationTime = animationTime,
            gradientColors = gradientColors,
            dark = dark,
            logoAlpha = logoAlpha,
            logoScale = logoScale,
            versionName = versionName,
            versionCode = versionCode,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .height(heroHeight)
                .padding(horizontal = 18.dp),
        )
    }
}

@Composable
private fun AboutHero(
    animationTime: Float,
    gradientColors: List<Color>,
    dark: Boolean,
    logoAlpha: Float,
    logoScale: Float,
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
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
            // The gradient field exports itself as a layer backdrop so the capsule
            // samples these exact pixels.
            val fieldBackdrop = rememberLayerBackdrop()
            Box(Modifier.fillMaxWidth().height(200.dp).layerBackdrop(fieldBackdrop)) {
                AnimatedGradientField(
                    animationTime = animationTime,
                    colors = gradientColors,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(18.dp))

            // The short glass capsule, rendered with the same material chain as
            // the watermark panel: blur, lens refraction, rim highlight.
            val pageBackdrop = liquidGlassBackdrop()
            val backdrop = if (pageBackdrop != null) {
                rememberCombinedBackdrop(pageBackdrop, fieldBackdrop)
            } else {
                fieldBackdrop
            }
            Box(
                modifier = Modifier
                    .size(width = 120.dp, height = 44.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(3.dp.toPx())
                            lens(10.dp.toPx(), 14.dp.toPx(), chromaticAberration = true)
                        },
                        highlight = {
                            Highlight.Default.copy(
                                alpha = if (dark) 0.36f else 0.52f,
                            )
                        },
                        shadow = {
                            Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.18f))
                        },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = if (dark) 0.06f else 0.10f))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "LiquidFrame",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (dark) Color.White else Color(0xFF111113),
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "$versionName ($versionCode)",
                fontSize = 13.sp,
                color = if (dark) Color.White.copy(alpha = 0.75f) else Color(0xFF111113).copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun AnimatedAboutBackground(
    animationTime: Float,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawGradientField(animationTime, colors, size, Offset.Zero)
        // Vertical white fade: fully opaque through 68% of the field height, then
        // dissolving to transparent. BlendMode.DstIn turns the gradient into a mask.
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
    // Strengthen the palette first: the translucent composite washes colors out,
    // so pre-saturating keeps the field vivid.
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
    val palettes = if (dark) DarkPalettes else LightPalettes
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