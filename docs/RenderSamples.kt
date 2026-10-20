// Copyright 2026, LiquidFrame contributors
// SPDX-License-Identifier: Apache-2.0
//
// Off-device render harness for the material documented in the README.
//
// Builds a synthetic photo, renders the module's own capsule watermark over
// the finished bitmap in capsule mode (the same call WatermarkHooks
// processCapsule() makes -- params.copy(preserveContent = false), panelIsOpaque
// Plate = false), and writes the README's comparison renders. Text is drawn
// with a real JVM font, mirroring CapsuleRenderer's Typeface-based layout.
//
// Runs on a plain JVM with the standalone Kotlin compiler:
//   java -jar kotlin-compiler.jar docs/RenderSamples.kt <glass sources> \
//     -include-runtime -d renders.jar && java -cp renders.jar README_Renders docs
@file:JvmName("README_Renders")

import com.mouya.LiquidFrame.glass.GlassParams
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.PanelRect
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin

// ---- render frame and capsule design constants ----
private const val PHOTO_W = 900
private const val PHOTO_H = 400
private const val UNIT = PHOTO_H / 1080f
private const val PAD_X = (34f * UNIT).toInt().coerceAtLeast(6)
private const val PAD_Y = (22f * UNIT).toInt().coerceAtLeast(4)
private const val LINE_GAP = (15f * UNIT).toInt().coerceAtLeast(3)
private const val BOX_INSET = (42f * UNIT).toInt().coerceAtLeast(6)
private const val TEXT_COLOR = 0xF21F1F26.toInt()

private fun argb(a: Int, r: Int, g: Int, b: Int) =
    (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

/**
 * A backdrop chosen to make the lens legible: fine grid, coloured rules,
 * mid-grey exposure. A flat or near-white scene would make any material look
 * identical.
 */
private fun backdrop(kind: String): IntArray {
    val px = IntArray(PHOTO_W * PHOTO_H)
    for (y in 0 until PHOTO_H) for (x in 0 until PHOTO_W) {
        val c: Int
        if (kind == "grid") {
            // Fine but not extreme: enough structure for the lens to bend,
            // coarse enough that point sampling does not alias into moire.
            val grid = if (x % 26 == 0 || y % 26 == 0) 46 else 0
            val diag = if ((x + y) % 41 < 3) 52 else 0
            val hue = ((x / 26) * 41 + (y / 26) * 67) % 255
            val base = 168 - grid + diag
            c = argb(
                255,
                (base * 0.55f + hue * 0.5f).toInt(),
                (base * 0.75f + 30).toInt(),
                (base * 0.85f + (255 - hue) * 0.4f).toInt(),
            )
        } else {
            // Colour blocks with hard edges, like a wallpaper with tiles.
            val v = (70 + 90 * sin((x * 0.9f + y * 0.4f) / 55f)).toInt().coerceIn(0, 255)
            val w = (70 + 90 * sin((x * 0.3f - y * 1.1f) / 41f)).toInt().coerceIn(0, 255)
            val tile = ((x / 74) * 7 + (y / 74) * 5) % 3 != 0
            c = if (tile) {
                argb(255, (214 - v / 5), (216 - w / 5), 226)
            } else {
                argb(255, v, (w * 0.8f + 25).toInt(), (255 - v / 2).coerceIn(0, 255))
            }
        }
        px[y * PHOTO_W + x] = c
    }
    return px
}

/** Draws a capsule's four text lines with a real font, like CapsuleRenderer. */
private fun drawCapsuleTexts(img: BufferedImage, lines: List<Pair<String, Boolean>>, left: Int, top: Int) {
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val baseSize = (31f * UNIT).toInt().coerceAtLeast(9)
    var y = top + PAD_Y
    for ((text, bold) in lines) {
        val font = if (bold) {
            java.awt.Font("Dialog.bold", java.awt.Font.BOLD, (baseSize * 1.35f).toInt())
        } else {
            java.awt.Font("Dialog.plain", java.awt.Font.PLAIN, baseSize)
        }
        g.font = font
        g.paint = java.awt.Color(0xF21F1F26.toInt(), true)
        g.drawString(text, (left + PAD_X + 6).toFloat(), (y + font.size2D).toFloat())
        y += font.size2D.toInt() + LINE_GAP
    }
    g.dispose()
}

private fun writePng(path: String, px: IntArray, w: Int, h: Int) {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, w, h, px, 0, w)
    File(path).parentFile?.mkdirs()
    ImageIO.write(img, "png", File(path))
}

// ANCHOR-B2

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

    // The capsule is placed the way the module places it (scale 1.0, position
    // centred): horizontally centred, near the photo's bottom with BOX_INSET.
    val lines = listOf(
        "2X 2312RAACG" to true,
        "2026.10.02 19:30:17" to false,
        "9/100s F1.69 ISO50 23mm" to false,
        "GPS OFF" to false,
    )
    val baseSize = (31f * UNIT).toInt().coerceAtLeast(9)
    val boldSize = (baseSize * 1.35f).toInt()
    val textW = 19 * baseSize  // "9/100s F1.69 ISO50 23mm" is the longest line.
    val textH = boldSize + 3 * (baseSize + LINE_GAP)
    val capsuleW = textW + 2 * PAD_X + 6
    val capsuleH = textH + 2 * PAD_Y
    val capsuleLeft = (PHOTO_W - capsuleW) / 2
    val capsuleTop = PHOTO_H - BOX_INSET - capsuleH
    val panel = PanelRect(
        left = capsuleLeft.toFloat(),
        top = capsuleTop.toFloat(),
        width = capsuleW.toFloat(),
        height = capsuleH.toFloat(),
        cornerRadiusPx = capsuleH * 0.5f,
    )

    val summary = StringBuilder()
    summary.appendLine("photo ${PHOTO_W}x$PHOTO_H, capsule ${capsuleW}x${capsuleH} " +
            "at ($capsuleLeft,$capsuleTop) r=H/2, text lines ${lines.size}, " +
            "font ${boldSize}/${baseSize}px")

    for (c in cases()) {
        val photo = backdrop(c.scene)

        // Capsule mode, exactly as processCapsule calls it: the glass bends the
        // scene, then the text layer goes on top of the finished surface.
        val px = photo.copyOf()
        val result = LiquidGlassOptics.renderPanel(
            px, PHOTO_W, PHOTO_H, panel,
            c.params.copy(preserveContent = false),
            panelIsOpaquePlate = false,
        )

        val img = BufferedImage(PHOTO_W, PHOTO_H, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, PHOTO_W, PHOTO_H, px, 0, PHOTO_W)
        drawCapsuleTexts(img, lines, capsuleLeft, capsuleTop)
        val outPx = img.getRGB(0, 0, PHOTO_W, PHOTO_H, IntArray(PHOTO_W * PHOTO_H), 0, PHOTO_W)
        writePng("${outDir.absolutePath}/${c.name}.png", outPx, PHOTO_W, PHOTO_H)

        // Tight crop for the README's close-ups.
        val zoom = 2
        val m = 26
        val zw = (capsuleW + m * 2) * zoom
        val zh = (capsuleH + m * 2) * zoom
        val crop = IntArray(zw * zh)
        for (yy in 0 until zh) {
            for (xx in 0 until zw) {
                val sx = (capsuleLeft - m + xx / zoom).coerceIn(0, PHOTO_W - 1)
                val sy = (capsuleTop - m + yy / zoom).coerceIn(0, PHOTO_H - 1)
                crop[yy * zw + xx] = outPx[sy * PHOTO_W + sx]
            }
        }
        writePng("${outDir.absolutePath}/${c.name}-crop.png", crop, zw, zh)

        summary.appendLine("- ${c.name}: $result")
        println("${c.name}: $result")
    }

    File(outDir, "renders.txt").writeText(summary.toString())
}