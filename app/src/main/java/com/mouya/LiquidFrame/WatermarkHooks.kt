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
        
        // iOS 液态玻璃核心参数
        val glassBaseColor = Color.argb(35, 220, 230, 255) // 冷调半透明白
        val glassHighlight = Color.argb(60, 255, 255, 255)
        val glassShadow = Color.argb(40, 0, 0, 50)
        
        // 1. 底层颜色填充（带冷色调）
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glassBaseColor
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)
        
        // 2. 多层高光（模拟液态玻璃折射）
        val highlight1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.25f, height * 0.25f,
                width * 0.4f,
                intArrayOf(glassHighlight, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), highlight1)
        
        val highlight2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.7f, height * 0.6f,
                width * 0.3f,
                intArrayOf(glassHighlight, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), highlight2)
        
        // 3. 边缘光（圆角描边 + 内发光）
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(100, 255, 255, 255)
            shadowColor = Color.argb(50, 255, 255, 255)
            shadowDx = 0f
            shadowDy = 1f
            shadowRadius = 3f
        }
        val rimPath = Path().apply {
            addRoundRect(
                1.5f, 1.5f, width - 1.5f, height - 1.5f,
                height * 0.12f, height * 0.12f,
                Path.Direction.CW
            )
        }
        canvas.drawPath(rimPath, rimPaint)
        
        // 4. 阴影层（增加立体感）
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = glassShadow
        }
        canvas.drawRect(2f, height - 2f, width - 2f, height + 1f, shadowPaint)
        canvas.drawRect(2f, 2f, width - 2f, 1f, shadowPaint)
        canvas.drawRect(2f, 2f, width - 1f, height - 2f, shadowPaint)
        canvas.drawRect(1f, 2f, width - 2f, height - 2f, shadowPaint)
        
        // 5. 获取像素并混合
        val pixels = IntArray(width * height)
        glassBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (i in pixels.indices) {
            if (backgroundMask[i]) {
                // 液态玻璃效果：背景像素 + 玻璃层混合
                pixels[i] = mixLiquidGlass(pixels[i], glassPixels[i], 0.3f)
            }
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        glassBitmap.recycle()
    }
    
    // 混合液态玻璃效果（简单模拟折射）
    private fun mixLiquidGlass(bg: Int, glass: Int, alpha: Float): Int {
        val bgA = (bg shr 24) and 0xFF
        val bgR = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bgB = bg and 0xFF
        
        val glassA = (glass shr 24) and 0xFF
        val glassR = (glass shr 16) and 0xFF
        val glassG = (glass shr 8) and 0xFF
        val glassB = glass and 0xFF
        
        val finalA = (bgA * (1 - alpha) + glassA * alpha).toInt().coerceIn(0, 255)
        val finalR = (bgR * (1 - alpha) + glassR * alpha).toInt().coerceIn(0, 255)
        val finalG = (bgG * (1 - alpha) + glassG * alpha).toInt().coerceIn(0, 255)
        val finalB = (bgB * (1 - alpha) + glassB * alpha).toInt().coerceIn(0, 255)
        
        return (finalA shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
    }
}