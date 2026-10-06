package com.mouya.LiquidFrame.ui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import java.util.Locale

// ---------------------------------------------------------------------------------------
// Design tokens
// ---------------------------------------------------------------------------------------

object IOSColors {
    val blue = Color(0xFF007AFF)
    val green = Color(0xFF34C759)
    val lightBg = Color(0xFFF2F2F7)
    val darkBg = Color(0xFF000000)
    val lightCard = Color(0xFFFFFFFF)
    val darkCard = Color(0xFF1C1C1E)
    val glassLight = Color(0xFFFFFFFF).copy(alpha = 0.72f)
    val glassDark = Color(0xFF1C1C1E).copy(alpha = 0.72f)
    val lightTextPrimary = Color(0xFF000000)
    val darkTextPrimary = Color(0xFFFFFFFF)
    val lightTextSecondary = Color(0xFF3C3C43).copy(alpha = 0.6f)
    val darkTextSecondary = Color(0xFFEBEBF5).copy(alpha = 0.6f)
    val separatorLight = Color(0xFFC6C6C8)
    val separatorDark = Color.White.copy(alpha = 0.10f)
    val toggleOffLight = Color(0xFFE9E9EA)
    val toggleOffDark = Color(0xFF39393B)
}

@Composable
fun isDark() = isSystemInDarkTheme()

@Composable
fun bgPrimary() = if (isDark()) IOSColors.darkBg else IOSColors.lightBg

@Composable
fun glassColor() = if (isDark()) IOSColors.glassDark else IOSColors.glassLight

@Composable
fun textPrimary() = if (isDark()) IOSColors.darkTextPrimary else IOSColors.lightTextPrimary

@Composable
fun textSecondary() = if (isDark()) IOSColors.darkTextSecondary else IOSColors.lightTextSecondary

@Composable
fun separatorColor() = if (isDark()) IOSColors.separatorDark else IOSColors.separatorLight

// ---------------------------------------------------------------------------------------
// Haptics + spring specs
// ---------------------------------------------------------------------------------------

/**
 * Feedback on direct manipulation. The settings screen is a tuning surface, so every gesture that
 * changes a value has to be felt as well as seen — otherwise the eye has to travel back to the
 * label on every adjustment.
 *
 * Uses [View.performHapticFeedback] rather than a vibrator so the feedback follows the platform's
 * haptic settings (and stays silent when the user has turned haptics off).
 */
class HapticFeedback(context: Context) {

    /**
     * The token used for [View.performHapticFeedback].
     *
     * A View has to be attached to a window for its haptic tokens to resolve, so this holds the
     * activity decor view when there is one and falls back to a detached view otherwise. The
     * fallback still keeps the call site honest: if the token does not resolve, the tick is
     * simply not delivered, which is preferable to crashing the settings screen.
     */
    private var view: View? = null

    enum class Style { LIGHT_TICK, SELECTION, CONTINUOUS_HUM, KICK }

    fun attach(candidate: View?) {
        if (candidate != null) view = candidate
    }

    fun perform(style: Style) {
        val v = view ?: return
        val constant = when {
            style == Style.LIGHT_TICK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            style == Style.SELECTION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                HapticFeedbackConstants.SEGMENT_TICK
            style == Style.KICK -> HapticFeedbackConstants.LONG_PRESS
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }
        try {
            v.performHapticFeedback(constant)
        } catch (_: Throwable) {
        }
    }

    fun release() {
        view = null
    }
}

/** Spring specs matching the reference implementation's feel. */
object PhysicsSpring {
    fun uiFast() = spring<Float>(
        dampingRatio = 0.55f,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun uiStandard() = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = Spring.StiffnessMediumLow,
    )
}

// ---------------------------------------------------------------------------------------
// Toggle
// ---------------------------------------------------------------------------------------

/**
 * iOS-style switch. The whole row is the hit target, not just the 52x32 track, because a switch
 * this small is otherwise hard to hit and — more importantly — hard to *see* that it responded.
 */
@Composable
fun IOSToggle(checked: Boolean, onToggle: () -> Unit) {
    val haptic = remember { HapticFeedback(LocalContext.current).apply { attach(LocalView.current) } }
    val scope = rememberCoroutineScope()
    val thumbOffset = remember { Animatable(if (checked) 22f else 2f) }
    val trackWidth = 52f
    val thumbSize = 28f

    LaunchedEffect(checked) {
        thumbOffset.animateTo(
            targetValue = if (checked) (trackWidth - thumbSize - 2f) else 2f,
            animationSpec = spring(
                dampingRatio = 0.62f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (checked) IOSColors.green
                else if (isDark()) IOSColors.toggleOffDark
                else IOSColors.toggleOffLight
            )
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    haptic.perform(HapticFeedback.Style.SELECTION)
                    onToggle()
                })
            },
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.value.toInt(), 2.dp.roundToPx()) }
                .size(thumbSize.dp)
                .clip(CircleShape)
                .background(Color.White)
                .shadow(2.dp, CircleShape)
        )
    }
}

// ---------------------------------------------------------------------------------------
// Slider
// ---------------------------------------------------------------------------------------

/**
 * iOS-style slider with real direct manipulation.
 *
 * The previous version drew a track and a thumb but registered no pointer input at all, so the
 * row was inert: touching it did nothing and only the value label moved. This one owns two
 * gesture detectors — drag and tap — and maps pointer x to a value through the measured track
 * width, exactly like the reference implementation.
 */
@Composable
fun IOSSettingSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit,
) {
    val haptic = remember { HapticFeedback(LocalContext.current).apply { attach(LocalView.current) } }
    val thumbScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // The thumb must track the finger continuously, including values that arrive from outside
    // (reset-to-defaults), so it is derived from `value` rather than accumulated in an Animatable.
    val thumbScaleSpring = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 15.sp, color = textPrimary())
            Text(
                text = String.format(Locale.ROOT, "%.2f", value) + if (unit.isEmpty()) "" else " $unit",
                fontSize = 15.sp,
                color = IOSColors.blue,
                fontWeight = FontWeight.Medium,
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                // `value` is deliberately NOT a key here: it changes on every drag frame and would
                // cancel and restart the gesture coroutine mid-drag, which is exactly the
                // 'the slider drops my finger' bug. The handler reaches the current value
                // through the lambda, so it never needs to be part of the key set.
                .pointerInput(range) {
                    val widthPx = size.width.toFloat()
                    fun xToValue(x: Float): Float {
                        val progress = (x / widthPx).coerceIn(0f, 1f)
                        return range.start + progress * (range.endInclusive - range.start)
                    }
                    detectDragGestures(
                        onDragStart = { offset ->
                            scope.launch { thumbScale.animateTo(1.25f, thumbScaleSpring) }
                            haptic.perform(HapticFeedback.Style.CONTINUOUS_HUM)
                            onValueChange(xToValue(offset.x))
                        },
                        onDragEnd = {
                            scope.launch { thumbScale.animateTo(1f, thumbScaleSpring) }
                            haptic.perform(HapticFeedback.Style.KICK)
                        },
                        onDragCancel = {
                            scope.launch { thumbScale.animateTo(1f, thumbScaleSpring) }
                        },
                    ) { change, _ ->
                        change.consume()
                        onValueChange(xToValue(change.position.x))
                    }
                }
                .pointerInput(range) {
                    detectTapGestures(
                        onTap = { offset ->
                            val widthPx = size.width.toFloat()
                            val progress = (offset.x / widthPx).coerceIn(0f, 1f)
                            onValueChange(range.start + progress * (range.endInclusive - range.start))
                            haptic.perform(HapticFeedback.Style.LIGHT_TICK)
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val progress = ((value - range.start) / (range.endInclusive - range.start))
                .coerceIn(0f, 1f)
            val thumbSize = 24.dp
            val thumbOffset = maxWidth * progress - thumbSize / 2

            LiquidGlassSliderTrack(
                value = value,
                range = range,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .graphicsLayer {
                        scaleX = thumbScale.value
                        scaleY = thumbScale.value
                    }
                    .shadow(5.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(0.5.dp, IOSColors.blue.copy(alpha = 0.18f), CircleShape),
            )
        }
    }
}

/**
 * The track: a translucent groove with a gradient "fill" behind the thumb, so it reads as a glass
 * channel catching light rather than a flat coloured bar.
 */
@Composable
fun LiquidGlassSliderTrack(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val progress = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val trackHeight = 6.dp
    val corner = RoundedCornerShape(3.dp)
    val groove = if (isDark()) IOSColors.toggleOffDark else IOSColors.toggleOffLight

    Box(modifier = modifier.height(trackHeight)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(corner)
                .background(groove),
        )
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(trackHeight)
                .clip(corner)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            IOSColors.blue.copy(alpha = 0.72f),
                            IOSColors.blue,
                        ),
                    ),
                ),
        )
        // Specular hairline along the top edge — the same trick the reference uses on its cards.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = if (isDark()) 0.10f else 0.45f)),
        )
    }
}

// ---------------------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------------------

/** Translucent rounded card. Used for every group of controls. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(glassColor())
            .border(
                0.5.dp,
                if (isDark()) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f),
                RoundedCornerShape(22.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/**
 * A switch row where the *entire row* toggles. [IOSToggle] alone is a 52x32 target; the reference
 * makes the label part of it so the affordance is unambiguous.
 */
@Composable
fun IOSToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
) {
    val haptic = remember { HapticFeedback(LocalContext.current).apply { attach(LocalView.current) } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        haptic.perform(HapticFeedback.Style.SELECTION)
                        onToggle()
                    },
                )
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 12.dp)) {
            Text(label, fontSize = 16.sp, color = textPrimary())
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = textSecondary())
            }
        }
        IOSToggle(checked = checked, onToggle = onToggle)
    }
}