package com.mouya.LiquidFrame.glass

import android.graphics.Bitmap

/**
 * Android-facing front end for [PanelScan].
 *
 * Kept in its own file so the scan itself, like the rest of the optics, compiles and can be
 * exercised on the JVM without an Android runtime.
 */
object BitmapPanelScan {

    fun find(bitmap: Bitmap): PanelRect? {
        if (bitmap.isRecycled) return null
        val w = bitmap.width
        val h = bitmap.height
        if (w < 32 || h < 8) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return PanelScan.find(pixels, w, h)
    }
}
