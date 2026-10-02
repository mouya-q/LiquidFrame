// Copyright 2026, LiquidFrame contributors
// SPDX-License-Identifier: Apache-2.0

package com.mouya.LiquidFrame.gallery.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.mouya.LiquidFrame.gallery.LiquidBackdrop
import com.mouya.LiquidFrame.gallery.glass.GlassSpec
import com.mouya.LiquidFrame.gallery.glass.liquidGlass

/**
 * The material itself, with its own backdrop so each card is judged independently.
 *
 * Two of these panels are deliberately identical apart from the lens: `FrostedOnly` is the
 * blur-and-tint material, and `Pill` adds refraction on top. Putting them next to each other is
 * the only honest way to show what the lens contributes — on its own a frosted panel already
 * looks like glass, and the difference only appears where the backdrop has structure to bend.
 */
@Composable
fun MaterialScreen() {
    val backdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        // Capture the same animated backdrop, so these panels refract the real thing.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            LiquidBackdrop()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 96.dp, bottom = 140.dp, start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { ScreenHeader("液态玻璃材质", "同一背景下的材质对照") }

            item {
                GlassPanel(
                    backdrop = backdrop,
                    spec = GlassSpec.FrostedOnly,
                    title = "仅磨砂",
                    subtitle = "blur + 着色 + 边缘高光，没有折射",
                )
            }

            item {
                GlassPanel(
                    backdrop = backdrop,
                    spec = GlassSpec.Pill,
                    title = "液态玻璃（折射）",
                    subtitle = "在上面基础上加入圆角折射与边缘色散",
                )
            }

            item {
                GlassPanel(
                    backdrop = backdrop,
                    spec = GlassSpec.Showy,
                    title = "强色散",
                    subtitle = "色散 0.45，折射带更厚 —— 看边缘的彩边",
                )
            }

            item { LiveMaterialTuner(backdrop) }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(text = title, fontSize = 26.sp, color = MiuixTheme.colorScheme.onBackground)
        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun GlassPanel(
    backdrop: LayerBackdrop,
    spec: GlassSpec,
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .liquidGlass(backdrop = backdrop, spec = spec)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, fontSize = 19.sp, color = MiuixTheme.colorScheme.onSurface)
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/**
 * Every quantity in the material on one slider each.
 *
 * Judging glass from fixed presets is guesswork: the right values depend on the host's palette
 * and on how busy the wallpaper is. Being able to drag the refraction band and the dispersion
 * until the rim looks right is the difference between a plausible material and one that is
 * obviously a blur with a highlight drawn on it.
 */
@Composable
private fun LiveMaterialTuner(backdrop: LayerBackdrop) {
    var band by remember { mutableStateOf(18f) }
    var amount by remember { mutableStateOf(26f) }
    var dispersion by remember { mutableStateOf(0.2f) }
    var tintAlpha by remember { mutableStateOf(0.34f) }
    var blurRadius by remember { mutableStateOf(4f) }
    var depth by remember { mutableStateOf(true) }
    var rim by remember { mutableStateOf(0.75f) }

    val spec = remember(band, amount, dispersion, tintAlpha, blurRadius, depth, rim) {
        GlassSpec(
            cornerRadius = 26.dp,
            blurRadius = blurRadius.dp,
            refractionHeight = band.dp,
            refractionAmount = amount.dp,
            depthEffect = depth,
            chromaticAberration = dispersion,
            tint = Color.White.copy(alpha = tintAlpha),
            rimAlpha = rim,
        )
    }

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            SmallTitle("实时调参")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .padding(top = 12.dp)
                    .liquidGlass(backdrop = backdrop, spec = spec),
            )

            TunerSlider("折射带  %.0f dp".format(band), band, 0f..40f) { band = it }
            TunerSlider("折射强度  %.0f dp".format(amount), amount, 0f..64f) { amount = it }
            TunerSlider("色散  %.2f".format(dispersion), dispersion, 0f..0.6f) { dispersion = it }
            TunerSlider("着色  %.2f".format(tintAlpha), tintAlpha, 0f..0.7f) { tintAlpha = it }
            TunerSlider("模糊  %.0f dp".format(blurRadius), blurRadius, 0f..24f) { blurRadius = it }
            TunerSlider("边缘高光  %.2f".format(rim), rim, 0f..1.2f) { rim = it }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "立体感（法线向心倾斜）",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Switch(checked = depth, onCheckedChange = { depth = it })
            }
        }
    }
}

@Composable
private fun TunerSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(text = label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
