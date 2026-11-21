package com.mouya.LiquidFrame.glass

import kotlin.math.abs

object PanelScan {

    private const val WORK_WIDTH = 160

    private const val RUN_TOLERANCE = 12f

    private const val MIN_RUN_FRACTION = 0.42f

    private const val ROW_MEAN_MIN = 84f

    private const val EDGE_MIN = 14f

    private const val MAX_HEIGHT_FRACTION = 0.35f

    private const val MIN_HEIGHT_FRACTION = 0.012f

    private const val BAND_LUMA_TOLERANCE = 34f

    private const val COLUMN_COVERAGE = 0.55f

    fun find(pixels: IntArray, w: Int, h: Int): PanelRect? {
        if (pixels.size < w * h) return null
        if (w < 32 || h < 32) return null

        val step = maxOf(1, w / WORK_WIDTH)
        val sw = w / step
        val sh = h / step
        if (sw < 8 || sh < 8) return null

        val mean = FloatArray(sh)
        val runFraction = FloatArray(sh)
        val runStart = IntArray(sh)
        val runEnd = IntArray(sh)
        for (sy in 0 until sh) {
            val rowBase = (sy * step) * w
            var sum = 0f
            var runAnchor = 0f
            var runLen = 0
            var runFrom = 0
            var bestRun = 0
            var bestFrom = 0
            var bestTo = 0
            for (sx in 0 until sw) {
                val p = pixels[rowBase + sx * step]
                val luma = 0.213f * ((p shr 16) and 0xFF) +
                        0.715f * ((p shr 8) and 0xFF) +
                        0.072f * (p and 0xFF)
                sum += luma
                if (runLen == 0) {
                    runAnchor = luma; runLen = 1; runFrom = sx
                } else if (abs(luma - runAnchor) <= RUN_TOLERANCE) {
                    runLen++
                } else {
                    if (runLen > bestRun) { bestRun = runLen; bestFrom = runFrom; bestTo = sx - 1 }
                    runAnchor = luma; runLen = 1; runFrom = sx
                }
            }
            if (runLen > bestRun) { bestRun = runLen; bestFrom = runFrom; bestTo = sw - 1 }
            mean[sy] = sum / sw
            runFraction[sy] = bestRun.toFloat() / sw
            runStart[sy] = bestFrom
            runEnd[sy] = bestTo
        }

        val flat = BooleanArray(sh)
        for (sy in 0 until sh) {
            flat[sy] = runFraction[sy] >= MIN_RUN_FRACTION && mean[sy] >= ROW_MEAN_MIN
        }

        var bestTop = -1
        var bestBottom = -1
        var bestScore = 0f
        var runTop = -1
        for (sy in 0 until sh) {
            if (flat[sy]) {
                if (runTop < 0) runTop = sy
            } else if (runTop >= 0) {
                consider(mean, runTop, sy - 1, sh) { top, bottom, score ->
                    if (score > bestScore) { bestScore = score; bestTop = top; bestBottom = bottom }
                }
                runTop = -1
            }
        }
        if (runTop >= 0) {
            consider(mean, runTop, sh - 1, sh) { top, bottom, score ->
                if (score > bestScore) { bestScore = score; bestTop = top; bestBottom = bottom }
            }
        }
        if (bestTop < 0 || bestBottom <= bestTop) return null

        val rows = bestBottom - bestTop + 1

        val minRun = (sw * MIN_RUN_FRACTION).toInt().coerceAtLeast(1)
        if (rows < maxOf(2, (sh * MIN_HEIGHT_FRACTION).toInt())) return null
        if (rows > sh * MAX_HEIGHT_FRACTION) return null

        var left = sw
        var right = -1
        for (sy in bestTop..bestBottom) {
            val start = runStart[sy]
            val end = runEnd[sy]
            if (end - start + 1 < minRun) continue
            if (start < left) left = start
            if (end > right) right = end
        }
        if (right < left) return null

        val panelW = (right - left + 1) * step
        val panelH = rows * step
        if (panelW < 32 || panelH < 6) return null

        val radius = (minOf(panelW, panelH) * 0.5f)
            .coerceIn(0f, minOf(panelW, panelH) * 0.5f)
        return PanelRect(
            left = (left * step).toFloat(),
            top = (bestTop * step).toFloat(),
            width = panelW.toFloat(),
            height = panelH.toFloat(),
            cornerRadiusPx = radius,
        )
    }

    fun hasLabelDetail(pixels: IntArray, w: Int, h: Int, panel: PanelRect): Boolean {
        val left = panel.left.toInt().coerceIn(0, w - 1)
        val top = panel.top.toInt().coerceIn(0, h - 1)
        val right = panel.right.toInt().coerceIn(left + 2, w)
        val bottom = panel.bottom.toInt().coerceIn(top + 2, h)
        if (right - left < 16 || bottom - top < 6) return false

        var sum = 0f
        var n = 0
        for (y in top until bottom) {
            for (x in left until right step 2) {
                sum += luma(pixels[y * w + x]); n++
            }
        }
        if (n == 0) return false
        val tone = sum / n

        var columns = 0
        var darkColumns = 0
        var longestRun = 0
        var currentRun = 0
        for (x in left until right) {
            columns++
            var colMin = 255f
            for (y in top until bottom) {
                val v = luma(pixels[y * w + x])
                if (v < colMin) colMin = v
            }
            if (tone - colMin > GLYPH_DROP_MIN) {
                darkColumns++
                currentRun++
                if (currentRun > longestRun) longestRun = currentRun
            } else {
                currentRun = 0
            }
        }
        if (columns == 0 || longestRun < GLYPH_MIN_RUN) return false

        val fraction = darkColumns.toFloat() / columns
        return fraction <= 0.80f
    }

    private const val GLYPH_DROP_MIN = 34f

    private const val GLYPH_MIN_RUN = 3

    private fun luma(p: Int): Float =
        0.213f * ((p shr 16) and 0xFF) + 0.715f * ((p shr 8) and 0xFF) + 0.072f * (p and 0xFF)

    private inline fun consider(
        mean: FloatArray, top: Int, bottom: Int, sh: Int, emit: (Int, Int, Float) -> Unit,
    ) {
        if (bottom <= top) return
        val bandMean = meanOf(mean, top, bottom)
        if (bandMean < ROW_MEAN_MIN) return

        var deviation = 0f
        var n = 0
        for (sy in top..bottom) {
            deviation += abs(mean[sy] - bandMean); n++
        }
        if (n == 0) return
        val consistency = 1f - (deviation / n).coerceIn(0f, 30f) / 30f

        val above = if (top > 0) mean[top - 1] else bandMean
        val topEdge = abs(bandMean - above)
        if (topEdge < EDGE_MIN) return

        val touchesFrame = bottom >= sh - 1
        val below = if (bottom + 1 < sh) mean[bottom + 1] else bandMean
        val bottomEdge = abs(bandMean - below)
        if (!touchesFrame && bottomEdge < EDGE_MIN) return

        emit(top, bottom, consistency * (topEdge / EDGE_MIN).coerceAtMost(2f))
    }

    private fun meanOf(mean: FloatArray, top: Int, bottom: Int): Float {
        var sum = 0f
        var n = 0
        for (sy in top..bottom) {
            sum += mean[sy]; n++
        }
        return if (n == 0) 0f else sum / n
    }
}
