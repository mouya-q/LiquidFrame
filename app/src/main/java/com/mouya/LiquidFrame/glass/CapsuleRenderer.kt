package com.mouya.LiquidFrame.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object CapsuleRenderer {

    class Options(
        val modelOverride: String,

        val position: String = "left",
        val showDateTime: Boolean,
        val showExif: Boolean,
        val showGps: Boolean,
        val scale: Float,

        val showLogo: Boolean = false,

        val logo: Bitmap? = null,

        val logoPreset: String = "LeicaWhite",

        val logoPosition: String = "MIDDLE",

        val metaLineSpacing: Float = -0.18f,

        val logoSizeScale: Float = 1.0f,
    )

    class CaptureMeta(
        val captureMillis: Long,
        val location: Location?,
        val exif: ExifBits?,
    )

    class ExifBits(
        val focalLengthMm: Double?,
        val aperture: Double?,
        val exposureSeconds: Double?,
        val iso: Int?,
    )

    class Line(val text: String, val bold: Boolean, val sizeRef: Float, val column: Int = 0)

    class PlacedLine(
        val text: String,
        val bold: Boolean,
        val sizePx: Float,
        val centerX: Float,
        val baselineY: Float,

        val leftX: Float = 0f,

        val align: Int = 2,
    )

    class LogoPlacement(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    class DividerPlacement(
        val x: Float,
        val top: Float,
        val bottom: Float,
        val strokeWidth: Float,
    )

    class Metrics(
        val placed: List<PlacedLine>,
        val capsuleWidth: Float,
        val capsuleHeight: Float,

        val unitPx: Float,

        val logo: LogoPlacement? = null,

        val logoBitmap: Bitmap? = null,

        val divider: DividerPlacement? = null,
    )

    private const val REFERENCE_SHORT_SIDE = 1080f

    private const val TARGET_WIDTH_FRACTION = 0.91f
    private const val TARGET_HEIGHT_REF = 128f
    const val CORNER_RADIUS_REF = 64f
    private const val PAD_X_FRACTION = 0.05f
    private const val PAD_Y_FRACTION = 0.10f
    const val HORIZONTAL_EDGE_INSET_FRACTION = 0.045f

    private const val MODEL_SIZE_REF = 30f
    private const val BODY_SIZE_REF = 24f
    private const val LINE_GAP_REF = 6f

    private const val LOGO_CENTER_X_FRACTION = 0.635f
    private const val LOGO_HEIGHT_FRACTION = 0.42f
    private const val DIVIDER_X_FRACTION = 0.685f
    private const val DIVIDER_HEIGHT_FRACTION = 0.42f
    private const val DIVIDER_GAP_REF = 16f

    const val BOX_INSET_REF = 18f

    const val TEXT_COLOR_WHITE = 0xF2FFFFFF.toInt()
    const val TEXT_COLOR_LIGHT = 0xF21F1F26.toInt()
    const val TEXT_COLOR_DARK = TEXT_COLOR_WHITE
    private const val DIVIDER_COLOR = 0xB8FFFFFF.toInt()

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss", Locale.US)

    fun buildLines(opts: Options, meta: CaptureMeta, shortSide: Int): List<Line> {
        if (shortSide < 64) return emptyList()
        val lines = ArrayList<Line>(4)
        lines.add(Line(modelText(opts), bold = true, sizeRef = MODEL_SIZE_REF))
        if (opts.showDateTime && meta.captureMillis > 0L) {
            formatTime(meta.captureMillis).takeIf { it.isNotBlank() }?.let {
                lines.add(Line(it, bold = false, sizeRef = BODY_SIZE_REF))
            }
        }
        if (opts.showExif) {
            val t = exifText(meta.exif)
            if (t != null) lines.add(Line(t, bold = false, sizeRef = BODY_SIZE_REF, column = 1))
        }
        if (opts.showGps && meta.location != null) {
            val t = locationText(meta.location)
            if (t != null) lines.add(Line(t, bold = false, sizeRef = BODY_SIZE_REF, column = 1))
        }
        return lines.filter { it.text.isNotBlank() }
    }

    private fun modelText(opts: Options): String {
        val override = opts.modelOverride.trim()
        if (override.isNotEmpty()) return override
        val market = systemProperty("ro.product.marketname")
        if (market.isNotEmpty()) return market
        return android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Camera"
    }

    private fun systemProperty(key: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java)
        (m.invoke(null, key) as? String)?.trim().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    private fun formatTime(millis: Long): String = try {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            .format(TIME_FORMAT)
    } catch (t: Throwable) {
        ""
    }

    private fun exifText(e: ExifBits?): String? {
        if (e == null) return null
        val parts = ArrayList<String>(4)
        e.focalLengthMm?.let { parts.add("${it.roundToInt()}mm") }
        e.aperture?.let { parts.add("f/" + String.format(Locale.US, "%.2f", it)) }
        e.exposureSeconds?.let { parts.add(exposureText(it)) }
        e.iso?.let { parts.add("ISO$it") }

        if (parts.isEmpty()) return null
        return parts.joinToString(" ")
    }

    private fun exposureText(seconds: Double): String =
        if (seconds >= 1.0) {
            val t = seconds.roundToLong()
            if (kotlin.math.abs(seconds - t) < 0.05) "${t}s" else String.format(Locale.US, "%.1fs", seconds)
        } else {
            "1/${(1.0 / seconds).roundToLong()}s"
        }

    private fun locationText(loc: Location): String? = try {
        val lat = dmsComponent(abs(loc.latitude), if (loc.latitude >= 0.0) "N" else "S")
        val lon = dmsComponent(abs(loc.longitude), if (loc.longitude >= 0.0) "E" else "W")
        "$lat $lon"
    } catch (t: Throwable) {
        null
    }

    private fun dmsComponent(v: Double, hemisphere: String): String {
        var d = v.toInt()
        var mFull = (v - d) * 60.0
        var m = mFull.toInt()
        var s = ((mFull - m) * 60.0).roundToInt()

        if (s == 60) { s = 0; m += 1 }
        if (m == 60) { m = 0; d += 1 }
        return String.format(Locale.US, "%d°%d'%d\"%s", d, m, s, hemisphere)
    }

    fun measureCapsule(
        photoW: Int,
        shortSide: Int,
        scale: Float,
        lines: List<Line>,
        opts: Options = Options("", "left", true, true, true, 1f),
    ): Metrics? {
        if (lines.isEmpty() || photoW < 64 || shortSide < 64) return null
        val unit = (shortSide / REFERENCE_SHORT_SIDE) * scale.coerceIn(0.6f, 2.0f)
        val capsuleW = TARGET_WIDTH_FRACTION * photoW
        val capsuleH = TARGET_HEIGHT_REF * unit
        if (capsuleW <= 0f || capsuleH <= 0f) return null

        val padX = PAD_X_FRACTION * capsuleW
        val padY = PAD_Y_FRACTION * capsuleH
        val interiorH = (capsuleH - 2f * padY).coerceAtLeast(1f)
        val leftLines = lines.filter { it.column == 0 }
        val rightLines = lines.filter { it.column == 1 }
        if (leftLines.isEmpty() && rightLines.isEmpty()) return null

        val logoBmp = opts.logo?.takeIf { opts.showLogo && !it.isRecycled && it.width > 0 && it.height > 0 }
        val hasLogo = logoBmp != null
        val colGap = DIVIDER_GAP_REF * unit

        val preset = LogoPreset.fromName(opts.logoPreset)
        val logoSizeFactor = opts.logoSizeScale.coerceIn(0.01f, 2.0f)
        val logoH = if (hasLogo) LOGO_HEIGHT_FRACTION * interiorH * logoSizeFactor else 0f
        val logoW = if (logoH > 0f && logoBmp != null) {

            (logoH * logoBmp.width.toFloat() / logoBmp.height.toFloat() * preset.aspectCompensation)
                .coerceAtMost(capsuleW * 0.15f)
        } else 0f
        val logoCenterX = LOGO_CENTER_X_FRACTION * capsuleW
        val logoLeft = logoCenterX - logoW * 0.5f
        val dividerX = DIVIDER_X_FRACTION * capsuleW

        val leftAvailableW = if (hasLogo) {
            (logoLeft - colGap - padX).coerceAtLeast(1f)
        } else if (rightLines.isNotEmpty()) {
            (dividerX - colGap - padX).coerceAtLeast(1f)
        } else {
            (capsuleW - 2f * padX).coerceAtLeast(1f)
        }
        val rightAvailableW = (capsuleW - padX - dividerX - colGap).coerceAtLeast(1f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun preparePaint(ln: Line, factor: Float) {
            paint.typeface = typefaceFor(ln.bold)
            paint.letterSpacing = if (ln.bold) 0.005f else 0f
            paint.textSize = ln.sizeRef * unit * factor
        }
        fun fitWidth(columnLines: List<Line>, availableW: Float): Float {
            if (columnLines.isEmpty()) return 1f
            var maxWidth = 0f
            for (ln in columnLines) {
                preparePaint(ln, 1f)
                maxWidth = maxOf(maxWidth, paint.measureText(ln.text))
            }
            return if (maxWidth > availableW && maxWidth > 0f) {
                (availableW / maxWidth).coerceIn(0.48f, 1f)
            } else 1f
        }
        fun blockHeight(columnLines: List<Line>, factor: Float): Float {
            var height = 0f
            for ((i, ln) in columnLines.withIndex()) {
                preparePaint(ln, factor)
                val fm = paint.fontMetrics
                height += -fm.ascent + fm.descent
                if (i < columnLines.size - 1) height += LINE_GAP_REF * unit * factor
            }
            return height
        }
        fun fitHeight(columnLines: List<Line>, widthFit: Float): Float {
            val height = blockHeight(columnLines, widthFit)
            if (height <= interiorH || height <= 0f) return widthFit
            return widthFit * (interiorH / height).coerceIn(0.35f, 1f)
        }

        val leftScale = fitHeight(leftLines, fitWidth(leftLines, leftAvailableW))
        val rightScale = fitHeight(rightLines, fitWidth(rightLines, rightAvailableW))
        val placed = ArrayList<PlacedLine>(lines.size)

        fun placeBlock(columnLines: List<Line>, factor: Float, rightAligned: Boolean) {
            if (columnLines.isEmpty()) return
            val height = blockHeight(columnLines, factor)
            var baseline = padY + (interiorH - height) * 0.5f
            for ((i, ln) in columnLines.withIndex()) {
                preparePaint(ln, factor)
                val fm = paint.fontMetrics
                baseline += -fm.ascent
                placed.add(
                    PlacedLine(
                        text = ln.text,
                        bold = ln.bold,
                        sizePx = ln.sizeRef * unit * factor,
                        centerX = 0f,
                        baselineY = baseline,
                        leftX = if (rightAligned) capsuleW - padX else padX,
                        align = if (rightAligned) 1 else 0,
                    ),
                )
                baseline += fm.descent
                if (i < columnLines.size - 1) baseline += LINE_GAP_REF * unit * factor
            }
        }
        placeBlock(leftLines, leftScale, rightAligned = false)
        placeBlock(rightLines, rightScale, rightAligned = true)

        val logoPlacement = if (hasLogo && logoW > 0f && logoH > 0f) {
            LogoPlacement(
                left = logoLeft,
                top = (capsuleH - logoH) * 0.5f * preset.verticalAlignment,
                width = logoW,
                height = logoH,
            )
        } else null
        val divider = if (rightLines.isNotEmpty()) {
            val dividerH = capsuleH * DIVIDER_HEIGHT_FRACTION
            DividerPlacement(
                x = dividerX,
                top = (capsuleH - dividerH) * 0.5f,
                bottom = (capsuleH + dividerH) * 0.5f,
                strokeWidth = (unit * 0.8f).coerceAtLeast(0.75f),
            )
        } else null
        return Metrics(placed, capsuleW, capsuleH, unit, logoPlacement, logoBmp, divider)
    }

    private fun typefaceFor(bold: Boolean): Typeface =
        if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else Typeface.create("sans-serif", Typeface.NORMAL)

    fun drawTextLayer(
        cw: Int,
        ch: Int,
        capsule: PanelRect,
        metrics: Metrics,
        textColor: Int = TEXT_COLOR_WHITE,
    ): IntArray? {
        if (cw <= 0 || ch <= 0 || (metrics.placed.isEmpty() && metrics.logo == null && metrics.divider == null)) {
            return null
        }
        var bmp: Bitmap? = null
        return try {
            bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val divider = metrics.divider
            if (divider != null) {
                val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = DIVIDER_COLOR
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = divider.strokeWidth
                    setShadowLayer(metrics.unitPx * 0.65f, 0f, 0f, 0x36000000)
                }
                canvas.drawLine(
                    capsule.left + divider.x,
                    capsule.top + divider.top,
                    capsule.left + divider.x,
                    capsule.top + divider.bottom,
                    dividerPaint,
                )
                dividerPaint.clearShadowLayer()
            }

            val logoPlacement = metrics.logo
            val logoBitmap = metrics.logoBitmap
            if (logoPlacement != null && logoBitmap != null && !logoBitmap.isRecycled) {

                try {
                    val dst = RectF(
                        capsule.left + logoPlacement.left,
                        capsule.top + logoPlacement.top,
                        capsule.left + logoPlacement.left + logoPlacement.width,
                        capsule.top + logoPlacement.top + logoPlacement.height,
                    )
                    val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        setShadowLayer(metrics.unitPx * 0.9f, 0f, metrics.unitPx * 0.2f, 0x52000000)
                    }
                    canvas.drawBitmap(logoBitmap, null, dst, logoPaint)
                    logoPaint.clearShadowLayer()
                } catch (t: Throwable) {
                    android.util.Log.w(
                        "LiquidFrame",
                        "capsule logo draw failed (dropping logo, keeping text): " +
                            "${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                setShadowLayer(metrics.unitPx * 0.85f, 0f, metrics.unitPx * 0.22f, 0x62000000)
            }
            for (pl in metrics.placed) {
                paint.typeface = typefaceFor(pl.bold)
                paint.letterSpacing = if (pl.bold) 0.005f else 0f
                paint.textSize = pl.sizePx
                val x: Float
                when (pl.align) {
                    0 -> { paint.textAlign = Paint.Align.LEFT; x = capsule.left + pl.leftX }
                    1 -> { paint.textAlign = Paint.Align.RIGHT; x = capsule.left + pl.leftX }
                    else -> { paint.textAlign = Paint.Align.CENTER; x = capsule.left + pl.centerX }
                }
                canvas.drawText(pl.text, x, capsule.top + pl.baselineY, paint)
            }
            paint.clearShadowLayer()
            val out = IntArray(cw * ch)
            bmp.getPixels(out, 0, cw, 0, 0, cw, ch)
            out
        } catch (t: Throwable) {

            android.util.Log.w(
                "LiquidFrame",
                "capsule text layer failed: ${t.javaClass.simpleName}: ${t.message}",
            )
            null
        } finally {
            bmp?.recycle()
        }
    }

    fun blendTextOver(target: IntArray, text: IntArray) {
        if (target.size != text.size || target.isEmpty()) return
        var i = 0
        val n = target.size
        while (i < n) {
            val t = text[i]
            val a = (t ushr 24) and 0xFF
            if (a >= 8) {
                val af = a / 255f
                val d = target[i]
                val sr = (t shr 16) and 0xFF
                val sg = (t shr 8) and 0xFF
                val sb = t and 0xFF
                val dr = (d shr 16) and 0xFF
                val dg = (d shr 8) and 0xFF
                val db = d and 0xFF
                val rr = (sr * af + dr * (1f - af) + 0.5f).toInt().coerceIn(0, 255)
                val rg = (sg * af + dg * (1f - af) + 0.5f).toInt().coerceIn(0, 255)
                val rb = (sb * af + db * (1f - af) + 0.5f).toInt().coerceIn(0, 255)
                target[i] = 0xFF000000.toInt() or (rr shl 16) or (rg shl 8) or rb
            }
            i++
        }
    }

    private fun rOf(c: Int) = (c shr 16) and 0xFF
    private fun gOf(c: Int) = (c shr 8) and 0xFF
    private fun bOf(c: Int) = c and 0xFF
    private fun pack(r: Int, g: Int, b: Int) = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    internal fun eraseFlatRegion(pixels: IntArray, cw: Int, ch: Int, region: CropRect): Boolean {
        val left = region.left.coerceIn(0, cw - 1)
        val top = region.top.coerceIn(0, ch - 1)
        val right = region.right.coerceIn(left + 1, cw)
        val bottom = region.bottom.coerceIn(top + 1, ch)
        if (right <= left || bottom <= top) return false

        val sampleA: Int
        val sampleB: Int
        val outerLeft = left - 12
        val outerRight = right + 12
        if (outerLeft >= 0 && outerRight < cw) {
            sampleA = outerLeft
            sampleB = outerRight
        } else if (outerLeft >= 0) {
            sampleA = outerLeft
            sampleB = outerLeft
        } else if (outerRight < cw) {
            sampleA = outerRight
            sampleB = outerRight
        } else {
            return false
        }

        var rSum = 0L; var gSum = 0L; var bSum = 0L
        var rMin = 255; var rMax = 0
        var gMin = 255; var gMax = 0
        var bMin = 255; var bMax = 0
        var count = 0
        val sampleColumns = intArrayOf(sampleA, sampleB)
        var y = top
        while (y < bottom) {
            for (column in sampleColumns) {
                val c = pixels[y * cw + column]
                val r = rOf(c); val g = gOf(c); val b = bOf(c)
                rSum += r; gSum += g; bSum += b
                if (r < rMin) rMin = r
                if (r > rMax) rMax = r
                if (g < gMin) gMin = g
                if (g > gMax) gMax = g
                if (b < bMin) bMin = b
                if (b > bMax) bMax = b
                count++
            }
            y += 2
        }
        if (count == 0) return false
        val spread = max(rMax - rMin, max(gMax - gMin, bMax - bMin))
        if (spread > MAX_SPREAD) return false

        val fill = pack((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
        for (yy in top until bottom) {
            val base = yy * cw
            for (xx in left until right) {
                pixels[base + xx] = fill
            }
        }
        return true
    }

    private const val MAX_SPREAD = 40

    fun fillColorFromStripTop(strip: IntArray, cw: Int): Int? {
        if (cw < 8 || strip.size < cw * 2) return null
        val xm = cw / 2
        val columns = intArrayOf(xm - 1, xm, xm + 1)
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        var rMin = 255; var rMax = 0
        var gMin = 255; var gMax = 0
        var bMin = 255; var bMax = 0
        var n = 0
        var y = 5

        while (y <= 16 && (y + 1) * cw + xm < strip.size + cw) {
            for (x in columns) {
                val idx = y * cw + x
                if (idx >= strip.size) continue
                val c = strip[idx]
                val r = rOf(c); val g = gOf(c); val b = bOf(c)
                rSum += r; gSum += g; bSum += b
                if (r < rMin) rMin = r
                if (r > rMax) rMax = r
                if (g < gMin) gMin = g
                if (g > gMax) gMax = g
                if (b < bMin) bMin = b
                if (b > bMax) bMax = b
                n++
            }
            y += 3
        }
        if (n == 0) return null
        val spread = max(rMax - rMin, max(gMax - gMin, bMax - bMin))
        if (spread > MAX_SPREAD) return null
        return pack((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }

    fun fillStrip(strip: IntArray, cw: Int, ch: Int, fill: Int): Boolean {
        if (cw <= 0 || ch <= 0 || strip.size < cw * ch) return false
        var i = 0
        val n = cw * ch
        while (i < n) {
            strip[i] = fill
            i++
        }
        return true
    }

    fun patchCropFromSource(
        target: IntArray,
        cw: Int,
        ch: Int,
        cropLeft: Int,
        cropTop: Int,
        outW: Int,
        outH: Int,
        src: Bitmap?,
    ): Boolean {
        if (src == null || src.isRecycled || src.width < 8 || src.height < 8) return false
        if (cw <= 0 || ch <= 0 || target.size < cw * ch) return false
        if (outW < 8 || outH < 8) return false
        return try {
            val srcW = src.width
            val srcH = src.height
            val s = outW.toFloat() / srcW.toFloat()
            if (!s.isFinite() || s <= 0f) return false
            val maxGlobalY = minOf(outH, (srcH.toFloat() * s).toInt() - 1)
            val y0 = cropTop.coerceAtLeast(0)
            val y1 = (cropTop + ch).coerceAtMost(maxGlobalY + 1)
            if (y1 <= y0) return false
            val sy0 = (y0 / s).toInt().coerceIn(0, srcH - 1)
            val sy1 = (((y1 - 1) / s).toInt() + 1).coerceIn(sy0 + 1, srcH)
            val bandH = sy1 - sy0
            if (bandH <= 0) return false
            val band = IntArray(srcW * bandH)
            src.getPixels(band, 0, srcW, 0, sy0, srcW, bandH)
            var written = 0
            for (gy in y0 until y1) {
                val sy = (gy / s).toInt().coerceIn(sy0, sy1 - 1)
                val bandRow = (sy - sy0) * srcW
                val destRow = (gy - cropTop) * cw
                for (lx in 0 until cw) {
                    val sx = ((cropLeft + lx) / s).toInt().coerceIn(0, srcW - 1)
                    target[destRow + lx] = band[bandRow + sx]
                    written++
                }
            }
            written > 0
        } catch (t: Throwable) {
            false
        }
    }

    internal fun inpaintRegion(pixels: IntArray, cw: Int, ch: Int, region: CropRect): Boolean {
        val left = (region.left + 1).coerceIn(1, max(1, cw - 2))
        val top = (region.top + 1).coerceIn(1, max(1, ch - 2))
        val right = (region.right - 1).coerceIn(left + 1, cw - 1)
        val bottom = (region.bottom - 1).coerceIn(top + 1, ch - 1)
        if (right - left < 2 || bottom - top < 2) return false

        val spanW = right - left
        val spanH = bottom - top
        val colUp = IntArray(spanW)
        val colDn = IntArray(spanW)
        val rowL = IntArray(spanH)
        val rowR = IntArray(spanH)

        for (x in left until right) {
            colUp[x - left] = meanOfColumn(pixels, cw, ch, x, top - 3, top - 1)
            colDn[x - left] = meanOfColumn(pixels, cw, ch, x, bottom + 1, bottom + 3)
        }
        for (y in top until bottom) {
            rowL[y - top] = meanOfRow(pixels, cw, ch, left - 3, left - 1, y)
            rowR[y - top] = meanOfRow(pixels, cw, ch, right + 1, right + 3, y)
        }

        for (y in top until bottom) {
            val tv = (y - top + 0.5f) / spanH
            val rl = rowL[y - top]
            val rr = rowR[y - top]
            val base = y * cw
            for (x in left until right) {
                val sv = (x - left + 0.5f) / spanW
                val cu = colUp[x - left]
                val cd = colDn[x - left]
                val c1r = (rOf(cu) + (rOf(cd) - rOf(cu)) * tv).toInt()
                val c1g = (gOf(cu) + (gOf(cd) - gOf(cu)) * tv).toInt()
                val c1b = (bOf(cu) + (bOf(cd) - bOf(cu)) * tv).toInt()
                val c2r = (rOf(rl) + (rOf(rr) - rOf(rl)) * sv).toInt()
                val c2g = (gOf(rl) + (gOf(rr) - gOf(rl)) * sv).toInt()
                val c2b = (bOf(rl) + (bOf(rr) - bOf(rl)) * sv).toInt()
                pixels[base + x] = pack((c1r + c2r) shr 1, (c1g + c2g) shr 1, (c1b + c2b) shr 1)
            }
        }
        return true
    }

    private fun meanOfColumn(pixels: IntArray, cw: Int, ch: Int, x: Int, y0: Int, y1: Int): Int {
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        var n = 0
        var last = Int.MIN_VALUE
        for (y in y0..y1) {
            val yy = y.coerceIn(0, ch - 1)
            if (yy == last) continue
            val c = pixels[yy * cw + x]
            rSum += rOf(c); gSum += gOf(c); bSum += bOf(c)
            n++
            last = yy
        }
        if (n == 0) return pack(0, 0, 0)
        return pack((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }

    private fun meanOfRow(pixels: IntArray, cw: Int, ch: Int, x0: Int, x1: Int, y: Int): Int {
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        var n = 0
        val yy = y.coerceIn(0, ch - 1)
        val base = yy * cw
        var last = Int.MIN_VALUE
        for (x in x0..x1) {
            val xx = x.coerceIn(0, cw - 1)
            if (xx == last) continue
            val c = pixels[base + xx]
            rSum += rOf(c); gSum += gOf(c); bSum += bOf(c)
            n++
            last = xx
        }
        if (n == 0) return pack(0, 0, 0)
        return pack((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }
}
