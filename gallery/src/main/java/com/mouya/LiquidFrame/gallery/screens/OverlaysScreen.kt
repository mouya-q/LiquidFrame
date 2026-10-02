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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * miuix's overlay surfaces.
 *
 * These are the components where glass matters most in real HyperOS UI, and also where it is
 * hardest: a dimmed window and a large opaque sheet both hide the backdrop, so the material has
 * nothing left to refract underneath. Showing the stock behaviour next to the note is more useful
 * than pretending the platform dialogs are glassy — the honest statement is *which* surfaces can
 * carry the material and which cannot.
 */
@Composable
fun OverlaysScreen() {
    var showDialog by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 96.dp, bottom = 140.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(text = "弹层", fontSize = 26.sp, color = MiuixTheme.colorScheme.onBackground)
                Text(
                    text = "对话框与底部面板，以及它们和玻璃的关系",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    SmallTitle("为什么弹层不适合做玻璃")
                    Text(
                        text = "对话框会压暗背景，底部面板自带不透明底色。两者都把玻璃要折射的那层内容盖住了，" +
                            "这时候再加折射只会得到一片糊。玻璃适合的是「浮在内容之上、但让内容透过来」的常驻组件：" +
                            "导航栏、吸附底栏、悬浮工具条。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    SmallTitle("示例")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = { showDialog = true }) { Text("对话框") }
                        Button(onClick = { showSheet = true }) { Text("底部面板") }
                    }
                }
            }
        }

        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    SmallTitle("玻璃层叠")
                    Text(
                        text = "把一块玻璃放在另一块玻璃上时，下层必须先把上层的内容录进自己的 backdrop，" +
                            "否则上层折射到的还是上一帧。这是层叠玻璃唯一正确的做法，" +
                            "也是为什么这个示例里每一屏都各自持有一个 backdrop。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    OverlayDialog(
        show = showDialog,
        title = "对话框",
        summary = "平台自带的对话框，保持不透明以保证可读性。",
        onDismissRequest = { showDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            TextButton(text = "关闭", onClick = { showDialog = false })
            Button(onClick = { showDialog = false }) { Text("确定") }
        }
    }

    OverlayBottomSheet(
        show = showSheet,
        title = "底部面板",
        onDismissRequest = { showSheet = false },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "面板内容。整体式玻璃底栏在这里会被面板自己的底色盖住，" +
                    "所以常驻的玻璃条应该直接放在页面底部，而不是塞进一个 sheet 里。",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}
