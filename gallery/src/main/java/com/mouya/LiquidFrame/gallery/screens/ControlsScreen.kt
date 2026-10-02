// Copyright 2026, LiquidFrame contributors
// SPDX-License-Identifier: Apache-2.0

package com.mouya.LiquidFrame.gallery.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Stock miuix controls over the live backdrop.
 *
 * None of these are wrapped in glass on purpose. The point of this screen is the *contrast*: the
 * controls sit directly on the surface the navigation bar refracts, which is how you check that
 * the glass is not fighting the host design system — that miuix's own surfaces, type and spacing
 * still read correctly with the material behind them.
 */
@Composable
fun ControlsScreen() {
    var switchA by remember { mutableStateOf(true) }
    var switchB by remember { mutableStateOf(false) }
    var checkA by remember { mutableStateOf(true) }
    var checkB by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var slider by remember { mutableFloatStateOf(0.4f) }
    var progress by remember { mutableFloatStateOf(0.35f) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 96.dp, bottom = 140.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(text = "控件", fontSize = 26.sp, color = MiuixTheme.colorScheme.onBackground)
                Text(
                    text = "小米设计系统组件，直接铺在玻璃折射的那层背景上",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    SmallTitle("开关")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = "折射", fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                        Switch(checked = switchA, onCheckedChange = { switchA = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = "色散", fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                        Switch(checked = switchB, onCheckedChange = { switchB = it })
                    }
                }
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    SmallTitle("多选")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            state = if (checkA) ToggleableState.On else ToggleableState.Off,
                            onClick = { checkA = !checkA },
                        )
                        Text(
                            text = "保留相机原有文字",
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            state = if (checkB) ToggleableState.On else ToggleableState.Off,
                            onClick = { checkB = !checkB },
                        )
                        Text(
                            text = "自适应画面明暗",
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    SmallTitle("输入")
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        label = "水印文字",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    SmallTitle("滑块与进度")
                    Text(
                        text = "折射强度  %.2f".format(slider),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Slider(
                        value = slider,
                        onValueChange = { slider = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Text(
                        text = "进度 %.0f%%".format(progress * 100),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    SmallTitle("按钮")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = { progress = ((progress + 0.15f) % 1f) }) {
                            Text("主要操作")
                        }
                        TextButton(text = "重置", onClick = { slider = 0.2f })
                    }
                }
            }
        }
    }
}
