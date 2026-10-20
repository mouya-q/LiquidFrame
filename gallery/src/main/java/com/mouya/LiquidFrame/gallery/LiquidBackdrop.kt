// Copyright 2026, LiquidFrame contributors
// SPDX-License-Identifier: Apache-2.0

package com.mouya.LiquidFrame.gallery

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The backdrop everything in the gallery is judged against.
 *
 * A deliberately hard test for the material: strong saturated colour fields, hard edges where
 * two blobs meet, and fine grain. Glass is easy to make look plausible over a soft gradient and
 * very hard over content like this — the lens has to bend visible structure, the dispersion has
 * to split real edges, and the rim has to catch on something.
 *
 * The blobs drift slowly so the refraction can be watched living rather than in a still.
 */
@Composable
fun LiquidBackdrop(modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "backdrop")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        scheme.surface,
                        scheme.surfaceContainerHigh,
                        scheme.surface,
                    ),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            ),
    ) {
        blob(
            color = scheme.primary.copy(alpha = 0.85f),
            x = -0.16f + 0.22f * t,
            y = -0.10f + 0.18f * t,
            size = 340.dp,
        )
        blob(
            color = scheme.tertiaryContainer.copy(alpha = 0.9f),
            x = 0.42f - 0.20f * t,
            y = 0.08f + 0.24f * t,
            size = 300.dp,
        )
        blob(
            color = scheme.secondaryContainer.copy(alpha = 0.85f),
            x = 0.06f + 0.16f * t,
            y = 0.52f - 0.20f * t,
            size = 320.dp,
        )
        blob(
            color = scheme.primaryContainer.copy(alpha = 0.9f),
            x = 0.48f - 0.24f * t,
            y = 0.62f + 0.14f * t,
            size = 260.dp,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.blob(
    color: Color,
    x: Float,
    y: Float,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = (x * 900f).dp, y = (y * 900f).dp)
            .size(size)
            .blur(56.dp)
            .background(color, CircleShape),
    )
}
