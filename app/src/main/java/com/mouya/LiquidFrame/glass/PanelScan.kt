package com.mouya.LiquidFrame.glass

/**
 * Finds the watermark's background panel inside a finished photo bitmap.
 *
 * Used as a fallback: normally the module takes the rectangle straight from the camera's own
 * `Canvas.drawRect` call, which is exact. But that hook depends on an obfuscated name that a
 * future camera build can rename, so the panel is also recoverable from the pixels alone —
 * and recovering it is what makes the module survive an app update.
 *
 * The camera draws the watermark background as an opaque, roughly uniform rectangle near one
 * edge of the frame, with hard horizontal edges and fully transparent pixels above and below
 * it. That signature is distinctive enough to find by scanning rows.
 */
object PanelScan {

    /** A row must be at least this fraction opaque to belong to the panel. */
    private const val ROW_OPACITY_MIN = 0.35f

    /**
     * The panel may take at most this fraction of the frame's width.
     *
     * Xiaomi's background panel is nearly as wide as the watermark layer itself, so this is
     * close to 1; it exists only to reject a rectangle that is literally the whole frame.
     */
    private const val MAX_WIDTH_FRACTION = 1.0f

    /** The panel may not take more than this fraction of the frame's height. */
    private const val MAX_HEIGHT_FRACTION = 0.60f

    /** Fewer pixels than this across is not a watermark panel. */
    private const val MIN_WIDTH = 32

    /** Shorter than this is a rule or a divider, not a panel. */
    private const val MIN_HEIGHT = 6

    /** Alpha at which a pixel counts as belonging to the panel rather than the surround. */
    private const val OPAQUE_ALPHA = 200

    fun find(pixels: IntArray, w: Int, h: Int): PanelRect? {
        if (pixels.size < w * h) return null

        // Pass 1: which rows look like a panel. Row scans run from the outside in, so a frame
        // whose panel touches neither edge is still found.
        val threshold = (w * ROW_OPACITY_MIN).toInt()
        var top = -1
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            var opaque = 0
            for (x in 0 until w) {
                if ((pixels[row + x] ushr 24) >= OPAQUE_ALPHA) opaque++
                // Cheap early exit: once the row qualifies, the rest of it cannot change that.
                if (opaque >= threshold) break
            }
            if (opaque >= threshold) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0) return null

        val height = bottom - top + 1
        if (height < MIN_HEIGHT || height > h * MAX_HEIGHT_FRACTION) return null

        // Pass 2: the horizontal extent, from the rows that are most likely to span the whole
        // panel. An interior row is used rather than the very first or last, which the rounded
        // corners cut into.
        val sampleTop = top + height / 4
        val sampleBottom = bottom - height / 4
        var left = w
        var right = -1
        for (x in 0 until w) {
            var opaque = 0
            for (y in sampleTop..sampleBottom) {
                if ((pixels[y * w + x] ushr 24) >= OPAQUE_ALPHA) opaque++
            }
            if (opaque >= (sampleBottom - sampleTop + 1) * 0.75f) {
                if (x < left) left = x
                if (x > right) right = x
            }
        }
        if (right < 0) return null

        val width = right - left + 1
        if (width < MIN_WIDTH || width > w * MAX_WIDTH_FRACTION) return null
        if (width < MIN_WIDTH) return null

        val radius = measureRadius(pixels, w, h, left, top, right, bottom)
        return PanelRect(
            left = left.toFloat(),
            top = top.toFloat(),
            width = width.toFloat(),
            height = height.toFloat(),
            cornerRadiusPx = radius,
        )
    }

    /**
     * Corner radius from the panel's own silhouette: how far in the first opaque pixel sits on
     * the top and bottom rows.
     */
    private fun measureRadius(
        pixels: IntArray, w: Int, h: Int,
        left: Int, top: Int, right: Int, bottom: Int,
    ): Float {
        val maxRadius = minOf((right - left + 1) / 2f, (bottom - top + 1) / 2f)

        fun rowRun(row: Int, fromLeft: Boolean): Int {
            var n = 0
            val limit = maxRadius.toInt().coerceAtMost(96)
            while (n < limit) {
                val x = if (fromLeft) left + n else right - n
                if (x !in 0 until w) break
                if ((pixels[row * w + x] ushr 24) < OPAQUE_ALPHA) n++ else break
            }
            return n
        }

        val candidates = listOf(
            rowRun(top, true), rowRun(top, false),
            rowRun(bottom, true), rowRun(bottom, false),
        ).filter { it > 0 }

        if (candidates.isEmpty()) return (maxRadius * 0.5f).coerceAtLeast(1f)
        return candidates.min().toFloat().coerceIn(0f, maxRadius)
    }
}
