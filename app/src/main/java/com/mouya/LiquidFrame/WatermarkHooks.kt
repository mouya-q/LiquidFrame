package com.mouya.LiquidFrame

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object WatermarkHooks {

    private const val TAG = "LiquidFrame"

    fun install(param: XC_LoadPackage.LoadPackageParam) {
        hookWatermarkGeneration(param)
    }

    /**
     * Precision hook: com.xiaomi.cam.watermark.a.F() generates the watermark Bitmap.
     * We post-process the returned Bitmap to replace its background with Liquid Glass
     * while preserving text/metadata pixels.
     */
    private fun hookWatermarkGeneration(param: XC_LoadPackage.LoadPackageParam) {
        try {
            val watermarkClass = XposedHelpers.findClass(
                "com.xiaomi.cam.watermark.a",
                param.classLoader
            )

            XposedHelpers.findAndHookMethod(
                watermarkClass,
                "F",
                watermarkClass,
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val bitmap = param.result as? Bitmap ?: return
                        if (bitmap.isRecycled) return
                        processWatermarkBackground(bitmap)
                    }
                }
            )
            XposedBridge.log("$TAG: com.xiaomi.cam.watermark.a.F() hooked")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: watermark hook failed: ${e.message}")
        }
    }

    /**
     * Detect and replace watermark background with glass effect.
     * Preserves text/metadata pixels by analyzing alpha and brightness.
     */
    private fun processWatermarkBackground(bitmap: Bitmap) {
        try {
            val width = bitmap.width
            val height = bitmap.height

            // Watermark must be wide and short
            val aspectRatio = width.toFloat() / height.toFloat()
            if (aspectRatio < 3f || aspectRatio > 15f || width < 200) return

            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val backgroundMask = BooleanArray(width * height)
            var bgCount = 0

            for (i in pixels.indices) {
                val pixel = pixels[i]
                val alpha = pixel ushr 24
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF

                val maxC = maxOf(red, green, blue)
                val minC = minOf(red, green, blue)
                val saturation = if (maxC == 0) 0 else (maxC - minC)

                if (alpha in 10..200 && maxC > 150 && saturation < 40) {
                    backgroundMask[i] = true
                    bgCount++
                }
            }

            val bgRatio = bgCount.toFloat() / pixels.size
            if (bgRatio < 0.15f || bgRatio > 0.75f) return

            renderGlassBackground(bitmap, backgroundMask, width, height)

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: processWatermarkBackground failed: ${e.message}")
        }
    }

    private fun renderGlassBackground(
        bitmap: Bitmap,
        backgroundMask: BooleanArray,
        width: Int,
        height: Int
    ) {
        val glassBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(glassBitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(50, 255, 255, 255)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.argb(90, 255, 255, 255)
        }
        val rimPath = Path().apply {
            addRoundRect(
                1.25f, 1.25f, width - 1.25f, height - 1.25f,
                height * 0.15f, height * 0.15f,
                Path.Direction.CW
            )
        }
        canvas.drawPath(rimPath, rimPaint)

        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.3f, height * 0.3f,
                width * 0.6f,
                intArrayOf(Color.argb(60, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), highlightPaint)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val glassPixels = IntArray(width * height)
        glassBitmap.getPixels(glassPixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            if (backgroundMask[i]) {
                pixels[i] = glassPixels[i]
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        glassBitmap.recycle()
    }
}