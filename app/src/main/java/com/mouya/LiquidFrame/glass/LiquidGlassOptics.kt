package com.mouya.LiquidFrame.glass

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

object LiquidGlassOptics {

    private const val GLASS_ALPHA_MAX = 40

    private const val GLYPH_ALPHA_MIN = 200

    private const val VEIL_CHROMA_MAX = 18

    private const val CONTENT_LUMA_DELTA = 26f

    private const val CONTENT_CHROMA_MAX = 34

    private const val FLAT_PANEL_UNIFORMITY = 0.55f

    private const val CROP_PAD_FACTOR = 2.2f

    fun requiredPanelPaddingPx(panel: PanelRect, params: GlassParams = GlassParams.Default): Int {
        val shortSide = minOf(panel.width, panel.height)
        val maxBand = maxOf(1f, shortSide * 0.55f)
        val minBand = minOf(MIN_BAND_PX.toFloat(), maxBand)
        val heightFraction = params.refractionHeightFraction
            .takeIf { it.isFinite() }?.coerceIn(0.005f, 0.45f) ?: GlassParams.Default.refractionHeightFraction
        val amountFraction = params.refractionAmountFraction
            .takeIf { it.isFinite() }?.coerceIn(0f, 4f) ?: GlassParams.Default.refractionAmountFraction
        val blurFraction = params.blurFraction
            .takeIf { it.isFinite() }?.coerceIn(0f, 1.5f) ?: GlassParams.Default.blurFraction
        val band = (heightFraction * shortSide).coerceIn(minBand, maxBand)
        val displacement = band * amountFraction
        val blurRadius = band * blurFraction
        return maxOf(band * CROP_PAD_FACTOR, displacement + band, blurRadius * 3f).toInt() + 2
    }

    fun renderPanel(
        pixels: IntArray,
        width: Int,
        height: Int,
        panel: PanelRect,
        params: GlassParams = GlassParams.Default,
        panelIsOpaquePlate: Boolean = true,
        photoBackdrop: IntArray? = null,
        photoLeft: Int = 0,
        photoTop: Int = 0,
        photoW: Int = 0,
        photoH: Int = 0,
    ): String {
        if (width < 8 || height < 8) return "skip: bitmap too small (${width}x$height)"
        if (pixels.size < width * height) return "skip: pixel buffer too small"

        val shortSide = minOf(panel.width, panel.height)

        val radius = if (panel.cornerRadiusPx > 0f) {
            panel.cornerRadiusPx.coerceIn(0f, shortSide * 0.5f)
        } else {
            measureCornerRadius(pixels, width, height, panel)
        }
        val maxBand = maxOf(1f, shortSide * 0.55f)
        val minBand = minOf(MIN_BAND_PX.toFloat(), maxBand)
        val heightFraction = params.refractionHeightFraction.takeIf { it.isFinite() }?.coerceIn(0.005f, 0.45f) ?: GlassParams.Default.refractionHeightFraction
        val amountFraction = params.refractionAmountFraction.takeIf { it.isFinite() }?.coerceIn(0f, 4f) ?: GlassParams.Default.refractionAmountFraction
        val blurFraction = params.blurFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1.5f) ?: GlassParams.Default.blurFraction
        val band = (heightFraction * shortSide).coerceIn(minBand, maxBand)
        val amount = band * amountFraction
        val blurRadius = blurFraction * band

        val tone = measurePanelTone(pixels, width, panel)
        val preserveContent =
            panelIsOpaquePlate && params.preserveContent && tone.uniformity >= FLAT_PANEL_UNIFORMITY

        val pad = requiredPanelPaddingPx(panel, params)
        val cropLeft = (panel.left.toInt() - pad).coerceAtLeast(0)
        val cropTop = (panel.top.toInt() - pad).coerceAtLeast(0)
        val cropRight = (panel.right.toInt() + pad).coerceAtMost(width)
        val cropBottom = (panel.bottom.toInt() + pad).coerceAtMost(height)
        val cw = cropRight - cropLeft
        val ch = cropBottom - cropTop
        if (cw <= 0 || ch <= 0) return "skip: empty crop"

        val content = if (preserveContent) FloatArray(cw * ch) else null
        var preserved = 0
        if (content != null) {
            val left = maxOf(panel.left.toInt(), cropLeft, 0)
            val top = maxOf(panel.top.toInt(), cropTop, 0)
            val right = minOf(panel.right.toInt(), cropRight, width)
            val bottom = minOf(panel.bottom.toInt(), cropBottom, height)
            for (gy in top until bottom) {
                val localRow = (gy - cropTop) * cw
                val sourceRow = gy * width
                for (gx in left until right) {
                    if (sdRoundedRect(gx + 0.5f, gy + 0.5f, panel, radius) > 0.5f) continue
                    val soft = contentSoftness(pixels[sourceRow + gx], tone.tone)
                    if (soft > 0f) {
                        content[localRow + gx - cropLeft] = soft
                        if (soft >= 0.5f) preserved++
                    }
                }
            }
        }

        val backdrop = IntArray(cw * ch)
        for (y in 0 until ch) {
            System.arraycopy(pixels, (cropTop + y) * width + cropLeft, backdrop, y * cw, cw)
        }

        val photo = photoBackdrop
        val hasPhoto = photo != null && photoW > 0 && photoH > 0
        if (hasPhoto) {

            val interLeft = maxOf(0, photoLeft)
            val interTop = maxOf(0, photoTop)
            val interRight = minOf(cw, photoLeft + photoW)
            val interBottom = minOf(ch, photoTop + photoH)
            for (y in interTop until interBottom) {
                val srcRow = (y - photoTop) * photoW
                val dstRow = y * cw
                val count = interRight - interLeft
                if (count > 0) {
                    System.arraycopy(
                        photo, srcRow + (interLeft - photoLeft),
                        backdrop, dstRow + interLeft,
                        count,
                    )
                }
            }
        } else if (panelIsOpaquePlate && tone.uniformity >= FLAT_PANEL_UNIFORMITY) {

            mirrorInteriorFromAbove(backdrop, cw, ch, cropLeft, cropTop, panel, radius)
        }

        if (blurRadius >= 0.5f) {

            val interior = backdrop.copyOf()
            val blurred = blurInPlace(interior, cw, ch, blurRadius)
            val edgeFade = maxOf(1f, minOf(3f, band * 0.12f))
            for (y in 0 until ch) {
                for (x in 0 until cw) {
                    val gx = cropLeft + x
                    val gy = cropTop + y
                    val sd = sdRoundedRect(gx + 0.5f, gy + 0.5f, panel, radius)
                    if (sd < 0f) {
                        val t = (-sd / edgeFade).coerceIn(0f, 1f)
                        val mix = t * t * (3f - 2f * t)
                        val orig = backdrop[y * cw + x]
                        val blurredPx = blurred[y * cw + x]
                        backdrop[y * cw + x] = blend(orig, blurredPx, mix)
                    }

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

        val gradRadius = minOf(radius * 1.5f, shortSide * 0.5f)

        var written = 0

        var erasedCorners = 0
        for (y in 0 until ch) {
            val gy = cropTop + y
            val py = gy + 0.5f
            for (x in 0 until cw) {
                val gx = cropLeft + x
                val px = gx + 0.5f
                val i = y * cw + x

                val sd = sdRoundedRect(px, py, panel, radius)
                val coverage = (0.5f - sd).coerceIn(0f, 1f)
                if (coverage <= 0.001f) continue

                val keep = content?.get(i) ?: 0f
                if (keep >= 0.999f) continue

                val nx = (px - (panel.left + panel.width * 0.5f)) / (panel.width * 0.5f)
                val ny = (py - (panel.top + panel.height * 0.5f)) / (panel.height * 0.5f)

                val cx = px - (panel.left + panel.width * 0.5f)
                val cy = py - (panel.top + panel.height * 0.5f)
                val grad = gradSdRoundedRect(cx, cy, panel, gradRadius)

                var cr: Float
                var cg: Float
                var cb: Float

                if (-sd >= band) {

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

                    val dx = -d * normal[0]
                    val dy = -d * normal[1]

                    if (params.chromaticAberration) {
                        val s = sampleSpectrumRegion(
                            backdrop, cw, ch, panel, radius, cropLeft, cropTop, px + dx, py + dy,
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

                val glass = pack(255, cr, cg, cb)
                val dst = gy * width + gx
                pixels[dst] = if (keep > 0.001f) {

                    blend(pixels[dst], glass, coverage * (1f - keep))
                } else if (coverage >= 0.999f) {
                    glass
                } else {
                    blend(pixels[dst], glass, coverage)
                }
                written++
            }
        }

        if (panelIsOpaquePlate && (hasPhoto || tone.uniformity >= FLAT_PANEL_UNIFORMITY)) {
            var cleared = 0

            val clearLeft = maxOf(panel.left.toInt(), cropLeft, 0)
            val clearTop = maxOf(panel.top.toInt(), cropTop, 0)
            val clearRight = minOf(panel.right.toInt(), cropRight, width)
            val clearBottom = minOf(panel.bottom.toInt(), cropBottom, height)
            for (gy in clearTop until clearBottom) {
                val y = gy - cropTop
                val row = gy * width
                val localRow = y * cw
                for (gx in clearLeft until clearRight) {
                    val sd = sdRoundedRect(gx + 0.5f, gy + 0.5f, panel, radius)
                    if (sd <= 0.5f) continue
                    val index = row + gx

                    val keep = content?.get(localRow + gx - cropLeft) ?: 0f
                    if (keep >= 0.999f) continue
                    val scene = backdrop[localRow + gx - cropLeft]
                    pixels[index] = if (keep > 0.001f) blend(scene, pixels[index], keep) else scene
                    cleared++
                }
            }
            erasedCorners = cleared
        }

        return "ok: panel=${panel.width.toInt()}x${panel.height.toInt()} " +
                "r=${radius.toInt()} band=${band.toInt()} px=$written " +
                "corner=$erasedCorners " +
                "content=$preserved flat=$preserveContent " +
                "u=${fmt(tone.uniformity)} tone=${tone.tone.toInt()} adapt=${fmt(adapt)}"
    }

    private fun measurePanelTone(pixels: IntArray, width: Int, panel: PanelRect): PanelTone {
        val imageHeight = pixels.size / width
        val left = panel.left.toInt().coerceAtLeast(0)
        val top = panel.top.toInt().coerceAtLeast(0)
        val right = panel.right.toInt().coerceAtMost(width) - 1
        val bottom = panel.bottom.toInt().coerceAtMost(imageHeight) - 1
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

        var weighted = 0
        var weight = 0
        for (i in maxOf(0, best - 1)..minOf(63, best + 1)) {
            weighted += bins[i] * i
            weight += bins[i]
        }
        val tone = if (weight == 0) 128f else (weighted.toFloat() / weight) * 4f + 2f

        val near = ((tone - CONTENT_LUMA_DELTA).toInt() shr 2).coerceIn(0, 63)
        val far = ((tone + CONTENT_LUMA_DELTA).toInt() shr 2).coerceIn(0, 63)
        var nearCount = 0
        for (i in near..far) nearCount += bins[i]

        return PanelTone(tone, nearCount.toFloat() / total)
    }

    private data class PanelTone(val tone: Float, val uniformity: Float)

    private fun contentSoftness(pixel: Int, tone: Float): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val luma = 0.213f * r + 0.715f * g + 0.072f * b
        val delta = abs(luma - tone)
        val soft = if (delta >= CONTENT_LUMA_DELTA) {
            1f
        } else if (delta <= CONTENT_LUMA_DELTA * 0.5f) {
            0f
        } else {
            val t = ((delta - CONTENT_LUMA_DELTA * 0.5f) / (CONTENT_LUMA_DELTA * 0.5f)).coerceIn(0f, 1f)

            t * t * (3f - 2f * t)
        }
        if (soft >= 1f) return 1f

        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        return if (chroma > CONTENT_CHROMA_MAX) 1f else soft
    }

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

    private fun sampleRegion(
        region: IntArray, cw: Int, ch: Int, cropLeft: Int, cropTop: Int, x: Float, y: Float,
    ): Int {
        val ix = (x.toInt() - cropLeft).coerceIn(0, cw - 1)
        val iy = (y.toInt() - cropTop).coerceIn(0, ch - 1)
        return region[iy * cw + ix]
    }

    private fun mirrorInteriorFromAbove(
        backdrop: IntArray,
        cw: Int,
        ch: Int,
        cropLeft: Int,
        cropTop: Int,
        panel: PanelRect,
        radius: Float,
    ) {
        if (cw <= 0 || ch <= 0) return

        val topInCrop = panel.top.toInt() - cropTop

        if (topInCrop <= 0) return

        val left = (panel.left.toInt() - cropLeft).coerceIn(0, cw)
        val right = (panel.right.toInt() - cropLeft).coerceIn(left, cw)
        val bottom = (panel.bottom.toInt() - cropTop).coerceIn(topInCrop, ch)
        if (right <= left || bottom <= topInCrop) return

        val period = topInCrop * 2
        for (y in topInCrop until bottom) {
            val m = (y - topInCrop) % period
            val sourceY = if (m < topInCrop) topInCrop - 1 - m else m - topInCrop
            val src = sourceY.coerceIn(0, topInCrop - 1) * cw
            val dst = y * cw

            for (x in left until right) {
                backdrop[dst + x] = backdrop[src + x]
            }
        }
    }

    private fun sampleSpectrumRegion(
        region: IntArray, cw: Int, ch: Int, panel: PanelRect, radius: Float,
        cropLeft: Int, cropTop: Int,
        x: Float, y: Float, dx: Float, dy: Float, nx: Float, ny: Float, params: GlassParams,
    ): FloatArray {
        val disp = params.dispersionIntensity * nx * ny
        val ex = dx * disp
        val ey = dy * disp

        var r = 0f; var g = 0f; var b = 0f

        fun tap(ox: Float, oy: Float, block: (Int) -> Unit) {
            val tx = x + ox
            val ty = y + oy
            var fx = tx
            var fy = ty
            if (sdRoundedRect(tx, ty, panel, radius) > 0f) {
                var lo = 0f
                var hi = 1f
                repeat(6) {
                    val mid = (lo + hi) * 0.5f
                    val mx = x + (tx - x) * mid
                    val my = y + (ty - y) * mid
                    if (sdRoundedRect(mx, my, panel, radius) <= 0f) lo = mid else hi = mid
                }
                fx = x + (tx - x) * lo
                fy = y + (ty - y) * lo
            }
            block(sampleRegion(region, cw, ch, cropLeft, cropTop, fx, fy))
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

    private fun blurInPlace(region: IntArray, cw: Int, ch: Int, radius: Float): IntArray {
        var a = region.copyOf()
        var b = IntArray(region.size)
        repeat(3) {
            boxPass(a, b, cw, ch, radius)
            val t = a; a = b; b = t
        }
        return a
    }

    private fun blend(bottom: Int, top: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val t = ((top shr shift) and 0xFF).toFloat()
            val b = ((bottom shr shift) and 0xFF).toFloat()
            return (t * a + b * (1f - a)).toInt().coerceIn(0, 255)
        }
        return pack(255, ch(16), ch(8), ch(0))
    }

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
        val maxBand = maxOf(1f, shortSide * 0.5f)
        val minBand = minOf(MIN_BAND_PX.toFloat(), maxBand)
        val heightFraction = params.refractionHeightFraction.takeIf { it.isFinite() }?.coerceIn(0.005f, 0.45f) ?: GlassParams.Default.refractionHeightFraction
        val amountFraction = params.refractionAmountFraction.takeIf { it.isFinite() }?.coerceIn(0f, 4f) ?: GlassParams.Default.refractionAmountFraction
        val blurFraction = params.blurFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1.5f) ?: GlassParams.Default.blurFraction
        val band = (heightFraction * shortSide).coerceIn(minBand, maxBand)
        val amount = band * amountFraction

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

        val blurRadius = blurFraction * band
        val blurred = if (blurRadius >= 0.5f) boxBlur(source, srcW, srcH, blurRadius) else source

        val pad = requiredPanelPaddingPx(panel, params)
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

        val adapt = if (params.adaptive) {
            adaptiveScale(blurred, srcW, srcH, srcLeft, srcTop, srcPanelW, srcPanelH, panel)
        } else 1f

        val rimWidth = (params.rimWidthFraction * band).coerceIn(0.5f, band)
        val rimBlur = (params.rimBlurFraction * band).coerceIn(0.35f, band)
        val rimStrength = params.rimAlpha * 255f

        val tintAlpha = (params.tintAlpha * adapt).coerceIn(0f, 1f)
        val surfaceAlpha = (params.surfaceAlpha * adapt).coerceIn(0f, 1f)
        val highlightGain = (params.highlightGain / adapt).coerceIn(0.4f, 2.2f)

        for (y in 0 until ch) {
            val gy = cropTop + y
            for (x in 0 until cw) {
                val gx = cropLeft + x
                val i = y * cw + x

                val px = gx + 0.5f
                val py = gy + 0.5f

                val nx = (px - (panel.left + panel.width * 0.5f)) / (panel.width * 0.5f)
                val ny = (py - (panel.top + panel.height * 0.5f)) / (panel.height * 0.5f)

                val sd = sdRoundedRect(px, py, panel, radius)
                if (-sd >= band) {

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

                rendered[i] = pack(255, cr, cg, cb)
            }
        }

        for (y in 0 until ch) {
            val gy = cropTop + y
            for (x in 0 until cw) {
                val gx = cropLeft + x
                val i = gy * width + gx

                if (gx < 0 || gy < 0 || gx >= width || gy >= height) continue
                if (i < 0 || i >= src.size) continue
                val m = mask[i]
                if (m == 0.toByte()) continue

                val original = src[i]
                val layerAlpha = (original ushr 24) / 255f
                val glass = rendered[y * cw + x]

                src[i] = if (m == GLASS) {

                    val alpha = (layerAlpha * 0.85f + 0.30f + (1f - adapt) * 0.26f)
                        .coerceIn(0.26f, 0.94f)
                    withAlpha(glass, (alpha * 255f).toInt())
                } else {

                    if (layerAlpha >= 0.99f) original else srcOver(withAlpha(glass, 255), original)
                }
            }
        }

        return "ok: glass=${glassPixels}px glyph=${glyphPixels}px " +
                "band=${band.toInt()} r=${radius.toInt()} adapt=${fmt(adapt)}"
    }

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

    private fun gaussianBand(outwardDistance: Float, width: Float, sigma: Float): Float {
        val centre = -width * 0.5f
        val d = (outwardDistance - centre) / sigma
        return kotlin.math.exp(-0.5f * d * d)
    }

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

    private fun sampleSpectrum(
        px: IntArray, w: Int, h: Int,
        left: Float, top: Float, panelW: Float, panelH: Float,
        x: Float, y: Float,
        dx: Float, dy: Float,
        nx: Float, ny: Float,
        params: GlassParams,
    ): FloatArray {

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

    private const val GLASS: Byte = 2
    private const val GLYPH: Byte = 1

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
