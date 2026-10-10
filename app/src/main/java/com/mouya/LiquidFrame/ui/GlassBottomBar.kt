package com.mouya.LiquidFrame.ui

/*
 * Full-width liquid glass bottom navigation bar.
 *
 * The bar is a stack of three independent glass layers, each with its own
 * material:
 *
 *  1. Panel layer (`liquidBottomBar`) - the bar itself. Constant lens, full
 *     shadow/inner shadow, exports its own rendered glass as a LayerBackdrop so
 *     the droplet samples the panel without re-rendering a second copy.
 *  2. Capture layer (`liquidCaptureLayer`) - an invisible light window riding on
 *     the panel above the pressed tab. Lens gated by press progress, no shadow.
 *  3. Droplet selection layer (`liquidTabSelection`) - the moving glass bead.
 *     Samples the page through the exported panel backdrop; rest-state floors on
 *     rim/shadow/inner shadow keep it reading as glass instead of a hole.
 *
 * The bar's light/dark style is decided by real pixels: BottomBarToneSampler
 * captures a band of pure background just above the bar and drives a single
 * tone scalar through hysteresis; every depth-dependent value in the three
 * layers derives from that scalar.
 */

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle

enum class LiquidPage(val icon: ImageVector, val label: String) {
    Home(Icons.Outlined.Home, "首页"),
    Glass(Icons.Outlined.Tune, "参数"),
    About(Icons.Outlined.Info, "关于"),
}

/** Dims the tab content when the module is disabled in the host UI. */
private const val DISABLED_FEEDBACK_SCALE = 0.48f

/**
 * Tab geometry constants. The droplet is the tab slot minus a vertical inset so
 * the bead floats inside the panel instead of touching its edges; the lens scale
 * constant in GlassSystem must be revisited if the droplet height changes.
 */
private object TabGeometry {
    /** Height of the tab slot (the droplet's outer bounds). */
    val slotHeight = 46.dp

    /** Vertical inset between the droplet edge and the slot edge. */
    val dropletInset = 5.dp

    /** Horizontal padding around the tab row inside the panel. */
    val rowPaddingH = 10.dp
}

@Composable
fun GlassBottomBar(
    currentPage: LiquidPage,
    onPageSelected: (LiquidPage) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val systemDark = isDark()

    // Adaptive tone: real sampled pixels decide the bar's light/dark style. The
    // sampler is an enhancement; when its window is unavailable it publishes the
    // system theme as a stable fallback.
    val toneState = rememberBottomBarToneState(systemDark)
    DisposableEffect(toneState) {
        onDispose { toneState.release() }
    }
    val tone = toneState.darkness(systemDark)

    // The panel exports its own glass so the droplet samples the panel pixels
    // without a second render pass of the same material.
    val panelBackdrop = rememberLayerBackdrop()

    val surfaceColor = if (systemDark) {
        Color.Black.copy(alpha = BOTTOM_GLASS_SURFACE_ALPHA)
    } else {
        Color.White.copy(alpha = BOTTOM_GLASS_SURFACE_ALPHA)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned(toneState::updateBounds)
            .liquidBottomBar(
                shape = ContinuousRoundedRectangle(topStart = 22.dp, topEnd = 22.dp),
                tint = if (tone > 0.5f) BottomGlassTintDark else BottomGlassTintLight,
                surfaceColor = surfaceColor,
                tone = tone,
                exportedBackdrop = panelBackdrop,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = 8.dp,
                    bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    start = TabGeometry.rowPaddingH,
                    end = TabGeometry.rowPaddingH,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LiquidPage.entries.forEach { page ->
                val isSelected = currentPage == page
                TabItem(
                    page = page,
                    selected = isSelected,
                    enabled = enabled,
                    tone = tone,
                    panelBackdrop = panelBackdrop,
                    modifier = Modifier.weight(1f),
                    onClick = { onPageSelected(page) },
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    page: LiquidPage,
    selected: Boolean,
    enabled: Boolean,
    tone: Float,
    panelBackdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val contentAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.55f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 400f),
        label = "tab_alpha_${page.name}",
    )

    // Outer slot: the tap target. The capture layer rides here as an invisible
    // light window above the pressed tab.
    Box(
        modifier = modifier
            .height(TabGeometry.slotHeight)
            .liquidCaptureLayer(
                shape = ContinuousRoundedRectangle(TabGeometry.slotHeight / 2),
                surfaceColor = Color.Transparent,
                pressProgress = 0f,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        // The droplet: glass bead sampling the page through the exported panel
        // backdrop. Rest floors keep it visible without a press.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TabGeometry.slotHeight - TabGeometry.dropletInset * 2)
                .liquidTabSelection(
                    shape = ContinuousRoundedRectangle((TabGeometry.slotHeight - TabGeometry.dropletInset * 2) / 2),
                    selected = selected,
                    tint = Color.Black.copy(alpha = 0.10f),
                    panelBackdrop = panelBackdrop,
                    pressProgress = 0f,
                    tone = tone,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    page.icon,
                    contentDescription = page.label,
                    tint = if (selected) {
                        if (tone > 0.5f) Color.White else LiquidColors.blue
                    } else textSecondary(),
                    modifier = Modifier
                        .size(21.dp)
                        .graphicsLayer {
                            this.alpha = contentAlpha
                            if (!enabled) this.alpha *= DISABLED_FEEDBACK_SCALE
                        },
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    page.label,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        if (tone > 0.5f) Color.White else LiquidColors.blue
                    } else textSecondary(),
                    modifier = Modifier.graphicsLayer { this.alpha = contentAlpha },
                )
            }
        }
    }
}