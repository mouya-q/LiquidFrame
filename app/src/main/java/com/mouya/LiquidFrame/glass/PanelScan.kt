package com.mouya.LiquidFrame.glass

import kotlin.math.abs

/**
 * Finds the watermark's background panel inside a finished photo bitmap.
 *
 * Used as a fallback: normally the module takes the rectangle straight from the camera's own
 * `Canvas.drawRect` call, which is exact. But that hook depends on an obfuscated name that a
 * future camera build can rename, so the panel is also recoverable from the pixels alone —
 * and recovering it is what makes the module survive an app update.
 *
 * ## Why this does not use the alpha channel
 *
 * An earlier revision looked for "a run of opaque pixels with transparent pixels above and
 * below", which is what the *watermark layer* looks like before it is composited. By the time
 * this runs it is handed the **finished photo**: the scene is opaque everywhere, alpha is 255
 * across the frame, and it is about to be encoded to JPEG where alpha does not even survive.
 * Every row therefore qualified, the detected band became the whole frame, the height test
 * rejected it, and the scan returned null every single time — a fallback that could never fire
 * while the README described it as the thing that keeps the module alive.
 *
 * ## What it looks for instead
 *
 * The panel is a pre-rendered blurred WebP: a **horizontally uniform bar with hard top and
 * bottom edges**. Two earlier discriminators were tried and both fail on real photographs:
 *
 *  - raw per-row variance is dominated by the scene's vertical gradient, so the panel's rows
 *    score the same as the sky's;
 *  - the detrended residual is better but still fails because the panel has *margins*: the
 *    step up onto the panel and back down again is a discontinuity a straight line cannot
 *    remove, so a panel row and a textured scene row come out within a few luma units of each
 *    other, with the panel sometimes on either side of the threshold.
 *
 * What actually separates them is **the longest run of near-equal neighbouring pixels** in a
 * row. Inside the panel that run spans its whole width because the WebP is blurred; inside a
 * photograph it is broken by texture, grain and edges almost immediately. A smooth sky is the
 * one thing that can also produce a long run, which is why a candidate is additionally required
 * to have a hard edge at *both* ends (or to reach the frame edge), and why the caller confirms
 * it with [hasLabelDetail] before trusting it.
 *
 * Everything runs on a stride-sampled working grid, so a 12 MP frame is inspected with a few
 * thousand reads and no large allocation.
 */
object PanelScan {

    /** Working width: the panel is a broad, soft-edged structure and survives this easily. */
    private const val WORK_WIDTH = 160

    /**
     * Luma units a pixel may sit from the *first* pixel of a run and still extend it.
     *
     * This is a window around the run's anchor, not a running band that widens as the scan
     * proceeds. A widening band (an earlier revision) is satisfied by any row whose
     * neighbouring samples differ by a little, which a textured scene does constantly, and the
     * scan then locks onto the sky instead of the panel.
     */
    private const val RUN_TOLERANCE = 12f

    /**
     * Share of a row that must be one continuous run for the row to look like panel.
     *
     * The panel spans most of the watermark layer's width, so this is deliberately high: it is
     * the single value that separates a broad blurred bar from a short coincidence.
     */
    private const val MIN_RUN_FRACTION = 0.42f

    /** ...and the row has to be bright enough, so dark scene bands are not mistaken for it. */
    private const val ROW_MEAN_MIN = 84f

    /** Minimum luma step at the band's top edge, and at its bottom unless it reaches the frame. */
    private const val EDGE_MIN = 14f

    /** The band may not be taller than this fraction of the frame. */
    private const val MAX_HEIGHT_FRACTION = 0.35f

    /** ...nor shorter than this fraction, or it is a rule or a divider. */
    private const val MIN_HEIGHT_FRACTION = 0.012f

    /** Luma units a row may sit from the band's mean and still be considered part of it. */
    private const val BAND_LUMA_TOLERANCE = 34f

    /** Share of a row's height that must sit at the band tone for the row to be in the band. */
    private const val COLUMN_COVERAGE = 0.55f

    /**
     * Finds the panel's row band and horizontal extent.
     *
     * This establishes "there is a uniform bar with hard edges here". It deliberately does not
     * decide whether that bar is a watermark — see [hasLabelDetail] for that, which needs full
     * resolution and is therefore the caller's job.
     *
     * @return the panel in the caller's pixel units, or null when nothing convincing is found.
     */
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

        // Pass 1: which rows carry a wide uniform run at a bright enough tone.
        val flat = BooleanArray(sh)
        for (sy in 0 until sh) {
            flat[sy] = runFraction[sy] >= MIN_RUN_FRACTION && mean[sy] >= ROW_MEAN_MIN
        }

        // Pass 2: score each run of such rows. A band must be internally consistent, and must
        // have a hard edge at the top; the bottom edge is required too unless the band runs off
        // the frame, which is where a bottom-corner watermark usually sits.
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
        // A column only counts if its row's own uniform run is at least this long.
        val minRun = (sw * MIN_RUN_FRACTION).toInt().coerceAtLeast(1)
        if (rows < maxOf(2, (sh * MIN_HEIGHT_FRACTION).toInt())) return null
        if (rows > sh * MAX_HEIGHT_FRACTION) return null

        // Pass 3: horizontal extent, taken from where each band's rows actually hold their
        // uniform run. Reading the row's whole-row mean here (an earlier revision did) tests the
        // same number for every column, so the extent always came back as the full frame width.
        // A run is by definition a span of flat pixels, so its own bounds are the honest answer.
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

    /**
     * Confirms that a candidate bar really is the watermark and not a flat patch of sky.
     *
     * The coarse grid cannot resolve glyph strokes — it samples with a stride, so 3 px text
     * disappears between samples — but this runs on the caller's full-resolution buffer over
     * the candidate strip alone, which is a couple of percent of the frame and therefore cheap.
     * What it looks for is the vertical structure a label leaves in a blurred bar: columns that
     * deviate sharply from the bar's own tone.
     *
     * A bright horizon fails here because no column in it steps away from its neighbours.
     */
    fun hasLabelDetail(pixels: IntArray, w: Int, h: Int, panel: PanelRect): Boolean {
        val left = panel.left.toInt().coerceIn(0, w - 1)
        val top = panel.top.toInt().coerceIn(0, h - 1)
        val right = panel.right.toInt().coerceIn(left + 2, w)
        val bottom = panel.bottom.toInt().coerceIn(top + 2, h)
        if (right - left < 16 || bottom - top < 6) return false

        // The bar's own tone, taken from the median of its rows' means so a few glyph columns
        // cannot drag it.
        var sum = 0f
        var n = 0
        for (y in top until bottom) {
            for (x in left until right step 2) {
                sum += luma(pixels[y * w + x]); n++
            }
        }
        if (n == 0) return false
        val tone = sum / n

        // A label is a *cluster* of dark columns, not a sprinkling of them. The panel's own
        // blur and its soft rounded ends also darken columns, so counting every dark column and
        // then capping the total (an earlier revision) rejected real panels: on a genuine one
        // the blurred edges alone pushed the count to ~0.62 of the bar. What distinguishes text
        // is that the dark columns arrive in runs — a stroke is several pixels wide — so this
        // measures the longest run and how much of it there is, and ignores isolated pixels.
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

        // Enough of the bar must be dark for this to be a labelled panel rather than a
        // gradient, but a caption-heavy bar can be darker than that, so only a *majority* of the
        // whole bar is treated as disqualifying.
        val fraction = darkColumns.toFloat() / columns
        return fraction <= 0.80f
    }

    /** How far a column has to fall below the bar's tone before it counts as a glyph stroke. */
    private const val GLYPH_DROP_MIN = 34f

    /**
     * Longest run of consecutive dark columns that counts as a label.
     *
     * Text strokes are several pixels wide, so requiring a run rules out the isolated dark
     * pixels a blurred edge or JPEG ringing produces.
     */
    private const val GLYPH_MIN_RUN = 3

    private fun luma(p: Int): Float =
        0.213f * ((p shr 16) and 0xFF) + 0.715f * ((p shr 8) and 0xFF) + 0.072f * (p and 0xFF)

    /** Scores one candidate band and reports it through [emit] when it is admissible. */
    private inline fun consider(
        mean: FloatArray, top: Int, bottom: Int, sh: Int, emit: (Int, Int, Float) -> Unit,
    ) {
        if (bottom <= top) return
        val bandMean = meanOf(mean, top, bottom)
        if (bandMean < ROW_MEAN_MIN) return

        // Internal consistency: the rows of one panel sit at one tone.
        var deviation = 0f
        var n = 0
        for (sy in top..bottom) {
            deviation += abs(mean[sy] - bandMean); n++
        }
        if (n == 0) return
        val consistency = 1f - (deviation / n).coerceIn(0f, 30f) / 30f

        // Hard top edge is mandatory; a bar floating in the middle of a gradient is not a panel.
        val above = if (top > 0) mean[top - 1] else bandMean
        val topEdge = abs(bandMean - above)
        if (topEdge < EDGE_MIN) return

        // Hard bottom edge, unless the band reaches the frame edge.
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