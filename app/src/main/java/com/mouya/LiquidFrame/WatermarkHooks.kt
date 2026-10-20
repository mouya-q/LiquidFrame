package com.mouya.LiquidFrame

import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.mouya.LiquidFrame.glass.CapsuleRenderer
import com.mouya.LiquidFrame.glass.PanelScan
import com.mouya.LiquidFrame.glass.GlassParams
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.PanelRect
import com.mouya.LiquidFrame.glass.CropRect
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

    /**
     * Largest share of the frame one watermark element may cover, as a fraction of its width.
     *
     * The Leica-style watermarks stack several elements — a logo block, a line of text, a
     * capsule — instead of the single full-width strip Xiaomi draws. Those pieces are narrow,
     * and the old width test (`>= MIN_PANEL_WIDTH` only) happily accepted any of them. Picking
     * whichever came first then meant the module could attach itself to the logo box, or to a
     * scene highlight that happened to be wide enough, and re-render it as glass. That is the
     * "到处乱飘 / 渲染异常" the user reported on Leica: the glass was on a moving target because
     * the chosen element was not the watermark panel.
     *
     * An element must therefore be both a plausible size and, when several candidates exist,
     * the one that best explains the strip. See [pickPanel].
     */
    private const val MAX_PANEL_WIDTH_FRACTION = 0.92f

    /**
     * Minimum share of the frame a *watermark* element spans, as a fraction of the frame width.
     *
     * Deliberately low. A Leica watermark's capsule is roughly a third of the frame; a plain
     * Xiaomi strip is nearly all of it. Anything narrower than this is a logo, a date stamp or
     * a piece of scene, never the plate the glass belongs on.
     */
    private const val MIN_PANEL_WIDTH_FRACTION = 0.12f

    /**
     * Largest share of the frame the *merged* panel may span, as a fraction of the frame
     * height.
     *
     * The union of a watermark group is wide and short; a union that reaches most of the
     * way down the frame is scene content that survived the element filter, and merging
     * it would paint glass over the photograph.
     */
    private const val MAX_PANEL_HEIGHT_FRACTION = 0.45f

    /** Bounded work: keep at most this many bitmap locations per composite. */
    private const val MAX_PANELS = 8

    /** Working width for the pixel fallback, in pixels. Structure survives this comfortably. */
    private const val SCAN_WORK_WIDTH = 192

    /**
     * Maximum wall-clock time [process] may spend on a single composite before bailing out.
     *
     * The hook runs on the camera's capture thread; exceeding the system ANR threshold
     * (5 s on foreground activities) would crash the host. 1200 ms is generous enough for a
     * 4032 × 3024 panel crop with source-bitmap read, yet leaves headroom for the capture
     * pipeline's own work.
     */
    private const val RENDER_TIME_BUDGET_MS = 1200L

    /** Maximum log lines per second from the capture thread, to bound I/O on continuous shooting. */
    private const val LOG_RATE_LIMIT = 15

    /** Backdrop padding around the panel when reading the crop, as a fraction of the panel. */
    private const val PANEL_CROP_PAD_FRACTION = 0.55f

    /**
     * Minimum clear air between the photograph's bottom edge and the plate's top
     * for the plate to count as floating in an appended fill strip. A plate at or
     * above the boundary was drawn on the photograph itself and needs source-photo
     * reconstruction, not strip repaint.
     */
    private const val STRIP_PLATE_GAP_MIN = 12

    /** Per-composite panel observations. No strong reference to the destination bitmap is kept. */
    private class Frame {
        val panels = ArrayList<PanelRect>(MAX_PANELS)
    }

    /** Keyed by the `Lpe/o` canvas wrapper, which is created once per composite. */
    private val frames = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, Frame>()
    )

    /** Secondary lookup by destination Bitmap, so the composite callback can find its frame. */
    private val framesByBitmap = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Bitmap, Frame>()
    )

    /** Classes already hooked, so the late discovery pass cannot double-hook a fallback target. */
    private val hookedClasses = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Composite methods already hooked, keyed by `class#method`. */
    private val hookedComposites = java.util.Collections.synchronizedSet(HashSet<String>())

    /** How long the late pass is willing to wait for the background dex scan, in ms. */
    private const val DISCOVERY_WAIT_MS = 45_000L

    /**
     * Cached logo bitmap loaded from the module's assets, or null when the asset
     * is missing or decoding failed. Volatile: the first capture thread that
     * finds it null performs the load; subsequent threads see the result.
     */
    @Volatile
    private var cachedLogo: Bitmap? = null
    private val logoLock = Any()

    private fun loadLogo(): Bitmap? {
        val existing = cachedLogo
        if (existing != null) return existing
        synchronized(logoLock) {
            val existing2 = cachedLogo
            if (existing2 != null) return existing2
            try {
                // XposedBridge.getModulePath() is a runtime method not present in the
                // compile-only API 82 JAR; use reflection to obtain the module APK path.
                val apkPath: String? = try {
                    val m = XposedBridge::class.java.getMethod("getModulePath")
                    m.invoke(null) as? String
                } catch (e: Throwable) { null }
                if (apkPath == null) return null
                val apk = java.io.File(apkPath)
                if (!apk.isFile) return null
                val bmp: Bitmap? = java.util.zip.ZipFile(apk).use { z ->
                    val entry = z.getEntry("assets/leica_logo.png")
                    if (entry == null) null else {
                        z.getInputStream(entry).use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    }
                }
                cachedLogo = bmp
                return bmp
            } catch (t: Throwable) {
                return null
            }
        }
    }

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
        log("installing fallback hooks (trying 6.7.x then 6.2.x names)")
        // 6.7.x fallback
        hookCanvasWrapperFallback(loader, "pe.o")
        hookDrawRectFallback(loader, "pe.o")
        hookCompositeFallback(loader, "Fe.a", "j")
        // 6.2.x fallback — different obfuscated names, no canvas wrapper
        hookCompositeFallback(loader, "Cd.a", "i")
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
                // Keyed by the wrapper *instance*, not by this hook object: the wrapper is
                // created once per composite, so concurrent composites no longer overwrite each
                // other's state and the drawRect hook can find its own canvas.
                frames[self] = frame
                framesByBitmap[bitmap] = frame
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
                val frame = frames[self] ?: return
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
                val frame = framesByBitmap.remove(out)
                // Refresh before the fast disabled path. Otherwise a camera process that
                // starts disabled can never observe the UI enabling the module: params()
                // refreshes later, but this early return would prevent reaching it.
                GlassConfig.refresh()
                if (!GlassConfig.masterEnabled) return
                if (out.isRecycled) return
                if (!out.isMutable) {
                    log("skip: composite bitmap not mutable (${out.width}x${out.height})")
                    return
                }
                // The source photograph is one of the composite's own arguments: the
                // camera passes the photo in and blits it into [out] before any plate is
                // drawn. Taking it from the arguments is exact and does not depend on the
                // drawRect capture timing at all — the largest bitmap argument that is not
                // the destination is the photo.
                val src = try {
                    p.args?.filterIsInstance<Bitmap>()
                        ?.filter { it !== out && !it.isRecycled && it.width >= 8 && it.height >= 8 }
                        ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                } catch (t: Throwable) {
                    log("src bitmap extraction failed: ${t.javaClass.simpleName}: ${t.message}")
                    null
                }
                try {
                    process(out, frame, src, p.thisObject, p.args)
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

    private fun hookCanvasWrapperFallback(classLoader: ClassLoader, className: String) {
        val cls = findClass(className, classLoader) ?: return
        hookCanvasWrapper(cls)
    }

    private fun hookDrawRectFallback(classLoader: ClassLoader, className: String) {
        val cls = findClass(className, classLoader) ?: return
        hookDrawRect(cls)
    }

    private fun hookCompositeFallback(classLoader: ClassLoader, className: String, methodName: String) {
        val cls = findClass(className, classLoader) ?: run {
            log("class $className not found")
            return
        }
        val target = cls.declaredMethods.firstOrNull { method ->
            method.name == methodName &&
                    Bitmap::class.java.isAssignableFrom(method.returnType) &&
                    method.parameterTypes.any { Bitmap::class.java.isAssignableFrom(it) }
        }
        if (target == null) {
            log(
                "$className->$methodName not found; candidates=" +
                        cls.declaredMethods.joinToString { "${it.name}/${it.parameterTypes.size}" }
            )
            return
        }
        hookComposite(target)
        log("hooked $className->$methodName (${target.parameterTypes.size} params)")
    }

    private fun findClass(name: String, loader: ClassLoader): Class<*>? =
        try {
            XposedHelpers.findClass(name, loader)
        } catch (t: Throwable) {
            log("findClass failed: $name: ${t.javaClass.simpleName}")
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
        } catch (t: Throwable) {
            log("absoluteRect matrix mapping failed: ${t.javaClass.simpleName}")
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
        } catch (t: Throwable) {
            log("canvasOf reflection failed: ${t.javaClass.simpleName}: ${t.message}")
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
        return (shortSide * 0.5f).coerceIn(0f, shortSide * 0.5f)
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private fun process(
        out: Bitmap,
        frame: Frame?,
        srcPhoto: Bitmap?,
        thisObj: Any?,
        args: Array<Any?>?,
    ) {
        val startTime = SystemClock.elapsedRealtime()
        val w = out.width
        val h = out.height
        if (w < MIN_PANEL_WIDTH || h < 16) {
            log("skip: composite too small (${w}x$h)")
            return
        }

        // The app-reported rectangles are ranked rather than taken first-wins, so a multi-element
        // watermark (Leica) resolves to the same plate on every frame instead of whichever
        // element happened to be drawn first.
        val reported = frame?.panels?.takeIf { it.isNotEmpty() }
        val panel = pickPanel(reported.orEmpty(), w, h)
        if (panel == null && reported != null) {
            log("app rects rejected (${reported.size} candidates in ${w}x$h); scanning pixels")
        }
        val resolved = panel ?: scanPanel(out, w, h)
        if (resolved == null) {
            log("skip: no watermark panel found in ${w}x$h")
            return
        }

        // --- Capsule rebuild: erase the stock plate and draw a fresh iOS-style
        // capsule onto the photograph itself.
        //
        // The camera paints its watermark onto a strip it appends below the photo
        // (or over the photo's bottom on some styles), then layers the plate and
        // glyphs on top. Dressing that plate in glass refracts whatever the camera
        // left behind it — white fill, glyph remnants, or a mirrored stand-in —
        // which is exactly the "no real scene under the glass and no text" the user
        // reported. Rebuilding is the honest route: repair the plate area with the
        // original photograph, then render a compact glass capsule carrying fresh
        // text lines at a fixed position inside the photo.
        if (ConfigStore.capsuleEnabled()) {
            try {
                processCapsule(out, w, h, resolved, srcPhoto, System.currentTimeMillis(), thisObj, args)
            } catch (t: Throwable) {
                log("capsule failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            return
        }

        // --- Legacy path: dress the detected stock panel itself in glass. ---

        // Position overlay: move the glass from the camera's white fill area
        // onto the photograph itself. The camera draws the watermark strip below
        // the photo, extending the canvas with a flat white fill. Rendering glass
        // there samples white, not the scene — the root cause of every white-edge
        // and mirror artifact. Instead, detect the photo/fill boundary and shift
        // the panel's render target up into the photo so the backdrop is real.
        val photoBottom = detectPhotoBottom(out, w, h, resolved)
        val overlayPanel = if (photoBottom >= 0 && resolved.bottom > photoBottom) {
            // Panel is (fully or partly) below the photo — shift it up so its
            // bottom aligns with the photo's bottom edge, with a small inset.
            val inset = (resolved.height * 0.15f).coerceAtLeast(2f)
            val newBottom = (photoBottom - inset).toFloat()
            val newTop = newBottom - resolved.height
            PanelRect(
                left = resolved.left,
                top = newTop.coerceAtLeast(0f),
                width = resolved.width,
                height = resolved.height,
                cornerRadiusPx = resolved.cornerRadiusPx,
            )
        } else {
            resolved
        }
        val shifted = overlayPanel !== resolved
        if (shifted) {
            log("overlay: panel ${f(resolved.top)}→${f(overlayPanel.top)} photoBottom=$photoBottom shift=yes")
        } else {
            log("overlay: no shift photoBottom=$photoBottom shift=no")
        }

        // Read only the crop the material touches. The panel is a few percent of the frame, so
        // this is the difference between a ~48 MB buffer and a few hundred KB. It also keeps
        // the full-resolution pass off the critical path of a capture.
        // Match the outer bitmap crop to the configured maximum lens displacement and blur.
        // A fixed fraction of the panel height clips the backdrop at high refraction settings,
        // causing the glass to lose its full-area blur and smear against a hard crop boundary.
        val cropParams = GlassConfig.params()
        val padPx = maxOf(
            24f,
            PANEL_CROP_PAD_FRACTION * minOf(overlayPanel.width, overlayPanel.height),
            LiquidGlassOptics.requiredPanelPaddingPx(overlayPanel, cropParams).toFloat(),
        )
        val left = (overlayPanel.left - padPx).toInt().coerceIn(0, w - 1)
        val top = (overlayPanel.top - padPx).toInt().coerceIn(0, h - 1)
        val right = (overlayPanel.right + padPx).toInt().coerceIn(left + 1, w)
        val bottom = (overlayPanel.bottom + padPx).toInt().coerceIn(top + 1, h)
        val cw = right - left
        val ch = bottom - top

        val pixels = IntArray(cw * ch)
        out.getPixels(pixels, 0, cw, left, top, cw, ch)

        // The optics index in crop-local coordinates, so the panel is shifted with the crop.
        val localPanel = PanelRect(
            left = overlayPanel.left - left,
            top = overlayPanel.top - top,
            width = overlayPanel.width,
            height = overlayPanel.height,
            cornerRadiusPx = overlayPanel.cornerRadiusPx,
        )

        val params = resolveParams(pixels, cw, ch, localPanel)

        // Source the backdrop from the original photograph passed to the composite.
        // The camera blits the photo into [out] first, then draws the watermark plate
        // over it. So [out] already holds the photo everywhere except inside the plate.
        // When the source bitmap is available and dimensionally compatible, reading the
        // panel region directly from it gives the optics the true scene — no mirroring,
        // no white corners, no scale change.
        var photoPixels: IntArray? = null
        var photoLeft = 0
        var photoTop = 0
        var photoW = 0
        var photoH = 0
        if (srcPhoto != null && !srcPhoto.isRecycled && srcPhoto.width >= 8 && srcPhoto.height >= 8) {
            val sx: Int
            val sy: Int
            val sw: Int
            val sh: Int
            if (srcPhoto.width == w && srcPhoto.height == h) {
                // Exact match: same coordinate space as [out].
                sx = left; sy = top; sw = cw; sh = ch
            } else {
                // The source may be a different resolution that the camera scales to fit
                // [out]. Map the crop rectangle proportionally.
                val sxw = (cw.toLong() * srcPhoto.width / w).toInt().coerceAtLeast(1)
                val sxh = (ch.toLong() * srcPhoto.height / h).toInt().coerceAtLeast(1)
                sx = (left.toLong() * srcPhoto.width / w).toInt().coerceIn(0, srcPhoto.width - 1)
                sy = (top.toLong() * srcPhoto.height / h).toInt().coerceIn(0, srcPhoto.height - 1)
                sw = sxw.coerceAtMost(srcPhoto.width - sx)
                sh = sxh.coerceAtMost(srcPhoto.height - sy)
            }
            if (sw > 0 && sh > 0) {
                try {
                    // Read the panel crop region from the original photograph.
                    // For the exact-match case (same resolution as [out]) this is a direct
                    // copy. For a resolution mismatch, read at source resolution and then
                    // nearest-neighbour resample back to the output crop dimensions.
                    val rawW: Int
                    val rawH: Int
                    if (srcPhoto.width == w && srcPhoto.height == h) {
                        rawW = cw; rawH = ch
                    } else {
                        rawW = sw; rawH = sh
                    }
                    val px = IntArray(rawW * rawH)
                    srcPhoto.getPixels(px, 0, rawW, sx, sy, rawW, rawH)
                    if (srcPhoto.width != w || srcPhoto.height != h) {
                        val scaled = IntArray(cw * ch)
                        for (y in 0 until ch) {
                            val srcY = (y.toLong() * sh / ch).toInt().coerceIn(0, sh - 1)
                            for (x in 0 until cw) {
                                val srcX = (x.toLong() * sw / cw).toInt().coerceIn(0, sw - 1)
                                scaled[y * cw + x] = px[srcY * rawW + srcX]
                            }
                        }
                        photoPixels = scaled
                    } else {
                        photoPixels = px
                    }
                    // renderPanel works in crop-local coordinates: the crop starts at (0,0)
                    // in the backdrop buffer and the photo block covers the entire crop.
                    photoLeft = 0
                    photoTop = 0
                    photoW = cw
                    photoH = ch
                } catch (t: Throwable) {
                    log("photo pixel read failed: ${t.javaClass.simpleName}: ${t.message}")
                    // Falls back to the mirror path.
                }
            }
        }

        // Time budget: if the pre-render work (scan + crop + photo read) already
        // consumed most of the allowance, skip the expensive renderPanel pass
        // rather than risk an ANR on the camera's capture thread.
        val elapsed = SystemClock.elapsedRealtime() - startTime
        if (elapsed > RENDER_TIME_BUDGET_MS) {
            log("skip: time budget ${elapsed}ms > ${RENDER_TIME_BUDGET_MS}ms before render")
            return
        }

        val result = if (photoPixels != null && photoW > 0 && photoH > 0) {
            LiquidGlassOptics.renderPanel(
                pixels, cw, ch, localPanel, params,
                panelIsOpaquePlate = !shifted,
                photoBackdrop = photoPixels,
                photoLeft = photoLeft,
                photoTop = photoTop,
                photoW = photoW,
                photoH = photoH,
            )
        } else {
            LiquidGlassOptics.renderPanel(
                pixels, cw, ch, localPanel, params,
                panelIsOpaquePlate = !shifted,
            )
        }
        if (!result.startsWith("skip")) {
            out.setPixels(pixels, 0, cw, left, top, cw, ch)
        }

        log("panel=${f(overlayPanel.left)},${f(overlayPanel.top)} " +
                "${f(overlayPanel.width)}x${f(overlayPanel.height)} " +
                "r=${f(overlayPanel.cornerRadiusPx)} src=${if (panel != null) "app" else "scan"} " +
                "photo=${if (photoPixels != null) "yes" else "no"} shift=${if (shifted) "yes" else "no"} ${elapsed}ms -> $result")
    }

    /**
     * The rebuilt-capsule pipeline.
     *
     * 1. Find where the photograph ends inside [out] (the camera extends the canvas
     *    with flat fill below it to make room for the stock watermark strip).
     * 2. Eradicate the stock marks: an appended fill strip is repainted with its
     *    own factory fill, and a plate drawn over the photograph is rebuilt
     *    from the original photograph. Rows whose repair cannot be proven safe
     *    are left untouched instead of being smeared.
     * 3. Place and render a fresh glass capsule strictly inside the photo. The
     *    capsule floats on a real-scene backdrop — the eradicate above
     *    guarantees the crop is scene — and the optics' two opaque-plate
     *    fallbacks (mirrored interior, content preserve-blend) are switched
     *    off at the call boundary, so the bent backdrop is always true scene.
     */
    // The rebuild needs the composite call's live receiver and arguments to mine
    // capture metadata out of the camera stack (model name, shutter parameters,
    // GPS fix). They travel from hookComposite through process() to here.
    private fun processCapsule(
        out: Bitmap,
        w: Int,
        h: Int,
        plate: PanelRect,
        srcPhoto: Bitmap?,
        now: Long,
        thisObj: Any?,
        args: Array<Any?>?,
    ) {
        val start = SystemClock.elapsedRealtime()
        val shortSide = minOf(w, h)
        var photoBottom = detectPhotoBottom(out, w, h, plate)
        if (photoBottom < 0 && srcPhoto != null && !srcPhoto.isRecycled &&
            srcPhoto.width >= 8 && srcPhoto.height >= 8
        ) {
            // The uniformity scan could not see a photo/fill boundary — the rows
            // above the plate happened to be flat. The composite paints the
            // source bitmap at one uniform scale with the fill appended below,
            // so the source's own height times that scale is the exact boundary
            // without scanning a single pixel.
            val s = w.toFloat() / srcPhoto.width.toFloat()
            val candidate = (srcPhoto.height.toFloat() * s).toInt()
            if (candidate in 8 until h) photoBottom = candidate
        }
        val anchorY = if (photoBottom >= 0) photoBottom.toFloat() else h.toFloat()

        // ------------------------------------------------------------------
        // Eradicate the stock marks. Two layouts exist, told apart by whether
        // the plate floats with clear air below the photograph's bottom:
        //
        //  - strip layout: the band the camera appended below the photo is
        //    repainted whole with its own verified fill colour, so no stock
        //    glyph, glow or plate seam survives anywhere across the width.
        //  - over-photo layout: the plate was painted on the photograph
        //    itself, so its rectangle (plus a glow margin) is rebuilt from
        //    the original photograph via the composite's uniform scale.
        //
        // Repairs that cannot be proven safe are refused rather than smeared:
        // stock marks left standing under fresh glass beat a stretched
        // repaint of real scene content.
        // ------------------------------------------------------------------
        val eraseTopY = if (photoBottom >= 0) {
            (photoBottom - 2).coerceAtLeast(0)
        } else {
            (plate.top.toInt() - 16).coerceAtLeast(0)
        }
        val eraseRowsGlobal = if (photoBottom >= 0) {
            h - eraseTopY
        } else {
            ((plate.bottom + 24f).toInt() - eraseTopY).coerceIn(0, h - eraseTopY)
        }
        val isStripLayout = photoBottom >= 0 && plate.top >= photoBottom + STRIP_PLATE_GAP_MIN
        var eraseMode = "none"
        if (eraseRowsGlobal > 0) {
            if (isStripLayout) {
                val strip = IntArray(w * eraseRowsGlobal)
                out.getPixels(strip, 0, w, 0, eraseTopY, w, eraseRowsGlobal)
                val fill = CapsuleRenderer.fillColorFromStripTop(strip, w)
                val repaired = fill != null &&
                        CapsuleRenderer.fillStrip(strip, w, eraseRowsGlobal, fill)
                if (repaired) {
                    out.setPixels(strip, 0, w, 0, eraseTopY, w, eraseRowsGlobal)
                    eraseMode = "strip-fill"
                }
            } else if (srcPhoto != null) {
                // Rebuild the plate rectangle from the true photograph.
                val pad = 16
                val pl = (plate.left - pad).toInt().coerceIn(0, w - 1)
                val pt = (plate.top - pad).toInt().coerceIn(0, h - 1)
                val pr = (plate.right + pad).toInt().coerceIn(pl + 1, w)
                val pb = (plate.bottom + pad).toInt().coerceIn(pt + 1, h)
                val ppw = pr - pl
                val pph = pb - pt
                if (ppw >= 8 && pph >= 8) {
                    val patch = IntArray(ppw * pph)
                    out.getPixels(patch, 0, ppw, pl, pt, ppw, pph)
                    val ok = CapsuleRenderer.patchCropFromSource(
                        patch, ppw, pph, pl, pt, w, h, srcPhoto,
                    )
                    // patchCropFromSource rewrites only rows the source can
                    // truly serve and leaves the rest untouched in the buffer,
                    // so writing back is safe whether or not it succeeded.
                    out.setPixels(patch, 0, ppw, pl, pt, ppw, pph)
                    if (ok) eraseMode = "plate-patch"
                }
            }
            if (eraseMode == "none") {
                // Last resort, any layout: erase only the plate's own band
                // with the colour sampled just left and right of it. In an
                // over-photo layout those samples are scene and the routine
                // refuses on its own variance gate; in a band layout they are
                // fill, and the stock plate disappears without ever touching
                // the photograph above the boundary.
                val pTop = (plate.top.toInt() - 1).coerceAtLeast(0)
                val pBot = (plate.bottom.toInt() + 24).coerceAtMost(h).coerceAtLeast(pTop + 8)
                if (pBot <= h && pBot - pTop >= 8 && w >= 8) {
                    val sub = IntArray(w * (pBot - pTop))
                    out.getPixels(sub, 0, w, 0, pTop, w, pBot - pTop)
                    val region = CropRect(
                        (plate.left.toInt() - 16).coerceIn(0, w - 1),
                        0,
                        (plate.right.toInt() + 16).coerceIn(1, w),
                        pBot - pTop,
                    )
                    val ok = CapsuleRenderer.eraseFlatRegion(sub, w, pBot - pTop, region)
                    if (ok) {
                        out.setPixels(sub, 0, w, 0, pTop, w, pBot - pTop)
                        eraseMode = "plate-flat"
                    }
                }
            }
        }
        log("eradicate: mode=$eraseMode y=$eraseTopY..${eraseTopY + eraseRowsGlobal} " +
                "photoBottom=$photoBottom strip=${if (isStripLayout) "yes" else "no"}")

        if (SystemClock.elapsedRealtime() - start > RENDER_TIME_BUDGET_MS) {
            log("skip: capsule erase exhausted time budget")
            return
        }

        // ------------------------------------------------------------------
        // Place the capsule inside the photograph.
        // ------------------------------------------------------------------
        val showLogo = ConfigStore.capsuleShowLogo()
        val logoBmp = if (showLogo) loadLogo() else null
        val opts = CapsuleRenderer.Options(
            modelOverride = ConfigStore.capsuleModel(),
            position = ConfigStore.capsulePos(),
            showDateTime = ConfigStore.capsuleShowDatetime(),
            showExif = ConfigStore.capsuleShowExif(),
            showGps = ConfigStore.capsuleShowGps(),
            scale = ConfigStore.capsuleScale(),
            showLogo = showLogo,
            logo = logoBmp,
        )
        val meta = CapsuleRenderer.extractMeta(thisObj, args, now)
        val lines = CapsuleRenderer.buildLines(opts, meta, shortSide)
        if (lines.isEmpty()) {
            log("skip: no capsule text")
            return
        }
        val metrics = CapsuleRenderer.measureCapsule(w, shortSide, opts.scale, lines, opts)
        if (metrics == null) {
            log("skip: capsule layout failed")
            return
        }

        val capsuleW = metrics.capsuleWidth
        val capsuleH = metrics.capsuleHeight
        // The optics pad depends only on the capsule's box (shortSide × params),
        // so it is computable before placement. The inset must clear that pad:
        // an inset smaller than the pad puts the crop's bottom edge past the
        // photograph's end, and the clamp backer then amputates the lens's lower
        // sample window — the refraction band stretches and the bottom edge
        // washes out with fill-band white.
        val protoCapsule = PanelRect(0f, 0f, capsuleW, capsuleH, capsuleH * 0.5f)
        val cropParams = GlassConfig.params()
        val padPx = maxOf(
            24f,
            PANEL_CROP_PAD_FRACTION * minOf(capsuleW, capsuleH),
            LiquidGlassOptics.requiredPanelPaddingPx(protoCapsule, cropParams).toFloat(),
        )
        val inset = (CapsuleRenderer.BOX_INSET_REF * metrics.unitPx)
            .coerceAtLeast(padPx + 6f)
        // Placement cap. In the strip layout the photograph's bottom edge is the
        // ceiling. When the eradicate refused (eraseMode still "none") the stock
        // plate is still standing; the capsule — including its optics pad — must
        // then stay clear of it, because a fresh capsule stamped onto the
        // surviving stock mark reads worse than the mark alone. With the plate
        // eradicated the ceiling moves back to the photo's bottom edge.
        val placementCap = when {
            isStripLayout -> anchorY
            eraseMode == "none" -> (plate.top - inset - 8f).coerceAtLeast(0f)
            else -> anchorY
        }
        val capsuleBottom = (placementCap - inset).coerceAtLeast(capsuleH + 4f)
        val capsuleTop = capsuleBottom - capsuleH
        val capsuleLeft = when (opts.position) {
            "left" -> inset
            "right" -> w - inset - capsuleW
            else -> (w - capsuleW) * 0.5f
        }
        val capsule = PanelRect(
            left = capsuleLeft,
            top = capsuleTop,
            width = capsuleW,
            height = capsuleH,
            cornerRadiusPx = (capsuleH * 0.5f).coerceAtLeast(1f),
        )

        // ------------------------------------------------------------------
        // Render the capsule: crop, optics, text. Crop-local coords again.
        // ------------------------------------------------------------------
        val cropL = (capsule.left - padPx).toInt().coerceIn(0, w - 1)
        val cropT = (capsule.top - padPx).toInt().coerceIn(0, h - 1)
        val cropR = (capsule.right + padPx).toInt().coerceIn(cropL + 1, w)
        val cropB0 = (capsule.bottom + padPx).toInt().coerceIn(cropT + 1, h)
        // The optics must not sample the fill band: flat white inside the blur
        // window is the seed of the washed-out bottom edge. Clamp the crop to
        // the photograph whenever that keeps the capsule fully covered; when it
        // cannot, the capsule itself overlaps the fill and the render is
        // refused — geometry errors must fail loud, not silently bend.
        var cropB = cropB0
        if (photoBottom >= 0) {
            val bound = (photoBottom - 2).coerceAtLeast(cropT + 1)
            if (cropB > bound) {
                if (bound >= capsule.bottom.toInt() + 2) {
                    cropB = bound
                } else {
                    log("skip: capsule crop would reach the fill band")
                    return
                }
            }
        }
        val cw2 = cropR - cropL
        val ch2 = cropB - cropT
        if (cw2 < 8 || ch2 < 8) {
            log("skip: capsule crop empty")
            return
        }
        val pixels = IntArray(cw2 * ch2)
        out.getPixels(pixels, 0, cw2, cropL, cropT, cw2, ch2)

        val localCapsule = PanelRect(
            left = capsule.left - cropL,
            top = capsule.top - cropT,
            width = capsule.width,
            height = capsule.height,
            cornerRadiusPx = capsule.cornerRadiusPx,
        )
        val params = resolveParams(pixels, cw2, ch2, localCapsule)

        // The glass body is transparent where the stock panel was opaque, so it
        // samples-and-bends this crop's own scene: the crop lies fully inside
        // the photograph, so the backdrop is genuinely the scene. The camera's
        // plate is no longer part of the backdrop at all.
        //
        // panelIsOpaquePlate=false tells the optics this capsule was never an
        // opaque stock plate — its two opaque-plate fallbacks (mirrored
        // interior, preserve-content blend) are off at the call boundary, and
        // preserveContent=false keeps even the uniformity-gated branch from
        // firing: the bent backdrop must always be real scene.
        val result = LiquidGlassOptics.renderPanel(
            pixels, cw2, ch2, localCapsule, params.copy(preserveContent = false),
            panelIsOpaquePlate = false,
        )
        if (result.startsWith("skip")) {
            log("capsule render: $result")
            return
        }

        // ------------------------------------------------------------------
        // Text layer. Rasterized into its own transparent layer and blended over
        // the finished glass, so the glyphs never bend with the refraction the
        // way the scene does — they sit on the surface, as stamped ink.
        // ------------------------------------------------------------------
        val textLayer = CapsuleRenderer.drawTextLayer(cw2, ch2, localCapsule, metrics)
        if (textLayer != null) {
            CapsuleRenderer.blendTextOver(pixels, textLayer)
        }

        out.setPixels(pixels, 0, cw2, cropL, cropT, cw2, ch2)

        val elapsed = SystemClock.elapsedRealtime() - start
        log("capsule: ${f(capsule.width)}x${f(capsule.height)} at ${f(capsule.left)},${f(capsule.top)} " +
                "lines=${lines.size} anchor=${f(anchorY)} photoBottom=${photoBottom} " +
                "text=${if (textLayer != null) "yes" else "no"} $elapsed -> $result")
    }

    /**
     * Chooses the region the glass should be applied to.
     *
     * The camera's watermark is not always one rectangle. On the Leica style the plate is
     * composed of several drawn rectangles — the capsule body, plus thin strips that extend
     * above and below it for the outer glow the WebP carries. Taking only the widest one left
     * the glow strips as raw white plates around the glass capsule, which is the leftover
     * backing the user still saw in photos.
     *
     * So the reported rectangles are merged into their **union bounding box** before ranking.
     * A watermark group's elements overlap or vertically stack, so the union is the honest
     * plate; unrelated scene elements would fail [plausible] individually and are filtered
     * first.
     *
     * Elements that fail [plausible], or that are too narrow to be a plate at all, are dropped
     * before merging.
     */
    private fun pickPanel(candidates: List<PanelRect>, w: Int, h: Int): PanelRect? {
        val eligible = candidates.filter { rect ->
            rect.width >= MIN_PANEL_WIDTH &&
                    rect.width >= w * MIN_PANEL_WIDTH_FRACTION &&
                    rect.width <= w * MAX_PANEL_WIDTH_FRACTION &&
                    plausible(rect, w, h)
        }
        if (eligible.isEmpty()) return null
        if (eligible.size == 1) return eligible[0]

        // Union bounding box of every plausible element. The camera's plate fragments all sit
        // in one composition; merging them removes the fragment gaps the glass used to leave.
        val left = eligible.minOf { it.left }
        val top = eligible.minOf { it.top }
        val right = eligible.maxOf { it.right }
        val bottom = eligible.maxOf { it.bottom }
        val merged = PanelRect(
            left = left,
            top = top,
            width = right - left,
            height = bottom - top,
            // The capsule's own radius hint is the largest the group reported; the optics
            // re-measure from the silhouette when this is outside the valid range.
            cornerRadiusPx = eligible.maxOf { it.cornerRadiusPx },
        )

        // A union that swallows most of the frame is not a watermark; the filter above let
        // through a piece of scene and the merge amplified it. Decline the whole group.
        if (merged.width > w * MAX_PANEL_WIDTH_FRACTION ||
            merged.height > h * MAX_PANEL_HEIGHT_FRACTION
        ) {
            log("panel union too large (${f(merged.width)}x${f(merged.height)} in ${w}x$h); declining")
            return null
        }
        return merged
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
     * The user's own settings are the base and are never discarded: `GlassConfig.params()`
     * carries everything the settings screen writes, and this function only decides whether to
     * additionally scale the tint/veil for the local brightness.
     *
     * The earlier revision returned `GlassParams.DarkScene` / `.BrightScene` outright. Those are
     * hardcoded presets, so every slider the user moved — refraction band, blur, interior lift,
     * rim strength — was thrown away the moment a photo was taken. That is exactly the "I
     * changed it and the shot looks identical" symptom, and it was invisible because the
     * settings screen happily reported a successful write.
     *
     * Now: take the user's parameters verbatim, and scale only the three veil-like terms that
     * genuinely depend on scene brightness (tint, surface veil, rim) so white labels stay
     * legible on a bright photo. Every other knob is the user's, unmodified.
     *
     * Measured on the crop only, over the panel's own rectangle, because that is the backdrop
     * the material has to cope with — averaging the whole photo would let a bright sky decide
     * how dense a shadow strip at the bottom of the frame is rendered.
     */
    private fun resolveParams(pixels: IntArray, w: Int, h: Int, panel: PanelRect): GlassParams {
        // The user's settings, exactly as written by the settings screen.
        val user = GlassConfig.params()
        if (!user.adaptive) return user

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
        if (count == 0) return user
        val mean = total / count / 255.0

        // A bright backdrop needs a denser veil for the white labels; a dark one needs less.
        // The ratios match what BrightScene/DarkScene used to hardcode, but they are applied as
        // multipliers on the user's own values rather than as replacements for them.
        val veilGain = if (mean < 0.42) 0.62f else 1.75f
        val rimGain = if (mean < 0.42) 0.80f else 1.12f
        return user.copy(
            tintAlpha = (user.tintAlpha * veilGain).coerceIn(0f, 1f),
            surfaceAlpha = (user.surfaceAlpha * veilGain).coerceIn(0f, 1f),
            rimAlpha = (user.rimAlpha * rimGain).coerceIn(0f, 1.5f),
            // Brightness adaptation is already folded into the multipliers above.
            adaptive = false,
        )
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
            // Bitmap.getPixels cannot stride, so each sampled row is fetched in one bulk
            // read — a single JNI crossing per row instead of one per pixel — and the
            // coarse grid is decimated from a reusable row buffer on the Java side.
            val grid = IntArray(sw * sh)
            val row = IntArray(w)
            for (sy in 0 until sh) {
                val y = sy * step
                out.getPixels(row, 0, w, 0, y, w, 1)
                val base = sy * sw
                for (sx in 0 until sw) grid[base + sx] = row[sx * step]
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

/**
     * Detects the bottom edge of the photograph inside [out].
     *
     * The camera composites the photo at the top of [out] and extends the canvas
     * downward with a near-white fill to accommodate the watermark strip. The
     * boundary between photo content (varied colour, texture, or dark scene)
     * and the fill (flat near-white) is a sharp transition in row luminance.
     *
     * Walks upward from the panel's top edge. The rows immediately above the
     * panel are the camera's white fill; the first row that deviates from flat
     * near-white is the photo's bottom edge.
     *
     * Returns -1 when no white fill is found above the panel — the photo fills
     * the entire canvas and the panel is already on the photograph.
     */
    private fun detectPhotoBottom(out: Bitmap, w: Int, h: Int, panel: PanelRect): Int {
        // One bulk getPixels per scanned row: a single JNI crossing instead of one
        // per sampled column, with the column stride applied on the Java side over
        // a reusable row buffer — the boxed column list is gone too.
        val scanStep = maxOf(1, w / 64)
        val row = IntArray(w)
        val startY = panel.top.toInt().coerceIn(1, h - 1)
        for (y in startY downTo 1) {
            out.getPixels(row, 0, w, 0, y, w, 1)
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            var x = 0
            while (x < w) {
                val p = row[x]
                val luma = (0.213f * ((p shr 16) and 0xFF) +
                        0.715f * ((p shr 8) and 0xFF) +
                        0.072f * (p and 0xFF))
                sum += luma
                sumSq += luma.toDouble() * luma
                n++
                x += scanStep
            }
            if (n == 0) continue
            val mean = sum / n
            val variance = (sumSq / n - mean * mean)
            // Photo content: coloured/varied or dark — anything that is not
            // flat near-white fill.
            if (variance > 4.0 || mean < 240.0) {
                return y
            }
        }
        // No non-white row found above the panel — the entire region above
        // is white fill, which means the photo does not extend here at all.
        // Return -1 so the caller knows there is no photo boundary to use.
        return -1
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.1f", v)

    /** Rate-limited log: at most [LOG_RATE_LIMIT] lines per second, extras counted and summarised. */
    // Multiple camera threads (capture, preview, video) can reach log() concurrently, so the
    // window counters are atomic rather than plain vars.
    private val logWindowStart = java.util.concurrent.atomic.AtomicLong(0L)
    private val logCountInWindow = java.util.concurrent.atomic.AtomicInteger(0)
    private val logDroppedInWindow = java.util.concurrent.atomic.AtomicInteger(0)
    private val logLock = Any()

    private fun log(message: String) {
        val now = SystemClock.elapsedRealtime()
        val dropped: Int
        val emit: Boolean
        synchronized(logLock) {
            if (logWindowStart.get() == 0L) logWindowStart.set(now)
            if (now - logWindowStart.get() >= 1000L) {
                dropped = logDroppedInWindow.getAndSet(0)
                logWindowStart.set(now)
                logCountInWindow.set(0)
            } else {
                dropped = 0
            }
            if (logCountInWindow.get() < LOG_RATE_LIMIT) {
                logCountInWindow.incrementAndGet()
                emit = true
            } else {
                logDroppedInWindow.incrementAndGet()
                emit = false
            }
        }
        if (dropped > 0) {
            XposedBridge.log("$TAG: [$dropped log lines dropped in last window]")
            LogHelper.log(TAG, "[$dropped log lines dropped in last window]")
        }
        if (emit) {
            XposedBridge.log("$TAG: $message")
            LogHelper.log(TAG, message)
        }
    }
}