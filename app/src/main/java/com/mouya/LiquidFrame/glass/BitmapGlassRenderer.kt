package com.mouya.LiquidFrame.glass

import android.graphics.Bitmap

/**
 * Renders the material onto a real [Bitmap], which is what the camera hook needs.
 *
 * Kept separate from [LiquidGlassOptics] so the optics stay free of Android types and can be
 * exercised off-device.
 */
object BitmapGlassRenderer {

    /**
     * Applies the material to [bitmap] in place.
     *
     * @return the optics' outcome description, or a reason the render was skipped
     */
    fun renderPanel(
        bitmap: Bitmap,
        panel: PanelRect,
        params: GlassParams = GlassParams.Default,
    ): String {
        if (bitmap.isRecycled) return "skip: bitmap recycled"
        if (!bitmap.isMutable) return "skip: bitmap not mutable"
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return "skip: bitmap too small (${w}x$h)"

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = LiquidGlassOptics.renderPanel(pixels, w, h, panel, params)
        if (result.startsWith("skip")) return result
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }
}
