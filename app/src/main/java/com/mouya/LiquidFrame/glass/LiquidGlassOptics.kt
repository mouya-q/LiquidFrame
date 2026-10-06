package com.mouya.LiquidFrame.glass

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The liquid glass optics, with no Android dependency at all.
 *
 * Everything here is a faithful port of the reference renderer
 * (Kyant0/AndroidLiquidGlass, `backdrop` module, Apache-2.0), which runs as an AGSL
 * `RuntimeShader` on Android 13+. A camera watermark, however, is produced while the camera
 * app composites a JPEG: there is no hardware-accelerated canvas and no live view hierarchy
 * to reflect, so the same optics are reproduced here on integer pixel buffers.
 *
 * The port keeps the reference math unchanged where it defines the look:
 *
 *  - `sdRoundedRect` / `gradSdRoundedRect` are the reference's own SDF and gradient;
 *  - the displacement profile is `circleMap(1 - -sd / refractionHeight)` times a *negative*
 *    amount, so the backdrop is pulled inwards and the panel magnifies what is behind it;
 *  - the normal is `normalize(gradSdRoundedRect(c, halfSize, min(1.5r, minHalf))
 *    + depthEffect * normalize(c))`;
 *  - chromatic aberration is the reference's seven-tap R/O/Y/G/C/B/P spectrum with its
 *    per-channel divisors (1/3.5 dominant, 1/3.0 for pure red and blue, 1/7.0 cross-talk);
 *  - `vibrancy()` is the reference's saturation colour matrix (1.5, luma 0.213/0.715/0.072);
 *  - the effect order is the documented one: colour filter, then blur, then lens.
 *
 * Two things are modelled rather than copied, because the reference expresses them as
 * drawing operations rather than as shader code:
 *
 *  - the rim highlight is the reference's `Highlight` modifier: a white **stroke** of
 *    `0.5.dp` on the shape outline, blurred by half that width, composited with
 *    `BlendMode.Plus`. Here that is an analytic Gaussian band around the outline plus the
 *    modifier's directional weighting.
 *  - the inner shadow is the reference's recorded layer: the shape filled with the shadow
 *    colour, the *offset* shape cleared out of it, then blurred. Here that is a smoothstep
 *    band along the offset contour.
 */
object LiquidGlassOptics {

    /**
     * Alpha at or below which a pixel is glass whatever its colour. The glass layer is a
     * white veil the app draws at low alpha, so it cannot be told from a white label by
     * colour alone; alpha is the reliable discriminator.
     */
    private const val GLASS_ALPHA_MAX = 40

    /** Alpha at or above which a pixel is a label, icon or other opaque detail. */
    private const val GLYPH_ALPHA_MIN = 200

    /** Mid-alpha achromatic brightness above which a pixel is a white veil, not a label. */
    private const val VEIL_CHROMA_MAX = 18

    /**
     * How far a panel pixel's luminance may sit from the panel's dominant tone before it counts
     * as the camera's own content. Wide enough to absorb the noise a baked blur in the panel
     * background introduces, narrow enough to catch a label.
     */
    private const val CONTENT_LUMA_DELTA = 26f

    /** Chroma above which a panel pixel is content whatever its luminance. */
    private const val CONTENT_CHROMA_MAX = 34

    /**
     * Share of a panel's pixels that must sit at its dominant tone before the panel counts as a
     * flat baked background. Below this the panel is photographic, so there is no background to
     * separate labels from.
     */
    private const val FLAT_PANEL_UNIFORMITY = 0.55f

    /** Backdrop crop padding, as a fraction of the refraction band. */
    private const val CROP_PAD_FACTOR = 2.2f

    /**
     * Replaces a flat watermark panel with liquid glass, directly in a finished bitmap.
     *
     * This is the path the camera uses. The panel the camera draws is an opaque rectangle of
     * pre-baked background, and the bitmap it was drawn into already holds the photo, so:
     *
     *  - the backdrop needs no external source 鈥?the pixels under the panel *are* the photo;
     *  - the corner radius is measured from the panel instead of assumed;
     *  - the result is composited through a rounded-rect mask, so the square panel the camera
     *    drew comes back as the glass silhouette.
     *
     * @param pixels straight ARGB, width * height, modified in place
     * @param panel  panel geometry in bitmap pixels
     * @return a short outcome description for the module log
     */
    fun renderPanel(
        pixels: IntArray,
        width: Int,
        height: Int,
        panel: PanelRect,
        params: GlassParams = GlassParams.Default,
    ): String {
        if (width < 8 || height < 8) return "skip: bitmap too small (${width}x$height)"
        if (pixels.size < width * height) return "skip: pixel buffer too small"

        val shortSide = minOf(panel.width, panel.height)
        val radius = measureCornerRadius(pixels, width, height, panel)
        val band = (params.refractionHeightFraction * shortSide)
            .coerceIn(MIN_BAND_PX, shortSide * 0.55f)
        val amount = band * params.refractionAmountFraction

        // The camera may bake either a flat colour or a blurred copy of the photo into the
        // panel. A flat panel is one the labels were drawn onto, so they can be recognised and
        // kept; a photographic panel has no flat tone to compare against, and its own texture
        // is what makes the lens readable, so nothing is preserved and nothing needs to be.
        val tone = measurePanelTone(pixels, width, panel)
        val preserveContent = params.preserveContent && tone.uniformity >= FLAT_PANEL_UNIFORMITY

        // The camera's own labels are found once, from the pixels the camera produced, and then
        // left completely alone while the material is drawn around them. Without an explicit
        // mask the replacement would either erase the labels or keep the whole panel.
        val content = if (preserveContent) BooleanArray(width * height) else null
        var preserved = 0
        if (content != null) {
            val left = panel.left.toInt().coerceAtLeast(0)
            val top = panel.top.toInt().coerceAtLeast(0)
            val right = panel.right.toInt().coerceAtMost(width)
            val bottom = panel.bottom.toInt().coerceAtMost(height)
            for (gy in top until bottom) {
                val row = gy * width
                for (gx in left until right) {
                    if (sdRoundedRect(gx + 0.5f, gy + 0.5f, panel, radius) > 0.5f) continue
                    if (isPanelContent(pixels[row + gx], tone.tone)) {
                        content[row + gx] = true
                        preserved++
                    }
                }
            }
        }

        // The lens samples from a padded crop so displacement near the silhouette still lands
        // on real pixels instead of clamping to the panel edge.
        val pad = (band * CROP_PAD_FACTOR).toInt() + 2
        val cropLeft = (panel.left.toInt() - pad).coerceAtLeast(0)
        val cropTop = (panel.top.toInt() - pad).coerceAtLeast(0)
        val cropRight = (panel.right.toInt() + pad).coerceAtMost(width)
        val cropBottom = (panel.bottom.toInt() + pad).coerceAtMost(height)
        val cw = cropRight - cropLeft
        val ch = cropBottom - cropTop
        if (cw <= 0 || ch <= 0) return "skip: empty crop"

        val backdrop = IntArray(cw * ch)
        for (y in 0 until ch) {
            System.arraycopy(pixels, (cropTop + y) * width + cropLeft, backdrop, y * cw, cw)
        }

        // NOTE ON BLUR. The camera's own watermark background is already a blurred layer — the
        // assets are literally named `icon_background_light_blur` / `_dark_blur`, and the
        // element tree blurs the photo into that panel before any label is drawn. Blurring
        // again here would destroy exactly the low-contrast local detail the lens is supposed
        // to bend, which is why the default blur is small and why it is applied only where the
        // lens does not reach. Kept as a parameter because on a photo without that pre-baked
        // layer a little blur is what sells the glass.
        val blurRadius = params.blurFraction * band
        if (blurRadius >= 0.5f) {
            // Blur the panel interior only, leaving the lens margin sharp: sampling across the
            // silhouette would pull the panel's own flat colour into the backdrop.
            val interior = IntArray(cw * ch)
            for (y in 0 until ch) {
                for (x in 0 until cw) {
                    val gx = cropLeft + x
                    val gy = cropTop + y
                    val sd = sdRoundedRect(gx + 0.5f, gy + 0.5f, panel, radius)
                    interior[y * cw + x] = if (-sd >= band) backdrop[y * cw + x] else pixels[gy * width + gx]
                }
            }
            val blurred = blurInPlace(interior, cw, ch, blurRadius)
            for (y in 0 until ch) {
                for (x in 0 until cw) {
                    val gx = cropLeft + x
                    val gy = cropTop + y
                    val sd = sdRoundedRect(gx + 0.5f, gy + 0.5f, panel, radius)
                    if (-sd >= band) backdrop[y * cw + x] = blurred[y * cw + x]
                }
            }
        }

        val adapt = if (params.adaptive) {
            adaptiveScaleRegion(backdrop, cw, ch, panel, cropLeft, cropTop)
        } else 1f

        val highlightDirX = cosDeg(params.highlightAngleDeg)
        val highlightDirY = sinDeg(params.highlightAngleDeg)
        val rimWidth = (params.rimWidthFraction * band).coerceIn(0.5f, band)
        val rimBlur = (params.rimBlurFraction * band).coerceIn(0.35f, band)
        val rimStrength = params.rimAlpha * 255f
        val tintAlpha = (params.tintAlpha * adapt).coerceIn(0f, 1f)
        val surfaceAlpha = (params.surfaceAlpha * adapt).coerceIn(0f, 1f)
        val interiorLift = params.interiorLift * adapt
        val highlightGain = (params.highlightGain / adapt).coerceIn(0.4f, 2.2f)
        val shadowRadius = (params.innerShadowRadiusFraction * shortSide)
            .coerceIn(MIN_SHADOW_RADIUS_PX, shortSide * 0.5f)
        val shadowOffX = params.innerShadowOffsetXFraction * shortSide * 0.25f
        val shadowOffY = params.innerShadowOffsetYFraction * shortSide * 0.25f
        val shadowAlpha =
            (params.innerShadowAlpha * params.innerShadowAdaptiveGain(adapt)).coerceIn(0f, 1f)
        val gradRadius = minOf(radius * 1.5f, shortSide * 0.5f)

        var written = 0
        for (y in 0 until ch) {
            val gy = cropTop + y
            val py = gy + 0.5f
            for (x in 0 until cw) {
                val gx = cropLeft + x
                val px = gx + 0.5f
                val i = y * cw + x

                // Antialiased silhouette from the measured corner radius. Outside it the photo
                // is already correct and is left alone.
                val sd = sdRoundedRect(px, py, panel, radius)
                val coverage = (0.5f - sd).coerceIn(0f, 1f)
                if (coverage <= 0.001f) continue

                // The camera's own labels are drawn over the material, untouched.
                val index = gy * width + gx
                if (content != null && content[index]) continue

                val nx = (px - (panel.left + panel.width * 0.5f)) / (panel.width * 0.5f)
                val ny = (py - (panel.top + panel.height * 0.5f)) / (panel.height * 0.5f)

                val cx = px - (panel.left + panel.width * 0.5f)
                val cy = py - (panel.top + panel.height * 0.5f)
                val grad = gradSdRoundedRect(cx, cy, panel, gradRadius)

                var cr: Float
                var cg: Float
                var cb: Float

                if (-sd >= band) {
                    // Interior, past the lens: the frosted backdrop passes through.
                    val c = sampleRegion(backdrop, cw, ch, cropLeft, cropTop, px, py)
                    cr = ((c shr 16) and 0xFF).toFloat()
                    cg = ((c shr 8) and 0xFF).toFloat()
                    cb = (c and 0xFF).toFloat()
                } else {
                    val sdClamped = minOf(sd, 0f)
                    val t = (1f - (-sdClamped) / band).coerceIn(0f, 1f)
                    val d = circleMap(t) * amount

                    val normal = normalizeOrZero(
                        grad[0] + params.depthEffect * nx,
                        grad[1] + params.depthEffect * ny,
                    )
                    // The reference sends a negative amount; the panel here sits over the
                    // photo, so pulling the backdrop inwards magnifies it the same way.
                    val dx = -d * normal[0]
                    val dy = -d * normal[1]

                    if (params.chromaticAberration) {
                        val s = sampleSpectrumRegion(
                            backdrop, cw, ch, cropLeft, cropTop, px + dx, py + dy,
                            dx, dy, nx, ny, params,
                        )
                        cr = s[0]; cg = s[1]; cb = s[2]
                    } else {
                        val c = sampleRegion(backdrop, cw, ch, cropLeft, cropTop, px + dx, py + dy)
                        cr = ((c shr 16) and 0xFF).toFloat()
                        cg = ((c shr 8) and 0xFF).toFloat()
                        cb = (c and 0xFF).toFloat()
                    }
                }

                if (params.vibrancy != 1f || params.vibrancyBrightness != 0f) {
                    val vib = vibrancy(cr, cg, cb, params.vibrancy, params.vibrancyBrightness)
                    cr = vib[0]; cg = vib[1]; cb = vib[2]
                }

                if (params.tintColor != 0xFFFFFFFF.toInt() && params.tintHueWeight > 0f) {
                    val tinted = hueBlend(cr, cg, cb, params.tintColor)
                    cr = lerp(cr, tinted[0], params.tintHueWeight)
                    cg = lerp(cg, tinted[1], params.tintHueWeight)
                    cb = lerp(cb, tinted[2], params.tintHueWeight)
                }
                cr = lerp(cr, 255f, tintAlpha)
                cg = lerp(cg, 255f, tintAlpha)
                cb = lerp(cb, 255f, tintAlpha)
                if (surfaceAlpha > 1f / 255f) {
                    cr = lerp(cr, 255f, surfaceAlpha)
                    cg = lerp(cg, 255f, surfaceAlpha)
                    cb = lerp(cb, 255f, surfaceAlpha)
                }
                // Apple's material keeps a faint frosted lift across the whole panel; without
                // it a bright photo swallows white watermark labels.
                if (interiorLift > 0.05f) {
                    cr += interiorLift; cg += interiorLift; cb += interiorLift
                }

                if (rimStrength > 0f) {
                    val g2 = gaussianBand(-sd, rimWidth, rimBlur)
                    if (g2 > 0.001f) {
                        val dotDir = grad[0] * highlightDirX + grad[1] * highlightDirY
                        val directional = abs(dotDir).pow(params.highlightFalloff)
                        val rim = g2 * rimStrength *
                                (params.rimAmbient + (1f - params.rimAmbient) * directional) *
                                highlightGain
                        if (rim > 0.01f) {
                            cr += rim; cg += rim; cb += rim
                        }
                    }
                }

                if (shadowAlpha > 0.001f) {
                    val sdOff = sdRoundedRect(px - shadowOffX, py - shadowOffY, panel, radius)
                    val outward = minOf(sdOff / shadowRadius, 1f)
                    val a = (1f - smoothStep(-1f, 0f, outward)) * shadowAlpha
                    if (a > 0.001f) {
                        cr = lerp(cr, 0f, a)
                        cg = lerp(cg, 0f, a)
                        cb = lerp(cb, 0f, a)
                    }
                }

                val glass = pack(255, cr, cg, cb)
                val dst = gy * width + gx
                pixels[dst] = if (coverage >= 0.999f) glass else blend(pixels[dst], glass, coverage)
                written++
            }
        }

        return "ok: panel=${panel.width.toInt()}x${panel.height.toInt()} " +
                "r=${radius.toInt()} band=${band.toInt()} px=$written " +
                "content=$preserved flat=$preserveContent " +
                "u=${fmt(tone.uniformity)} tone=${tone.tone.toInt()} adapt=${fmt(adapt)}"
    }

    /**
     * The panel's dominant tone, as luminance.
     *
     * The camera bakes the panel background as an almost flat colour (it is a pre-rendered
     * WebP, optionally with a blur baked in), so the histogram of the panel region is dominated
     * by that one tone. Taking the median therefore recovers the background, and the labels are
     * whatever stands away from it.
     */
    private fun measurePanelTone(pixels: IntArray, width: Int, panel: PanelRect): PanelTone {
        val left = panel.left.toInt().coerceAtLeast(0)
        val top = panel.top.toInt().coerceAtLeast(0)
        val right = panel.right.toInt().coerceAtMost(width) - 1
        val bottom = panel.bottom.toInt() - 1
        if (right <= left || bottom <= top) return PanelTone(128f, 0f)

        val bins = IntArray(64)
        val step = maxOf(1, (panel.width / 96f).toInt())
        var total = 0
        var x = left
        while (x <= right) {
            var y = top
            while (y <= bottom) {
                val p = pixels[y * width + x]
                val luma = 0.213f * ((p shr 16) and 0xFF) +
                        0.715f * ((p shr 8) and 0xFF) +
                        0.072f * (p and 0xFF)
                bins[(luma.toInt() shr 2).coerceIn(0, 63)]++
                total++
                y += 3
            }
            x += step
        }
        if (total == 0) return PanelTone(128f, 0f)

        var best = 0
        for (i in bins.indices) if (bins[i] > bins[best]) best = i

        // Centroid over the winning bin and its neighbours, for sub-bin accuracy.
        var weighted = 0
        var weight = 0
        for (i in maxOf(0, best - 1)..minOf(63, best + 1)) {
            weighted += bins[i] * i
            weight += bins[i]
        }
        val tone = if (weight == 0) 128f else (weighted.toFloat() / weight) * 4f + 2f

        // Share of the panel that actually sits at that tone. A flat baked background scores
        // high; a photographic one is spread across the histogram and scores low.
        val near = ((tone - CONTENT_LUMA_DELTA).toInt() shr 2).coerceIn(0, 63)
        val far = ((tone + CONTENT_LUMA_DELTA).toInt() shr 2).coerceIn(0, 63)
        var nearCount = 0
        for (i in near..far) nearCount += bins[i]

        return PanelTone(tone, nearCount.toFloat() / total)
    }

    /** The panel's dominant luminance and how much of the panel actually sits at it. */
    private data class PanelTone(val tone: Float, val uniformity: Float)

    /**
     * Whether a panel pixel is the camera's own content (a label, logo or icon) rather than the
     * flat background the material is replacing.
     */
    private fun isPanelContent(pixel: Int, tone: Float): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val luma = 0.213f * r + 0.715f * g + 0.072f * b
        if (abs(luma - tone) > CONTENT_LUMA_DELTA) return true
        // A saturated pixel is content even at the background's brightness: coloured icons and
        // accents sit on these panels.
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        return chroma > CONTENT_CHROMA_MAX
    }

    /**
     * Measures the panel's corner radius from its own silhouette, so the glass copies the
     * shape the camera drew rather than guessing at it.
     *
     * The panel is opaque against a transparent surround, so the radius shows up as the
     * horizontal run of transparent pixels at each end of the panel's top and bottom rows,
     * and the vertical run at each end of its side columns. The smallest agreement wins.
     */
    fun measureCornerRadius(
        pixels: IntArray, width: Int, height: Int, panel: PanelRect,
    ): Float {
        val maxRadius = minOf(panel.width, panel.height) * 0.5f
        val top = panel.top.toInt()
        val left = panel.left.toInt()
        val right = panel.right.toInt() - 1
        val bottom = panel.bottom.toInt() - 1
        if (top < 0 || left < 0 || right >= width || bottom >= height) {
            return (maxRadius * 0.6f).coerceAtLeast(1f)
        }

        fun rowRun(row: Int, fromLeft: Boolean): Int {
            var n = 0
            val limit = (panel.width.toInt() / 2).coerceAtMost(96)
            while (n < limit) {
                val x = if (fromLeft) left + n else right - n
                if (x !in 0 until width) break
                if ((pixels[row * width + x] ushr 24) < 200) n++ else break
            }
            return n
        }

        fun columnRun(col: Int, fromTop: Boolean): Int {
            var n = 0
            val limit = (panel.height.toInt() / 2).coerceAtMost(96)
            while (n < limit) {
                val y = if (fromTop) top + n else bottom - n
                if (y !in 0 until height) break
                if ((pixels[y * width + col] ushr 24) < 200) n++ else break
            }
            return n
        }

        val candidates = listOf(
            rowRun(top, true), rowRun(top, false),
            rowRun(bottom, true), rowRun(bottom, false),
            columnRun(left, true), columnRun(left, false),
            columnRun(right, true), columnRun(right, false),
        ).filter { it > 0 }

        if (candidates.isEmpty()) return (maxRadius * 0.6f).coerceAtLeast(1f)
        return candidates.min().toFloat().coerceIn(0f, maxRadius)
    }

    /** Samples a crop-local pixel buffer at a global bitmap coordinate. */
    private fun sampleRegion(
        region: IntArray, cw: Int, ch: Int, cropLeft: Int, cropTop: Int, x: Float, y: Float,
    ): Int {
        val ix = (x.toInt() - cropLeft).coerceIn(0, cw - 1)
        val iy = (y.toInt() - cropTop).coerceIn(0, ch - 1)
        return region[iy * cw + ix]
    }

    /** Reference dispersion, sampled from a crop-local buffer. */
    private fun sampleSpectrumRegion(
        region: IntArray, cw: Int, ch: Int, cropLeft: Int, cropTop: Int,
        x: Float, y: Float, dx: Float, dy: Float, nx: Float, ny: Float, params: GlassParams,
    ): FloatArray {
        val disp = params.dispersionIntensity * nx * ny
        val ex = dx * disp
        val ey = dy * disp

        var r = 0f; var g = 0f; var b = 0f

        fun tap(ox: Float, oy: Float, block: (Int) -> Unit) {
            block(sampleRegion(region, cw, ch, cropLeft, cropTop, x + ox, y + oy))
        }

        tap(ex, ey) { r += ((it shr 16) and 0xFF) / 3.5f }
        tap(ex * 0.6667f, ey * 0.6667f) {
            r += ((it shr 16) and 0xFF) / 3.5f
            g += ((it shr 8) and 0xFF) / 7f
        }
        tap(ex * 0.3333f, ey * 0.3333f) {
            r += ((it shr 16) and 0xFF) / 3.5f
            g += ((it shr 8) and 0xFF) / 3.5f
        }
        tap(0f, 0f) { g += ((it shr 8) and 0xFF) / 3.5f }
        tap(-ex * 0.3333f, -ey * 0.3333f) {
            g += ((it shr 8) and 0xFF) / 3.5f
            b += (it and 0xFF) / 3f
        }
        tap(-ex * 0.6667f, -ey * 0.6667f) { b += (it and 0xFF) / 3f }
        tap(-ex, -ey) {
            r += ((it shr 16) and 0xFF) / 7f
            b += (it and 0xFF) / 3f
        }

        return floatArrayOf(r, g, b)
    }

    private fun adaptiveScaleRegion(
        region: IntArray, cw: Int, ch: Int, panel: PanelRect, cropLeft: Int, cropTop: Int,
    ): Float {
        var total = 0.0
        var count = 0
        val stepX = maxOf(1, (panel.width / 12f).toInt())
        val stepY = maxOf(1, (panel.height / 6f).toInt())
        var y = 0f
        while (y < panel.height) {
            var x = 0f
            while (x < panel.width) {
                val c = sampleRegion(region, cw, ch, cropLeft, cropTop, panel.left + x, panel.top + y)
                total += 0.213 * ((c shr 16) and 0xFF) +
                        0.715 * ((c shr 8) and 0xFF) +
                        0.072 * (c and 0xFF)
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return 1f
        val mean = total / count / 255.0
        return (1.25 - 0.75 * mean).coerceIn(0.45, 1.55).toFloat()
    }

    /** Blurs a crop-local buffer, returning a new one. */
    private fun blurInPlace(region: IntArray, cw: Int, ch: Int, radius: Float): IntArray {
        var a = region.copyOf()
        var b = IntArray(region.size)
        repeat(3) {
            boxPass(a, b, cw, ch, radius)
            val t = a; a = b; b = t
        }
        return a
    }

    /** Source-over of [top] onto [bottom] at [alpha]. */
    private fun blend(bottom: Int, top: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val t = ((top shr shift) and 0xFF).toFloat()
            val b = ((bottom shr shift) and 0xFF).toFloat()
            return (t * a + b * (1f - a)).toInt().coerceIn(0, 255)
        }
        return pack(255, ch(16), ch(8), ch(0))
    }

    /**
     * Renders the material over the panel region of [src] (width x height, straight ARGB).
     *
     * @param src        watermark pixels, modified in place
     * @param backdrop   captured photo pixels, straight ARGB; may be any size
     * @param backdropW  backdrop width
     * @param backdropH  backdrop height
     * @param panel      panel placement inside the backdrop, in backdrop pixels
     * @param params     material parameters
     * @param refractBackdrop whether [backdrop] really is the photo behind the panel. When
     *                        false the watermark's own layer is used as the backdrop, which
     *                        keeps the optics correct while revealing nothing.
     * @return a short outcome description for the module log
     */
    fun render(
        src: IntArray,
        width: Int,
        height: Int,
        backdrop: IntArray?,
        backdropW: Int,
        backdropH: Int,
        panel: PanelRect,
        params: GlassParams = GlassParams.Default,
        refractBackdrop: Boolean = true,
    ): String {
        if (width < 8 || height < 8) return "skip: bitmap too small (${width}x$height)"
        if (src.size < width * height) return "skip: pixel buffer too small"

        val shortSide = minOf(width, height).toFloat()
        val radius = panel.cornerRadiusPx.coerceIn(0f, shortSide * 0.5f)
        val band = (params.refractionHeightFraction * shortSide)
            .coerceIn(MIN_BAND_PX, shortSide * 0.5f)
        val amount = band * params.refractionAmountFraction

        // ---- pass 1: classify glass body vs glyph ------------------------------------------
        val mask = ByteArray(width * height)
        var glassPixels = 0
        var glyphPixels = 0
        for (i in 0 until width * height) {
            val p = src[i]
            val a = p ushr 24
            if (a == 0) continue
            if (isGlass(a, (p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)) {
                mask[i] = GLASS
                glassPixels++
            } else {
                mask[i] = GLYPH
                glyphPixels++
            }
        }
        if (glassPixels == 0) {
            return "skip: no translucent glass layer ($glyphPixels opaque px)"
        }

        // ---- resolve and blur the backdrop (documented order: colour filter, blur, lens) ----
        val source: IntArray
        val srcW: Int
        val srcH: Int
        val srcLeft: Float
        val srcTop: Float
        val srcPanelW: Float
        val srcPanelH: Float

        if (refractBackdrop && backdrop != null && backdropW > 0 && backdropH > 0 &&
            backdrop.size >= backdropW * backdropH
        ) {
            source = backdrop
            srcW = backdropW
            srcH = backdropH
            srcLeft = panel.left
            srcTop = panel.top
            srcPanelW = panel.width
            srcPanelH = panel.height
        } else {
            source = src
            srcW = width
            srcH = height
            srcLeft = 0f
            srcTop = 0f
            srcPanelW = width.toFloat()
            srcPanelH = height.toFloat()
        }

        val blurRadius = params.blurFraction * band
        val blurred = if (blurRadius >= 0.5f) boxBlur(source, srcW, srcH, blurRadius) else source

        // ---- pass 2: refraction ------------------------------------------------------------
        val pad = (band * CROP_PAD_FACTOR).toInt() + 2
        val cropLeft = (-pad).coerceAtLeast(0)
        val cropTop = (-pad).coerceAtLeast(0)
        val cropRight = (width + pad).coerceAtMost(width)
        val cropBottom = (height + pad).coerceAtMost(height)
        val cw = cropRight - cropLeft
        val ch = cropBottom - cropTop
        if (cw <= 0 || ch <= 0) return "skip: empty crop"

        val rendered = IntArray(cw * ch)
        val highlightDirX = cosDeg(params.highlightAngleDeg)
        val highlightDirY = sinDeg(params.highlightAngleDeg)

        // Adaptive exposure sampled from the real backdrop behind the panel.
        val adapt = if (params.adaptive) {
            adaptiveScale(blurred, srcW, srcH, srcLeft, srcTop, srcPanelW, srcPanelH, panel)
        } else 1f

        val shadowRadius = (params.innerShadowRadiusFraction * shortSide)
            .coerceIn(MIN_SHADOW_RADIUS_PX, shortSide * 0.5f)
        val shadowOffX = params.innerShadowOffsetXFraction * shortSide * 0.25f
        val shadowOffY = params.innerShadowOffsetYFraction * shortSide * 0.25f
        val shadowAlpha = (params.innerShadowAlpha * params.innerShadowAdaptiveGain(adapt)).coerceIn(0f, 1f)

        // Rim highlight: the reference's blurred 0.5.dp white stroke with BlendMode.Plus.
        val rimWidth = (params.rimWidthFraction * band).coerceIn(0.5f, band)
        val rimBlur = (params.rimBlurFraction * band).coerceIn(0.35f, band)
        val rimStrength = params.rimAlpha * 255f

        // Surface stack constants.
        val tintAlpha = (params.tintAlpha * adapt).coerceIn(0f, 1f)
        val surfaceAlpha = (params.surfaceAlpha * adapt).coerceIn(0f, 1f)
        val highlightGain = (params.highlightGain / adapt).coerceIn(0.4f, 2.2f)

        for (y in 0 until ch) {
            val gy = cropTop + y
            for (x in 0 until cw) {
                val gx = cropLeft + x
                val i = y * cw + x

                // Panel-space position, normalised to -1..1 for the depth lean.
                val px = gx + 0.5f
                val py = gy + 0.5f
                // NB: px/py are global bitmap coordinates, so the centre has to include the
                // panel's own origin. Using `panel.width * 0.5` here (as an earlier revision did)
                // put the "centre" at the bitmap's middle-left, which on a full-size frame made
                // the depth normal collapse to a near-constant and silently disabled the lens.
                val nx = (px - (panel.left + panel.width * 0.5f)) / (panel.width * 0.5f)
                val ny = (py - (panel.top + panel.height * 0.5f)) / (panel.height * 0.5f)

                val sd = sdRoundedRect(px, py, panel, radius)
                if (-sd >= band) {
                    // Beyond the lens: the backdrop passes through untouched.
                    rendered[i] = samplePhoto(
                        blurred, srcW, srcH, srcLeft, srcTop, srcPanelW, srcPanelH, px, py
                    )
                    continue
                }

                val sdClamped = minOf(sd, 0f)
                val t = (1f - (-sdClamped) / band).coerceIn(0f, 1f)
                val d = circleMap(t) * -amount

                val cx = px - (panel.left + panel.width * 0.5f)
                val cy = py - (panel.top + panel.height * 0.5f)
                val gradRadius = minOf(radius * 1.5f, minOf(panel.width, panel.height) * 0.5f)
                val g = gradSdRoundedRect(cx, cy, panel, gradRadius)
                val normal = normalizeOrZero(
                    g[0] + params.depthEffect * nx,
                    g[1] + params.depthEffect * ny,
                )

                val dx = d * normal[0]
                val dy = d * normal[1]

                var cr: Float
                var cg: Float
                var cb: Float

                if (params.chromaticAberration) {
                    val spectrum = sampleSpectrum(
                        blurred, srcW, srcH, srcLeft, srcTop, srcPanelW, srcPanelH,
                        px + dx, py + dy, dx, dy, nx, ny, params,
                    )
                    cr = spectrum[0]; cg = spectrum[1]; cb = spectrum[2]
                } else {
                    val c = samplePhoto(
                        blurred, srcW, srcH, srcLeft, srcTop, srcPanelW, srcPanelH,
                        px + dx, py + dy,
                    )
                    cr = ((c shr 16) and 0xFF).toFloat()
                    cg = ((c shr 8) and 0xFF).toFloat()
                    cb = (c and 0xFF).toFloat()
                }

                // vibrancy()
                if (params.vibrancy != 1f || params.vibrancyBrightness != 0f) {
                    val vib = vibrancy(cr, cg, cb, params.vibrancy, params.vibrancyBrightness)
                    cr = vib[0]; cg = vib[1]; cb = vib[2]
                }

                // onDrawSurface: hue tint, then the tint again, then the flat surface veil.
                if (params.tintColor != 0xFFFFFFFF.toInt() && params.tintHueWeight > 0f) {
                    val tinted = hueBlend(cr, cg, cb, params.tintColor)
                    cr = lerp(cr, tinted[0], params.tintHueWeight)
                    cg = lerp(cg, tinted[1], params.tintHueWeight)
                    cb = lerp(cb, tinted[2], params.tintHueWeight)
                }
                cr = lerp(cr, 255f, tintAlpha)
                cg = lerp(cg, 255f, tintAlpha)
                cb = lerp(cb, 255f, tintAlpha)
                if (surfaceAlpha > 1f / 255f) {
                    cr = lerp(cr, 255f, surfaceAlpha)
                    cg = lerp(cg, 255f, surfaceAlpha)
                    cb = lerp(cb, 255f, surfaceAlpha)
                }

                // Rim highlight: blurred stroke band, weighted by the light direction like
                // the reference's Default highlight shader (pow(abs(dot), falloff)).
                if (rimStrength > 0f) {
                    val bandBand = gaussianBand(-sd, rimWidth, rimBlur)
                    if (bandBand > 0.001f) {
                        val dotDir = g[0] * highlightDirX + g[1] * highlightDirY
                        val directional = kotlin.math.abs(dotDir).pow(params.highlightFalloff)
                        val rim = bandBand * rimStrength *
                                (params.rimAmbient + (1f - params.rimAmbient) * directional) *
                                highlightGain
                        if (rim > 0.01f) {
                            cr += rim; cg += rim; cb += rim
                        }
                    }
                }

                // Inner shadow: shape filled, offset shape cleared, blurred, clipped.
                if (shadowAlpha > 0.001f) {
                    // Inner shadow = shape minus an offset copy of itself. The offset shape is
                    // the one that has to be *cleared*, so it is subtracted first; `shadowOffY`
                    // is +1 (top edge) by default, which is `InnerShadow.Default`'s offset.
                    val sdOffset = sdRoundedRect(
                        px - shadowOffX, py - shadowOffY, panel, radius
                    )
                    val cleared = (1f - smoothStep(-1f, 0f, minOf(sdOffset / shadowRadius, 1f)))
                    val density = (cleared * shadowAlpha).coerceIn(0f, 1f)
                    if (density > 0.001f) {
                        cr = lerp(cr, 0f, density)
                        cg = lerp(cg, 0f, density)
                        cb = lerp(cb, 0f, density)
                    }
                }

                rendered[i] = pack(255, cr, cg, cb)
            }
        }

        // ---- pass 3: composite the material back through the watermark's own alpha --------
        for (y in 0 until ch) {
            val gy = cropTop + y
            for (x in 0 until cw) {
                val gx = cropLeft + x
                val i = gy * width + gx
                // The mask spans the whole bitmap while this loop only covers the crop, so the
                // index has to be re-checked here; `cropTop + y` is already >= 0 but the
                // product can still leave the buffer when the panel sits against an edge.
                if (gx < 0 || gy < 0 || gx >= width || gy >= height) continue
                if (i < 0 || i >= src.size) continue
                val m = mask[i]
                if (m == 0.toByte()) continue

                val original = src[i]
                val layerAlpha = (original ushr 24) / 255f
                val glass = rendered[y * cw + x]

                src[i] = if (m == GLASS) {
                    // How much of the material shows through is a *look* decision, not a fixed
                    // curve: the app's own translucency sets the base, and the adaptive exposure
                    // measured from the real backdrop thins it over bright scenes and densifies it
                    // over dark ones. An earlier revision hard-clamped this to 0.18..0.72, which
                    // capped the lens no matter how the scene was lit.
                    val alpha = (layerAlpha * 0.85f + 0.30f + (1f - adapt) * 0.26f)
                        .coerceIn(0.26f, 0.94f)
                    withAlpha(glass, (alpha * 255f).toInt())
                } else {
                    // Glyph or opaque detail: keep it, over the glass underneath.
                    if (layerAlpha >= 0.99f) original else srcOver(withAlpha(glass, 255), original)
                }
            }
        }

        return "ok: glass=${glassPixels}px glyph=${glyphPixels}px " +
                "band=${band.toInt()} r=${radius.toInt()} adapt=${fmt(adapt)}"
    }

    // ---------------------------------------------------------------------------------------
    // Optics
    // ---------------------------------------------------------------------------------------

    /**
     * Reference `sdRoundedRect`: negative inside, zero on the outline, positive outside.
     */
    fun sdRoundedRect(px: Float, py: Float, panel: PanelRect, radius: Float): Float {
        val cx = px - (panel.left + panel.width * 0.5f)
        val cy = py - (panel.top + panel.height * 0.5f)
        val hx = panel.width * 0.5f
        val hy = panel.height * 0.5f
        val qx = abs(cx) - (hx - radius)
        val qy = abs(cy) - (hy - radius)
        val outside = hypot(maxOf(qx, 0f), maxOf(qy, 0f)) - radius
        val inside = minOf(maxOf(qx, qy), 0f)
        return outside + inside
    }

    /**
     * Reference `gradSdRoundedRect`. Callers must pass the reference's own `gradRadius`,
     * which is deliberately 1.5x the corner radius: the upstream commit that introduced it
     * ("Update shader to match Apple's effect") says that factor exists purely to match
     * Apple's render.
     */
    fun gradSdRoundedRect(px: Float, py: Float, panel: PanelRect, radius: Float): FloatArray {
        val cx = px - (panel.left + panel.width * 0.5f)
        val cy = py - (panel.top + panel.height * 0.5f)
        val hx = panel.width * 0.5f
        val hy = panel.height * 0.5f
        val qx = abs(cx) - (hx - radius)
        val qy = abs(cy) - (hy - radius)

        if (qx >= 0f || qy >= 0f) {
            val mx = maxOf(qx, 0f)
            val my = maxOf(qy, 0f)
            val len = hypot(mx, my)
            if (len < 1e-6f) return floatArrayOf(sign(cx), sign(cy))
            return floatArrayOf(sign(cx) * mx / len, sign(cy) * my / len)
        }
        val gradX = if (qy <= qx) 1f else 0f
        return floatArrayOf(sign(cx) * gradX, sign(cy) * (1f - gradX))
    }

    /** Reference `circleMap`: 0 at the outline, 1 at the inner boundary of the band. */
    fun circleMap(x: Float): Float {
        val c = x.coerceIn(-1f, 1f)
        return 1f - sqrt(1f - c * c)
    }

    private fun normalizeOrZero(x: Float, y: Float): FloatArray {
        val len = hypot(x, y)
        if (len < 1e-6f) return floatArrayOf(0f, 0f)
        return floatArrayOf(x / len, y / len)
    }

    private fun sign(v: Float): Float = if (v >= 0f) 1f else -1f

    /**
     * The reference's rim highlight is a stroke of `width` centred on the outline, blurred
     * by `width / 2`, so a Gaussian of that sigma around the outline reproduces it.
     */
    private fun gaussianBand(outwardDistance: Float, width: Float, sigma: Float): Float {
        val centre = -width * 0.5f
        val d = (outwardDistance - centre) / sigma
        return kotlin.math.exp(-0.5f * d * d)
    }

    // ---------------------------------------------------------------------------------------
    // Colour
    // ---------------------------------------------------------------------------------------

    /** Reference `vibrancy()`: saturation colour matrix, 1.5, luma 0.213/0.715/0.072. */
    private fun vibrancy(r: Float, g: Float, b: Float, saturation: Float, brightness: Float): FloatArray {
        val invSat = 1f - saturation
        val cr = 0.213f * invSat
        val cg = 0.715f * invSat
        val cb = 0.072f * invSat
        val t = brightness * 255f
        return floatArrayOf(
            (cr + saturation) * r + cg * g + cb * b + t,
            cr * r + (cg + saturation) * g + cb * b + t,
            cr * r + cg * g + (cb + saturation) * b + t,
        )
    }

    /**
     * Android `BlendMode.HUE`, which the reference's docs recommend for tint: keep the
     * backdrop's luminosity, take the tint's hue. Weights are the Skia/Compose HSL ones.
     */
    private fun hueBlend(r: Float, g: Float, b: Float, tint: Int): FloatArray {
        val tr = ((tint shr 16) and 0xFF) / 255f
        val tg = ((tint shr 8) and 0xFF) / 255f
        val tb = (tint and 0xFF) / 255f

        val dr = r / 255f; val dg = g / 255f; val db = b / 255f
        val lumD = 0.3f * dr + 0.59f * dg + 0.11f * db
        val lumT = 0.3f * tr + 0.59f * tg + 0.11f * tb
        val delta = lumD - lumT
        return floatArrayOf(
            ((tr + delta) * 255f).coerceIn(0f, 255f),
            ((tg + delta) * 255f).coerceIn(0f, 255f),
            ((tb + delta) * 255f).coerceIn(0f, 255f),
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Mean luminance of the backdrop behind the panel. Apple's material thins out over a
     * bright backdrop and densifies over a dark one; the returned scale drives tint, veil
     * and rim strength together so the panel always reads as one material.
     */
    private fun adaptiveScale(
        px: IntArray, w: Int, h: Int,
        left: Float, top: Float, panelW: Float, panelH: Float,
        panel: PanelRect,
    ): Float {
        var total = 0.0
        var count = 0
        val stepX = maxOf(1, (panel.width / 12f).toInt())
        val stepY = maxOf(1, (panel.height / 6f).toInt())
        var y = 0f
        while (y < panel.height) {
            var x = 0f
            while (x < panel.width) {
                val c = samplePhoto(px, w, h, left, top, panelW, panelH, x, y)
                total += 0.213 * ((c shr 16) and 0xFF) +
                        0.715 * ((c shr 8) and 0xFF) +
                        0.072 * (c and 0xFF)
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return 1f
        val mean = total / count / 255.0
        return (1.25 - 0.75 * mean).coerceIn(0.45, 1.55).toFloat()
    }

    // ---------------------------------------------------------------------------------------
    // Sampling
    // ---------------------------------------------------------------------------------------

    /**
     * Maps a panel-space point into the backdrop and samples it.
     *
     * The panel is the watermark, which the camera composites at its own scale, so its
     * coordinates are rescaled onto the backdrop's grid: the glass therefore refracts the
     * photo at the photo's real resolution rather than at the watermark's.
     */
    private fun samplePhoto(
        px: IntArray, w: Int, h: Int,
        left: Float, top: Float, panelW: Float, panelH: Float,
        x: Float, y: Float,
    ): Int {
        val sx = x * w / panelW + left
        val sy = y * h / panelH + top
        val ix = sx.toInt().coerceIn(0, w - 1)
        val iy = sy.toInt().coerceIn(0, h - 1)
        return px[iy * w + ix]
    }

    /**
     * Reference dispersion: the same displacement sampled at seven wavelengths, each with
     * its own divisors. `dispersionIntensity = x*y / (halfW*halfH)` is antisymmetric across
     * the diagonals, so the spectrum splits one way on two opposite edges and the other way
     * on the remaining two, like a prism.
     */
    private fun sampleSpectrum(
        px: IntArray, w: Int, h: Int,
        left: Float, top: Float, panelW: Float, panelH: Float,
        x: Float, y: Float,
        dx: Float, dy: Float,
        nx: Float, ny: Float,
        params: GlassParams,
    ): FloatArray {
        // Displacement follows the panel grid, so the spectrum offsets must be scaled too.
        val sdx = dx * w / panelW
        val sdy = dy * h / panelH
        val disp = params.dispersionIntensity * nx * ny
        val ex = sdx * disp
        val ey = sdy * disp

        var r = 0f; var g = 0f; var b = 0f

        fun tap(ox: Float, oy: Float, block: (Int) -> Unit) {
            val sx = x * w / panelW + left + sdx + ox
            val sy = y * h / panelH + top + sdy + oy
            val ix = sx.toInt().coerceIn(0, w - 1)
            val iy = sy.toInt().coerceIn(0, h - 1)
            block(px[iy * w + ix])
        }

        tap(ex, ey) { r += ((it shr 16) and 0xFF) / 3.5f }
        tap(ex * 0.6667f, ey * 0.6667f) {
            r += ((it shr 16) and 0xFF) / 3.5f
            g += ((it shr 8) and 0xFF) / 7f
        }
        tap(ex * 0.3333f, ey * 0.3333f) {
            r += ((it shr 16) and 0xFF) / 3.5f
            g += ((it shr 8) and 0xFF) / 3.5f
        }
        tap(0f, 0f) { g += ((it shr 8) and 0xFF) / 3.5f }
        tap(-ex * 0.3333f, -ey * 0.3333f) {
            g += ((it shr 8) and 0xFF) / 3.5f
            b += (it and 0xFF) / 3f
        }
        tap(-ex * 0.6667f, -ey * 0.6667f) { b += (it and 0xFF) / 3f }
        tap(-ex, -ey) {
            r += ((it shr 16) and 0xFF) / 7f
            b += (it and 0xFF) / 3f
        }

        return floatArrayOf(r, g, b)
    }

    // ---------------------------------------------------------------------------------------
    // Blur
    // ---------------------------------------------------------------------------------------

    /**
     * Three box passes approximate a Gaussian, which is what the reference's `BlurEffect`
     * does. Radius is in backdrop pixels.
     */
    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Float): IntArray {
        var a = src
        var b = IntArray(src.size)
        val r = radius.coerceAtLeast(0.5f)
        repeat(3) {
            boxPass(a, b, w, h, r)
            val t = a; a = b; b = t
        }
        return a
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Float) {
        val ri = radius.toInt().coerceAtLeast(1)
        val window = ri * 2 + 1
        val tmp = IntArray(src.size)

        // Horizontal.
        for (y in 0 until h) {
            val row = y * w
            var ar = 0; var ag = 0; var ab = 0; var aa = 0
            for (i in -ri..ri) {
                val c = src[row + i.coerceIn(0, w - 1)]
                ar += (c shr 16) and 0xFF; ag += (c shr 8) and 0xFF
                ab += c and 0xFF; aa += c ushr 24
            }
            for (x in 0 until w) {
                tmp[row + x] = pack(aa / window, ar / window, ag / window, ab / window)
                val out = src[row + (x - ri).coerceIn(0, w - 1)]
                val inn = src[row + (x + ri + 1).coerceIn(0, w - 1)]
                ar += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                ag += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                ab += (inn and 0xFF) - (out and 0xFF)
                aa += (inn ushr 24) - (out ushr 24)
            }
        }

        // Vertical.
        for (x in 0 until w) {
            var ar = 0; var ag = 0; var ab = 0; var aa = 0
            for (i in -ri..ri) {
                val c = tmp[i.coerceIn(0, h - 1) * w + x]
                ar += (c shr 16) and 0xFF; ag += (c shr 8) and 0xFF
                ab += c and 0xFF; aa += c ushr 24
            }
            for (y in 0 until h) {
                dst[y * w + x] = pack(aa / window, ar / window, ag / window, ab / window)
                val out = tmp[(y - ri).coerceIn(0, h - 1) * w + x]
                val inn = tmp[(y + ri + 1).coerceIn(0, h - 1) * w + x]
                ar += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                ag += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                ab += (inn and 0xFF) - (out and 0xFF)
                aa += (inn ushr 24) - (out ushr 24)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------

    private const val GLASS: Byte = 2
    private const val GLYPH: Byte = 1

    /**
     * Decides whether a watermark pixel belongs to the glass backdrop or is content that
     * must stay on top of it.
     *
     * A Xiaomi watermark is two layers: a white veil drawn at low alpha, and opaque white
     * labels over it. Alpha alone separates the extremes; for the antialiased pixels in
     * between, an achromatic bright pixel is treated as more veil while anything with
     * colour is content.
     */
    private fun isGlass(alpha: Int, r: Int, g: Int, b: Int): Boolean {
        if (alpha <= GLASS_ALPHA_MAX) return true
        if (alpha >= GLYPH_ALPHA_MIN) return false
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        val luma = 0.213f * r + 0.715f * g + 0.072f * b
        return chroma <= VEIL_CHROMA_MAX && luma >= 235f
    }

    private const val MIN_BAND_PX = 2f
    private const val MIN_SHADOW_RADIUS_PX = 1f

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun pack(a: Int, r: Float, g: Float, b: Float): Int =
        pack(a, r.toInt(), g.toInt(), b.toInt())

    private fun withAlpha(color: Int, a: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    /** Standard source-over of [top] onto [bottom]; both straight (non-premultiplied). */
    fun srcOver(bottom: Int, top: Int): Int {
        val ta = ((top ushr 24) and 0xFF) / 255f
        if (ta >= 1f) return top
        val ba = ((bottom ushr 24) and 0xFF) / 255f
        val outA = ta + ba * (1f - ta)
        if (outA <= 0f) return 0
        fun ch(shift: Int): Int {
            val t = ((top shr shift) and 0xFF).toFloat()
            val bb = ((bottom shr shift) and 0xFF).toFloat()
            return ((t * ta + bb * ba * (1f - ta)) / outA).toInt().coerceIn(0, 255)
        }
        return pack((outA * 255f).toInt(), ch(16), ch(8), ch(0))
    }

    private fun cosDeg(deg: Float): Float = kotlin.math.cos(deg * DEG_TO_RAD)
    private fun sinDeg(deg: Float): Float = kotlin.math.sin(deg * DEG_TO_RAD)

    private const val DEG_TO_RAD = 0.017453292f

    private fun fmt(v: Float): String {
        val scaled = (v * 100f).toInt()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }
}


