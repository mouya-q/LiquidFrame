package com.mouya.LiquidFrame

import android.graphics.Bitmap
import com.mouya.LiquidFrame.glass.GlassParams

/**
 * The module's view of the current settings, inside the camera process.
 *
 * The config UI runs in the module's own process, so nothing here is shared memory: values are
 * read from [ConfigStore] at a bounded interval, which lets a slider take effect without
 * restarting the camera.
 */
object GlassConfig {

    private const val TAG = "LiquidFrame"

    /** Serve the user's settings. */
    var masterEnabled: Boolean = true
        private set

    /** Let the scene decide tint, veil and rim strength. */
    var adaptiveGlass: Boolean = true
        private set

    private var cachedParams: GlassParams = GlassParams.Default

    fun init(classLoader: ClassLoader) {
        refresh(force = true)
    }

    /** Re-reads settings when the cache has expired. Cheap enough to call per capture. */
    fun refresh(force: Boolean = false) {
        if (force) ConfigStore.load() else ConfigStore.loadIfStale()
        masterEnabled = ConfigStore.isEnabled()
        cachedParams = ConfigStore.glassParams()
        adaptiveGlass = cachedParams.adaptive
    }

    /** The material parameters for this capture. */
    fun params(): GlassParams {
        refresh()
        return cachedParams
    }

    /**
     * Mean luminance test used to pick a scene variant. Samples on a stride rather than
     * calling `getPixel` per point, which on a 4000 px frame would dominate the capture time.
     */
    fun isDarkScene(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val step = maxOf(1, w / 64)
        val pixels = IntArray(w * h)
        return try {
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            var total = 0.0
            var count = 0
            var y = 0
            while (y < h) {
                val row = y * w
                var x = 0
                while (x < w) {
                    val p = pixels[row + x]
                    total += 0.213 * ((p shr 16) and 0xFF) +
                            0.715 * ((p shr 8) and 0xFF) +
                            0.072 * (p and 0xFF)
                    count++
                    x += step
                }
                y += step
            }
            if (count == 0) false else (total / count) < 108.0
        } catch (_: Throwable) {
            false
        }
    }
}
