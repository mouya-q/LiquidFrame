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
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Font
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin

// ---- render frame and capsule design constants ----
private const val PHOTO_W = 900
private const val PHOTO_H = 400
private const val UNIT = PHOTO_H / 1080f
private const val CAPSULE_WIDTH_FRACTION = 0.90f
private const val CAPSULE_HEIGHT_REF = 128f
private const val PAD_X_FRACTION = 0.05f
private const val PAD_Y_FRACTION = 0.10f
private const val LINE_GAP_REF = 6f
private const val BOX_INSET_REF = 104f
private const val LOGO_CENTER_X_FRACTION = 0.635f
private const val DIVIDER_X_FRACTION = 0.685f
private const val DIVIDER_HEIGHT_FRACTION = 0.42f
private const val DIVIDER_GAP_REF = 16f
private const val TEXT_COLOR = 0xF2FFFFFF.toInt()

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

/** Draws the same asymmetric text/logo/divider composition used by the capture renderer. */
private fun drawCapsuleTexts(img: BufferedImage, left: Int, top: Int, capsuleW: Int, capsuleH: Int) {
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)

    val padX = capsuleW * PAD_X_FRACTION
    val padY = capsuleH * PAD_Y_FRACTION
    val lineGap = LINE_GAP_REF * UNIT
    val modelFont = Font(Font.SANS_SERIF, Font.BOLD, (30f * UNIT).toInt().coerceAtLeast(9))
    val bodyFont = Font(Font.SANS_SERIF, Font.PLAIN, (24f * UNIT).toInt().coerceAtLeast(7))

    fun drawLabel(text: String, x: Float, baseline: Float, font: Font, alignRight: Boolean = false) {
        g.font = font
        val width = g.fontMetrics.stringWidth(text).toFloat()
        val tx = if (alignRight) x - width else x
        g.color = Color(0, 0, 0, 100)
        g.drawString(text, tx + UNIT * 0.25f, baseline + UNIT * 0.25f)
        g.color = Color(255, 255, 255, 242)
        g.drawString(text, tx, baseline)
    }

    val model = "2X 2312RAACG"
    val time = "2026.10.02 19:30:17"
    val exif = "48mm f/1.78 1/1070s ISO64"
    val gps = "30°15'45\"N 120°8'30\"E"
    val leftH = g.getFontMetrics(modelFont).height + lineGap + g.getFontMetrics(bodyFont).height
    var baseline = top + padY + (capsuleH - 2f * padY - leftH) * 0.5f
    g.font = modelFont
    baseline += g.fontMetrics.ascent
    drawLabel(model, left + padX, baseline, modelFont)
    baseline += g.fontMetrics.descent + lineGap
    g.font = bodyFont
    baseline += g.fontMetrics.ascent
    drawLabel(time, left + padX, baseline, bodyFont)

    // The logo asset is the same transparent white wordmark shipped in the Android module.
    val logoFile = File("app/src/main/assets/leica_wordmark_white.png")
    val logo = if (logoFile.isFile) runCatching { ImageIO.read(logoFile) }.getOrNull() else null
    if (logo != null) {
        val interiorH = capsuleH - 2f * padY
        val logoH = interiorH * 0.42f
        val logoW = logoH * logo.width.toFloat() / logo.height.toFloat()
        val cx = left + capsuleW * LOGO_CENTER_X_FRACTION
        val ly = top + (capsuleH - logoH) * 0.5f
        g.composite = AlphaComposite.SrcOver
        g.drawImage(logo, (cx - logoW * 0.5f).toInt(), ly.toInt(), logoW.toInt(), logoH.toInt(), null)
    } else {
        drawLabel("Leica", left + capsuleW * (LOGO_CENTER_X_FRACTION - 0.035f), top + capsuleH * 0.61f,
            Font(Font.SERIF, Font.ITALIC, (18f * UNIT).toInt().coerceAtLeast(8)))
    }

    val dividerX = left + capsuleW * DIVIDER_X_FRACTION
    val dividerH = capsuleH * DIVIDER_HEIGHT_FRACTION
    g.stroke = BasicStroke((UNIT * 0.8f).coerceAtLeast(0.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g.color = Color(255, 255, 255, 184)
    g.drawLine(dividerX.toInt(), (top + (capsuleH - dividerH) * 0.5f).toInt(),
        dividerX.toInt(), (top + (capsuleH + dividerH) * 0.5f).toInt())

    val rightEdge = left + capsuleW - padX
    val rightX = left + capsuleW * DIVIDER_X_FRACTION + DIVIDER_GAP_REF * UNIT
    val rightAvailable = rightEdge - rightX
    for ((text, row) in listOf(exif to 0, gps to 1)) {
        g.font = bodyFont
        val scale = minOf(1f, rightAvailable / g.fontMetrics.stringWidth(text).coerceAtLeast(1))
        val drawFont = if (scale < 0.995f) bodyFont.deriveFont(bodyFont.size2D * scale) else bodyFont
        val blockH = g.getFontMetrics(drawFont).height * 2f + lineGap
        val firstBaseline = top + (capsuleH - blockH) * 0.5f + g.getFontMetrics(drawFont).ascent
        drawLabel(text, rightEdge, firstBaseline + row * (g.getFontMetrics(drawFont).height + lineGap), drawFont, alignRight = true)
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

    // Fixed geometry, matching the asymmetric capsule in WatermarkHooks.
    val capsuleW = (PHOTO_W * CAPSULE_WIDTH_FRACTION).toInt()
    val capsuleH = (PHOTO_H * CAPSULE_HEIGHT_REF / 1080f).toInt()
    val capsuleLeft = (PHOTO_W * 0.01f).toInt()
    val capsuleTop = PHOTO_H - (BOX_INSET_REF * UNIT).toInt() - capsuleH
    val panel = PanelRect(
        left = capsuleLeft.toFloat(),
        top = capsuleTop.toFloat(),
        width = capsuleW.toFloat(),
        height = capsuleH.toFloat(),
        cornerRadiusPx = capsuleH * 0.5f,
    )

    val summary = StringBuilder()
    summary.appendLine("photo ${PHOTO_W}x$PHOTO_H, capsule ${capsuleW}x${capsuleH} " +
            "at ($capsuleLeft,$capsuleTop) r=H/2, asymmetric text/logo/divider layout")

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
        drawCapsuleTexts(img, capsuleLeft, capsuleTop, capsuleW, capsuleH)
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