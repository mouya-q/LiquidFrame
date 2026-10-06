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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ControlsScreen() {
    var refraction by remember { mutableStateOf(true) }
    var dispersion by remember { mutableStateOf(false) }
    var preserve by remember { mutableStateOf(true) }
    var adaptive by remember { mutableStateOf(true) }
    var strength by remember { mutableFloatStateOf(0.4f) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 68.dp, bottom = 96.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("控件", "看看 HyperOS 原生控件放进这套材质后的比例。") }

        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    SmallTitle("状态")
                    ControlSwitch("折射", "边缘位移", refraction) { refraction = it }
                    ControlSwitch("色散", "彩边强度", dispersion) { dispersion = it }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    SmallTitle("参数")
                    Text("折射强度  %.2f".format(strength), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 12.dp))
                    Slider(value = strength, onValueChange = { strength = it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text("这个页只演示控件；真正参数在主模块设置页修改。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    SmallTitle("选项")
                    ControlCheck("保留相机原有文字", preserve) { preserve = it }
                    ControlCheck("自适应画面明暗", adaptive) { adaptive = it }
                }
            }
        }
    }
}

@Composable
private fun ControlSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ControlCheck(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off, onClick = { onChecked(!checked) })
        Text(title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 2.dp)) {
        Text(title, fontSize = 28.sp, color = MiuixTheme.colorScheme.onBackground)
        Text(subtitle, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 4.dp))
    }
}
