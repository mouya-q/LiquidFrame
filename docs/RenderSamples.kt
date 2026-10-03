// Copyright 2026, LiquidFrame contributors
// SPDX-License-Identifier: Apache-2.0
//
// Off-device render harness for the material documented in the README.
//
// It builds a synthetic photo, an opaque watermark layer shaped like Xiaomi Camera's, composites
// the layer onto the photo exactly as the camera does, runs the real LiquidGlassOptics over the
// finished bitmap, and writes images for inspection. The README's comparison renders come from
// here.
//
// Usage: see docs/README.md or run the command in the repository's docs tooling notes.
@file:JvmName("README_Renders")

import com.mouya.LiquidFrame.glass.GlassParams
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.PanelRect
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin

private const val PHOTO_W = 900
private const val PHOTO_H = 400
private const val WM_W = 660
private const val WM_H = 150
private const val PAD_TOP = 30
private const val PAD_LEFT = 14
private const val PANEL_W = 640
private const val PANEL_H = 60
private const val RADIUS = 20f

private fun argb(a: Int, r: Int, g: Int, b: Int) =
    (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

/**
 * A backdrop chosen to make the lens legible: fine grid, coloured rules, mid-grey exposure.
 * A flat or near-white scene would make any material look identical.
 */
private fun backdrop(kind: String): IntArray {
    val px = IntArray(PHOTO_W * PHOTO_H)
    for (y in 0 until PHOTO_H) {
        for (x in 0 until PHOTO_W) {
            val c = when (kind) {
                "grid" -> {
                    // Fine but not extreme: enough structure for the lens to bend, coarse
                    // enough that point sampling does not alias into moire.
                    val grid = if (x % 26 == 0 || y % 26 == 0) 46 else 0
                    val diag = if ((x + y) % 41 < 3) 52 else 0
                    val hue = ((x / 26) * 41 + (y / 26) * 67) % 255
                    val base = 168 - grid + diag
                    argb(
                        255,
                        (base * 0.55f + hue * 0.5f).toInt(),
                        (base * 0.75f + 30).toInt(),
                        (base * 0.85f + (255 - hue) * 0.4f).toInt(),
                    )
                }
                else -> {
                    // Colour blocks with hard edges, like a wallpaper with tiles.
                    val v = (70 + 90 * sin((x * 0.9f + y * 0.4f) / 55f)).toInt().coerceIn(0, 255)
                    val v2 = (70 + 90 * sin((x * 0.3f - y * 1.1f) / 41f)).toInt().coerceIn(0, 255)
                    val tile = ((x / 74) * 7 + (y / 74) * 5) % 3 != 0
                    if (tile) {
                        argb(255, (214 - v / 5), (216 - v2 / 5), 226)
                    } else {
                        argb(255, v, (v2 * 0.8f + 25).toInt(), (255 - v / 2).coerceIn(0, 255))
                    }
                }
            }
            px[y * PHOTO_W + x] = c
        }
    }
    return px
}

/** Xiaomi's watermark layer: transparent, with an opaque panel and opaque labels on top. */
private fun watermarkLayer(): IntArray {
    val px = IntArray(WM_W * WM_H)
    for (y in 0 until PANEL_H) {
        for (x in 0 until PANEL_W) {
            if (insideRounded(x + 0.5f, y + 0.5f, PANEL_W, PANEL_H, RADIUS)) {
                px[(PAD_TOP + y) * WM_W + PAD_LEFT + x] = argb(255, 240, 242, 246)
            }
        }
    }
    drawLabel(px, "LIQUIDFRAME", PAD_LEFT + 22, PAD_TOP + PANEL_H / 2 - 8)
    val stamp = "2026-10-02 19:30"
    drawLabel(px, stamp, PAD_LEFT + PANEL_W - 22 - stamp.length * 13, PAD_TOP + PANEL_H / 2 - 8)
    return px
}

private val GLYPHS: Map<Char, IntArray> = mapOf(
    '0' to intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110),
    '1' to intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
    '2' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111),
    '3' to intArrayOf(0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110),
    '4' to intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010),
    '5' to intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110),
    '6' to intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110),
    '7' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000),
    '8' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110),
    '9' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100),
    'A' to intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
    'B' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110),
    'C' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110),
    'D' to intArrayOf(0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110),
    'E' to intArrayOf(0b11111, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000, 0b11111),
    'F' to intArrayOf(0b11111, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000, 0b10000),
    'G' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111),
    'H' to intArrayOf(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
    'I' to intArrayOf(0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
    'J' to intArrayOf(0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100),
    'K' to intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001),
    'L' to intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111),
    'M' to intArrayOf(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001),
    'N' to intArrayOf(0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001),
    'O' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
    'P' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000),
    'Q' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101),
    'R' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001),
    'S' to intArrayOf(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110),
    'T' to intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100),
    'U' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
    'V' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100),
    'W' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001),
    'X' to intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001),
    'Y' to intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100),
    'Z' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111),
    '-' to intArrayOf(0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000),
    ':' to intArrayOf(0, 0b00100, 0b00100, 0, 0b00100, 0b00100, 0),
    ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0),
)

private fun drawLabel(px: IntArray, text: String, x0: Int, y0: Int) {
    var x = x0
    for (ch in text.uppercase()) {
        val rows = GLYPHS[ch] ?: continue
        for (ry in 0 until 7) {
            for (rx in 0 until 5) {
                if ((rows[ry] shr (4 - rx)) and 1 == 0) continue
                for (sy in 0 until 2) {
                    for (sx in 0 until 2) {
                        val px1 = x + rx * 2 + sx
                        val py1 = y0 + ry * 2 + sy
                        if (px1 in 0 until WM_W && py1 in 0 until WM_H) {
                            px[py1 * WM_W + px1] = argb(255, 54, 56, 62)
                        }
                    }
                }
            }
        }
        x += 13
    }
}

private fun insideRounded(x: Float, y: Float, w: Int, h: Int, r: Float): Boolean {
    val cx = x - w / 2f
    val cy = y - h / 2f
    val hx = w / 2f - 1f
    val hy = h / 2f - 1f
    val qx = abs(cx) - (hx - r)
    val qy = abs(cy) - (hy - r)
    val outside = kotlin.math.hypot(kotlin.math.max(qx, 0f), kotlin.math.max(qy, 0f)) - r
    val inside = kotlin.math.min(kotlin.math.max(qx, qy), 0f)
    return (outside + inside) <= 0f
}

private fun composite(photo: Int, top: Int): Int {
    val ta = ((top ushr 24) and 0xFF) / 255f
    if (ta <= 0f) return photo
    if (ta >= 1f) return top
    fun ch(shift: Int): Int {
        val t = ((top shr shift) and 0xFF).toFloat()
        val b = ((photo shr shift) and 0xFF).toFloat()
        return (t * ta + b * (1f - ta)).toInt().coerceIn(0, 255)
    }
    return argb(255, ch(16), ch(8), ch(0))
}

private fun writePng(path: String, px: IntArray, w: Int, h: Int) {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, w, h, px, 0, w)
    File(path).parentFile?.mkdirs()
    ImageIO.write(img, "png", File(path))
}

private class Case(val name: String, val scene: String, val params: GlassParams)

private fun cases() = listOf(
    Case("01-watermark-only", "grid", GlassParams.Default.copy(
        refractionHeightFraction = 0f, refractionAmountFraction = 0f,
        chromaticAberration = false, rimAlpha = 0f, innerShadowAlpha = 0f,
        tintAlpha = 0f, surfaceAlpha = 0f, interiorLift = 0f, adaptive = false,
    )),
    Case("02-frosted-only", "grid", GlassParams.Default.copy(
        refractionHeightFraction = 0f, refractionAmountFraction = 0f,
        chromaticAberration = false, adaptive = false,
    )),
    Case("03-liquid-glass", "grid", GlassParams.Default.copy(adaptive = false)),
    Case("04-high-dispersion", "grid", GlassParams.Default.copy(
        adaptive = false, chromaticAberration = true, dispersionIntensity = 2.6f,
        refractionHeightFraction = 0.24f, refractionAmountFraction = 3.6f,
    )),
    Case("05-tiles-liquid-glass", "tiles", GlassParams.Default.copy(adaptive = false)),
    Case("06-tiles-frosted-only", "tiles", GlassParams.Default.copy(
        refractionHeightFraction = 0f, refractionAmountFraction = 0f,
        chromaticAberration = false, adaptive = false,
    )),
)

fun main(args: Array<String>) {
    val outDir = File(if (args.isNotEmpty()) args[0] else "docs")
    outDir.mkdirs()

    // The panel is placed with generous margins on every side. The lens samples outside its own
    // bounds, so a panel near the image edge would clamp those samples and produce edge fringing
    // that says nothing about the material.
    val panelLeft = 130
    val panelTop = PHOTO_H / 2 - PANEL_H / 2 + 20
    val panel = PanelRect(
        left = panelLeft.toFloat(),
        top = panelTop.toFloat(),
        width = PANEL_W.toFloat(),
        height = PANEL_H.toFloat(),
        cornerRadiusPx = RADIUS,
    )

    val summary = StringBuilder()
    summary.appendLine("photo ${PHOTO_W}x$PHOTO_H, watermark layer ${WM_W}x$WM_H, " +
            "panel ${PANEL_W}x$PANEL_H at ($panelLeft,$panelTop) r=$RADIUS")

    for (c in cases()) {
        val photo = backdrop(c.scene)
        val wm = watermarkLayer()

        // Exactly what the camera hands back: photo with the watermark already composited.
        val px = IntArray(photo.size)
        for (y in 0 until PHOTO_H) {
            for (x in 0 until PHOTO_W) {
                val wx = x - panelLeft
                val wy = y - panelTop
                val top = if (wx in 0 until WM_W && wy in 0 until WM_H) wm[wy * WM_W + wx] else 0
                px[y * PHOTO_W + x] = composite(photo[y * PHOTO_W + x], top)
            }
        }

        val result = LiquidGlassOptics.renderPanel(px, PHOTO_W, PHOTO_H, panel, c.params)
        writePng("${outDir.absolutePath}/${c.name}.png", px, PHOTO_W, PHOTO_H)

        // Tight crop on the panel at 2x, for the README's close-ups.
        val zoom = 2
        val pad = 18
        val zw = (PANEL_W + pad * 2) * zoom
        val zh = (PANEL_H + pad * 2) * zoom
        val crop = IntArray(zw * zh)
        for (y in 0 until zh) {
            for (x in 0 until zw) {
                val sx = (panelLeft - pad + x / zoom).coerceIn(0, PHOTO_W - 1)
                val sy = (panelTop - pad + y / zoom).coerceIn(0, PHOTO_H - 1)
                crop[y * zw + x] = px[sy * PHOTO_W + sx]
            }
        }
        writePng("${outDir.absolutePath}/${c.name}-crop.png", crop, zw, zh)

        summary.appendLine("- ${c.name}: $result")
        println("${c.name}: $result")
    }

    File(outDir, "renders.txt").writeText(summary.toString())
}
