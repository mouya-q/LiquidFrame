package com.mouya.LiquidFrame

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.mouya.LiquidFrame.glass.PanelScan
import com.mouya.LiquidFrame.glass.GlassParams
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.PanelRect
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.Locale

/**
 * Installs the liquid glass watermark.
 *
 * Xiaomi's watermark pipeline, reconstructed by disassembling `com.android.camera`
 * `1+6.4.000250.2` (the HyperOS 4 port this module targets) and cross-checked against
 * `6.4.000370.0`:
 *
 * ```
 *   LE5/b->h(Lla/a, Z, I)Lla/f;            app side "processWatermark": lifts the I420 frame
 *                                          into a full-size photo Bitmap
 *   watermark/b->b(Application, Bitmap, Dc/b, I) -> Bitmap
 *   watermark/b->c(b, Context, Bitmap, Dc/b, I, String, I) -> Bitmap
 *   watermark/c->c(Context, Bitmap, Dc/b, I, Cc/a, String, Z,
 *                  PorterDuff$Mode, String, o9/O) -> Bitmap
 *   Fe/a->j(Fe/a, Bitmap src, ColorSpace, I wHint, I hHint, String, I) -> Bitmap
 * ```
 *
 * `Fe/a->j` is the composite. It resolves the element tree's size, creates ONE output bitmap,
 * wraps it in `Lpe/o`, and draws the tree into it in painter order. Critically, the photo is
 * blitted into that same bitmap by `Fe/a->b` (via `Lpe/o->g` == `Canvas.drawBitmap`), and the
 * bitmap `Fe/a->j` returns is encoded one-to-one as the final image. So the output bitmap is
 * the full photo with the watermark already composited, and the pixels behind the panel are
 * the scene itself.
 *
 * The background panel is not a solid colour: it is a pre-rendered WebP
 * (`assets/watermarks/<style>/<id>/icon_background_{light,dark}_blur.webp`) drawn as a
 * `BitmapShader`-filled rectangle, with its corner radius baked into the WebP alpha plus
 * `rect_params.rect_radius` in the per-style `config.json`.
 *
 * Two facts decide the hook design:
 *
 *  1. **The rectangle passed to `Lpe/o->h` is element-local, always `(0, 0, w, h)`.** Absolute
 *     placement lives in `Canvas.translate` calls made by the parent group, so the rectangle
 *     must be mapped through the canvas's own matrix — see [absoluteRect].
 *  2. **There is no canvas-level scale factor.** dp values in `config.json` are multiplied by
 *     `min(photoW, photoH) / 1080` once at layout time, after which every element is already
 *     in absolute output pixels. The 1080 constant that appears next to the panel is only the
 *     `BitmapShader`'s local matrix, which fits the WebP into the panel rectangle.
 *
 * The material is therefore applied by post-processing the finished composite, with the panel
 * located from the app's own draw call where possible and from the pixels otherwise.
 */
object WatermarkHooks {

    private const val TAG = "LiquidFrame"

    /** Smallest believable panel, in output pixels. */
    private const val MIN_PANEL_WIDTH = 32

    /** Bounded work: keep at most this many bitmap locations per composite. */
    private const val MAX_PANELS = 8

    /** Working width for the pixel fallback, in pixels. Structure survives this comfortably. */
    private const val SCAN_WORK_WIDTH = 192

    /** Backdrop padding around the panel when reading the crop, as a fraction of the panel. */
    private const val PANEL_CROP_PAD_FRACTION = 0.6f

    /** The destination bitmap `Fe/a->j` is drawing into, and the panels found in it. */
    private class Frame {
        var destination: Bitmap? = null
        val panels = ArrayList<PanelRect>(MAX_PANELS)
    }

    /** Keyed by the `Lpe/o` canvas wrapper, which is created once per composite. */
    private val frames = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, Frame>()
    )

    /** The frame currently being built, so `drawRect` knows where to record its panel. */
    @Volatile
    private var current: Frame? = null

    /** Classes already hooked, so the late discovery pass cannot double-hook a fallback target. */
    private val hookedClasses = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Composite methods already hooked, keyed by `class#method`. */
    private val hookedComposites = java.util.Collections.synchronizedSet(HashSet<String>())

    /** How long the late pass is willing to wait for the background dex scan, in ms. */
    private const val DISCOVERY_WAIT_MS = 45_000L

    fun install(param: XC_LoadPackage.LoadPackageParam) {
        DexKitHelper.setClassLoader(param.classLoader)

        // handleLoadPackage usually runs before the host Application exists, so the scan waits for
        // it in the background rather than requiring a context right here.
        DexKitHelper.initDeferred()

        // The dex scan is asynchronous (it reads the whole camera APK), so it is almost never
        // finished this early. Install the known-good names first so a shot taken immediately
        // still works, then upgrade to discovered names when the scan lands.
        installFallback(param.classLoader)
        startLateDiscovery(param.classLoader)
    }

    /**
     * Re-runs installation once the background scan resolves the obfuscated names, so the module
     * keeps working after a camera update even though the hardcoded fallback names changed.
     */
    private fun startLateDiscovery(loader: ClassLoader) {
        val thread = Thread({
            val found = DexKitHelper.awaitDiscovery(DISCOVERY_WAIT_MS)
            if (!found) {
                log("dex discovery unavailable; staying on fallback hook names")
                return@Thread
            }
            try {
                DexKitHelper.findCanvasWrapper()?.let { cls ->
                    hookCanvasWrapper(cls)
                    hookDrawRect(cls)
                }
                DexKitHelper.findCompositeMethod(loader)?.let { hookComposite(it) }
            } catch (t: Throwable) {
                log("late discovery hooking failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "LiquidFrame-LateHook")
        thread.isDaemon = true
        thread.start()
    }

    /** Hardcoded names known to exist in the targeted camera builds. */
    private fun installFallback(loader: ClassLoader) {
        log("installing fallback hooks (pe.o / Fe.a->j)")
        hookCanvasWrapperFallback(loader)
        hookDrawRectFallback(loader)
        hookCompositeFallback(loader)
    }

    // ---------------------------------------------------------------------------------------
    // DexKit-based Hooks
    // ---------------------------------------------------------------------------------------

    private fun hookCanvasWrapper(cls: Class<*>) {
        if (!hookedClasses.add(cls.name + "#ctor")) return
        val ctor = cls.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size == 1 && Bitmap::class.java.isAssignableFrom(c.parameterTypes[0])
        } ?: return

        XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val bitmap = p.args?.getOrNull(0) as? Bitmap ?: return
                val self = p.thisObject ?: return
                val frame = Frame()
                frame.destination = bitmap
                // Keyed by the wrapper *instance*, not by this hook object: the wrapper is
                // created once per composite, so concurrent composites no longer overwrite each
                // other's state and the drawRect hook can find its own canvas.
                frames[self] = frame
                current = frame
            }
        })
        log("hooked canvas wrapper via DexKit")
    }

    private fun hookDrawRect(cls: Class<*>) {
        if (!hookedClasses.add(cls.name + "#drawRect")) return
        val target = DexKitHelper.findDrawRectMethod(cls) ?: return

        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                // The receiver is the `Lpe/o` wrapper this draw is issued on. Both the frame and
                // the canvas come from it, so a draw belonging to composite B can never be
                // recorded against composite A's panel list.
                val self = p.thisObject ?: return
                val frame = frames[self] ?: current ?: return
                val paint = p.args?.getOrNull(4) as? Paint ?: return
                val shader = paint.shader ?: return
                val x0 = p.args[0] as? Float ?: return
                val y0 = p.args[1] as? Float ?: return
                val x1 = p.args[2] as? Float ?: return
                val y1 = p.args[3] as? Float ?: return
                val w = x1 - x0
                val h = y1 - y0
                if (w < MIN_PANEL_WIDTH || h < 4f) return

                val radiusHint = shaderRadius(shader, w, h)
                val rect = absoluteRect(canvasOf(self), x0, y0, x1, y1)
                if (frame.panels.size < MAX_PANELS) {
                    frame.panels.add(
                        PanelRect(
                            left = rect.left,
                            top = rect.top,
                            width = rect.width(),
                            height = rect.height(),
                            cornerRadiusPx = radiusHint,
                        )
                    )
                }
            }
        })
        log("hooked drawRect via DexKit")
    }

    private fun hookComposite(method: Method) {
        if (!hookedComposites.add(method.declaringClass.name + "#" + method.name)) return
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val out = p.result as? Bitmap ?: return
                val frame = current
                // Only clear the shared slot if it still holds this frame. Another thread may
                // have begun a composite in the meantime; clobbering it here would drop that
                // one's panels.
                if (current === frame) current = null
                if (!GlassConfig.masterEnabled) return
                if (out.isRecycled) return
                if (!out.isMutable) {
                    log("skip: composite bitmap not mutable (${out.width}x${out.height})")
                    return
                }
                try {
                    process(out, frame)
                } catch (t: Throwable) {
                    log("process failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        })
        log("hooked composite method via DexKit")
    }
    // ---------------------------------------------------------------------------------------
    // Fallback Hardcoded Hooks (used until the dex scan resolves the real names)
    // ---------------------------------------------------------------------------------------

    /** Class name of the canvas wrapper in the targeted camera builds. */
    private const val FALLBACK_WRAPPER = "pe.o"

    /** Class name of the composite holder in the targeted camera builds. */
    private const val FALLBACK_COMPOSITE = "Fe.a"

    /** Method name of the composite in the targeted camera builds. */
    private const val FALLBACK_COMPOSITE_METHOD = "j"

    private fun hookCanvasWrapperFallback(classLoader: ClassLoader) {
        val cls = findClass(FALLBACK_WRAPPER, classLoader) ?: return
        hookCanvasWrapper(cls)
    }

    private fun hookDrawRectFallback(classLoader: ClassLoader) {
        val cls = findClass(FALLBACK_WRAPPER, classLoader) ?: return
        hookDrawRect(cls)
    }

    private fun hookCompositeFallback(classLoader: ClassLoader) {
        val cls = findClass(FALLBACK_COMPOSITE, classLoader) ?: run {
            log("class $FALLBACK_COMPOSITE not found")
            return
        }
        val target = cls.declaredMethods.firstOrNull { method ->
            method.name == FALLBACK_COMPOSITE_METHOD &&
                    method.parameterTypes.size == 6 &&
                    Bitmap::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        if (target == null) {
            log(
                "$FALLBACK_COMPOSITE->$FALLBACK_COMPOSITE_METHOD not found; candidates=" +
                        cls.declaredMethods.joinToString { "${it.name}/${it.parameterTypes.size}" }
            )
            return
        }
        hookComposite(target)
        log("hooked $FALLBACK_COMPOSITE->$FALLBACK_COMPOSITE_METHOD")
    }

    private fun findClass(name: String, loader: ClassLoader): Class<*>? =
        try {
            XposedHelpers.findClass(name, loader)
        } catch (_: Throwable) {
            null
        }


    /**
     * Maps an element-local rectangle into destination-bitmap pixels.
     *
     * The rectangle is in the canvas's current user space, so the canvas matrix is what carries
     * it to absolute coordinates. Because the panel may be rotated by its own element, the
     * mapped rectangle is taken as the axis-aligned bounding box.
     */
    private fun absoluteRect(
        canvas: Canvas?, x0: Float, y0: Float, x1: Float, y1: Float,
    ): RectF {
        val local = RectF(x0, y0, x1, y1)
        if (canvas == null) return local
        return try {
            val out = RectF()
            val matrix = Matrix()
            // Canvas.getMatrix is deprecated in favour of getMatrix(Matrix), but the
            // replacement only exists from API 30 and this module supports 26.
            @Suppress("DEPRECATION")
            canvas.getMatrix(matrix)
            matrix.mapRect(out, local)
            if (out.width() <= 0f || out.height() <= 0f) local else out
        } catch (_: Throwable) {
            local
        }
    }

    /**
     * `Lpe/o` is a thin wrapper: its `a` field holds a real `android.graphics.Canvas`. Reading
     * it reflectively avoids having to resolve the obfuscated interface it implements.
     */
    private fun canvasOf(wrapper: Any): Canvas? {
        return try {
            val field = wrapper.javaClass.getDeclaredField("a")
            field.isAccessible = true
            field.get(wrapper) as? Canvas
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * A starting hint for the panel's corner radius. The real value is measured from the
     * panel's own silhouette while rendering, because the radius is baked into the background
     * WebP's alpha and into `rect_params.rect_radius` rather than being a constant in code.
     */
    private fun shaderRadius(shader: Shader, width: Float, height: Float): Float {
        val shortSide = minOf(width, height)
        // The shader's local matrix is min(elementW, elementH) / 1080, the panel WebP's own
        // reference size, so the WebP is fitted to the rectangle. Its baked radius therefore
        // scales with the rectangle.
        return (shortSide * 0.34f).coerceIn(0f, shortSide * 0.5f)
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private fun process(out: Bitmap, frame: Frame?) {
        val w = out.width
        val h = out.height
        if (w < MIN_PANEL_WIDTH || h < 16) {
            log("skip: composite too small (${w}x$h)")
            return
        }

        val reported = frame?.panels?.firstOrNull { it.width >= MIN_PANEL_WIDTH }
        val panel = reported?.takeIf { plausible(it, w, h) }
        if (panel == null && reported != null) {
            log("app rect rejected (${f(reported.left)},${f(reported.top)} " +
                    "${f(reported.width)}x${f(reported.height)} in ${w}x$h); scanning pixels")
        }
        val resolved = panel ?: scanPanel(out, w, h)
        if (resolved == null) {
            log("skip: no watermark panel found in ${w}x$h")
            return
        }

        // Read only the crop the material touches. The panel is a few percent of the frame, so
        // this is the difference between a ~48 MB buffer and a few hundred KB. It also keeps
        // the full-resolution pass off the critical path of a capture.
        val padPx = PANEL_CROP_PAD_FRACTION * maxOf(resolved.width, resolved.height)
        val left = (resolved.left - padPx).toInt().coerceIn(0, w - 1)
        val top = (resolved.top - padPx).toInt().coerceIn(0, h - 1)
        val right = (resolved.right + padPx).toInt().coerceIn(left + 1, w)
        val bottom = (resolved.bottom + padPx).toInt().coerceIn(top + 1, h)
        val cw = right - left
        val ch = bottom - top

        val pixels = IntArray(cw * ch)
        out.getPixels(pixels, 0, cw, left, top, cw, ch)

        // The optics index in crop-local coordinates, so the panel is shifted with the crop.
        val localPanel = PanelRect(
            left = resolved.left - left,
            top = resolved.top - top,
            width = resolved.width,
            height = resolved.height,
            cornerRadiusPx = resolved.cornerRadiusPx,
        )

        val params = resolveParams(pixels, cw, ch, localPanel)
        val result = LiquidGlassOptics.renderPanel(pixels, cw, ch, localPanel, params)
        if (!result.startsWith("skip")) {
            out.setPixels(pixels, 0, cw, left, top, cw, ch)
        }

        log("panel=${f(resolved.left)},${f(resolved.top)} " +
                "${f(resolved.width)}x${f(resolved.height)} " +
                "r=${f(resolved.cornerRadiusPx)} src=${if (panel != null) "app" else "scan"} -> $result")
    }

    /** A reported rectangle is only trusted when it could plausibly be a watermark panel. */
    private fun plausible(rect: PanelRect, w: Int, h: Int): Boolean {
        if (rect.left < -2f || rect.top < -2f) return false
        if (rect.width > w || rect.height > h) return false
        if (rect.right > w + 2f || rect.bottom > h + 2f) return false
        // The panel carries labels, so it cannot be a hairline or a full-frame wash.
        return rect.height >= 6f && rect.height <= h * 0.6f
    }

    /**
     * Picks the material variant for the scene behind the panel.
     *
     * Measured on the crop only, over the panel's own rectangle, because that is the backdrop
     * the material has to cope with — averaging the whole photo would let a bright sky decide
     * how dense a shadow strip at the bottom of the frame is rendered.
     */
    private fun resolveParams(pixels: IntArray, w: Int, h: Int, panel: PanelRect): GlassParams {
        if (!GlassConfig.adaptiveGlass) return GlassParams.Default.copy(adaptive = false)
        val left = panel.left.toInt().coerceIn(0, w - 1)
        val top = panel.top.toInt().coerceIn(0, h - 1)
        val right = panel.right.toInt().coerceIn(left + 1, w)
        val bottom = panel.bottom.toInt().coerceIn(top + 1, h)
        var total = 0.0
        var count = 0
        for (y in top until bottom) {
            val row = y * w
            var x = left
            while (x < right) {
                val p = pixels[row + x]
                total += 0.213 * ((p shr 16) and 0xFF) +
                        0.715 * ((p shr 8) and 0xFF) +
                        0.072 * (p and 0xFF)
                count++
                x += 2
            }
        }
        if (count == 0) return GlassParams.Default
        val mean = total / count / 255.0
        return if (mean < 0.42) GlassParams.DarkScene else GlassParams.BrightScene
    }

    /**
     * The pixel fallback, run on a downscaled copy.
     *
     * The scan needs structure, not colour accuracy, so it runs at roughly a tenth of the
     * frame's width. That turns a 48 MB full-resolution read into a sub-megabyte one, and the
     * returned rectangle is scaled back up to output pixels.
     */
    private fun scanPanel(out: Bitmap, w: Int, h: Int): PanelRect? {
        val step = maxOf(1, w / SCAN_WORK_WIDTH)
        val sw = w / step
        val sh = h / step
        if (sw < 8 || sh < 8) return null
        return try {
            // Bitmap.getPixels cannot stride, so the coarse grid is gathered pixel by pixel.
            // At 192 px wide that is a few thousand reads, which is nothing next to a 12 MP
            // buffer copy, and it needs no large allocation at all.
            val grid = IntArray(sw * sh)
            for (sy in 0 until sh) {
                val y = sy * step
                for (sx in 0 until sw) grid[sy * sw + sx] = out.getPixel(sx * step, y)
            }
            val rect = PanelScan.find(grid, sw, sh) ?: return null

            // Scale back to output pixels, then confirm on the full-resolution buffer. The
            // coarse grid can find a flat bar with hard edges, but it cannot tell a watermark
            // panel from a strip of blown-out sky — only a *labelled* bar is a watermark. This
            // reads the candidate strip alone, so it costs a few percent of the frame rather
            // than another whole-image copy.
            val scaled = PanelRect(
                left = rect.left * step,
                top = rect.top * step,
                width = rect.width * step,
                height = rect.height * step,
                cornerRadiusPx = rect.cornerRadiusPx * step,
            )
            val stripW = scaled.width.toInt()
            val stripH = scaled.height.toInt()
            val sx0 = scaled.left.toInt().coerceIn(0, (w - 1).coerceAtLeast(0))
            val sy0 = scaled.top.toInt().coerceIn(0, (h - 1).coerceAtLeast(0))
            val cw = stripW.coerceAtMost(w - sx0)
            val ch = stripH.coerceAtMost(h - sy0)
            if (cw < 16 || ch < 6) return null

            val strip = IntArray(cw * ch)
            out.getPixels(strip, 0, cw, sx0, sy0, cw, ch)
            val local = PanelRect(
                left = 0f, top = 0f,
                width = cw.toFloat(), height = ch.toFloat(),
                cornerRadiusPx = scaled.cornerRadiusPx,
            )
            if (PanelScan.hasLabelDetail(strip, cw, ch, local)) {
                scaled
            } else {
                log("pixel scan: flat bar at ${f(scaled.left)},${f(scaled.top)} " +
                        "${f(scaled.width)}x${f(scaled.height)} carries no label; " +
                        "declining (most likely plain scene)")
                null
            }
        } catch (t: Throwable) {
            log("pixel scan failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun log(message: String) {
        XposedBridge.log("$TAG: $message")
        LogHelper.log(TAG, message)
    }
}