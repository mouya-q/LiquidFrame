package com.mouya.LiquidFrame.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Main destinations. The dock deliberately stays small and predictable. */
enum class LiquidPage(val icon: ImageVector, val label: String) {
    Home(Icons.Outlined.Home, "首页"),
    Glass(Icons.Outlined.Tune, "参数"),
    About(Icons.Outlined.Info, "关于"),
}

/**
 * A floating, capsule-shaped dock. The old full-width top-rounded sheet and its
 * three independently rendered glass layers have been replaced by one surface
 * and one moving selection lens. That removes the heavy white slab and avoids
 * repeatedly compositing a glass panel over another glass panel.
 */
@Composable
fun GlassBottomBar(
    currentPage: LiquidPage,
    onPageSelected: (LiquidPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isDark()
    val backdrop = liquidGlassBackdrop()
    val index = LiquidPage.entries.indexOf(currentPage).coerceAtLeast(0)
    val latestIndex = rememberUpdatedState(index)
    val latestOnPageSelected = rememberUpdatedState(onPageSelected)
    val animationScope = rememberCoroutineScope()
    val selectionAnimation = remember { Animatable(index.toFloat()) }
    var dockDragging by remember { mutableStateOf(false) }
    var dockDragIndex by remember { mutableFloatStateOf(index.toFloat()) }
    val density = LocalDensity.current
    val sideInsetPx = with(density) { 4.dp.toPx() }
    val slotInsetPx = with(density) { 8.dp.toPx() }
    val selectionScale by animateFloatAsState(
        targetValue = if (dockDragging) 1.045f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "dock_press_scale",
    )
    LaunchedEffect(index) {
        selectionAnimation.animateTo(
            index.toFloat(),
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 460f),
        )
    }
    val animatedIndex = if (dockDragging) dockDragIndex else selectionAnimation.value
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 10.dp + navBottom),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .height(68.dp)
                .pointerInput(Unit) {
                    // Let taps fall through to selectable tabs. Once horizontal touch slop
                    // is crossed, the indicator follows the finger continuously and the
                    // nearest tab is committed on release; vertical motion remains scroll.
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (dragging) {
                                    val slotWidthPx = ((size.width - slotInsetPx).coerceAtLeast(1f)) / LiquidPage.entries.size
                                    val firstCenter = sideInsetPx + slotWidthPx / 2f
                                    dockDragIndex = ((change.position.x - firstCenter) / slotWidthPx)
                                        .coerceIn(0f, LiquidPage.entries.lastIndex.toFloat())
                                    val targetIndex = dockDragIndex.roundToInt().coerceIn(0, LiquidPage.entries.lastIndex)
                                    animationScope.launch {
                                        selectionAnimation.snapTo(dockDragIndex)
                                        dockDragging = false
                                        latestOnPageSelected.value(LiquidPage.entries[targetIndex])
                                        if (targetIndex == latestIndex.value) {
                                            selectionAnimation.animateTo(
                                                targetIndex.toFloat(),
                                                spring(dampingRatio = 0.78f, stiffness = 460f),
                                            )
                                        }
                                    }
                                }
                                break
                            }
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!dragging) {
                                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.15f) {
                                    dragging = true
                                    dockDragging = true
                                    change.consume()
                                } else if (abs(dy) > touchSlop) {
                                    break
                                }
                            } else {
                                change.consume()
                            }
                            if (dragging) {
                                val slotWidthPx = ((size.width - slotInsetPx).coerceAtLeast(1f)) / LiquidPage.entries.size
                                val firstCenter = sideInsetPx + slotWidthPx / 2f
                                dockDragIndex = ((change.position.x - firstCenter) / slotWidthPx)
                                    .coerceIn(0f, LiquidPage.entries.lastIndex.toFloat())
                            }
                        }
                    }
                },
        ) {
            // The item row has 4dp outer padding on each side. Calculate indicator
            // geometry from that same inner width, then leave a further 4dp inset on
            // either side of each selected slot.
            val slotWidth = (maxWidth - 8.dp) / LiquidPage.entries.size
            val indicatorWidth = (slotWidth - 8.dp).coerceAtLeast(1.dp)
            val indicatorModifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 8.dp + slotWidth * animatedIndex)
                .width(indicatorWidth)
                .height(56.dp)
                .graphicsLayer {
                    scaleX = selectionScale
                    scaleY = 2f - selectionScale
                }

            // Base surface first; the selected capsule is drawn on top of it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .then(
                        if (backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { Capsule() },
                                effects = {
                                    vibrancy()
                                    blur(10.dp.toPx())
                                    lens(18.dp.toPx(), 16.dp.toPx())
                                },
                                highlight = {
                                    Highlight.Default.copy(alpha = if (dark) 0.28f else 0.40f)
                                },
                                shadow = {
                                    Shadow(
                                        radius = 20.dp,
                                        color = Color.Black.copy(alpha = if (dark) 0.25f else 0.13f),
                                    )
                                },
                                innerShadow = {
                                    InnerShadow(
                                        radius = 2.dp,
                                        color = Color.White.copy(alpha = 0.13f),
                                        alpha = 0.22f,
                                    )
                                },
                                onDrawSurface = {
                                    drawRect(Color.White.copy(alpha = if (dark) 0.025f else 0.055f))
                                },
                            )
                        } else {
                            Modifier
                                .clip(Capsule())
                                .background(if (dark) Color(0x991C2430) else Color(0x99F5F8FE))
                        },
                    ),
            )

            // The indicator is one translucent lens, not a white tile pasted over the dock.
            Box(
                modifier = if (backdrop != null) {
                    indicatorModifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(7.dp.toPx())
                            lens(13.dp.toPx(), 17.dp.toPx(), chromaticAberration = true)
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = if (dockDragging) 0.72f else if (dark) 0.42f else 0.58f)
                        },
                        shadow = {
                            Shadow(
                                radius = 10.dp,
                                color = Color.Black.copy(alpha = if (dark) 0.22f else 0.12f),
                            )
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 2.dp,
                                color = Color.White.copy(alpha = 0.18f),
                                alpha = 0.34f,
                            )
                        },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = if (dark) 0.035f else 0.075f))
                        },
                    )
                } else {
                    indicatorModifier
                        .clip(Capsule())
                        .background(if (dark) Color.White.copy(alpha = 0.10f) else LiquidColors.blue.copy(alpha = 0.09f))
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiquidPage.entries.forEach { page ->
                    val selected = currentPage == page
                    val alpha by animateFloatAsState(
                        targetValue = if (selected) 1f else 0.66f,
                        animationSpec = spring(dampingRatio = 0.86f, stiffness = 520f),
                        label = "dock_content_${page.name}",
                    )
                    val foreground by animateColorAsState(
                        targetValue = when {
                            selected && !dark -> LiquidColors.blue
                            selected && dark -> Color.White
                            else -> textSecondary()
                        },
                        animationSpec = spring(dampingRatio = 0.92f, stiffness = 600f),
                        label = "dock_tint_${page.name}",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .clip(Capsule())
                            .selectable(
                                selected = selected,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = { onPageSelected(page) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = page.icon,
                            contentDescription = page.label,
                            tint = foreground,
                            modifier = Modifier.graphicsLayer { this.alpha = alpha },
                        )
                        Text(
                            text = page.label,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = foreground,
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .graphicsLayer { this.alpha = alpha },
                        )
                    }
                }
            }
        }
    }
}