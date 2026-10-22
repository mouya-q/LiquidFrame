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

/**
 * Pure-pixel helpers for the rebuilt capsule watermark: capture metadata extraction,
 * text layout, offscreen text raster, and colour repair of the erased stock plate.
 *
 * The camera's own watermark (a pre-baked WebP plate plus glyph layers) is erased
 * from the finished composite and replaced with a fresh capsule placed inside the
 * photograph itself. The capsule carries up to four text lines: device name,
 * capture time, exposure parameters and GPS coordinates — each line coming from
 * a different source and each one disappearing on its own when that source is
 * unavailable, so a partial-extraction device still produces a clean shot.
 *
 * Design rules kept throughout:
 *  - never paint over real scene pixels unless the replacement colour provably
 *    matches its surroundings (repair routines bail out instead of guessing);
 *  - never throw: every reflective/IO path returns null or false on failure;
 *  - no shared mutable state, so concurrent composites in the camera process
 *    cannot corrupt each other.
 */
object CapsuleRenderer {

    // ------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------

    /** Normalized per-shot configuration, assembled by the hook layer. */
    class Options(
        val modelOverride: String,
        /** Anchor of the capsule: "left" (reference default), "center" or "right". */
        val position: String = "left",
        val showDateTime: Boolean,
        val showExif: Boolean,
        val showGps: Boolean,
        val scale: Float,
        /** When true, a logo bitmap is drawn in the centre column. */
        val showLogo: Boolean = false,
        /** Pre-decoded logo bitmap, or null when unavailable. */
        val logo: Bitmap? = null,
        /** Logo preset name, for aspect compensation. */
        val logoPreset: String = "LeicaWhite",
        /** Logo position: "FRONT", "MIDDLE", or "BACK". */
        val logoPosition: String = "MIDDLE",
        /** Metadata line spacing multiplier. */
        val metaLineSpacing: Float = -0.18f,
        /** Logo size scale (0.01–2.0). */
        val logoSizeScale: Float = 1.0f,
    )

    class CaptureMeta(
        val captureMillis: Long,
        val location: Location?,
        val exif: ExifBits?,
        val strings: List<String>,
    )

    class ExifBits(
        val focalLengthMm: Double?,
        val aperture: Double?,
        val exposureSeconds: Double?,
        val iso: Int?,
    )

    /** One text line; [sizeRef] is in 1080-short-side units, scaled during layout.
     *  [column] 0 = left block (model + time), 1 = right block (exif + gps). */
    class Line(val text: String, val bold: Boolean, val sizeRef: Float, val column: Int = 0)

    /** A line with final pixel geometry, positioned inside capsule-local space. */
    class PlacedLine(
        val text: String,
        val bold: Boolean,
        val sizePx: Float,
        val centerX: Float,
        val baselineY: Float,
        /** Left edge x for this line's text (for left/right-aligned drawing). */
        val leftX: Float = 0f,
        /** 0 = left-aligned at leftX, 1 = right-aligned at leftX (which is the right edge), 2 = centred at centerX. */
        val align: Int = 2,
    )

    /** Logo placement in capsule-local coordinates, or null when no logo. */
    class LogoPlacement(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    /** Thin separator geometry, also expressed in capsule-local coordinates. */
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
        /** One 1080-short-side design unit in this photo's pixels, scale applied. */
        val unitPx: Float,
        /** Logo placement if a logo column exists, null otherwise. */
        val logo: LogoPlacement? = null,
        /** The logo bitmap reference, for drawing in the text layer. */
        val logoBitmap: Bitmap? = null,
        /** The reference's fine vertical separator, if the right metadata column is visible. */
        val divider: DividerPlacement? = null,
    )

    // ------------------------------------------------------------------
    // Design constants (1080-short-side reference units)
    // ------------------------------------------------------------------

    private const val REFERENCE_SHORT_SIDE = 1080f

    // Measured from the supplied 1536 × 1152 reference: ~90% frame width, ~12% height,
    // a 1%-width leading inset and about 9.5% of the image left below the capsule.
    private const val TARGET_WIDTH_FRACTION = 0.90f
    private const val TARGET_HEIGHT_REF = 128f
    const val CORNER_RADIUS_REF = 64f
    private const val PAD_X_FRACTION = 0.05f
    private const val PAD_Y_FRACTION = 0.10f
    const val HORIZONTAL_EDGE_INSET_FRACTION = 0.01f

    private const val MODEL_SIZE_REF = 30f
    private const val BODY_SIZE_REF = 24f
    private const val LINE_GAP_REF = 6f

    // The reference is deliberately asymmetrical, not a three-equal-column layout:
    // the Leica mark sits around 63.5%, the divider around 68.5%, and the specs hug right.
    private const val LOGO_CENTER_X_FRACTION = 0.635f
    private const val LOGO_HEIGHT_FRACTION = 0.42f
    private const val DIVIDER_X_FRACTION = 0.685f
    private const val DIVIDER_HEIGHT_FRACTION = 0.42f
    private const val DIVIDER_GAP_REF = 16f

    /** Bottom inset measured from the reference; horizontally the capsule has its own inset. */
    const val BOX_INSET_REF = 104f

    /** White iOS-style lettering, with a restrained shadow added at raster time for contrast. */
    const val TEXT_COLOR_WHITE = 0xF2FFFFFF.toInt()
    const val TEXT_COLOR_LIGHT = 0xF21F1F26.toInt()
    const val TEXT_COLOR_DARK = TEXT_COLOR_WHITE
    private const val DIVIDER_COLOR = 0xB8FFFFFF.toInt()

    /** Immutable and thread-safe, so one shared instance serves every capture thread. */
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss", Locale.US)

    private val TIME_RE = Regex(
        "(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?"
    )

    // ------------------------------------------------------------------
    // Capture metadata extraction
    // ------------------------------------------------------------------

    /**
     * Best-effort scan of the composite call site for capture metadata.
     *
     * The camera passes its whole watermark context through the composite's
     * arguments: string constants, parameter objects, and — on most builds — the
     * capture [Location]. Everything readable is collected here; anything that
     * cannot be found simply stays null and its text line is omitted later.
     */
    fun extractMeta(thisObject: Any?, args: Array<Any?>?, now: Long): CaptureMeta {
        val roots = ArrayList<Any>(8)
        if (thisObject != null && isScannable(thisObject)) roots.add(thisObject)
        val strings = ArrayList<String>(8)
        if (args != null) {
            for (a in args) {
                when {
                    a is String -> if (a.isNotBlank()) strings.add(a.trim())
                    a != null && isScannable(a) -> roots.add(a)
                }
            }
        }

        var location: Location? = null
        for (root in roots) {
            location = location ?: findFieldValue(root, Location::class.java, 2)
            if (location != null) break
        }

        // String fields of the context objects are worth scanning once too — the
        // timestamp frequently lives there when it is not a direct argument.
        for (root in roots) {
            collectStringFields(root, 1, strings)
        }

        var exif: ExifBits? = null
        for (root in roots) {
            if (exif != null) break
            exif = readExifHints(root)
        }

        val deduped = strings.distinct()
        val time = parseTime(deduped, now) ?: now
        return CaptureMeta(time, location, exif, deduped)
    }

    private fun isScannable(v: Any): Boolean {
        if (v is String || v is Number || v is Boolean || v is Char) return false
        if (v is Bitmap || v is Canvas || v is Paint) return false
        val n = v.javaClass.name
        if (n.startsWith("android.graphics.") || n.startsWith("android.view.")) return false
        if (n.startsWith("java.lang.") || n.startsWith("java.util.") && n != "java.util.Date") return false
        return true
    }

    /** Depth-limited search of an object graph for the first field of [type]. */
    private fun <T : Any> findFieldValue(root: Any, type: Class<T>, depth: Int): T? {
        val cls = root.javaClass
        val fields = try {
            cls.declaredFields
        } catch (t: Throwable) {
            return null
        }
        for (f in fields) {
            if (type.isAssignableFrom(f.type)) {
                try {
                    f.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    return type.cast(f.get(root)) as T?
                } catch (t: Throwable) {
                    // Access denied or opaque — keep looking.
                }
            }
        }
        if (depth <= 0) return null
        for (f in fields) {
            try {
                f.isAccessible = true
                val v = f.get(root) ?: continue
                if (!isScannable(v)) continue
                val hit = findFieldValue(v, type, depth - 1)
                if (hit != null) return hit
            } catch (t: Throwable) {
                // Non-vital.
            }
        }
        return null
    }

    /** Collects short non-empty string fields up to [depth] levels into [out]. */
    private fun collectStringFields(obj: Any, depth: Int, out: ArrayList<String>) {
        val cls = obj.javaClass
        val fields = try {
            cls.declaredFields
        } catch (t: Throwable) {
            return
        }
        for (f in fields) {
            if (f.type != String::class.java) continue
            try {
                f.isAccessible = true
                val v = f.get(obj) as? String ?: continue
                if (v.isNotBlank() && v.length <= 128) out.add(v.trim())
            } catch (t: Throwable) {
                // Non-vital.
            }
        }
        if (depth <= 0) return
        for (f in fields) {
            try {
                f.isAccessible = true
                val v = f.get(obj) ?: continue
                if (!isScannable(v)) continue
                collectStringFields(v, depth - 1, out)
            } catch (t: Throwable) {
                // Non-vital.
            }
        }
    }

    /**
     * Looks for zero-arg readable getters on the context object whose names betray
     * capture parameters (focal length, aperture, exposure, ISO). Camera builds
     * keep such wrapper methods un-obfuscated far more often than fields.
     */
    private fun readExifHints(obj: Any): ExifBits? {
        val cls = obj.javaClass
        val name = cls.name
        if (name.startsWith("android.") || name.startsWith("java.")) return null
        val methods = try {
            cls.methods
        } catch (t: Throwable) {
            return null
        }
        var focal: Double? = null
        var aperture: Double? = null
        var exposure: Double? = null
        var iso: Int? = null

        fun numberOf(m: java.lang.reflect.Method): Number? = try {
            when (m.returnType) {
                java.lang.Integer.TYPE, java.lang.Integer::class.java,
                java.lang.Long.TYPE, java.lang.Long::class.java,
                java.lang.Double.TYPE, java.lang.Double::class.java,
                java.lang.Float.TYPE, java.lang.Float::class.java,
                java.lang.Short.TYPE, java.lang.Short::class.java,
                -> (m.invoke(obj) as? Number)
                else -> null
            }
        } catch (t: Throwable) {
            null
        }

        for (m in methods) {
            if (m.parameterCount != 0) continue
            val lower = m.name.lowercase(Locale.US)
            when {
                iso == null && (lower.contains("iso") && !lower.contains("visible") && !lower.contains("noise")) ->
                    numberOf(m)?.let { v ->
                        val d = v.toDouble()
                        if (d >= 25.0 && d <= 25600.0 && d == d.toInt().toDouble()) iso = d.toInt()
                    }
                aperture == null && (lower.contains("aperture") || lower.contains("fnum")) ->
                    numberOf(m)?.let { v ->
                        var d = v.toDouble()
                        if (d >= 9.0 && d <= 100.0) d /= 10.0
                        if (d >= 0.95 && d <= 10.0) aperture = d
                    }
                exposure == null && (lower.contains("exposure") || lower.contains("shutter")) ->
                    numberOf(m)?.let { v ->
                        var s = v.toDouble()
                        if (s > 1e6 && s < 1e10) s /= 1e9 // nanoseconds
                        if (s in 1e-6..10.0) exposure = s
                    }
                focal == null && lower.contains("focal") ->
                    numberOf(m)?.let { v ->
                        val d = v.toDouble()
                        if (d >= 0.5 && d <= 500.0) focal = d
                    }
            }
        }
        if (focal == null && aperture == null && exposure == null && iso == null) return null
        return ExifBits(focal, aperture, exposure, iso)
    }

    /** Finds a wall-clock time inside any collected string, within ±1 year of [now]. */
    private fun parseTime(strs: List<String>, now: Long): Long? {
        val windowMs = 366L * 24 * 3600 * 1000
        for (s in strs) {
            if (s.length < 8 || s.length > 64) continue
            val m = TIME_RE.find(s) ?: continue
            val g = m.groupValues
            val y = g[1].toIntOrNull() ?: continue
            val mo = g[2].toIntOrNull() ?: continue
            val d = g[3].toIntOrNull() ?: continue
            if (y < 2000 || y > 2100) continue
            val hh = g[4].toIntOrNull() ?: 0
            val mi = g[5].toIntOrNull() ?: 0
            val ss = g[6].toIntOrNull() ?: 0
            val millis = try {
                val cal = java.util.Calendar.getInstance()
                cal.clear()
                cal.set(y, (mo - 1).coerceIn(0, 11), d.coerceIn(1, 31),
                    hh.coerceIn(0, 23), mi.coerceIn(0, 59), ss.coerceIn(0, 59))
                cal.timeInMillis
            } catch (t: Throwable) {
                continue
            }
            if (abs(millis - now) <= windowMs) return millis
        }
        return null
    }

    // ------------------------------------------------------------------
    // Text content
    // ------------------------------------------------------------------

    fun buildLines(opts: Options, meta: CaptureMeta, shortSide: Int): List<Line> {
        if (shortSide < 64) return emptyList()
        val lines = ArrayList<Line>(4)
        lines.add(Line(modelText(opts), bold = true, sizeRef = MODEL_SIZE_REF))
        if (opts.showDateTime) {
            lines.add(Line(formatTime(meta.captureMillis), bold = false, sizeRef = BODY_SIZE_REF))
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
        // Two or more parameters make a credible spec line; a lone hit is more
        // likely a misread getter, so it is dropped.
        if (parts.size < 2) return null
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
        // Round-up cascades: 59.99" -> 60" would read as an invalid coordinate
        // component, so the overflow carries into minutes and degrees.
        if (s == 60) { s = 0; m += 1 }
        if (m == 60) { m = 0; d += 1 }
        return String.format(Locale.US, "%d°%d'%d\"%s", d, m, s, hemisphere)
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /**
     * Fixed-dimension capsule layout matching the iOS liquid-glass reference.
     *
     * Uses the reference's fixed, asymmetric composition: a ~90%-width, ~12%-of-short-side
     * capsule, left-aligned to the photo edge; a broad left text area; a wordmark near 63.5%;
     * a hairline separator near 68.5%; and a compact right-aligned metadata block. The footprint
     * stays stable across shots while each text block independently fits its own reserved area.
     */
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
        // Resolve the logo preset for aspect compensation and vertical alignment.
        val preset = LogoPreset.fromName(opts.logoPreset)
        val logoSizeFactor = opts.logoSizeScale.coerceIn(0.01f, 2.0f)
        val logoH = if (hasLogo) LOGO_HEIGHT_FRACTION * interiorH * logoSizeFactor else 0f
        val logoW = if (logoH > 0f && logoBmp != null) {
            // Apply the preset's aspect compensation: height = logoWidth / aspect.
            // So effective height scaling uses aspectCompensation to correct non-square logos.
            (logoH * logoBmp.width.toFloat() / logoBmp.height.toFloat() * preset.aspectCompensation)
                .coerceAtMost(capsuleW * 0.15f)
        } else 0f
        val logoCenterX = LOGO_CENTER_X_FRACTION * capsuleW
        val logoLeft = logoCenterX - logoW * 0.5f
        val dividerX = DIVIDER_X_FRACTION * capsuleW

        // Reserve real, fixed zones instead of splitting the capsule into equal columns.
        // This is the defining geometry in the photo: broad breathing room before the mark,
        // then a compact separator-to-specs region on the right.
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

        // Fit the two text blocks independently so a long GPS/spec line never makes the
        // device name look visibly smaller. Each block retains its own consistent line scale.
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

    // ------------------------------------------------------------------
    // Text raster and compositing
    // ------------------------------------------------------------------

    /**
     * Draws the laid-out lines into a fresh ARGB layer the size of the optics
     * crop, positioned at [capsule]'s crop-local rectangle. Returns raw pixels,
     * or null when the layer could not be created.
     */
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

            // Keep the divider and mark optically attached to the material, not to the scene.
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

    // ------------------------------------------------------------------
    // Colour repair of the erased plate
    // ------------------------------------------------------------------

    private fun rOf(c: Int) = (c shr 16) and 0xFF
    private fun gOf(c: Int) = (c shr 8) and 0xFF
    private fun bOf(c: Int) = c and 0xFF
    private fun pack(r: Int, g: Int, b: Int) = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    /**
     * Fills [region] inside the buffer with a single averaged colour sampled from
     * just outside its horizontal span. The samples must be near-uniform — a
     * spread above [MAX_SPREAD] per channel means the "strip" is actually varied
     * scene content, and filling would destroy detail, so the routine refuses.
     */
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

    /**
     * Samples the strip crop's top rows on its centre columns for the band's own
     * fill colour. The rows just below the photograph's bottom are untouched
     * band rows (the plate and its glow live lower), so when they are
     * near-uniform the colour is by definition the fill the camera itself uses —
     * repainting the band with it cannot introduce a foreign tone.
     *
     * Returns null when the samples vary too much: the band then holds
     * non-fill content (e.g. stretched photo material) and a flat repaint
     * would destroy it, so the caller falls back to narrower repairs.
     */
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
        // Sample rows 5/8/11/14: the crop starts at the photo's bottom edge, so
        // the first rows can still carry photograph content and the ones right
        // after sit closest to the joint's edge gradient. The three-row skip
        // keeps a thin ripple from hiding behind two adjacent rows.
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

    /** Paints the whole strip flat [fill] after the caller verified the band really is fill. */
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

    /**
     * Repairs an in-photo erase region by copying the true photograph over it.
     *
     * The composite scales the photo uniformly into the canvas — both axes the
     * same factor `s = outW / srcW` — and paints it top-aligned, so source
     * coordinates map as `sx = gx / s`, `sy = gy / s`, valid for canvas rows
     * `[0, srcH * s)`. Below that row the canvas holds the fill strip, which the
     * source does not mirror; those rows are left untouched instead of being
     * filled with stretched content (that stretch was exactly the defect of the
     * proportional mapping this replaces).
     *
     * The needed source band is read with one bulk `getPixels` — never a
     * per-pixel JNI hop on the capture thread.
     *
     * @return true when at least one row was rebuilt; rows the source cannot
     *         serve are left exactly as they were.
     */
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

    /**
     * Rebuilds [region] by bilinear blending of the rows above/below and the
     * columns left/right of it. Used only where the source photo is unavailable;
     * the region is small and most of it ends up covered by the capsule anyway.
     */
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

    /** Mean colour of rows from y0 through y1 at column x, each row index clamped. */
    private fun meanOfColumn(pixels: IntArray, cw: Int, ch: Int, x: Int, y0: Int, y1: Int): Int {
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        var n = 0
        var last = Int.MIN_VALUE
        for (y in y0..y1) {
            val yy = y.coerceIn(0, ch - 1)
            if (yy == last) continue // row clamped to the edge: sample it once
            val c = pixels[yy * cw + x]
            rSum += rOf(c); gSum += gOf(c); bSum += bOf(c)
            n++
            last = yy
        }
        if (n == 0) return pack(0, 0, 0)
        return pack((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }

    /** Mean colour of columns from x0 through x1 at row y, each column index clamped. */
    private fun meanOfRow(pixels: IntArray, cw: Int, ch: Int, x0: Int, x1: Int, y: Int): Int {
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        var n = 0
        val yy = y.coerceIn(0, ch - 1)
        val base = yy * cw
        var last = Int.MIN_VALUE
        for (x in x0..x1) {
            val xx = x.coerceIn(0, cw - 1)
            if (xx == last) continue // column clamped to the edge: sample it once
            val c = pixels[base + xx]
            rSum += rOf(c); gSum += gOf(c); bSum += bOf(c)
            n++
            last = xx
        }
        if (n == 0) return pack(0, 0, 0)
        return pack((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }
}