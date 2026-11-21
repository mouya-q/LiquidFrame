package com.mouya.LiquidFrame.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

val LocalLiquidBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun LiquidBackdropProvider(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {

    val backdrop = rememberLayerBackdrop()
    val dark = isSystemInDarkTheme()
    Box(modifier.fillMaxSize()) {

        val base = if (dark) Color(0xFF0B0B0D) else Color(0xFFFFFFFF)
        Canvas(Modifier.layerBackdrop(backdrop).fillMaxSize()) {
            drawRect(base)
        }
        CompositionLocalProvider(LocalLiquidBackdrop provides backdrop) {
            content()
        }
    }
}

@Composable
fun liquidGlassAvailable(): Boolean {

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (!LocalView.current.isHardwareAccelerated) return false
    return LocalLiquidBackdrop.current != null
}

@Composable
fun liquidGlassBackdrop(): Backdrop? {
    if (!liquidGlassAvailable()) return null
    return LocalLiquidBackdrop.current
}

internal fun safeProgress(value: Float): Float =
    if (value.isNaN() || value.isInfinite()) 0f else value.coerceIn(0f, 1f)

internal fun Float.finiteOrZero(): Float = if (isNaN() || isInfinite()) 0f else this

enum class LiquidGlassMaterial { Clear, Regular }

data class LiquidGlassSpec(
    val blurRadius: Dp,
    val lensRadius: Dp,
    val refractionHeight: Dp,
    val useLens: Boolean,
) {
    companion object {
        fun forMaterial(material: LiquidGlassMaterial): LiquidGlassSpec = when (material) {
            LiquidGlassMaterial.Clear -> LiquidGlassSpec(2.dp, 24.dp, 12.dp, useLens = true)
            LiquidGlassMaterial.Regular -> LiquidGlassSpec(2.dp, 24.dp, 12.dp, useLens = true)
        }
    }
}

@Composable
fun Modifier.liquidGlassCard(
    shape: Shape,
    cornerRadius: Dp,
    glass: Color,
    border: Color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.08f)
    else Color.Black.copy(alpha = 0.06f),
): Modifier {
    val backdrop = liquidGlassBackdrop()
    return if (backdrop != null) {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(18.dp.toPx())
                lens(8f, 18f, depthEffect = true, chromaticAberration = false)
            },
            highlight = { Highlight.Default },
            shadow = { Shadow(radius = 22.dp, alpha = 0.42f) },
            onDrawSurface = {
                drawRoundRect(glass, cornerRadius = CornerRadius(cornerRadius.toPx()))
            },
        )
    } else {
        clip(shape).background(glass).border(0.5.dp, border, shape)
    }
}

@Composable
fun glassContainer(alpha: Float? = null): Color {
    val resolvedAlpha = (alpha ?: 0.72f).coerceIn(0.08f, 1f)
    return if (isSystemInDarkTheme()) Color(0xFF1C1C1E).copy(alpha = resolvedAlpha)
    else Color.White.copy(alpha = resolvedAlpha)
}

@Composable
fun Modifier.liquidGlassSurface(
    shape: Shape,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Regular,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    pressProgress: Float = 0f,
    dragOffset: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
    spec: LiquidGlassSpec = LiquidGlassSpec.forMaterial(material),
): Modifier {
    val backdrop = liquidGlassBackdrop()
    val alphaScale = if (enabled) 1f else 0.48f
    val p = safeProgress(pressProgress)
    val isPlain = surfaceColor == Color.Transparent && tint == Color.Unspecified
    val dark = isSystemInDarkTheme()
    if (isPlain) return this
    if (backdrop == null) {
        val defaultAlpha = if (dark) 0.84f else 0.88f
        val tintAlphaFloor = if (dark) 0.18f else 0.22f
        val stableSurface = when {
            surfaceColor != Color.Unspecified -> surfaceColor.copy(alpha = surfaceColor.alpha * alphaScale)
            tint == Color.Unspecified -> MaterialTheme.colorScheme.surface.copy(alpha = defaultAlpha * alphaScale)
            else -> tint.copy(alpha = maxOf(tint.alpha, tintAlphaFloor) * alphaScale)
        }
        return background(stableSurface, shape)
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(spec.blurRadius.toPx())
            if (spec.useLens) {
                lens(
                    spec.lensRadius.toPx(),
                    spec.refractionHeight.toPx(),
                    depthEffect = p > 0.01f,
                    chromaticAberration = true,
                )
            }
        },
        highlight = {
            Highlight.Default.copy(
                alpha = ((if (dark) 0.32f else 0.48f) + 0.30f * p).coerceAtMost(1f),
            )
        },
        shadow = {
            Shadow(
                radius = 24.dp,
                color = Color.Black.copy(alpha = 0.12f),
                alpha = (0.08f + 0.22f * p) * if (enabled) 1f else 0.35f,
            )
        },
        innerShadow = {
            InnerShadow(
                radius = 4.dp + 8.dp * p,
                color = Color.Black.copy(alpha = 0.12f),
                alpha = (0.10f + 0.30f * p) * if (enabled) 1f else 0.35f,
            )
        },
        layerBlock = {
            val controlHeight = size.height.coerceAtLeast(1f)
            val scale = (1f + (4.dp.toPx() / controlHeight) * p).finiteOrZero()
            val maxOffset = size.minDimension.coerceAtLeast(1f)
            translationX = (maxOffset * tanh(0.05f * dragOffset.x.finiteOrZero() / maxOffset)).finiteOrZero()
            translationY = (maxOffset * tanh(0.05f * dragOffset.y.finiteOrZero() / maxOffset)).finiteOrZero()
            val maxDragScale = 4.dp.toPx() / controlHeight
            val angle = atan2(dragOffset.y.finiteOrZero(), dragOffset.x.finiteOrZero())
            scaleX = scale + maxDragScale * abs(cos(angle) * dragOffset.x.finiteOrZero() / size.maxDimension.coerceAtLeast(1f)) *
                (size.width / controlHeight).fastCoerceAtMost(1f)
            scaleY = scale + maxDragScale * abs(sin(angle) * dragOffset.y.finiteOrZero() / size.maxDimension.coerceAtLeast(1f)) *
                (controlHeight / size.width.coerceAtLeast(1f)).fastCoerceAtMost(1f)
        },
        onDrawSurface = {
            val darkOn = if (dark) 0.045f else 0.12f
            drawRect(Color.White.copy(alpha = darkOn), blendMode = BlendMode.Screen)
            if (p > 0.001f) {
                drawRect(Color.White.copy(alpha = 0.08f * p), blendMode = BlendMode.Plus)
            }
            if (tint != Color.Unspecified && tint.alpha > 0.001f) {
                drawRect(tint.copy(alpha = tint.alpha * alphaScale), blendMode = BlendMode.Overlay)
            }
            if (surfaceColor != Color.Unspecified) {
                drawRect(surfaceColor.copy(alpha = surfaceColor.alpha * alphaScale))
            }
        },
    )
}

@Composable
fun Modifier.liquidBackdropBlur(
    shape: Shape,
    blurRadius: Dp = 20.dp,
    surfaceColor: Color = Color.Transparent,
): Modifier {
    val backdrop = liquidGlassBackdrop()
    if (backdrop == null) return background(surfaceColor, shape)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { blur(blurRadius.toPx()) },
        highlight = null,
        shadow = null,
        innerShadow = null,
        onDrawSurface = {
            if (surfaceColor != Color.Transparent) drawRect(surfaceColor)
        },
    )
}
