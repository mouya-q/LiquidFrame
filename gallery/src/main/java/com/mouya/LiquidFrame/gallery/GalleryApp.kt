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
import androidx.compose.foundation.layout.statusBars
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

@Composable
fun GalleryApp() {
    val backdrop = rememberLayerBackdrop()
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val navBarSpec = remember { GlassSpec.forHeight(58.dp, cornerRadius = 29.dp) }

    Scaffold(containerColor = Color.Transparent) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { LiquidBackdrop() }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> MaterialScreen()
                    1 -> ControlsScreen()
                    else -> OverlaysScreen()
                }
            }

            GalleryTopMark(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                        start = 20.dp,
                        end = 20.dp,
                    ),
            )

            GalleryNavigationBar(
                selectedIndex = pagerState.currentPage,
                onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                backdrop = backdrop,
                spec = navBarSpec,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp),
            )
        }
    }
}

@Composable
private fun GalleryTopMark(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("LiquidFrame", color = MiuixTheme.colorScheme.onBackground, fontSize = 16.sp)
        Text("Material Lab", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 11.sp)
    }
}

@Composable
private fun GalleryNavigationBar(
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
            .height(58.dp)
            .liquidGlass(backdrop = backdrop, spec = spec)
            .selectableGroup()
            .padding(horizontal = 5.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val textColor by animateColorAsState(
                targetValue = if (selected) scheme.primary else scheme.onSurfaceVariantSummary,
                label = "galleryTabColor",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .selectable(
                        selected = selected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = textColor, fontSize = if (selected) 14.sp else 13.sp)
            }
        }
    }
}
