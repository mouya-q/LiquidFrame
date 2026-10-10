package com.mouya.LiquidFrame.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle

enum class LiquidPage(val icon: ImageVector, val label: String) {
    Home(Icons.Outlined.Home, "首页"),
    Glass(Icons.Outlined.Tune, "参数"),
    About(Icons.Outlined.Info, "关于"),
}

/**
 * Three-tab liquid glass bottom navigation bar.
 *
 * The bar floats above the scrollable content and provides page switching
 * between Home, Glass Parameters, and About. The selected tab is highlighted
 * with a translucent accent capsule.
 */
@Composable
fun GlassBottomBar(
    currentPage: LiquidPage,
    onPageSelected: (LiquidPage) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = isDark()
    val surfaceColor = if (dark) Color(0xFF1C1C1E).copy(alpha = 0.52f) else Color.White.copy(alpha = 0.52f)
    val tint = if (dark) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.12f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .liquidGlassSurface(
                    shape = ContinuousRoundedRectangle(28.dp),
                    surfaceColor = surfaceColor,
                    tint = tint,
                    enabled = enabled,
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            LiquidPage.entries.forEach { page ->
                val isSelected = currentPage == page
                val alpha by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0.5f,
                    animationSpec = tween(200),
                    label = "tab_${page.name}",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isSelected) LiquidColors.blue.copy(alpha = 0.14f) else Color.Transparent
                        )
                        .clickable(
                            interactionSource = MutableInteractionSource(),
                            indication = null,
                        ) { onPageSelected(page) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            page.icon,
                            contentDescription = page.label,
                            tint = if (isSelected) LiquidColors.blue else textSecondary(),
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayerAlpha(alpha),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            page.label,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) LiquidColors.blue else textSecondary(),
                        )
                    }
                }
            }
        }
    }
}

/** Helper to apply alpha via graphicsLayer. */
@Composable
private fun Modifier.graphicsLayerAlpha(alpha: Float): Modifier =
    this.then(graphicsLayer { this.alpha = alpha })