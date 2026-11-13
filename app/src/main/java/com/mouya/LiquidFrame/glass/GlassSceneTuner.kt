package com.mouya.LiquidFrame.glass

import kotlin.math.max

/** Shared scene adaptation used by both the live editor preview and the camera capture path. */
object GlassSceneTuner {
    fun resolveParams(pixels: IntArray, width: Int, height: Int, panel: PanelRect, user: GlassParams): GlassParams {
        if (!user.adaptive || pixels.isEmpty() || width <= 0 || height <= 0) return user
        val left = panel.left.toInt().coerceIn(0, width - 1)
        val top = panel.top.toInt().coerceIn(0, height - 1)
        val right = panel.right.toInt().coerceIn(left + 1, width)
        val bottom = panel.bottom.toInt().coerceIn(top + 1, height)
        var total = 0.0
        var count = 0
        val stride = max(1, (right - left) / 320)
        for (y in top until bottom step max(1, (bottom - top) / 96)) {
            val row = y * width
            var x = left
            while (x < right) {
                val p = pixels[row + x]
                total += 0.213 * ((p shr 16) and 0xFF) + 0.715 * ((p shr 8) and 0xFF) + 0.072 * (p and 0xFF)
                count++
                x += stride
            }
        }
        if (count == 0) return user
        val mean = total / count / 255.0
        // Preserve transparency: exposure adaptation is deliberately restrained, never a broad white veil.
        val veilGain = if (mean < 0.42) 0.82f else 1.12f
        val rimGain = if (mean < 0.42) 0.92f else 1.08f
        return user.copy(
            tintAlpha = (user.tintAlpha * veilGain).coerceIn(0f, 0.12f),
            surfaceAlpha = (user.surfaceAlpha * veilGain).coerceIn(0f, 0.12f),
            rimAlpha = (user.rimAlpha * rimGain).coerceIn(0f, 0.8f),
            adaptive = false,
        )
    }
}
