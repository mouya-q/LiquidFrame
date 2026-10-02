// Copyright 2026, LiquidFrame contributors
// SPDX-License-Identifier: Apache-2.0

package com.mouya.LiquidFrame.gallery

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Wraps the gallery in Xiaomi's own design system.
 *
 * [ThemeController] with [ColorSchemeMode.MonetSystem] derives the palette from the wallpaper
 * through Monet, so the gallery picks up the device's real HyperOS accent colour instead of an
 * invented one. That matters for judging glass: the material has to sit correctly on top of the
 * host's actual colour scheme, not on a palette chosen to flatter it.
 */
@Composable
fun GalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MiuixTheme(
        controller = ThemeController(
            if (darkTheme) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
        ),
        content = content,
    )
}
