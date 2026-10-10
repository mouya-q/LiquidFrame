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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
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

// =====================================================================================
// Bottom bar material stack (three independent layers)
// =====================================================================================

/**
 * Shared coat endpoints for the bottom chrome, driven by the tone scalar
 * `d` (0 = light, 1 = dark). Every depth-dependent value must be derived from
 * this one scalar; any layer reading the system theme directly will disagree
 * with the others while `d` is mid-flight.
 */
internal val BottomGlassTintLight = Color.White.copy(alpha = 0.12f)
internal val BottomGlassTintDark = Color.Black.copy(alpha = 0.10f)

/**
 * Coat concentration shared by all three bottom-bar glass layers (single source
 * of truth). 0.52 balances against the 2dp blur: the bar reads as "more
 * transparent but more coated". Blur and coat are designed to move in opposite
 * directions — using blur to compensate for a coat change undoes it.
 */
internal const val BOTTOM_GLASS_SURFACE_ALPHA = 0.52f

/**
 * Equal-proportion lens scale for the droplet = droplet height / the 56dp
 * reference droplet. `refractionHeight` samples inward from the droplet's own
 * contour, so a smaller droplet needs proportionally smaller lens values or the
 * refraction band reads too deep relative to the droplet.
 */
internal const val DROPLET_LENS_SCALE = 49f / 56f

/**
 * Rest-state floor for the droplet's outer shadow. Without it a resting droplet
 * has zero elevation cue and reads as a hole punched in the panel rather than a
 * glass bead floating in it. `Shadow`'s default color is Black 0.06, so 0.5
 * alpha is roughly 3% effective; beyond this it stops being glass and starts
 * being a pasted-on dark ring.
 */
internal const val DROPLET_REST_SHADOW = 0.50f

/** Rest-state floor for the droplet's inner shadow; the radius still animates 4dp -> 8dp with p. */
internal const val DROPLET_REST_INNER_SHADOW = 0.60f

/**
 * Resolves the single tone scalar for the bottom chrome: 0 = light, 1 = dark.
 * Accepts a sampled value when available; otherwise falls back to the theme.
 */
@Composable
internal fun bottomBarTone(systemDark: Boolean, sampled: Float): Float =
    if (sampled.isNaN()) if (systemDark) 1f else 0f else sampled

/**
 * Panel layer. The bar itself: constant lens (never multiplied by press
 * progress), full shadow/inner-shadow, and an exported backdrop so nested
 * controls can sample the panel without re-rendering a second copy of the same
 * glass.
 *
 * The panel blur and the capture layer blur must stay equal (2dp here and in
 * [liquidCaptureLayer]). If they diverge, sharp page content entering the lens
 * chain tears into fragments — when that symptom appears, check these two blur
 * calls before touching the lens values.
 *
 * The `tint` parameter exists for call-site compatibility and is not consumed:
 * the coat is controlled solely by `surfaceColor`.
 */
@Composable
fun Modifier.liquidBottomBar(
    shape: Shape,
    tint: Color,
    surfaceColor: Color,
    pressProgress: Float = 0f,
    tone: Float = Float.NaN,
    exportedBackdrop: com.kyant.backdrop.backdrops.LayerBackdrop? = null,
): Modifier {
    val backdrop = liquidGlassBackdrop()
    val dark = isSystemInDarkTheme()
    val d = bottomBarTone(dark, tone)
    if (backdrop == null) {
        val stableSurface = surfaceColor.compositeOver(MaterialTheme.colorScheme.background)
        return background(stableSurface, shape)
    }
    val p = safeProgress(pressProgress)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            // Keep in sync with the capture layer blur. Constant lens, never
            // multiplied by p: the panel refracts at rest, by design.
            blur(2.dp.toPx())
            lens(24.dp.toPx(), 24.dp.toPx())
        },
        highlight = {
            Highlight.Default.copy(
                alpha = (lerp(0.48f, 0.32f, d) + 0.30f * p).coerceAtMost(1f),
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
                radius = 4.dp + 8.dp * p,
                color = Color.Black.copy(alpha = 0.12f),
                alpha = 0.10f + 0.30f * p,
            )
        },
        layerBlock = {
            val scale = (1f + 16.dp.toPx() / size.width.coerceAtLeast(1f) * p).finiteOrZero()
            scaleX = scale
            scaleY = scale
        },
        exportedBackdrop = exportedBackdrop,
        onDrawSurface = { drawRect(surfaceColor) },
    )
}

/**
 * The droplet's capture layer. This is NOT the same material as the panel and
 * must not reuse it:
 *
 * - The panel lens is constant; this one scales with press progress. At rest
 *   (p ~ 0) the lens is skipped entirely — never call lens(0, 0): inside the
 *   AGSL shader a zero refraction height makes anti-aliased edge pixels (sd > 0)
 *   fall through to a division by zero, producing NaN sample coordinates whose
 *   behavior on GPU is undefined. Skipping the lens outputs the same pixels as
 *   the shader's early return would.
 * - No shadow, no inner shadow. The panel's constant lens would first consume
 *   the page's boundary fold, the panel's top-edge inner shadow band would sit
 *   exactly on the droplet's most contrast-critical fold zone, and the panel's
 *   outer shadow would bake a duplicate lip into the droplet edge.
 *
 * At rest the capture layer is a clean light window; the fold opens only under
 * press. Same blur as the panel, by constraint.
 */
@Composable
fun Modifier.liquidCaptureLayer(
    shape: Shape,
    surfaceColor: Color,
    pressProgress: Float,
): Modifier {
    val backdrop = liquidGlassBackdrop() ?: return this
    val p = safeProgress(pressProgress)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(2.dp.toPx())
            if (p > 0.001f) {
                lens(24.dp.toPx() * p, 24.dp.toPx() * p)
            }
        },
        highlight = { Highlight.Default.copy(alpha = p) },
        shadow = null,
        innerShadow = null,
        onDrawSurface = { drawRect(surfaceColor) },
    )
}

/**
 * The moving selection droplet inside the panel. Samples the page through the
 * panel's exported backdrop (combined), scales the lens proportionally to the
 * droplet height, and carries rest-state floors for rim/shadow/inner shadow so
 * a resting droplet still reads as glass instead of a hole.
 *
 * `pressProgress` gates the fold: at rest the floors keep the bead visible, at
 * full press the values match the reference material exactly.
 */
@Composable
fun Modifier.liquidTabSelection(
    shape: Shape,
    selected: Boolean,
    tint: Color,
    panelBackdrop: Backdrop? = null,
    pressProgress: Float = 0f,
    tone: Float = Float.NaN,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    if (!selected) return this
    val backdrop = liquidGlassBackdrop()
    if (backdrop == null) {
        return background(tint.copy(alpha = maxOf(tint.alpha, 0.36f)), shape)
    }
    val dark = isSystemInDarkTheme()
    val d = bottomBarTone(dark, tone)
    val selectionBackdrop = rememberCombinedBackdrop(backdrop, panelBackdrop ?: backdrop)
    val restRim = lerp(0.48f, 0.32f, d)
    val p = safeProgress(pressProgress)
    return drawBackdrop(
        backdrop = selectionBackdrop,
        shape = { shape },
        effects = {
            // Skip entirely at rest — see liquidCaptureLayer for why lens(0, 0) is
            // forbidden (division by zero in the shader on AA edge pixels).
            if (p > 0.001f) {
                lens(
                    refractionHeight = 10.dp.toPx() * p * DROPLET_LENS_SCALE,
                    refractionAmount = 14.dp.toPx() * p * DROPLET_LENS_SCALE,
                    chromaticAberration = true,
                )
            }
        },
        highlight = { Highlight.Default.copy(alpha = lerp(restRim, 1f, p)) },
        shadow = { Shadow(alpha = lerp(DROPLET_REST_SHADOW, 1f, p)) },
        innerShadow = {
            InnerShadow(
                radius = 4.dp + 4.dp * p,
                alpha = lerp(DROPLET_REST_INNER_SHADOW, 1f, p),
            )
        },
        layerBlock = layerBlock?.let { userBlock ->
            {
                userBlock(this)
                // Everything here lands in a HWUI RenderNode transform matrix;
                // wash NaN/Inf at the single write exit. Identity for normal values.
                scaleX = scaleX.finiteOrZero()
                scaleY = scaleY.finiteOrZero()
                translationX = translationX.finiteOrZero()
                translationY = translationY.finiteOrZero()
                alpha = alpha.finiteOrZero()
            }
        },
        onDrawSurface = {
            // Reference behavior: the selection coat fades out under press and a
            // very faint dark layer fades in.
            drawRect(
                lerp(
                    Color.Black.copy(0.1f),
                    Color.White.copy(0.1f),
                    d,
                ),
                alpha = 1f - p,
            )
            drawRect(Color.Black.copy(alpha = 0.03f * p))
        },
    )
}