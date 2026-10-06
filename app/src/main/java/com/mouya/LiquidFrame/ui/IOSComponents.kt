package com.mouya.LiquidFrame.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * LiquidFrame's small, restrained design system.
 *
 * The old screen tried to make every surface look like glass. That made the material editor read
 * like a prototype rather than a system settings page. The new system keeps glass for previews
 * only and uses quiet grouped surfaces for controls, matching the information hierarchy users
 * expect from a first-party mobile settings app.
 */
object IOSColors {
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

@Composable
fun isDark(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

@Composable
fun bgPrimary(): Color = if (isDark()) IOSColors.darkBg else IOSColors.lightBg

@Composable
fun textPrimary(): Color = if (isDark()) IOSColors.darkTextPrimary else IOSColors.lightTextPrimary

@Composable
fun textSecondary(): Color = if (isDark()) IOSColors.darkTextSecondary else IOSColors.lightTextSecondary

@Composable
fun separatorColor(): Color = if (isDark()) IOSColors.darkSeparator else IOSColors.lightSeparator

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
                .clip(RoundedCornerShape(16.dp))
                .background(if (isDark()) IOSColors.darkSurface else IOSColors.lightSurface),
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
    val modifier = Modifier
        .fillMaxWidth()
        .then(
            if (onClick != null) Modifier.clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ) else Modifier,
        )
        .padding(horizontal = 16.dp, vertical = 13.dp)

    Row(
        modifier = modifier,
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

@Composable
fun IOSToggle(checked: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = IOSColors.success,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = if (isDark()) Color(0xFF39393D) else Color(0xFFE9E9EA),
            uncheckedBorderColor = Color.Transparent,
            checkedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun IOSToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
) {
    SettingRow(
        title = label,
        subtitle = subtitle,
        trailing = { IOSToggle(checked, onToggle) },
        onClick = onToggle,
    )
}

@Composable
fun IOSSettingSliderRow(
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
            Text(valueLabel, color = IOSColors.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = IOSColors.blue,
                activeTrackColor = IOSColors.blue,
                inactiveTrackColor = if (isDark()) Color(0xFF3A3A3C) else Color(0xFFD1D1D6),
            ),
        )
    }
}

/** A deliberately quieter container than a glass card: glass belongs in the preview, not around every control. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = if (isDark()) 0.045f else 0.64f))
            .padding(16.dp),
        content = content,
    )
}

// Kept for source compatibility with older callers.
@Composable
fun LiquidGlassSliderTrack(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val progress = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Box(modifier = modifier.height(4.dp).clip(RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth().background(if (isDark()) Color(0xFF3A3A3C) else Color(0xFFD1D1D6)))
        Box(Modifier.fillMaxWidth(progress).background(IOSColors.blue))
    }
}