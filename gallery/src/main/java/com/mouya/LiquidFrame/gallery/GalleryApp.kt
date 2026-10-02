// Copyright 2026, LiquidFrame contributors
// SPDX-License-Identifier: Apache-2.0

package com.mouya.LiquidFrame.gallery

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.mouya.LiquidFrame.gallery.glass.GlassSpec
import com.mouya.LiquidFrame.gallery.glass.liquidGlass
import com.mouya.LiquidFrame.gallery.screens.ControlsScreen
import com.mouya.LiquidFrame.gallery.screens.MaterialScreen
import com.mouya.LiquidFrame.gallery.screens.OverlaysScreen

private val tabs = listOf("材质", "控件", "弹层")

/**
 * The gallery.
 *
 * The layering is what makes the glass real, so it is worth being explicit about it:
 *
 * 1. [LiquidBackdrop] is the *only* thing inside `Modifier.layerBackdrop(backdrop)`, so the
 *    captured layer holds the colourful backdrop and nothing else. Capturing more than intended
 *    is the classic way to end up with glass that refracts the UI sitting on top of it.
 * 2. The pager sits above the backdrop and is not captured.
 * 3. The navigation bar floats above both and samples (1) through [liquidGlass] — which is why
 *    its rim visibly bends the colour blobs as they drift past.
 */
@Composable
fun GalleryApp() {
    val backdrop = rememberLayerBackdrop()
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val navBarSpec = remember { GlassSpec.forHeight(64.dp, cornerRadius = 32.dp) }

    // The scaffold contributes no colour of its own — it is here because miuix's Overlay*
    // components render into a root scaffold's popup host by default. An opaque container here
    // would cover the backdrop before the glass ever got to sample it.
    Scaffold(containerColor = Color.Transparent) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                LiquidBackdrop()
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> MaterialScreen()
                    1 -> ControlsScreen()
                    else -> OverlaysScreen()
                }
            }

            GlassNavigationBar(
                selectedIndex = pagerState.currentPage,
                onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                backdrop = backdrop,
                spec = navBarSpec,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues()
                            .calculateBottomPadding() + 12.dp,
                    ),
            )
        }
    }
}

/**
 * A floating glass navigation bar.
 *
 * The bar has no background colour of its own: everything visible inside it is either the
 * refracted backdrop, the tint the material paints over it, or a label. That is the whole point —
 * the moment a bar draws an opaque surface, the glass underneath stops mattering.
 */
@Composable
fun GlassNavigationBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop,
    spec: GlassSpec,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            // A bar this wide and short reads as a capsule, which is the proportion the
            // reference's lens is tuned for.
            .liquidGlass(backdrop = backdrop, spec = spec.copy(cornerRadius = 32.dp))
            .selectableGroup()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val contentColor by animateColorAsState(
                targetValue = if (selected) {
                    scheme.primary
                } else {
                    scheme.onSurfaceVariantSummary
                },
                label = "tabColor",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .selectable(
                        selected = selected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        color = contentColor,
                        fontSize = if (selected) 15.sp else 14.sp,
                    )
                }
            }
        }
    }
}
