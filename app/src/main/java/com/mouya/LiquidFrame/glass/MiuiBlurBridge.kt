package com.mouya.LiquidFrame.glass

import android.view.View

object MiuiBlurBridge {

    val ALPHA_SETTERS = listOf(
        "setBlurAlpha",
        "setBackgroundBlurAlpha",
        "setMiBackgroundBlurAlpha",
        "setMiViewBlurAlpha",
    )

    fun remap(view: View, setterName: String, originalAlpha: Float): Float {
        if (setterName !in ALPHA_SETTERS) return originalAlpha
        return (originalAlpha * GLASS_BLUR_GAIN).coerceIn(0f, 1f)
    }

    const val GLASS_BLUR_GAIN = 1.35f
}
