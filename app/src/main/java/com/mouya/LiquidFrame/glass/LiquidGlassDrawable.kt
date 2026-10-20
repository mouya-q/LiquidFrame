package com.mouya.LiquidFrame.glass

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View

class LiquidGlassDrawable(
    private val radiusPx:           Float,
    private val refractionHeightPx: Float,
    private val refractionAmountPx: Float,
    private val highlightStrength:  Float,
    private val tintAlpha:          Float,
    private val depthEffect:        Float = 0.0f,
    private val highlightFalloff:   Float = 1.6f,
) : android.graphics.drawable.Drawable() {

    private val fillPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = RIM_WIDTH_PX
        maskFilter  = BlurMaskFilter(RIM_BLUR_PX, BlurMaskFilter.Blur.NORMAL)
    }
    private val clipPath = Path()

    private var highlightShader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GlassShaders.highlight()
        else                                                       null

    private var refractionShader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GlassShaders.refraction()
        else                                                       null

    private var registeredHost: View? = null

    fun attachToHost(host: View) {
        registeredHost = host
        BackdropEngine.register(this, host)
    }

    fun detachFromHost() {
        val host = registeredHost ?: return
        BackdropEngine.unregister(this, host)
        registeredHost = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val host = registeredHost ?: return
        if (BackdropEngine.isCapturing(host)) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()

        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w, h, radiusPx, radiusPx, Path.Direction.CW)

        fillPaint.color = Color.argb((tintAlpha * 255f).toInt(), 255, 255, 255)
        canvas.drawPath(clipPath, fillPaint)

        val rs       = refractionShader
        var drewReal = false

        if (host != null && rs != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canvas.isHardwareAccelerated) {
            val sample = runCatching {
                BackdropEngine.acquire(this, host, b.left, b.top, b.right, b.bottom)
            }.getOrNull()
            if (sample != null) {
                try {
                    rs.setInputShader("content", BackdropEngine.asShader(sample))
                    rs.setFloatUniform("uSize", w, h)
                    rs.setFloatUniform("uOffset", -b.left.toFloat(), -b.top.toFloat())
                    rs.setFloatUniform("uCornerRadii", radiusPx, radiusPx, radiusPx, radiusPx)
                    rs.setFloatUniform("uRefractionHeight", refractionHeightPx)
                    rs.setFloatUniform("uRefractionAmount", -refractionAmountPx)
                    rs.setFloatUniform("uDepthEffect", depthEffect)
                    glassPaint.shader = rs
                    canvas.drawPath(clipPath, glassPaint)
                    drewReal = true
                } catch (_: Throwable) {
                    drewReal = false
                }
            }
        }

        if (!drewReal) {
            fillPaint.alpha = FALLBACK_GLASS_ALPHA
            canvas.drawPath(clipPath, fillPaint)
            fillPaint.alpha = 255
        }

        val hs = highlightShader
        if (hs != null && canvas.isHardwareAccelerated) {
            runCatching {
                hs.setFloatUniform("uSize", w, h)
                hs.setFloatUniform("uCornerRadii", radiusPx, radiusPx, radiusPx, radiusPx)
                hs.setFloatUniform("uAngle", DIAGONAL_ANGLE_RAD)
                hs.setFloatUniform("uStrength", highlightStrength)
                hs.setFloatUniform("uFalloff", highlightFalloff)
                hs.setColorUniform("uColor", WHITE)
                glassPaint.shader = hs
                canvas.save()
                canvas.clipPath(clipPath)
                canvas.drawCircle(w / 2f, h / 2f, maxOf(w, h), glassPaint)
                canvas.restore()
            }
        }

        rimPaint.color = Color.argb(RIM_ALPHA, 255, 255, 255)
        canvas.drawPath(clipPath, rimPaint)

        canvas.restore()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = fillPaint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {

        const val DIAGONAL_ANGLE_RAD = -0.7853982f
        const val WHITE              = -0x1

        const val RIM_WIDTH_PX       = 2.5f
        const val RIM_BLUR_PX        = 1.5f
        const val RIM_ALPHA          = 90

        const val FALLBACK_GLASS_ALPHA = 46
    }
}