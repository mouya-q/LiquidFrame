// Copyright 2026, LiquidFrame contributors
// SPDX-License-Identifier: Apache-2.0

package com.mouya.LiquidFrame.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Hosts the liquid glass gallery.
 *
 * The activity is edge-to-edge and draws nothing itself: the whole surface is Compose, because
 * the backdrop the glass refracts is a Compose graphics layer, and any platform-drawn chrome
 * behind it would not be part of the capture.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GalleryTheme {
                GalleryApp()
            }
        }
    }
}
