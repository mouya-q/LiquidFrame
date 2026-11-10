package com.mouya.LiquidFrame.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import java.util.Locale

/** Palette, shapes and shared settings-row components for the config UI. */
object LiquidColors {
    val blue = Color(0xFF007AFF)
    val lightBg = Color(0xFFFFFFFF)
    val darkBg = Color(0xFF0B0B0D)
    val lightSurface = Color(0xFFFFFFFF)
    val darkSurface = Color(0xFF1C1C1E)
    val lightTextPrimary = Color(0xFF111113)
    val darkTextPrimary = Color(0xFFF5F5F7)
    val lightTextSecondary = Color(0xFF6E6E73)
    val darkTextSecondary = Color(0xFF98989F)
    val lightSeparator = Color(0xFFE5E5EA)
    val darkSeparator = Color(0xFF2C2C2E)
    val success = Color(0xFF34C759)
    val warning = Color(0xFFFF9F0A)
    val error = Color(0xFFFF3B30)
}

object LiquidShapes {
    val capsule: Shape = Capsule()
    val card: Shape = ContinuousRoundedRectangle(22.dp)
    val compact: Shape = ContinuousRoundedRectangle(16.dp)
}

@Composable
fun isDark(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

@Composable
fun bgPrimary(): Color = if (isDark()) LiquidColors.darkBg else LiquidColors.lightBg

@Composable
fun textPrimary(): Color = if (isDark()) LiquidColors.darkTextPrimary else LiquidColors.lightTextPrimary

@Composable
fun textSecondary(): Color = if (isDark()) LiquidColors.darkTextSecondary else LiquidColors.lightTextSecondary

@Composable
fun separatorColor(): Color = if (isDark()) LiquidColors.darkSeparator else LiquidColors.lightSeparator

@Composable
fun surfaceColor(): Color = if (isDark()) LiquidColors.darkSurface else LiquidColors.lightSurface

/**
 * A glass card: continuous-corner surface that samples the backdrop behind
 * it for real frosted glass when hardware acceleration is available, falling
 * back to a translucent solid surface otherwise.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassCard(shape = LiquidShapes.card, cornerRadius = 22.dp, glass = glassContainer())
            .padding(16.dp),
        content = content,
    )
}

/**
 * Grouped settings section with a section header and card body.
 *
 * The collapse is animated on both axes: the body expands with a spring rather than
 * appearing instantly, and the disclosure chevron sweeps 180° on the same spring so the
 * header and the body read as one gesture. Content fades in slightly *after* the height
 * starts moving, which stops text from popping into a half-open card.
 */
@Composable
fun SettingSection(
    title: String,
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    val visible = !collapsible || expanded

    // A pressed-state scale on the header, so tapping the title has tactile feedback even
    // though the row has no ripple (the glass surface would look wrong with one).
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val headerScale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "sectionHeaderScale",
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (visible) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "sectionChevron",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = headerScale
                    scaleY = headerScale
                }
                .then(
                    if (collapsible) Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { expanded = !expanded } else Modifier
                )
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title.uppercase(Locale.ROOT),
                color = textSecondary(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                modifier = Modifier.weight(1f),
            )
            if (collapsible) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = textSecondary(),
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                )
            }
        }
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = 380f,
                    visibilityThreshold = IntSize.VisibilityThreshold,
                ),
                expandFrom = Alignment.Top,
            ) + fadeIn(animationSpec = tween(durationMillis = 170, delayMillis = 55)),
            exit = shrinkVertically(
                animationSpec = spring(
                    dampingRatio = 0.92f,
                    stiffness = 460f,
                    visibilityThreshold = IntSize.VisibilityThreshold,
                ),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(animationSpec = tween(durationMillis = 110)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassCard(shape = LiquidShapes.compact, cornerRadius = 16.dp, glass = glassContainer()),
                content = content,
            )
        }
    }
}

/**
 * A tappable settings row.
 *
 * A press dims and very slightly shrinks the row, which is the closest a list row can get to
 * a button without a ripple. The motion is short and fully settled before a finger lifts in
 * the common case, so it reads as "this responded" rather than as an animation.
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && onClick != null) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 1100f),
        label = "settingRowPress",
    )
    val mod = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        }
        .then(
            if (onClick != null) Modifier.clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            ) else Modifier,
        )
        .padding(horizontal = 16.dp, vertical = 13.dp)

    Row(
        modifier = mod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                color = if (enabled) textPrimary() else textSecondary(),
                fontSize = 16.sp,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    color = textSecondary(),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        trailing()
    }
}

@Composable
fun SettingDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = separatorColor(),
    )
}

/**
 * Page transition for the dashboard.
 *
 * The four pages used to be a bare `when`, so switching tabs replaced the whole screen in one
 * frame while the bottom bar's capsule was still sliding — that mismatch is what made the UI
 * feel unbuilt. This fades and lifts the incoming page by a few pixels, and slides it in from
 * the side the tab sits on, so the content follows the indicator rather than cutting to it.
 *
 * Only a few pixels of horizontal travel are used: this is a tab switch, not a stack push, and
 * a large slide would fight the bottom bar's own spring.
 */
@Composable
fun <T> LiquidPageTransition(
    page: T,
    ordinalOf: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = {
            val forward = ordinalOf(targetState) >= ordinalOf(initialState)
            val shift = 18
            (slideInHorizontally(
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 620f),
                initialOffsetX = { if (forward) shift else -shift },
            ) + fadeIn(animationSpec = tween(durationMillis = 180))) togetherWith
                (slideOutHorizontally(
                    animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
                    targetOffsetX = { if (forward) -shift else shift },
                ) + fadeOut(animationSpec = tween(durationMillis = 110)))
        },
        label = "liquidPage",
        content = content,
    )
}

/**
 * Rounded status pill used in the page headers.
 * The colour and the text cross-fade instead of swapping instantly: a capture setting that
 * flips from "已启用" to "已停用" is the one place in the UI where the change is the whole
 * message, so it should be legible as a transition rather than as a flicker.
 */
@Composable
fun StatusPill(text: String, color: Color) {
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = 260),
        label = "statusPillColor",
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(animatedColor.copy(alpha = 0.14f))
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        // Crossfade keeps the pill's width from snapping when the two labels differ in length.
        Crossfade(
            targetState = text,
            animationSpec = tween(durationMillis = 200),
            label = "statusPillText",
        ) { label ->
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = animatedColor)
        }
    }
}