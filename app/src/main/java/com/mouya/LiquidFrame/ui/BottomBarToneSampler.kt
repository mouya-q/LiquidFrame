package com.mouya.LiquidFrame.ui

/*
 * Adaptive light/dark controller for the bottom chrome.
 *
 * `d = 0` renders the light style, `d = 1` the dark style. The switch itself is a
 * discrete decision driven by real pixels sampled from the window; the smoothness
 * comes from a critically damped transition animation, never from per-frame
 * adjustments of the threshold.
 *
 * Why a single scalar drives everything: the coat, the rim highlight, the shadows
 * and the foreground colors are all `lerp(light, dark, d)`. If any layer reads the
 * system theme directly it will disagree with the others while `d` is mid-flight,
 * which reads as a smear instead of a switch.
 *
 * Why the sample band must exclude our own glass: PixelCopy captures the composited
 * window, which already contains our own coat. A self-inclusive sample creates a
 * feedback loop — the coat brightens the captured mean, the mean crosses the
 * threshold, the flip changes the coat, the mean crosses back. Sampling a band of
 * pure background just above the bar makes the loop structurally impossible: the
 * candidate value is a constant while the background is still, so a symmetric
 * hysteresis band can flip at most once.
 *
 * The threshold lives on CIELAB L*, not on linear luminance Y. Y = 0.5 maps to
 * L* ~ 72, which is visibly bright to the eye; L* = 50 is the perceptual midpoint.
 * Comparing thresholds on Y would flip to dark far too eagerly.
 */

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal class BottomBarToneState internal constructor() {

    /** Bar rectangle in window coordinates, fed from `onGloballyPositioned`. */
    internal var bounds: Rect? = null

    internal var rawTarget: Float = Float.NaN

    /** Animated target consumed by the UI; drives recomposition. */
    internal var target by mutableFloatStateOf(Float.NaN)
        private set

    private var bitmap: Bitmap? = null

    /** Set on release: the sampling loop must exit instead of allocating a new bitmap. */
    internal var released: Boolean = false

    internal fun publishTarget(v: Float) {
        target = v
        rawTarget = v
    }

    /**
     * The rectangle this round should capture: a band of pure background just above
     * the bar's top edge, inset on both sides to skip the rounded screen corners
     * whose luminance does not represent the content.
     *
     * @return null when there is nothing valid to sample this round.
     */
    internal fun sampleRect(bandPx: Float, insetPx: Float): Rect? {
        val b = bounds ?: return null
        if (b.width() <= 0 || b.height() <= 0) return null
        val left = b.left + insetPx.toInt()
        val right = b.right - insetPx.toInt()
        if (right - left <= 0) return null
        val bottom = b.top
        val top = bottom - bandPx.toInt()
        if (top < 0) return null
        return Rect(left, top, right, bottom)
    }

    internal fun updateBounds(coords: LayoutCoordinates) {
        // positionInWindow() does not exist on every Compose version; localToWindow
        // is the equivalent that does.
        val pos = coords.localToWindow(Offset.Zero)
        bounds = Rect(
            pos.x.toInt(),
            pos.y.toInt(),
            (pos.x + coords.size.width).toInt(),
            (pos.y + coords.size.height).toInt(),
        )
    }

    internal fun ensureBitmap(): Bitmap =
        bitmap ?: Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888).also { bitmap = it }

    internal fun release() {
        released = true
        // Do not recycle: a PixelCopy may still be writing into this bitmap on
        // another thread. It is tiny; let the GC reclaim it.
        bitmap = null
    }
}

private const val SAMPLE_W = 32
private const val SAMPLE_H = 8

/** Interval when the tone is stable and far from the flip threshold. */
private const val SAMPLE_INTERVAL_IDLE_MS = 2_000L

/** Faster when the measured L* hugs the threshold. */
private const val SAMPLE_INTERVAL_NEAR_MS = 500L

/** Fastest while a flip is being confirmed; two hits take about 400 ms. */
private const val SAMPLE_INTERVAL_CONFIRM_MS = 200L

/** Within this many L* of the threshold counts as "near". */
private const val TONE_NEAR_BAND_LSTAR = 8f

/** Per-capture timeout so a missing callback can never stall the loop. */
private const val SAMPLE_TIMEOUT_MS = 900L

/** Sample band height, above the bar's top edge. */
private const val TONE_BAND_DP = 8f

/** Left/right inset of the band, skipping the rounded screen corners. */
private const val TONE_BAND_INSET_DP = 8f

/**
 * Flip midpoint on the perceptual L* scale (0-100), not linear luminance.
 * L* = 50 is the literal perceptual mid-gray.
 */
private const val TONE_FLIP_MID_LSTAR = 50.0f

/**
 * Symmetric hysteresis width around the midpoint, in L*. Light must drop below
 * (mid - delta) to flip dark; dark must rise above (mid + delta) to flip light.
 * Content inside the band never flips, so a still background cannot oscillate.
 */
private const val TONE_FLIP_DEADBAND_LSTAR = 5.0f

/**
 * Consecutive same-side threshold crossings required before actually flipping.
 * Filters single-frame noise from progress bars or other local animations.
 */
private const val TONE_CONFIRM_COUNT = 2

/**
 * Minimum dwell between flips. Covers the transition's settle time and rejects
 * flip trains while scrolling fast. The confirm count is deliberately NOT reset
 * during the cooldown, otherwise every retry starts from zero and scrolling feels
 * sluggish.
 */
private const val TONE_SWITCH_COOLDOWN_MS = 400L

/**
 * Transition curve. Critically damped on purpose: this is a material tone change,
 * not a gesture — overshoot reads as a flash.
 */
private val ToneTransition = spring<Float>(dampingRatio = 1f, stiffness = 180f)

@Composable
internal fun rememberBottomBarToneState(systemDark: Boolean): BottomBarToneState {
    val context = LocalContext.current
    val density = LocalDensity.current
    val state = remember { BottomBarToneState() }
    val bandPx = remember(density) { with(density) { TONE_BAND_DP.dp.toPx() } }
    val insetPx = remember(density) { with(density) { TONE_BAND_INSET_DP.dp.toPx() } }

    LaunchedEffect(state, systemDark) {
        val window = context.findActivity()?.window
        // First frame lands on the system theme so the bar never flashes before
        // the first sample.
        val fallback = if (systemDark) 1f else 0f
        if (window == null) {
            state.publishTarget(fallback)
            return@LaunchedEffect
        }
        state.publishTarget(fallback)

        var decided = Float.NaN
        var pending = Float.NaN
        var pendingCount = 0
        var lastSwitchAt = 0L
        var intervalMs = SAMPLE_INTERVAL_IDLE_MS

        while (true) {
            delay(intervalMs)
            if (state.released) break
            val rect = state.sampleRect(bandPx, insetPx) ?: continue
            val lum = withTimeoutOrNull(SAMPLE_TIMEOUT_MS) {
                state.capture(window, rect)
            } ?: continue

            val lstar = yToLstar(lum)

            val current = if (decided.isNaN()) fallback else decided
            val threshold = if (current > 0.5f) {
                TONE_FLIP_MID_LSTAR + TONE_FLIP_DEADBAND_LSTAR
            } else {
                TONE_FLIP_MID_LSTAR - TONE_FLIP_DEADBAND_LSTAR
            }
            val want = if (current > 0.5f) {
                // Dark now: the background must be clearly brighter than mid-gray
                // to flip back to light.
                if (lstar > threshold) 0f else 1f
            } else {
                // Light now: the background must be clearly darker than mid-gray
                // to flip to dark.
                if (lstar < threshold) 1f else 0f
            }
            val nearThreshold = abs(lstar - threshold) < TONE_NEAR_BAND_LSTAR

            if (want == current) {
                pending = Float.NaN
                pendingCount = 0
                intervalMs = if (nearThreshold) SAMPLE_INTERVAL_NEAR_MS else SAMPLE_INTERVAL_IDLE_MS
                continue
            }
            intervalMs = SAMPLE_INTERVAL_CONFIRM_MS
            if (pending != want) {
                pending = want
                pendingCount = 1
            } else {
                pendingCount++
            }
            if (pendingCount < TONE_CONFIRM_COUNT) continue

            val now = SystemClock.uptimeMillis()
            if (now - lastSwitchAt < TONE_SWITCH_COOLDOWN_MS) continue

            decided = want
            pendingCount = 0
            lastSwitchAt = now
            state.publishTarget(want)
        }
    }

    return state
}

/**
 * Reads the current darkness; returns a stable value whether or not sampling is
 * available. Falls back to the system theme until the first decision lands.
 */
@Composable
internal fun BottomBarToneState.darkness(systemDark: Boolean): Float {
    val fallback = if (systemDark) 1f else 0f
    val targetValue = if (target.isNaN()) fallback else target
    return animateFloatAsState(
        targetValue = targetValue,
        animationSpec = ToneTransition,
        label = "liquidframe-bottom-bar-tone",
    ).value
}

/** One PixelCopy against the window; returns the mean linear-light luminance, or null. */
private suspend fun BottomBarToneState.capture(window: Window, rect: Rect): Float? =
    suspendCancellableCoroutine { cont ->
        if (released) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val bmp = ensureBitmap()
        // suspendCancellableCoroutine, not suspendCoroutine: the outer withTimeoutOrNull
        // cancels on timeout, and a late callback resuming an already-resumed continuation
        // throws. PixelCopy can also throw synchronously when the window has no backing
        // surface yet (first frame, window rebuild, return from background), and some ROMs
        // throw IllegalStateException or SecurityException instead. Sampling is a bonus;
        // every failure degrades to null and the loop just skips this round. Catch Throwable.
        val launched = try {
            PixelCopy.request(
                window,
                rect,
                bmp,
                { result ->
                    if (cont.isActive && !bmp.isRecycled) {
                        cont.resume(
                            if (result == PixelCopy.SUCCESS) {
                                // release() may land between the request and this callback;
                                // getPixels() on a recycled bitmap throws.
                                if (bmp.isRecycled || released) null else meanLinearLuminance(bmp)
                            } else {
                                null
                            }
                        )
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            true
        } catch (t: Throwable) {
            // Let CancellationException through to preserve cancellation semantics.
            if (t is CancellationException) throw t
            false
        }
        if (launched) {
            cont.invokeOnCancellation { /* the bitmap is reclaimed by the GC */ }
        } else {
            cont.resume(null)
        }
    }

/**
 * sRGB pixels -> linear light -> relative luminance, averaged.
 *
 * The average must be taken in linear space: averaging encoded sRGB values
 * compresses the dark end and biases the midpoint low.
 */
private fun meanLinearLuminance(bmp: Bitmap): Float {
    val n = SAMPLE_W * SAMPLE_H
    val px = IntArray(n)
    bmp.getPixels(px, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
    var sum = 0f
    for (i in 0 until n) {
        val c = px[i]
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        sum += 0.2126f * srgbToLinear(r) + 0.7152f * srgbToLinear(g) + 0.0722f * srgbToLinear(b)
    }
    return sum / n
}

private fun srgbToLinear(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

/**
 * Linear relative luminance Y -> CIELAB L* (D65, 0-100).
 *
 * L* is perceptually uniform; the two branches join continuously at
 * 216/24389 with L* = 8, so no jump can distort the hysteresis band.
 */
private fun yToLstar(y: Float): Float {
    val yn = y.coerceIn(0f, 1f)
    val eps = 216f / 24389f
    return if (yn > eps) {
        116f * yn.pow(1f / 3f) - 16f
    } else {
        // Tangent continuation at eps: 116 * (841/108) * Y
        116f * 841f / 108f * yn
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx != null) {
        when (ctx) {
            is Activity -> return ctx
            is ContextWrapper -> ctx = ctx.baseContext
            else -> return null
        }
    }
    return null
}
