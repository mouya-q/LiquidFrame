package com.mouya.LiquidFrame.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Main destinations. */
enum class LiquidPage(val icon: ImageVector, val label: String) {
    Home(Icons.Outlined.Home, "首页"),
    Glass(Icons.Outlined.Tune, "参数"),
    About(Icons.Outlined.Info, "关于"),
}

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf<() -> Float> { { 1f } }

@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(Capsule())
            .selectable(
                selected = false,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/**
 * A floating, capsule-shaped liquid glass dock.
 *
 * Faithfully follows the reference architecture: one outer container layer
 * (vibrancy + blur + lens), a hidden accent-tinted duplicate for the indicator
 * to sample via combined backdrop, and a visible indicator lens driven by
 * DampedDragAnimation with velocity deformation and press feedback.
 */
@Composable
fun GlassBottomBar(
    currentPage: LiquidPage,
    onPageSelected: (LiquidPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isDark()
    val backdrop = liquidGlassBackdrop() ?: return
    val tabsCount = LiquidPage.entries.size
    val index = LiquidPage.entries.indexOf(currentPage).coerceAtLeast(0)

    val accentColor = if (!dark) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (!dark) Color(0xFFFAFAFA).copy(0.4f) else Color(0xFF121212).copy(0.4f)

    val tabsBackdrop = rememberLayerBackdrop()
    val animationScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

    var currentIndex by remember { mutableIntStateOf(index) }
    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density) {
        derivedStateOf {
            val fraction = (offsetAnimation.value / 360f).fastCoerceIn(-1f, 1f)
            with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
        }
    }

    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = index.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            onDragStarted = {},
            onDragStopped = {
                val targetIdx = targetValue.fastRoundToInt().coerceIn(0, tabsCount - 1)
                currentIndex = targetIdx
                animateToValue(targetIdx.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { size, dragAmount ->
                val tabWidth = size.width.toFloat() / tabsCount
                updateValue(
                    (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                        .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                )
                animationScope.launch {
                    offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                }
            },
        )
    }

    LaunchedEffect(index) {
        snapshotFlow { index }.collectLatest { i -> currentIndex = i }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }
            .drop(1)
            .collectLatest { i ->
                dampedDragAnimation.animateToValue(i.toFloat())
                onPageSelected(LiquidPage.entries[i])
            }
    }

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
                .height(64.dp)
                .then(dampedDragAnimation.modifier),
        ) {
            val tabWidth = with(density) {
                (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
            }

            // --- Layer 1: Outer container glass ---
            Row(
                Modifier
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(with(density) { 8f.dp.toPx() })
                            lens(with(density) { 24f.dp.toPx() }, with(density) { 24f.dp.toPx() })
                        },
                        layerBlock = {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + with(density) { 16f.dp.toPx() } / size.width.coerceAtLeast(1f), progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .height(64.dp)
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiquidPage.entries.forEachIndexed { i, page ->
                    val selected = currentPage == page
                    val fg by androidx.compose.animation.animateColorAsState(
                        targetValue = when {
                            selected && !dark -> accentColor
                            selected && dark -> Color.White
                            else -> textSecondary()
                        },
                        animationSpec = spring(dampingRatio = 0.92f, stiffness = 600f),
                        label = "dock_tint_${page.name}",
                    )
                    LiquidBottomTab(
                        onClick = {
                            currentIndex = i
                            onPageSelected(page)
                        },
                    ) {
                        Icon(
                            imageVector = page.icon,
                            contentDescription = page.label,
                            tint = fg,
                            modifier = Modifier.graphicsLayer {
                                this.alpha = if (selected) 1f else 0.66f
                            },
                        )
                        Text(
                            text = page.label,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = fg,
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .graphicsLayer { this.alpha = if (selected) 1f else 0.66f },
                        )
                    }
                }
            }

            // --- Layer 2: Hidden accent-tinted backdrop for indicator sampling ---
            CompositionLocalProvider(
                LocalLiquidBottomTabScale provides {
                    lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                }
            ) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                                blur(with(density) { 8f.dp.toPx() })
                                lens(
                                    with(density) { 24f.dp.toPx() } * progress,
                                    with(density) { 24f.dp.toPx() } * progress,
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                        .height(56.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiquidPage.entries.forEach { _ ->
                        LiquidBottomTab(onClick = {}) {
                            Box(Modifier.fillMaxHeight().weight(1f))
                        }
                    }
                }
            }

            // --- Layer 3: Visible indicator lens ---
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        translationX = if (isLtr) {
                            dampedDragAnimation.value * tabWidth + panelOffset
                        } else {
                            constraints.maxWidth.toFloat() -
                                (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                        }
                    }
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            lens(
                                with(density) { 10f.dp.toPx() } * progress,
                                with(density) { 14f.dp.toPx() } * progress,
                                chromaticAberration = true,
                            )
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                        },
                        shadow = {
                            Shadow(alpha = dampedDragAnimation.pressProgress)
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = with(density) { 8f.dp } * progress,
                                alpha = progress,
                            )
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(
                                if (!dark) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                                alpha = 1f - progress,
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        },
                    )
                    .height(56.dp)
                    .fillMaxWidth(1f / tabsCount),
            )
        }
    }
}