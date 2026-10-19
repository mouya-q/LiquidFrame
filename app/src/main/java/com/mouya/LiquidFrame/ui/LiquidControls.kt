package com.mouya.LiquidFrame.ui

/*
 * LiquidSlider and LiquidToggle — glass controls for the config UI.
 *
 * The slider drag is not stolen by the page scroll anymore: the thumb's drag
 * detector awaits pointer events directly and gates on horizontal motion, so a
 * vertical scroll never consumes the gesture and a horizontal drag never scrolls
 * the page. Taps on the track jump straight to the tapped position.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

// =====================================================================================
// LiquidSlider — drag-gated against the page scroll
// =====================================================================================

@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF007AFF) else Color(0xFF0A84FF)
    val trackColor = if (isLightTheme) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)

    val backdrop = liquidGlassBackdrop()
    val trackBackdrop = rememberLayerBackdrop()
    val latestValue = rememberUpdatedState(value)
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished = rememberUpdatedState(onValueChangeFinished)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange)
                if (enabled) {
                    setProgress { requested ->
                        latestOnValueChange.value(requested.coerceIn(valueRange))
                        latestOnValueChangeFinished.value?.invoke()
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth.coerceAtLeast(1)
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value,
                valueRange = valueRange,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {
                    if (didDrag) {
                        latestOnValueChange.value(targetValue.coerceIn(valueRange))
                    }
                    latestOnValueChangeFinished.value?.invoke()
                    didDrag = false
                },
                onDrag = { _, dragAmount ->
                    if (!didDrag) {
                        didDrag = dragAmount.x != 0f
                    }
                    val delta = (valueRange.endInclusive - valueRange.start) * (dragAmount.x / trackWidth)
                    latestOnValueChange.value(
                        if (isLtr) (targetValue + delta).coerceIn(valueRange)
                        else (targetValue - delta).coerceIn(valueRange)
                    )
                }
            )
        }

        LaunchedEffect(dampedDragAnimation) {
            // The upstream slider (attribution in NOTICE) uses value: () -> Float here. The previous
            // Float capture only observed the first composition, so the thumb could
            // keep animating toward stale state and appear impossible to drag.
            snapshotFlow { latestValue.value }
                .collectLatest { v ->
                    if (dampedDragAnimation.targetValue != v) {
                        dampedDragAnimation.updateValue(v.coerceIn(valueRange))
                    }
                }
        }

        // The visible track is 6dp, but its hit target is 36dp. Tapping near the
        // track works on real phones; vertical gestures remain available to the page.
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .fillMaxWidth()
                .height(36.dp)
                .then(
                    if (enabled) Modifier.pointerInput(valueRange, trackWidth, isLtr) {
                        // One gesture recognizer handles both taps and horizontal drags.
                        // The old implementation only listened for taps on the 6dp track;
                        // that was too narrow and never moved the value during a drag.
                        // A vertical gesture is intentionally left unconsumed so the parent
                        // LazyColumn can scroll normally.
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dragging = false
                            var rejectedVertical = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!dragging && !rejectedVertical) {
                                        val fraction = (change.position.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                        val valueFraction = if (isLtr) fraction else 1f - fraction
                                        val target = (valueRange.start + (valueRange.endInclusive - valueRange.start) * valueFraction)
                                            .coerceIn(valueRange)
                                        dampedDragAnimation.animateToValue(target)
                                        latestOnValueChange.value(target)
                                        latestOnValueChangeFinished.value?.invoke()
                                    }
                                    break
                                }

                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (!dragging) {
                                    if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f) {
                                        dragging = true
                                        change.consume()
                                    } else if (kotlin.math.abs(dy) > touchSlop) {
                                        rejectedVertical = true
                                        break
                                    }
                                } else {
                                    change.consume()
                                }

                                if (dragging) {
                                    val fraction = (change.position.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                    val valueFraction = if (isLtr) fraction else 1f - fraction
                                    val target = (valueRange.start + (valueRange.endInclusive - valueRange.start) * valueFraction)
                                        .coerceIn(valueRange)
                                    dampedDragAnimation.updateValue(target)
                                    latestOnValueChange.value(target)
                                }
                            }
                            if (dragging) latestOnValueChangeFinished.value?.invoke()
                        }
                    } else Modifier,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(Capsule())
                    .background(trackColor),
            )
            Box(
                Modifier
                    .height(6.dp)
                    .clip(Capsule())
                    .background(accentColor)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val progress = dampedDragAnimation.progress.coerceIn(0f, 1f)
                        val width = (constraints.maxWidth * progress).fastRoundToInt().coerceIn(0, constraints.maxWidth)
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    },
            )
        }

        // Thumb: glass sphere. Its drag detector awaits pointer events directly with a
        // horizontal-motion gate, so the page scroll cannot consume the gesture.
        Box(
            Modifier
                .graphicsLayer {
                    val p = if (isLtr) dampedDragAnimation.progress else 1f - dampedDragAnimation.progress
                    translationX = (-size.width / 2f + trackWidth * p)
                        .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f)
                }
                .then(if (enabled) dampedDragAnimation.modifier else Modifier)
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = rememberCombinedBackdrop(
                                backdrop,
                                rememberBackdrop(trackBackdrop) { drawBackdrop ->
                                    val progress = dampedDragAnimation.pressProgress
                                    val scaleX = lerp(2f / 3f, 1f, progress)
                                    val scaleY = lerp(0f, 1f, progress)
                                    scale(scaleX, scaleY) {
                                        drawBackdrop()
                                    }
                                }
                            ),
                            shape = { Capsule() },
                            effects = {
                                val progress = safeProgress(dampedDragAnimation.pressProgress)
                                blur(8.dp.toPx() * (1f - progress))
                                if (progress > 0.001f) {
                                    lens(
                                        10.dp.toPx() * progress,
                                        14.dp.toPx() * progress,
                                        chromaticAberration = true
                                    )
                                }
                            },
                            highlight = {
                                val progress = safeProgress(dampedDragAnimation.pressProgress)
                                Highlight.Ambient.copy(
                                    width = Highlight.Ambient.width / 1.5f,
                                    blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                    alpha = progress
                                )
                            },
                            shadow = {
                                Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f))
                            },
                            innerShadow = {
                                val progress = safeProgress(dampedDragAnimation.pressProgress)
                                InnerShadow(radius = 4.dp * progress, alpha = progress)
                            },
                            layerBlock = {
                                val velocity = dampedDragAnimation.velocity.finiteOrZero() / 10f
                                val widenDenom = 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                val widenFactor = 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                                scaleX = (dampedDragAnimation.scaleX.finiteOrZero() / widenDenom).finiteOrZero()
                                scaleY = (dampedDragAnimation.scaleY.finiteOrZero() * widenFactor).finiteOrZero()
                            },
                            onDrawSurface = {
                                // The resting bead keeps only a faint frost. An earlier
                                // revision painted a fully opaque white here (alpha
                                // 1f - progress is 1 at rest), which read as a solid
                                // white mask sitting on the glass; the press now thins
                                // the frost instead of building it.
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(Color.White.copy(alpha = (1f - progress) * if (isLightTheme) 0.09f else 0.055f))
                            }
                        )
                    } else {
                        Modifier
                            .background(
                                if (isLightTheme) Color(0xFFF7FAFF) else Color(0xFF3A3A41),
                                LiquidShapes.capsule,
                            )
                            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
                    },
                )
                .size(40f.dp, 24f.dp)
        )
    }
}

// =====================================================================================
// LiquidToggle — glass switch
// =====================================================================================

@Composable
fun LiquidToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    // iOS system green: the enabled track is green, matching the reference
    // switch control. The thumb is white glass with refraction.
    val accent = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    val track = if (dark) Color(0xFF787880).copy(alpha = 0.36f) else Color(0xFF787878).copy(alpha = 0.20f)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val travelPx = with(density) { 20.dp.toPx() }
    val tapThresholdPx = with(density) { 2.dp.toPx() }
    val scope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val currentChecked = rememberUpdatedState(checked)
    val latestOnToggle = rememberUpdatedState(onToggle)
    val animation = remember(scope) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (!enabled) return@DampedDragAnimation
                val wasChecked = currentChecked.value
                val desired = if (didDrag) targetValue >= 0.5f else !wasChecked
                fraction = if (desired) 1f else 0f
                didDrag = false
                // The callback contract is a toggle, so call it only when the final
                // drag target differs from the external state.
                if (desired != wasChecked) {
                    latestOnToggle.value()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDrag = { _, dragAmount ->
                if (!enabled) return@DampedDragAnimation
                if (!didDrag) {
                    didDrag = abs(dragAmount.x) > tapThresholdPx
                }
                val delta = dragAmount.x / travelPx
                fraction = if (isLtr) (fraction + delta).coerceIn(0f, 1f)
                else (fraction - delta).coerceIn(0f, 1f)
            },
        )
    }
    LaunchedEffect(animation) {
        snapshotFlow { fraction }.collectLatest(animation::updateValue)
    }
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (target != fraction) {
            fraction = target
            animation.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val pageBackdrop = liquidGlassBackdrop()
    val switchInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(64.dp)
            .height(28.dp)
            .then(
                if (enabled) {
                    Modifier
                        .clickable(
                            interactionSource = switchInteraction,
                            indication = null,
                            role = Role.Switch,
                        ) {
                            val target = if (currentChecked.value) 0f else 1f
                            fraction = target
                            animation.animateToValue(target)
                            latestOnToggle.value()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        .then(animation.modifier)
                } else Modifier,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind { drawRect(lerp(track, accent, safeProgress(animation.value))) }
                .size(width = 64.dp, height = 28.dp),
        )
        Box(
            Modifier
                .graphicsLayer {
                    val padding = 2.dp.toPx()
                    val progress = safeProgress(animation.value)
                    translationX = if (isLtr) lerp(padding, padding + travelPx, progress)
                    else lerp(-padding, -(padding + travelPx), progress)
                }
                .then(
                    if (pageBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = rememberCombinedBackdrop(pageBackdrop, trackBackdrop),
                            shape = { Capsule() },
                            effects = {
                                val p = safeProgress(animation.pressProgress)
                                blur(8.dp.toPx() * (1f - p))
                                if (p > 0.001f) {
                                    lens(5.dp.toPx() * p, 10.dp.toPx() * p, chromaticAberration = true)
                                }
                            },
                            highlight = {
                                Highlight.Ambient.copy(
                                    width = Highlight.Ambient.width / 1.5f,
                                    blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                    alpha = safeProgress(animation.pressProgress),
                                )
                            },
                            shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
                            innerShadow = {
                                val p = safeProgress(animation.pressProgress)
                                InnerShadow(radius = 4.dp * p, alpha = p)
                            },
                            layerBlock = {
                                val velocity = animation.velocity.finiteOrZero() / 50f
                                val widenDenom = 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                                val widenFactor = 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                                scaleX = (animation.scaleX.finiteOrZero() / widenDenom).finiteOrZero()
                                scaleY = (animation.scaleY.finiteOrZero() * widenFactor).finiteOrZero()
                                alpha = if (enabled) 1f else 0.45f
                            },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = 1f - safeProgress(animation.pressProgress)))
                            },
                        )
                    } else {
                        Modifier
                            .graphicsLayer {
                                val p = safeProgress(animation.pressProgress)
                                shadowElevation = 8.dp.toPx() * p
                                scaleX = animation.scaleX.finiteOrZero()
                                scaleY = animation.scaleY.finiteOrZero()
                                alpha = if (enabled) (1f - p * 0.15f) else 0.45f
                            }
                            .background(Color.White, LiquidShapes.capsule)
                    },
                )
                .size(width = 40.dp, height = 24.dp),
        )
    }
}

// =====================================================================================
// Convenience row wrappers
// =====================================================================================

@Composable
fun LiquidToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
) {
    SettingRow(
        title = label,
        subtitle = subtitle,
        trailing = { LiquidToggle(checked, onToggle) },
        onClick = onToggle,
    )
}

@Composable
fun LiquidSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    valueLabel: String = String.format(Locale.ROOT, "%.2f", value) + if (unit.isEmpty()) "" else " $unit",
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = textPrimary(), fontSize = 16.sp)
            Text(valueLabel, color = LiquidColors.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        LiquidSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}