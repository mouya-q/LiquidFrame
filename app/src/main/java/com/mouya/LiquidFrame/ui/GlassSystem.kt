package com.mouya.LiquidFrame.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
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

// =====================================================================================
// Backdrop provider
// =====================================================================================

/** The screen backdrop sampled by all LiquidFrame glass controls. */
val LocalLiquidBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Wraps content with a full-screen backdrop layer so every glass component
 * inside can sample the background behind it.
 *
 * Provides a full-screen backdrop layer so every glass component
 * inside can sample the background behind it.
 */
@Composable
fun LiquidBackdropProvider(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // rememberLayerBackdrop() is the entry point that creates a fresh recording layer.
    // rememberBackdrop(backdrop, onDraw) *wraps an existing* Backdrop, so calling it with no
    // arguments does not compile.
    val backdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Box(Modifier.fillMaxSize().background(bgPrimary()))
        }
        CompositionLocalProvider(LocalLiquidBackdrop provides backdrop) {
            content()
        }
    }
}

// =====================================================================================
// Safety gate
// =====================================================================================

/**
 * Whether liquid glass can be drawn this frame: the window has hardware
 * acceleration and a backdrop is available.
 */
@Composable
fun liquidGlassAvailable(): Boolean {
    if (!LocalView.current.isHardwareAccelerated) return false
    return LocalLiquidBackdrop.current != null
}

/**
 * Returns the current backdrop if glass is available; null otherwise.
 * Every component that draws glass must go through this gate.
 */
@Composable
fun liquidGlassBackdrop(): Backdrop? {
    if (!liquidGlassAvailable()) return null
    return LocalLiquidBackdrop.current
}

/**
 * Clamps a progress value to [0, 1], treating NaN/Inf as 0.
 *
 * Springs can overshoot — pressProgress from spring(0.5f, 300f) reaches
 * approximately [-0.16, 1.16]. This washes it back so lens() never receives
 * a value that would produce NaN inside its AGSL shader.
 */
internal fun safeProgress(value: Float): Float =
    if (value.isNaN() || value.isInfinite()) 0f else value.coerceIn(0f, 1f)

/**
 * Returns this Float if finite, else 0.
 *
 * Anything that goes into a GraphicsLayerScope (= HWUI RenderNode transform
 * matrix) must pass through this. NaN in a matrix makes it non-invertible and
 * the drawing result is undefined.
 */
internal fun Float.finiteOrZero(): Float = if (isNaN() || isInfinite()) 0f else this

// =====================================================================================
// Glass spec
// =====================================================================================

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

// =====================================================================================
// Core glass surface modifier
// =====================================================================================

/**
 * The shared material entry point for all liquid-glass surfaces.
 *
 * When a backdrop is available, this draws real backdrop-sampled glass with
 * blur, lens refraction, highlight, shadow, and inner shadow. When no
 * backdrop is available (no hardware acceleration), it falls back to a
 * translucent solid surface so the UI remains readable.
 */
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

/**
 * Plain backdrop blur without lens or refraction. Used for content cards
 * and settings sections where lens distortion is undesirable.
 */
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

/**
 * Liquid-bottom-bar-style glass panel. Exports its own LayerBackdrop so
 * nested controls can sample the panel without a second render pass.
 */
@Composable
fun Modifier.liquidBottomBar(
    shape: Shape,
    tint: Color,
    surfaceColor: Color,
    pressProgress: Float = 0f,
    refractionHeight: Dp = 24.dp,
    exportedBackdrop: com.kyant.backdrop.backdrops.LayerBackdrop? = null,
): Modifier {
    val backdrop = liquidGlassBackdrop()
    if (backdrop == null) {
        val stableSurface = surfaceColor.compositeOver(MaterialTheme.colorScheme.background)
        return background(stableSurface, shape)
    }
    val p = safeProgress(pressProgress)
    // isSystemInDarkTheme() is @Composable: it cannot be called from inside the draw-scope
    // lambdas below, so resolve it here while we are still in a composable context.
    val highlightDark = isSystemInDarkTheme()
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(2.dp.toPx())
            if (p > 0.001f) {
                lens(
                    24.dp.toPx() * p,
                    refractionHeight.toPx() * p,
                    chromaticAberration = true,
                )
            }
        },
        highlight = {
            Highlight.Default.copy(
                alpha = (lerp(0.48f, 0.32f, if (highlightDark) 1f else 0f) + 0.30f * p).coerceAtMost(1f),
            )
        },
        shadow = {
            Shadow(
                radius = 24.dp,
                color = Color.Black.copy(alpha = 0.12f),
                alpha = 0.08f + 0.22f * p,
            )
        },
        innerShadow = {
            InnerShadow(
                radius = 4.dp + 4.dp * p,
                color = Color.Black.copy(alpha = 0.12f),
                alpha = 0.10f + 0.30f * p,
            )
        },
        layerBlock = {
            val scale = (1f + (16.dp.toPx() / size.width.coerceAtLeast(1f)) * p).finiteOrZero()
            scaleX = scale
            scaleY = scale
        },
        exportedBackdrop = exportedBackdrop,
        onDrawSurface = { drawRect(surfaceColor) },
    )
}