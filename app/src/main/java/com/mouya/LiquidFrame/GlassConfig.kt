package com.mouya.LiquidFrame

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object GlassConfig {

    private const val TAG = "LiquidFrame"

    var masterEnabled = true
    var radiusDp = 24f
    var refractionHeightPx = 28f
    var refractionAmountPx = 90f
    var highlightStrength = 1.0f
    var tintAlpha = 0.10f

    // Adaptive glass: detect scene type for tint
    var adaptiveGlass = true
    var lightTintAlpha = 0.12f
    var darkTintAlpha = 0.25f
    var darkHighlightStrength = 0.5f

    fun init(classLoader: ClassLoader) {
        // Load preferences if needed
    }

    fun isDarkScene(bitmap: Bitmap): Boolean {
        // Quick brightness sampling
        val step = maxOf(1, bitmap.width / 10)
        var totalBrightness = 0
        var count = 0
        for (x in 0 until bitmap.width step step) {
            for (y in 0 until bitmap.height step step) {
                val pixel = bitmap.getPixel(x, y)
                totalBrightness += ((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)
                count++
            }
        }
        return if (count > 0) (totalBrightness / count) < 384 else false
    }
}
