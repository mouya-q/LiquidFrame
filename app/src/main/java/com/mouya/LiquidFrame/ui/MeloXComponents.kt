package com.mouya.LiquidFrame.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import java.util.Locale

/**
 * MeloX-derived liquid-glass component set for LiquidFrame's config UI.
 *
 * Ported from lladlam/MeloX-Android (GPL-3.0), adapted to run without the
 * kyant0 backdrop RuntimeShader layer so the module stays compatible with
 * minSdk 26. The visual language — capsule shapes, continuous corners,
 * frosted translucent surfaces, iOS toggle — follows MeloX's tokens.
 */
object MeloXColors {
    val blue = Color(0xFF007AFF)
    val lightBg = Color(0xFFF2F2F7)
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

object MeloXShapes {
    val capsule: Shape = Capsule()
    val card: Shape = ContinuousRoundedRectangle(22.dp)
    val compact: Shape = ContinuousRoundedRectangle(16.dp)
}

@Composable
fun isDark(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

@Composable
fun bgPrimary(): Color = if (isDark()) MeloXColors.darkBg else MeloXColors.lightBg

@Composable
fun textPrimary(): Color = if (isDark()) MeloXColors.darkTextPrimary else MeloXColors.lightTextPrimary

@Composable
fun textSecondary(): Color = if (isDark()) MeloXColors.darkTextSecondary else MeloXColors.lightTextSecondary

@Composable
fun separatorColor(): Color = if (isDark()) MeloXColors.darkSeparator else MeloXColors.lightSeparator

@Composable
fun surfaceColor(): Color = if (isDark()) MeloXColors.darkSurface else MeloXColors.lightSurface

/**
 * A MeloX-style glass card: continuous-corner translucent surface with a
 * subtle frosted tint, used for preview containers and grouped settings.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MeloXShapes.card)
            .background(
                if (isDark()) Color(0xFF1C1C1E).copy(alpha = 0.72f)
                else Color.White.copy(alpha = 0.64f)
            )
            .padding(16.dp),
        content = content,
    )
}

/**
 * MeloX-style grouped settings section with a section header and card body.
 */
@Composable
fun SettingSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            title.uppercase(Locale.ROOT),
            color = textSecondary(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MeloXShapes.compact)
                .background(surfaceColor()),
            content = content,
        )
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val mod = Modifier
        .fillMaxWidth()
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
 * MeloX-style glass toggle: a 64×28 capsule track with a 40×24 thumb that
 * slides with a spring animation. Uses the same accent colours as MeloX
 * (green 0xFF34C759 / 0xFF30D158) but without the backdrop lens, which
 * requires API 33+.
 */
@Composable
fun GlassToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val dark = isDark()
    val accent = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    val trackColor = if (dark) Color(0xFF787880).copy(alpha = 0.36f) else Color(0xFF787878).copy(alpha = 0.20f)

    val thumbFraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "toggleThumb",
    )

    val travelPx = 20.dp
    val padding = 2.dp

    Box(
        modifier = modifier
            .width(64.dp)
            .height(28.dp)
            .clip(Capsule())
            .background(if (checked) accent.copy(alpha = 0.88f) else trackColor)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onToggle,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = lerpOffset(padding, padding + travelPx, thumbFraction))
                .clip(Capsule())
                .background(Color.White.copy(alpha = if (enabled) 1f else 0.45f))
                .size(width = 40.dp, height = 24.dp),
        )
    }
}

private fun lerpOffset(start: androidx.compose.ui.unit.Dp, end: androidx.compose.ui.unit.Dp, fraction: Float): androidx.compose.ui.unit.Dp {
    val s = start.value
    val e = end.value
    return (s + (e - s) * fraction).dp
}

@Composable
fun GlassToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
) {
    SettingRow(
        title = label,
        subtitle = subtitle,
        trailing = { GlassToggle(checked, onToggle) },
        onClick = onToggle,
    )
}

@Composable
fun GlassSliderRow(
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
            Text(valueLabel, color = MeloXColors.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = MeloXColors.blue,
                activeTrackColor = MeloXColors.blue,
                inactiveTrackColor = if (isDark()) Color(0xFF3A3A3C) else Color(0xFFD1D1D6),
            ),
        )
    }
}
