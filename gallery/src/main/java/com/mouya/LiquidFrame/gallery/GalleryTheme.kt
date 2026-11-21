package com.mouya.LiquidFrame.gallery

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

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
