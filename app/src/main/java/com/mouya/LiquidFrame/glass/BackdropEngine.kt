package com.mouya.LiquidFrame.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import java.util.WeakHashMap

object BackdropEngine {

    private class RootState {
        var bitmap:          Bitmap? = null
        var canvas:          Canvas? = null
        var scale:           Float   = 0.5f
        var generation:      Long    = 0
        var dirty:           Boolean = true
        var lastCaptureMs:   Long    = 0
        var listenersAttached: Boolean = false
        var capturing:       Boolean = false
        var sampleBitmap:    Bitmap? = null
        val hostedDrawables          = WeakHashMap<android.graphics.drawable.Drawable, View>()
    }

    private val roots = WeakHashMap<View, RootState>()

    fun register(drawable: android.graphics.drawable.Drawable, host: View) {
        val root  = host.rootView ?: return
        val state = roots.getOrPut(root) { RootState() }
        state.hostedDrawables[drawable] = host
        attachListeners(root, state)
        state.dirty = true
    }

    fun unregister(drawable: android.graphics.drawable.Drawable, host: View) {
        val root = host.rootView ?: return
        roots[root]?.hostedDrawables?.remove(drawable)
    }

    fun acquire(
        drawable: android.graphics.drawable.Drawable,
        host:     View,
        left:     Int,
        top:      Int,
        right:    Int,
        bottom:   Int,
    ): RegionSample? {
        val root  = host.rootView ?: return null
        val state = roots[root] ?: return null
        if (state.capturing) return null
        if (state.generation == 0L && !state.dirty) return null

        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0 || w * h > MAX_REGION_PIXELS) return null

        if (state.dirty && state.lastCaptureMs == 0L) {
            capture(root, state)
            if (state.bitmap == null) return null
        }

        val bmp = state.bitmap ?: return null

        var sample = state.sampleBitmap
        if (sample == null || sample.width != w || sample.height != h || sample.isRecycled) {
            runCatching { sample?.recycle() }
            sample = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) {
                return null
            }
            state.sampleBitmap = sample
        }
        return try {
            sample.eraseColor(android.graphics.Color.WHITE)
            val canvas = Canvas(sample)
            val s = state.scale
            canvas.scale(1f / s, 1f / s)
            canvas.translate(-left.toFloat(), -top.toFloat())
            canvas.drawBitmap(bmp, 0f, 0f, null)
            RegionSample(sample, state.generation)
        } catch (_: Throwable) {
            null
        }
    }

    private fun capture(root: View, state: RootState) {
        if (state.capturing) return
        state.capturing = true

        try {
            val rw = root.width
            val rh = root.height
            if (rw <= 0 || rh <= 0) throw IllegalStateException("root not laid out")

            val s     = state.scale
            val bw    = (rw * s).toInt().coerceAtLeast(1)
            val bh    = (rh * s).toInt().coerceAtLeast(1)
            var bmp   = state.bitmap
            if (bmp == null || bmp.width != bw || bmp.height != bh || bmp.isRecycled) {
                bmp?.recycle()
                bmp       = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                state.bitmap = bmp
                state.canvas = Canvas(bmp)
            }
            val canvas = state.canvas!!

            canvas.save()
            canvas.scale(s, s)
            canvas.drawColor(android.graphics.Color.WHITE)
            root.draw(canvas)
            canvas.restore()
            state.generation += 1
            state.dirty       = false
            state.lastCaptureMs = SystemClock.uptimeMillis()
        } catch (_: Throwable) {
            state.dirty = true
        } finally {
            state.capturing = false
        }
    }

    fun markDirtyAndSchedule(root: View) {
        val state = roots[root] ?: return
        val now = SystemClock.uptimeMillis()
        if (!state.dirty && now - state.lastCaptureMs < CAPTURE_INTERVAL_MS) return
        state.dirty = true
        if (now - state.lastCaptureMs >= CAPTURE_INTERVAL_MS) {
            root.post { roots[root]?.takeIf { it.dirty }?.let { capture(root, it) } }
        }
    }

    fun isCapturing(host: View): Boolean =
        host.rootView?.let { roots[it]?.capturing } ?: false

    private fun attachListeners(root: View, state: RootState) {
        if (state.listenersAttached) return
        state.listenersAttached = true

        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val now = SystemClock.uptimeMillis()
                if (state.dirty && now - state.lastCaptureMs >= CAPTURE_INTERVAL_MS && root.width > 0 && root.height > 0) {
                    capture(root, state)
                }
                return true
            }
        })

        root.viewTreeObserver.addOnScrollChangedListener { state.dirty = true }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> state.dirty = true }
    }

    data class RegionSample(val bitmap: Bitmap, val generation: Long)

    fun asShader(sample: RegionSample): BitmapShader =
        BitmapShader(sample.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    const val CAPTURE_INTERVAL_MS  = 40L
    const val MAX_REGION_PIXELS    = 4_000_000
}
