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
 * LiquidFrame glass component set.
 *
 * Uses the full kyant0 backdrop RuntimeShader layer (already declared in
 * build.gradle) for real backdrop-sampled glass. The visual language —
 * capsule shapes, continuous corners, frosted translucent surfaces —
 * follows the kyant0 AndroidLiquidGlass catalog.
 *
 * Glass surfaces fall back to solid translucent backgrounds when the
 * window lacks hardware acceleration (API < 33 or software rendering),
 * so the UI remains readable on every device.
 */
object LiquidColors {
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
            .liquidGlassSurface(
                shape = LiquidShapes.card,
                surfaceColor = if (isDark()) Color(0xFF1C1C1E).copy(alpha = 0.72f)
                else Color.White.copy(alpha = 0.64f),
            )
            .padding(16.dp),
        content = content,
    )
}

/**
 * Grouped settings section with a section header and card body.
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
                .liquidGlassSurface(
                    shape = LiquidShapes.compact,
                    surfaceColor = surfaceColor().copy(alpha = 0.52f),
                ),
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

private fun lerpOffset(start: androidx.compose.ui.unit.Dp, end: androidx.compose.ui.unit.Dp, fraction: Float): androidx.compose.ui.unit.Dp {
    val s = start.value
    val e = end.value
    return (s + (e - s) * fraction).dp
}