package com.mouya.LiquidFrame

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.mouya.LiquidFrame.glass.LiquidGlassDrawable
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object WatermarkHooks {

    private const val TAG = "LiquidFrame"

    fun install(param: XC_LoadPackage.LoadPackageParam) {
        hookDrawMethods(param)
    }

    private fun hookDrawMethods(param: XC_LoadPackage.LoadPackageParam) {
        // Hook Canvas.drawBitmap - intercept watermark bitmap drawing
        try {
            XposedHelpers.findAndHookMethod(
                Canvas::class.java,
                "drawBitmap",
                Bitmap::class.java,
                Rect::class.java,
                RectF::class.java,
                Paint::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val bitmap = param.args[0] as? Bitmap ?: return
                        val srcRect = param.args[1] as? Rect
                        val dstRect = param.args[2] as? RectF ?: return

                        if (isWatermarkBitmap(bitmap, dstRect)) {
                            // Replace watermark background with glass while preserving text
                            replaceWatermarkBackground(bitmap)
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: drawBitmap hooked")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: drawBitmap hook failed: ${e.message}")
        }

        // Hook Canvas.drawRoundRect - intercept watermark rounded rect background
        try {
            XposedHelpers.findAndHookMethod(
                Canvas::class.java,
                "drawRoundRect",
                RectF::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Paint::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rect = param.args[0] as? RectF ?: return
                        val rx = param.args[1] as Float

                        if (isWatermarkRect(rect, rx)) {
                            // Skip original draw, will be replaced by glass
                            param.result = null
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: drawRoundRect hooked")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: drawRoundRect hook failed: ${e.message}")
        }

        // Hook Bitmap.createBitmap for watermark-sized bitmaps
        try {
            XposedHelpers.findAndHookMethod(
                Bitmap::class.java,
                "createBitmap",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Bitmap.Config::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val bitmap = param.result as? Bitmap ?: return
                        val width = param.args[0] as Int
                        val height = param.args[1] as Int

                        if (isWatermarkSize(width, height)) {
                            markWatermarkBitmap(bitmap)
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: createBitmap hooked")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: createBitmap hook failed: ${e.message}")
        }
    }

    private fun isWatermarkBitmap(bitmap: Bitmap, dstRect: RectF): Boolean {
        // Watermark is typically at bottom of image, width 35-45%, height 8-12%
        val photoWidth = dstRect.width().toInt() // approximation
        val ratioW = dstRect.width() / photoWidth
        val ratioH = dstRect.height() / photoWidth

        return ratioW in 0.30f..0.50f && ratioH in 0.06f..0.15f &&
                bitmap.config == Bitmap.Config.ARGB_8888 &&
                bitmap.hasAlpha()
    }

    private fun isWatermarkRect(rect: RectF, cornerRadius: Float): Boolean {
        // Watermark background: large width, small height, rounded corners
        val aspectRatio = rect.width() / rect.height()
        return aspectRatio > 3f && aspectRatio < 10f && cornerRadius > 4f
    }

    private fun isWatermarkSize(width: Int, height: Int): Boolean {
        val aspectRatio = width.toFloat() / height.toFloat()
        return aspectRatio > 3f && aspectRatio < 10f && width > 200
    }

    private fun replaceWatermarkBackground(bitmap: Bitmap) {
        // Replace the watermark bitmap background with Liquid Glass
        // Preserves text pixels (non-transparent, non-background colored)
        try {
            val canvas = Canvas(bitmap)
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()

            // Create glass drawable
            val glass = LiquidGlassDrawable(
                radiusPx = height * 0.15f, // ~15% of height as corner radius
                refractionHeightPx = 20f,
                refractionAmountPx = 60f,
                highlightStrength = 0.8f,
                tintAlpha = 0.12f,
            )

            // Save original text pixels
            val originalPixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(originalPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

            // Draw glass background
            glass.setBounds(0, 0, bitmap.width, bitmap.height)
            glass.draw(canvas)

            // Restore text pixels (keep non-background pixels from original)
            restoreTextPixels(bitmap, originalPixels)

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: replaceWatermarkBackground failed: ${e.message}")
        }
    }

    private fun restoreTextPixels(bitmap: Bitmap, originalPixels: IntArray) {
        val width = bitmap.width
        val height = bitmap.height
        val newPixels = IntArray(width * height)
        bitmap.getPixels(newPixels, 0, width, 0, 0, width, height)

        for (i in originalPixels.indices) {
            val orig = originalPixels[i]
            val new = newPixels[i]

            // If original pixel was text (opaque, dark), keep it
            val origAlpha = orig ushr 24
            val origBrightness = ((orig shr 16) and 0xFF) + ((orig shr 8) and 0xFF) + (orig and 0xFF)

            if (origAlpha > 200 && origBrightness < 400) {
                // Text pixel - restore original
                newPixels[i] = orig
            }
        }

        bitmap.setPixels(newPixels, 0, width, 0, 0, width, height)
    }

    private fun markWatermarkBitmap(bitmap: Bitmap) {
        // Set a tag on the bitmap for later identification
        // In production, use a WeakHashMap or similar
    }
}
