package com.mouya.LiquidFrame

import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.location.Location
import com.mouya.LiquidFrame.dex.DexScan
import com.mouya.LiquidFrame.glass.CapsuleRenderer
import com.mouya.LiquidFrame.glass.PanelScan
import com.mouya.LiquidFrame.glass.GlassParams
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.LogoPreset
import com.mouya.LiquidFrame.glass.PanelRect
import com.mouya.LiquidFrame.glass.CropRect
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
object WatermarkHooks {

    private const val TAG = "LiquidFrame"

    /** Smallest believable panel, in output pixels (legacy pixel-scan path). */
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
     * Preflight budget before allocating/reading the pixel crop. This is not a hard
     * cancellation deadline for the CPU renderer; it is a fail-safe against entering expensive
     * work after an earlier stage has already consumed too much time.
     */
    private const val RENDER_TIME_BUDGET_MS = 1200L

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

    /**
     * The camera builds this typed record immediately before its watermark encoder. The
     * capture receiver and watermark renderer are synchronous on the same capture thread, so
     * a thread-local snapshot associates metadata with the correct frame even when
     * several captures are processed concurrently.
     */
    private data class CaptureState(
        val meta: CapsuleRenderer.CaptureMeta,
        @Volatile var sourcePhoto: Bitmap? = null,
        @Volatile var sourceWidth: Int = 0,
        @Volatile var sourceHeight: Int = 0,
        @Volatile var sourceSnapshot: Bitmap? = null,
        /** Source-space rect represented by sourceSnapshot (full frame or bottom recovery strip). */
        @Volatile var sourceSnapshotRect: Rect? = null,
        /** Exact output of Xiaomi's watermark factory; prevents patching its separate original-image branch. */
        @Volatile var composedPhoto: Bitmap? = null,
    ) {
        val patchedBitmaps: MutableSet<Bitmap> =
            Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())

        /**
         * Remember the input to Xiaomi's stock watermark factory before its output is composed.
         * Keep a bounded immutable snapshot when feasible; very large images use a bounded
         * bottom recovery strip so the stock watermark plate can be removed without copying
         * an entire 50 MP frame.
         */
        @Synchronized
        fun rememberPristineSource(bitmap: Bitmap) {
            if (bitmap.isRecycled || bitmap.width < 64 || bitmap.height < 64) return
            val current = sourcePhoto
            val currentSnapshot = sourceSnapshot
            val hasUsablePreviousSource = currentSnapshot != null && !currentSnapshot.isRecycled ||
                    current != null && !current.isRecycled
            if (hasUsablePreviousSource &&
                sourceWidth.toLong() * sourceHeight >= bitmap.width.toLong() * bitmap.height
            ) return
            sourcePhoto = bitmap
            sourceWidth = bitmap.width
            sourceHeight = bitmap.height
            sourceSnapshot?.let { if (!it.isRecycled) it.recycle() }
            sourceSnapshot = null
            sourceSnapshotRect = null

            // Full-frame preservation is best for normal resolutions. For 24–200 MP shots,
            // retain only the bottom recovery strip (the Xiaomi watermark compositor's plate
            // is bottom-anchored), bounded to 64 MiB. This avoids doubling the camera's peak
            // memory while still restoring the pixels underneath the original plate.
            val fullBytes = bitmap.width.toLong() * bitmap.height * 4L
            val fullFrame = fullBytes <= MAX_SOURCE_SNAPSHOT_BYTES
            val cropHeight = if (fullFrame) bitmap.height else {
                (bitmap.height * SOURCE_RECOVERY_STRIP_FRACTION).toInt().coerceIn(1, bitmap.height)
            }
            val top = bitmap.height - cropHeight
            // Preserve the complete watermark zone even on very wide 100–200 MP frames.
            // Downsample the recovery strip only as much as required to remain within 64 MiB;
            // drawing it back is preferable to leaving a slice of the OEM plate untouched.
            var sample = 1
            while (!fullFrame &&
                (bitmap.width.toLong() / sample) * (cropHeight.toLong() / sample) * 4L > MAX_SOURCE_SNAPSHOT_BYTES
            ) sample++
            sourceSnapshot = try {
                if (fullFrame) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    // Draw the source crop directly into the bounded destination. Creating a
                    // full-resolution crop first would temporarily allocate >64 MiB on 100–200 MP
                    // shots, defeating the memory cap even if that crop were scaled afterwards.
                    val targetWidth = (bitmap.width / sample).coerceAtLeast(1)
                    val targetHeight = (cropHeight / sample).coerceAtLeast(1)
                    Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { target ->
                        val canvas = Canvas(target)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            isDither = true
                        }
                        canvas.drawBitmap(
                            bitmap,
                            Rect(0, top, bitmap.width, bitmap.height),
                            Rect(0, 0, targetWidth, targetHeight),
                            paint,
                        )
                    }
                }
            } catch (_: Throwable) {
                null
            }
            if (sourceSnapshot != null) {
                sourceSnapshotRect = Rect(0, top, bitmap.width, bitmap.height)
                if (!fullFrame) {
                    LogHelper.log("LiquidFrame", "source snapshot: bottom recovery strip ${bitmap.width}x$cropHeight sample=$sample")
                }
            }
        }

        @Synchronized
        fun rememberComposedPhoto(bitmap: Bitmap?) {
            if (bitmap == null || bitmap.isRecycled || bitmap.width < 64 || bitmap.height < 64) return
            composedPhoto = bitmap
        }

        @Synchronized
        fun release() {
            sourceSnapshot?.let { if (!it.isRecycled) it.recycle() }
            sourceSnapshot = null
            sourceSnapshotRect = null
            sourcePhoto = null
            sourceWidth = 0
            sourceHeight = 0
            composedPhoto = null
            patchedBitmaps.clear()
        }
    }

    /** Upper bound for the optional clean-source safety copy (64 MiB). */
    private const val MAX_SOURCE_SNAPSHOT_BYTES = 64L * 1024L * 1024L
    private const val SOURCE_RECOVERY_STRIP_FRACTION = 0.22f
    private val activeCapture = ThreadLocal<CaptureState?>()
    private val captureStack = ThreadLocal.withInitial { ArrayList<CaptureState?>() }
    private val hookedCaptureMethods = java.util.Collections.synchronizedSet(HashSet<String>())

    /** How long the late pass is willing to wait for the background dex scan, in ms. */
    private const val DISCOVERY_WAIT_MS = 45_000L

    /**
     * Cached logo bitmap loaded from the module's assets, or null when the asset
     * is missing or decoding failed. Volatile: the first capture thread that
     * finds it null performs the load; subsequent threads see the result.
     */
    @Volatile
    private var cachedLogo: Bitmap? = null
    @Volatile
    private var cachedLogoPreset: String = ""
    private val logoLock = Any()

    private fun loadLogo(): Bitmap? {
        // Resolve the user's logo preset from config.
        val presetName = ConfigStore.capsuleLogoPreset()
        // If the preset changed since last load, invalidate the cache.
        if (cachedLogoPreset != presetName) {
            synchronized(logoLock) {
                if (cachedLogoPreset != presetName) {
                    cachedLogo?.let { if (!it.isRecycled) it.recycle() }
                    cachedLogo = null
                    cachedLogoPreset = presetName
                }
            }
        }
        val existing = cachedLogo
        if (existing != null) return existing
        synchronized(logoLock) {
            val existing2 = cachedLogo
            if (existing2 != null) return existing2
            try {
                // Resolve the user's logo preset from config.
                val preset = LogoPreset.fromName(presetName)
                if (preset == LogoPreset.None) {
                    log("loadLogo: preset is None, skipping")
                    return null
                }
                // Prefer the installed module package context for both vector resources and
                // assets. Open the APK ZIP only as a fallback when the package context is absent.
                val moduleContext = try {
                    // AndroidAppHelper.currentApplication() is not available in the
                    // compile-only Xposed API jar.  Use ActivityThread.currentApplication()
                    // via reflection — same object, no extra dependency.
                    val atClass = Class.forName("android.app.ActivityThread")
                    val currentApp = atClass.getMethod("currentApplication").invoke(null)
                    (currentApp as? android.content.Context)?.createPackageContext(
                        "com.mouya.LiquidFrame",
                        android.content.Context.CONTEXT_IGNORE_SECURITY,
                    )
                } catch (t: Throwable) {
                    log("loadLogo: module package context unavailable: ${t.javaClass.simpleName}")
                    null
                }
                val apkPath = if (moduleContext == null) {
                    resolveModuleApkPath()?.takeIf { java.io.File(it).isFile }
                } else null
                val zip = try {
                    apkPath?.let { java.util.zip.ZipFile(it) }
                } catch (t: Throwable) {
                    log("loadLogo: module APK cannot be opened: ${t.javaClass.simpleName}")
                    null
                }
                if (moduleContext == null && zip == null) {
                    log("loadLogo: module resources/assets unavailable")
                    return null
                }
                val bmp: Bitmap? = try {
                    fun decodeAsset(name: String): Bitmap? {
                        if (moduleContext != null) {
                            try {
                                return moduleContext.assets.open(name).use { stream ->
                                    android.graphics.BitmapFactory.decodeStream(stream)
                                }
                            } catch (_: Throwable) { /* try APK entry below */ }
                        }
                        val entry = zip?.getEntry("assets/$name") ?: return null
                        return zip.getInputStream(entry).use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    }
                    fun decodeDrawable(name: String): Bitmap? {
                        // APK resources are compiled binary XML; inflate them through the
                        // installed module package Resources, not a plain XmlPullParser.
                        val resources = moduleContext?.resources ?: return null
                        val resourceName = name.substringAfterLast('/').substringBeforeLast('.')
                        val resourceId = resources.getIdentifier(
                            resourceName,
                            "drawable",
                            "com.mouya.LiquidFrame",
                        )
                        if (resourceId == 0) return null
                        val drawable = resources.getDrawable(resourceId, null)
                        val w = drawable.intrinsicWidth.coerceAtLeast(24)
                        val h = drawable.intrinsicHeight.coerceAtLeast(24)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(canvas)
                        return bitmap
                    }
                    fun wordmark(label: String, width: Int = 320): Bitmap {
                        val height = 112
                        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(result)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            textAlign = Paint.Align.CENTER
                            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                            textSize = 54f
                            setShadowLayer(2f, 0f, 1f, 0x44000000)
                        }
                        val baseline = (height - (paint.ascent() + paint.descent())) * 0.5f
                        canvas.drawText(label, width * 0.5f, baseline, paint)
                        return result
                    }
                    // Only use the actual bundled marks for Apple/Leica. Other brands are
                    // explicitly labelled as typographic wordmarks in the selector; never
                    // silently substitute a Leica asset for a different brand.
                    when (preset) {
                        LogoPreset.Leica -> decodeAsset("leica_logo.png")
                        LogoPreset.LeicaWhite -> decodeAsset("leica_wordmark_white.png") ?: decodeAsset("leica_logo.png")
                        LogoPreset.Apple -> decodeDrawable("drawable/logo_apple.xml")
                        LogoPreset.Xiaomi -> wordmark("Xiaomi", 300)
                        LogoPreset.Hasselblad -> wordmark("HASSELBLAD", 360)
                        LogoPreset.Zeiss -> wordmark("ZEISS", 300)
                        LogoPreset.None -> null
                    }
                } finally {
                    try { zip?.close() } catch (_: Throwable) { }
                }
                if (bmp != null) log("loadLogo: decoded ${bmp.width}x${bmp.height} for preset ${preset.name}")
                else log("loadLogo: asset not found for preset ${preset.name}")
                cachedLogo = bmp
                return bmp
            } catch (t: Throwable) {
                log("loadLogo: ${t.javaClass.simpleName}: ${t.message}")
                return null
            }
        }
    }

    /**
     * Resolves the module's own APK path via multiple strategies, because
     * XposedBridge.getModulePath() may return null in some LSPosed configurations.
     *
     * Strategy order:
     *  1. XposedBridge.getModulePath() reflection (works on classic Xposed)
     *  2. Stack-trace class loader resource (works in-process, LSPosed)
     *  3. Thread context class loader resource
     *  4. AndroidAppHelper.currentApplicationInfo().sourceDir (LSPosed injected)
     */
    private fun resolveModuleApkPath(): String? {
        // Strategy 1: XposedBridge reflection.
        try {
            val m = XposedBridge::class.java.getMethod("getModulePath")
            val p = m.invoke(null) as? String
            if (p != null && java.io.File(p).isFile) return p
        } catch (e: Throwable) { /* expected on LSPosed */ }

        // Strategy 2: This class was loaded from the module APK — its class loader
        // can provide the resource URL, from which we extract the file path.
        try {
            val res = javaClass.classLoader?.getResource("assets/leica_logo.png")
            if (res != null) {
                val path = extractApkFromUrl(res.toString())
                if (path != null) return path
            }
        } catch (e: Throwable) { /* try next */ }

        // Strategy 2b: Also try the protection domain code source.
        try {
            val pd = javaClass.protectionDomain?.codeSource
            if (pd != null) {
                val loc = pd.location
                if (loc != null) {
                    val p = loc.path
                    if (p != null && java.io.File(p).isFile) return p
                }
            }
        } catch (e: Throwable) { /* try next */ }

        // Strategy 3: Thread context class loader.
        try {
            val tcl = Thread.currentThread().contextClassLoader
            val res = tcl?.getResource("assets/leica_logo.png")
            if (res != null) {
                val path = extractApkFromUrl(res.toString())
                if (path != null) return path
            }
        } catch (e: Throwable) { /* try next */ }

        // Strategy 4: AndroidAppHelper.currentApplicationInfo().sourceDir.
        // This gives the *host* app's APK path, not the module's, so it is not
        // useful here. Instead, scan /data/adb/modules for our package.
        try {
            val pkg = "com.mouya.LiquidFrame"
            val modDir = java.io.File("/data/adb/modules")
            if (modDir.isDirectory) {
                val propFile = java.io.File(modDir, "liquidframe/module.prop")
                if (!propFile.isFile) {
                    // Try LSPosed module directory layout
                    modDir.listFiles()?.forEach { subdir ->
                        if (subdir.isDirectory) {
                            val prop = java.io.File(subdir, "module.prop")
                            if (prop.isFile) {
                                val text = prop.readText()
                                if (text.contains("com.mouya.LiquidFrame")) {
                                    val apk = java.io.File(subdir, "system/app/LiquidFrame/LiquidFrame.apk")
                                    if (apk.isFile) return apk.path
                                    val apk2 = java.io.File(subdir, "system/framework/LiquidFrame.apk")
                                    if (apk2.isFile) return apk2.path
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) { /* last resort failed */ }

        return null
    }

    /** Extracts the on-disk APK path from a jar:file:...!/entry URL string. */
    private fun extractApkFromUrl(url: String): String? {
        // URL format: jar:file:/data/.../module.apk!/assets/leica_logo.png
        // or        : file:/data/.../module.apk
        val prefix = "file:"
        val idx = url.indexOf(prefix)
        if (idx < 0) return null
        val rest = url.substring(idx + prefix.length)
        val bang = rest.indexOf("!/")
        val path = if (bang >= 0) rest.substring(0, bang) else rest
        // Strip leading slash on absolute paths — File handles both, but keep clean.
        return try { java.io.File(path).absolutePath } catch (t: Throwable) { null }
    }

    fun install(param: XC_LoadPackage.LoadPackageParam) {
        log("install: begin, packageName=${param.packageName}")
        DexKitHelper.setClassLoader(param.classLoader)
        DexKitHelper.initDeferred()
        installCapturePipeline(param.classLoader)
        // Activate late discovery so the DexScan fallback can resolve obfuscated names
        // if the typed hooks below do not match (e.g. on newer camera versions).
        startLateDiscovery(param.classLoader)
        log("install: complete")
    }

    private fun installCapturePipeline(loader: ClassLoader) {
        installTypedPipelineHooks(loader)
    }

    /**
     * Installs the capture-metadata hook.
     *
     * Two routes, tried in order, and the first that matches wins — a build never gets both,
     * so there is no double bookkeeping of the ThreadLocal:
     *
     *  1. **structural** — the record class and its consumer were found by scanning the APK's
     *     DEX files ([DexScan.recordPlan]). This is the route that works on 6.7, where the
     *     names moved to `Ki/a` + `W8/d.f`.
     *  2. **verified 6.4 literal** — `E5.b.h(la.a, Z, I) -> la.f`. Kept verbatim so an
     *     installation that already works on 6.4 is not re-routed through newer machinery.
     */
    private fun hookCaptureContext(loader: ClassLoader) {
        if (hookCaptureContextStructural(loader)) return
        hookCaptureContext64(loader)
    }

    /**
     * Route 1: the names came from the DEX scan, so all this needs to do is resolve them and
     * verify the shape again through reflection before hooking.
     */
    private fun hookCaptureContextStructural(loader: ClassLoader): Boolean {
        val record = DexKitHelper.findCaptureRecord() ?: run {
            log("capture context: no structural record discovered (scan pending or absent)")
            return false
        }
        val plan = DexKitHelper.captureRecordPlan()
        val target = DexKitHelper.findCaptureReceiver(loader) ?: run {
            log("capture context: record ${record.name} found but its consumer method did not resolve")
            return false
        }
        val key = target.declaringClass.name + "#" + target.name + "(structural)"
        if (!hookedCaptureMethods.add(key)) return true
        installCaptureHook(
            target,
            plan,
            "structural ${record.simpleName}",
            DexKitHelper.captureReceiverRecordIndex(),
        )
        return true
    }

    /** Route 2: the literal 6.4 signature, unchanged. */
    private fun hookCaptureContext64(loader: ClassLoader) {
        val owner = findClass("E5.b", loader) ?: run {
            log("capture context: E5.b absent (expected on 6.7+; structural route is the fallback)")
            return
        }
        val target = owner.declaredMethods.firstOrNull { m ->
            m.name == "h" && m.parameterTypes.size == 3 &&
                    m.parameterTypes[0].name == "la.a" &&
                    m.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[2] == Int::class.javaPrimitiveType &&
                    m.returnType.name == "la.f"
        } ?: run {
            log("capture context: E5.b.h(la.a;ZI) missing. 'h' overloads on E5.b: " +
                    owner.declaredMethods.filter { it.name == "h" }
                        .joinToString(", ") { "${it.name}(${it.parameterTypes.map { t -> t.simpleName }})" })
            return
        }
        val key = target.declaringClass.name + "#" + target.name + "(la.a;ZI)"
        if (!hookedCaptureMethods.add(key)) return
        installCaptureHook(target, null, "literal E5.b.h")
    }

    /**
     * Shared body: snapshot the record into a ThreadLocal on entry and restore the previous
     * value on exit.
     *
     * The stack discipline matters because the camera can nest a capture inside another
     * capture's watermark pass; a single-value ThreadLocal would restore the wrong parent.
     */
    private fun installCaptureHook(
        target: Method,
        plan: DexScan.RecordPlan?,
        label: String,
        recordParamIndex: Int = 0,
    ) {
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val context = p.args?.getOrNull(recordParamIndex) ?: return
                val stack = captureStack.get()
                stack.add(activeCapture.get())
                activeCapture.set(
                    CaptureState(
                        meta = readCaptureMeta(context, plan),
                        sourcePhoto = readCaptureSourceBitmap(context, plan),
                    ),
                )
            }

            override fun afterHookedMethod(p: MethodHookParam) {
                val stack = captureStack.get()
                val previous = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else null
                activeCapture.get()?.takeIf { it !== previous }?.release()
                activeCapture.set(previous)
                if (stack.isEmpty()) captureStack.remove()
            }
        })
        log("hooked capture metadata [$label]: ${target.declaringClass.name}#${target.name} " +
                "params=${target.parameterTypes.size} recordArg=$recordParamIndex " +
                "plan=${if (plan != null) "indexed" else "names"}")
    }

    /**
     * Reads the capture metadata off a record instance, addressing the fields **by index**
     * rather than by letter.
     *
     * Why not by name: 6.4 stored the capture time in `j` and the Location in `k`, while 6.7
     * stores them in `l` and `m`. The obfuscator renames declared fields per build but keeps
     * their order and types, so the only stable access path is "the J after the S,F,J,I run"
     * and "the Location after it". [plan] carries those indices, resolved by [DexScan.recordPlan].
     *
     * Every value is range-checked; an implausible number becomes `null` instead of printing a
     * confident-looking wrong EXIF line.
     */
    private fun readCaptureMeta(context: Any, plan: DexScan.RecordPlan?): CapsuleRenderer.CaptureMeta {
        // Fall back to the historical letters when the structural plan is unavailable, so an
        // already-working 6.4 install keeps behaving exactly as before.
        fun byIndex(idx: Int?): Any? {
            if (idx == null) return null
            return try {
                val ordered = context.javaClass.declaredFields.filter {
                    !java.lang.reflect.Modifier.isStatic(it.modifiers)
                }
                ordered.getOrNull(idx)?.let { f ->
                    f.isAccessible = true
                    f.get(context)
                }
            } catch (_: Throwable) { null }
        }

        fun byName(name: String): Any? = try {
            val field = context.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.get(context)
        } catch (_: Throwable) { null }

        fun raw(idx: Int?, fallback: String): Any? =
            if (plan != null) byIndex(idx) else byName(fallback)

        // Ki/a.f is the Xiaomi camera's 35mm-equivalent focal length; Ki/b's
        // corresponding diagnostic field is explicitly named mFocalLength35mm.
        val focal = (raw(plan?.focal, "f") as? Number)?.toDouble()
            ?.takeIf { it in 1.0..2000.0 }
        val aperture = (raw(plan?.aperture, "g") as? Number)?.toDouble()
            ?.takeIf { it in 0.8..64.0 }
        val exposureNs = (raw(plan?.exposure, "h") as? Number)?.toLong()
            ?.takeIf { it in 1L..600_000_000_000L }
        val exposure = exposureNs?.let { it / 1_000_000_000.0 }
        val iso = (raw(plan?.iso, "i") as? Number)?.toInt()
            ?.takeIf { it in 20..204_800 }
        val rawMillis = (raw(plan?.time, "j") as? Number)?.toLong() ?: 0L
        val captureMillis = rawMillis.takeIf {
            it in 946_684_800_000L..4_102_444_800_000L
        } ?: 0L
        val location = (raw(plan?.location, "k") as? Location)?.let { loc ->
            if (loc.latitude.isFinite() && loc.longitude.isFinite() &&
                loc.latitude in -90.0..90.0 && loc.longitude in -180.0..180.0
            ) Location(loc) else null
        }
        val exif = if (focal != null || aperture != null || exposure != null || iso != null) {
            CapsuleRenderer.ExifBits(focal, aperture, exposure, iso)
        } else null
        log("capture metadata (${if (plan != null) "plan" else "names"}): " +
                "time=${captureMillis > 0} focal=${focal != null} aperture=${aperture != null} " +
                "exposure=${exposure != null} iso=${iso != null} gps=${location != null}")
        return CapsuleRenderer.CaptureMeta(captureMillis, location, exif)
    }

    /** Reads the camera's original bitmap slot when this build exposes one on its shot record. */
    private fun readCaptureSourceBitmap(
        context: Any,
        plan: DexScan.RecordPlan?,
    ): Bitmap? {
        return try {
            val fields = context.javaClass.declaredFields.filter {
                !Modifier.isStatic(it.modifiers)
            }
            val index = plan?.sourceBitmap ?: fields.indexOfFirst {
                Bitmap::class.java.isAssignableFrom(it.type)
            }.takeIf { it >= 0 }
            val field = index?.let { fields.getOrNull(it) } ?: return null
            if (!Bitmap::class.java.isAssignableFrom(field.type)) return null
            field.isAccessible = true
            (field.get(context) as? Bitmap)?.takeUnless { it.isRecycled }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Hook the actual watermark bitmap factory, com.xiaomi.cam.watermark.b.c(...): Bitmap.
     *
     * The class name `com.xiaomi.cam.watermark.b` is stable across MIUI/HyperOS camera versions
     * because it lives in a non-obfuscated package. However, the method signature of `c()` has
     * changed between versions:
     *
     * - **6.4.x**: `static c(b, Context, Bitmap, Dc.b, int, String, int) -> Bitmap` — 7 params, static
     * - **6.7.x**: `c(Context, Bitmap, As/b, int, zs/a, String, String, Rg/Z, int) -> Bitmap` — 9 params, instance
     *
     * Instead of hardcoding an exact signature, this hook uses **structural feature matching**:
     * it looks for a method named `c` that returns `Bitmap` and has a `Bitmap` parameter. The
     * index of the Bitmap parameter is discovered at match time, so the hook adapts to any
     * parameter ordering.
     *
     * This approach is forward-compatible: if a future camera version changes the param count
     * or types again (but keeps the method name and Bitmap return), the hook still matches.
     */
    private fun hookWatermarkBitmapFactory(loader: ClassLoader) {
        val owner = findClass("com.xiaomi.cam.watermark.b", loader) ?: run {
            log("watermark factory: com.xiaomi.cam.watermark.b absent — scanning the package for a factory")
            return hookWatermarkFactoryInPackage(loader)
        }

        val allMethods = owner.declaredMethods
        log("watermark factory: ${allMethods.size} declared methods on ${owner.name}")

        val target = selectFactoryMethod(allMethods) ?: run {
            log("watermark factory: no Bitmap-returning method on ${owner.name}; " +
                    "candidates=" + allMethods.filter { it.returnType == Bitmap::class.java }
                        .joinToString { "${it.name}(${it.parameterTypes.size})" })
            return hookWatermarkFactoryInPackage(loader)
        }
        installFactoryHook(target)
    }

    /**
     * Picks the watermark factory out of a class's methods.
     *
     * Accepts every build shape that has been observed, then narrows by preference:
     *
     *  6.4: `static b.c(b, Context, Bitmap, Dc.b, I, String, I) -> Bitmap`   (7 params)
     *  6.7: `b.c(Context, Bitmap, As/b, I, zs/a, String, String, Rg/Z, I) -> Bitmap`  (9 params)
     *
     * Both take a Context and a Bitmap and return a Bitmap, so those three traits — not the
     * arity, not the static flag, not the config type — are the contract. A named `c` wins when
     * several methods qualify, because that is the observed name on both builds.
     */
    private fun selectFactoryMethod(methods: Array<Method>): Method? {
        val qualifying = methods.filter { m ->
            m.returnType == Bitmap::class.java &&
                    m.parameterTypes.any { it == Bitmap::class.java } &&
                    m.parameterTypes.any { it == android.content.Context::class.java }
        }
        if (qualifying.isEmpty()) return null
        return qualifying.firstOrNull { it.name == "c" } ?: qualifying.first()
    }

    /**
     * Fallback when the package-private `b` class is missing or unrecognisable in a new build:
     * scan every `com.xiaomi.cam.watermark.*` class the DEX reader knows about for a factory.
     *
     * This is what makes an unseen camera version diagnosable instead of silently inert — the
     * log names the class and method that were chosen, so a bug report contains the hook target.
     */
    private fun hookWatermarkFactoryInPackage(loader: ClassLoader): Unit {
        val packages = listOf(
            "com.xiaomi.cam.watermark.b",
            "com.xiaomi.cam.watermark.a",
        )
        for (name in packages) {
            val cls = findClass(name, loader) ?: continue
            val target = selectFactoryMethod(cls.declaredMethods) ?: continue
            log("watermark factory: falling back to ${cls.name}#${target.name}" +
                    " (${target.parameterTypes.size} params)")
            installFactoryHook(target)
            return
        }
        log("watermark factory: FAILED — no factory resolved in com.xiaomi.cam.watermark.*")
    }

    /** Installs the replacement hook on a resolved factory method. */
    private fun installFactoryHook(target: Method) {
        val bitmapParamIndex = target.parameterTypes.indexOfFirst { it == Bitmap::class.java }
        if (bitmapParamIndex < 0) {
            log("watermark factory: ${target.name} has no Bitmap parameter; cannot proceed")
            return
        }
        val isStatic = Modifier.isStatic(target.modifiers)
        val paramTypes = target.parameterTypes.map { it.simpleName }
        val key = "${target.declaringClass.name}#${target.name}" +
                "(bitmapIdx=$bitmapParamIndex,static=$isStatic,params=${paramTypes.size})"
        if (!hookedCaptureMethods.add(key)) return

        log("watermark factory: hooked $key")
        log("watermark factory: params=$paramTypes static=$isStatic bitmapIdx=$bitmapParamIndex")

        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val state = activeCapture.get()
                GlassConfig.refresh()
                if (!GlassConfig.masterEnabled || !ConfigStore.capsuleEnabled()) return
                val source = p.args?.getOrNull(bitmapParamIndex) as? Bitmap ?: return
                if (source.isRecycled || source.width < 64 || source.height < 64) return

                // A capture is not a preview. Keep the clean source at this layer and wait until
                // the fully composed frame is about to be converted to YUV/JPEG. Drawing here is
                // precisely what allowed Xiaomi's later watermark pass to cover the glass.
                if (state != null) {
                    state.rememberPristineSource(source)
                    return
                }

                // Outside a capture context this method feeds the watermark-selection preview.
                // Keep its live glass preview, but never claim camera EXIF/GPS that is not present.
                val meta = CapsuleRenderer.CaptureMeta(0L, null, null)
                val output = if (source.isMutable && source.config != Bitmap.Config.HARDWARE) {
                    source
                } else {
                    copyForRendering(source) ?: run {
                        log("watermark factory: source bitmap cannot be made mutable; keep stock path")
                        return
                    }
                }
                try {
                    if (renderCaptureCapsule(output, meta)) {
                        p.result = output
                        log("watermark factory: selection preview glass rendered ${output.width}x${output.height}")
                    } else if (output !== source) {
                        output.recycle()
                    }
                } catch (t: Throwable) {
                    if (output !== source && !output.isRecycled) output.recycle()
                    log("watermark replacement failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }

            override fun afterHookedMethod(p: MethodHookParam) {
                // The source parameter is the unwatermarked photograph; p.result is Xiaomi's
                // completed stock-watermark bitmap. Bind by identity so a later encoder cannot
                // accidentally modify the separately retained original-photo output.
                activeCapture.get()?.rememberComposedPhoto(p.result as? Bitmap)
            }
        })
    }

    /**
     * Capture the clean, full-resolution Bitmap produced from the camera's YUV frame before
     * Xiaomi scales it for watermark layout. On Camera 6.7 this is the first I420ToBitmap call
     * inside W8/d.f; the stock watermark factory later receives a potentially scaled copy.
     */
    private fun hookYuvSourceAdapters(loader: ClassLoader) {
        val owner = findClass("com.xiaomi.libyuv.YuvUtils", loader) ?: run {
            log("YUV source adapter: com.xiaomi.libyuv.YuvUtils absent; factory input remains the fallback source")
            return
        }
        val targets = owner.declaredMethods.filter { method ->
            method.name == "I420ToBitmap" && Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.firstOrNull() == Bitmap::class.java
        }
        if (targets.isEmpty()) {
            log("YUV source adapter: no I420ToBitmap(Bitmap, ...) methods")
            return
        }
        for (target in targets) {
            val key = "${target.declaringClass.name}#${target.name}(source-bitmap)"
            if (!hookedCaptureMethods.add(key)) continue
            try {
                XposedBridge.hookMethod(target, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val state = activeCapture.get() ?: return
                        GlassConfig.refresh()
                        if (!GlassConfig.masterEnabled || !ConfigStore.capsuleEnabled()) return
                        val bitmap = p.args?.firstOrNull() as? Bitmap ?: return
                        state.rememberPristineSource(bitmap)
                    }
                })
                log("YUV source adapter hooked: ${target.name}${target.parameterTypes.map { it.simpleName }}")
            } catch (t: Throwable) {
                log("YUV source adapter hook failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * Hooks the camera's final Bitmap -> frame adapters discovered from DEX structure. The
     * adapter is called after `com.xiaomi.cam.watermark.a.c()` has composed the stock content,
     * but before the result is packed into YUV/JPEG. We restore the pristine factory input over
     * that composed bitmap and draw our capsule once, so the stock watermark cannot sit above it.
     */
    private fun hookCaptureFrameEncoders(loader: ClassLoader) {
        val encoders = DexKitHelper.findFrameEncoderMethods(loader)
        if (encoders.isEmpty()) {
            log("final capture encoder: no verified structural encoder candidate; safe final replacement unavailable")
            return
        }
        var installed = 0
        for ((target, bitmapIndex) in encoders) {
            val key = "${target.declaringClass.name}#${target.name}(final-bitmap=$bitmapIndex)"
            if (!hookedCaptureMethods.add(key)) continue
            try {
                XposedBridge.hookMethod(target, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val state = activeCapture.get() ?: return
                        GlassConfig.refresh()
                        if (!GlassConfig.masterEnabled || !ConfigStore.capsuleEnabled()) return
                        val input = p.args?.getOrNull(bitmapIndex) as? Bitmap ?: return
                        if (input.isRecycled || input.width < 256 || input.height < 256) return
                        val composedPhoto = state.composedPhoto
                        if (composedPhoto == null || composedPhoto !== input) {
                            // Mi/b is also called for the untouched original-image branch. Only
                            // patch the exact bitmap returned by Xiaomi's watermark factory.
                            return
                        }
                        synchronized(state.patchedBitmaps) {
                            if (!state.patchedBitmaps.add(input)) return
                        }

                        val snapshot = state.sourceSnapshot
                        val pristine = snapshot ?: state.sourcePhoto
                        if (pristine == null || pristine.isRecycled) {
                            synchronized(state.patchedBitmaps) { state.patchedBitmaps.remove(input) }
                            log("final capture encoder: skipped; no verified clean source bitmap")
                            return
                        }
                        if (!compatibleAspect(state.sourceWidth, state.sourceHeight, input)) {
                            synchronized(state.patchedBitmaps) { state.patchedBitmaps.remove(input) }
                            log("final capture encoder: skipped; source/output aspect mismatch " +
                                    "${state.sourceWidth}x${state.sourceHeight} -> ${input.width}x${input.height}")
                            return
                        }

                        val output = if (input.isMutable && input.config != Bitmap.Config.HARDWARE) {
                            input
                        } else {
                            copyForRendering(input) ?: run {
                                synchronized(state.patchedBitmaps) { state.patchedBitmaps.remove(input) }
                                log("final capture encoder: output is immutable and copy failed")
                                return
                            }
                        }
                        try {
                            val clean = snapshot ?: state.sourcePhoto ?: pristine
                            if (clean !== output || snapshot != null) {
                                val canvas = Canvas(output)
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                                    isDither = true
                                }
                                val sourceRect = state.sourceSnapshotRect
                                if (snapshot != null && sourceRect != null && sourceRect.top > 0) {
                                    val sourceHeight = state.sourceHeight.takeIf { it > 0 } ?: clean.height
                                    val topFraction = sourceRect.top.toFloat() / sourceHeight.coerceAtLeast(1)
                                    val heightFraction = sourceRect.height().toFloat() / sourceHeight.coerceAtLeast(1)
                                    val dstTop = (output.height * topFraction).toInt().coerceIn(0, output.height - 1)
                                    val dstBottom = (output.height * (topFraction + heightFraction))
                                        .toInt().coerceIn(dstTop + 1, output.height)
                                    canvas.drawBitmap(
                                        clean,
                                        Rect(0, 0, clean.width, clean.height),
                                        Rect(0, dstTop, output.width, dstBottom),
                                        paint,
                                    )
                                } else {
                                    canvas.drawBitmap(
                                        clean,
                                        null,
                                        Rect(0, 0, output.width, output.height),
                                        paint,
                                    )
                                }
                            }
                            if (renderCaptureCapsule(output, state.meta)) {
                                if (output !== input) p.args[bitmapIndex] = output
                                log("final capture encoder: replaced composed stock layer with glass on " +
                                        "${output.width}x${output.height}")
                            } else {
                                if (output !== input && !output.isRecycled) output.recycle()
                                synchronized(state.patchedBitmaps) { state.patchedBitmaps.remove(input) }
                                log("final capture encoder: capsule render failed; original camera frame kept")
                            }
                        } catch (t: Throwable) {
                            if (output !== input && !output.isRecycled) output.recycle()
                            synchronized(state.patchedBitmaps) { state.patchedBitmaps.remove(input) }
                            log("final capture encoder failed: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                })
                installed++
                log("final capture encoder hooked: $key")
            } catch (t: Throwable) {
                log("final capture encoder hook failed [$key]: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        log("final capture encoder: $installed method(s) installed")
    }

    private fun compatibleAspect(sourceWidth: Int, sourceHeight: Int, output: Bitmap): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) return false
        if ((sourceWidth > sourceHeight) != (output.width > output.height)) return false
        val sourceRatio = sourceWidth.toDouble() / sourceHeight
        val outputRatio = output.width.toDouble() / output.height.coerceAtLeast(1)
        return kotlin.math.abs(sourceRatio - outputRatio) / maxOf(sourceRatio, outputRatio) <= 0.025
    }

    private fun copyForRendering(source: Bitmap): Bitmap? = try {
        source.copy(Bitmap.Config.ARGB_8888, true)
    } catch (_: Throwable) { null }

    /** Draw on the verified clean capture after the stock composite has been restored. */
    private fun renderCaptureCapsule(out: Bitmap, meta: CapsuleRenderer.CaptureMeta): Boolean {
        val started = SystemClock.elapsedRealtime()
        val w = out.width
        val h = out.height
        val shortSide = minOf(w, h)
        val showLogo = ConfigStore.capsuleShowLogo()
        val opts = CapsuleRenderer.Options(
            modelOverride = ConfigStore.capsuleModel(),
            position = ConfigStore.capsulePos(),
            showDateTime = ConfigStore.capsuleShowDatetime(),
            showExif = ConfigStore.capsuleShowExif(),
            showGps = ConfigStore.capsuleShowGps(),
            scale = ConfigStore.capsuleScale(),
            showLogo = showLogo,
            logo = if (showLogo) loadLogo() else null,
            logoPreset = ConfigStore.capsuleLogoPreset(),
            logoPosition = ConfigStore.capsuleLogoPosition(),
        )
        val lines = CapsuleRenderer.buildLines(opts, meta, shortSide)
        if (lines.isEmpty()) return false
        val metrics = CapsuleRenderer.measureCapsule(w, shortSide, opts.scale, lines, opts) ?: return false
        val capsuleW = metrics.capsuleWidth
        val capsuleH = metrics.capsuleHeight
        if (capsuleW >= w || capsuleH >= h) return false

        // Placement is independent of the capsule size slider: keep the same raised baseline
        // as the preview even when the user scales the glass panel.
        val bottomMargin = (shortSide * (CapsuleRenderer.BOX_INSET_REF / 1080f))
            .coerceIn(4f, shortSide * 0.18f)
        val capsuleBottom = (h - bottomMargin).coerceAtLeast(capsuleH + 4f)
        val capsuleTop = capsuleBottom - capsuleH
        val horizontalInset = (w * CapsuleRenderer.HORIZONTAL_EDGE_INSET_FRACTION).coerceAtLeast(1f)
        val capsuleLeft = when (opts.position) {
            "right" -> w - horizontalInset - capsuleW
            "center" -> (w - capsuleW) * 0.5f
            else -> horizontalInset
        }.coerceIn(0f, (w - capsuleW).coerceAtLeast(0f))
        val radius = (CapsuleRenderer.CORNER_RADIUS_REF * metrics.unitPx)
            .coerceAtLeast(1f).coerceAtMost(capsuleH * 0.5f)
        val capsule = PanelRect(capsuleLeft, capsuleTop, capsuleW, capsuleH, radius)
        val padPx = maxOf(
            24f,
            PANEL_CROP_PAD_FRACTION * minOf(capsuleW, capsuleH),
            LiquidGlassOptics.requiredPanelPaddingPx(capsule, GlassConfig.params()).toFloat(),
        ).coerceAtMost(capsuleH * 0.5f)

        val cropLeft = (capsule.left - padPx).toInt().coerceIn(0, w - 1)
        val cropTop = (capsule.top - padPx).toInt().coerceIn(0, h - 1)
        val cropRight = (capsule.right + padPx).toInt().coerceIn(cropLeft + 1, w)
        val cropBottom = (capsule.bottom + padPx).toInt().coerceIn(cropTop + 1, h)
        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop
        if (cropW < 8 || cropH < 8) return false
        if (SystemClock.elapsedRealtime() - started > RENDER_TIME_BUDGET_MS) return false

        val pixels = IntArray(cropW * cropH)
        out.getPixels(pixels, 0, cropW, cropLeft, cropTop, cropW, cropH)
        val localCapsule = PanelRect(
            left = capsule.left - cropLeft,
            top = capsule.top - cropTop,
            width = capsule.width,
            height = capsule.height,
            cornerRadiusPx = capsule.cornerRadiusPx,
        )
        val params = resolveParams(pixels, cropW, cropH, localCapsule).copy(preserveContent = false)
        val result = LiquidGlassOptics.renderPanel(
            pixels, cropW, cropH, localCapsule, params, panelIsOpaquePlate = false,
        )
        if (result.startsWith("skip")) {
            log("direct capsule render skipped: $result")
            return false
        }
        val textLayer = CapsuleRenderer.drawTextLayer(
            cropW, cropH, localCapsule, metrics, CapsuleRenderer.TEXT_COLOR_WHITE,
        ) ?: run {
            log("direct capsule render skipped: text layer could not be allocated")
            return false
        }
        CapsuleRenderer.blendTextOver(pixels, textLayer)
        out.setPixels(pixels, 0, cropW, cropLeft, cropTop, cropW, cropH)
        log("direct capsule: ${f(capsuleW)}x${f(capsuleH)} at ${f(capsuleLeft)},${f(capsuleTop)} " +
                "lines=${lines.size} ${SystemClock.elapsedRealtime() - started}ms -> $result")
        return true
    }

    /**
     * Waits for the background DEX scan, then re-runs the exact hook installation.
     *
     * This is the path that makes an **unseen camera build** work: at `handleLoadPackage` time
     * the scan has not finished, so the first `installTypedPipelineHooks` usually only succeeds
     * on the stable `com.xiaomi.cam.watermark.b` literal. Once the scan resolves the capture
     * record (and, on a build whose names moved, its consumer), this retries and the EXIF/GPS
     * lines come alive on the very first shot instead of never.
     *
     * Retrying is safe: every hook is registered in [hookedCaptureMethods] under a signature key,
     * so a second pass cannot double-hook.
     */
    private fun startLateDiscovery(loader: ClassLoader) {
        val thread = Thread({
            val found = DexKitHelper.awaitDiscovery(DISCOVERY_WAIT_MS)
            if (!found) {
                log("late discovery: scan produced no usable names within ${DISCOVERY_WAIT_MS}ms; " +
                        "the literal hook targets remain the only ones installed")
                return@Thread
            }
            try {
                // Re-run the exact hooks now that the structural names are known. A build that
                // already matched literally is unaffected: the signature keys deduplicate.
                hookCaptureContext(loader)
                hookYuvSourceAdapters(loader)
                hookWatermarkBitmapFactory(loader)
                hookCaptureFrameEncoders(loader)
                log("late discovery: re-install complete, total=${hookedCaptureMethods.size}")

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

    /**
     * Install the typed capture pipeline. Camera 6.7 uses a DEX-identified record receiver
     * (`W8/d.f`) and frame adapters (`Mi/b.a|b|c`); the 6.4 literal receiver remains as a
     * fallback. The stock watermark factory is also hooked for selection previews, while the
     * final capture adapter is identity-gated so only the OEM-composited photo is replaced.
     * Unknown signatures are left untouched rather than guessed at runtime.
     */
    private fun installTypedPipelineHooks(loader: ClassLoader) {
        log("=== hook install begin ===")
        log("host=${hostFingerprint()}")
        hookCaptureContext(loader)
        hookYuvSourceAdapters(loader)
        hookWatermarkBitmapFactory(loader)
        hookCaptureFrameEncoders(loader)

        val captureOk = hookedCaptureMethods.any {
            it.contains("(structural)") || it.contains("(la.a;ZI)")
        }
        val factoryOk = hookedCaptureMethods.any { it.contains("bitmapIdx=") }
        val finalEncoderOk = hookedCaptureMethods.any { it.contains("final-bitmap=") }
        log(
            "=== hook install summary: capture=${if (captureOk) "OK" else "MISSING"} " +
                    "factory=${if (factoryOk) "OK" else "MISSING"} " +
                    "finalEncoder=${if (finalEncoderOk) "OK" else "MISSING"} " +
                    "total=${hookedCaptureMethods.size} ==="
        )
        when {
            captureOk && factoryOk && finalEncoderOk -> log("status: post-composition capture pipeline hooked (EXIF/GPS available)")
            captureOk && factoryOk -> log("status: partial — metadata and preview hooks active, but final encoder not verified; stock watermark will not be destructively overwritten")
            !captureOk && factoryOk -> log(
                "status: partial — watermark replacement works, but no capture context matched, " +
                        "so the capsule will show device name + clock only. " +
                        "The DEX scan result is logged above; report it to extend the matcher."
            )
            factoryOk.not() -> log(
                "status: FAILED — no watermark factory matched. The module cannot replace " +
                        "watermarks on this camera build. Share the log lines above."
            )
        }
    }

    /**
     * A compact description of the host, so a log from someone else's device is actionable
     * without asking them to run adb.
     */
    private fun hostFingerprint(): String = try {
        val cls = Class.forName("android.os.Build")
        val model = cls.getField("MODEL").get(null) as? String ?: "?"
        val sdk = cls.getField("SDK_INT").get(null) as? Int ?: -1
        "$model/api$sdk"
    } catch (_: Throwable) { "unknown" }

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
                // 6.4.x returns WatermarkRemover$b instead of a raw Bitmap.
                // Unwrap it: try direct cast first, then reflect into the wrapper
                // for a Bitmap field.
                val out = unwrapBitmap(p.result) ?: run {
                    log("composite result is not a bitmap: ${p.result?.javaClass?.name}")
                    return
                }
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
                // A Bitmap argument is not necessarily the source photo: on Camera 6.4 the
                // old Fe/a.j hook receives a render destination for the watermark layer only.
                // Do not infer or copy any arbitrary bitmap into the output photograph.
                val src: Bitmap? = null
                log("legacy composite ${out.width}x${out.height}: source image deliberately not inferred")
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
        // 6.4.x changed the return type from Bitmap to a wrapper (WatermarkRemover$b),
        // so accept either a Bitmap return or any non-void return that wraps one.
        val target = cls.declaredMethods.firstOrNull { method ->
            method.name == methodName &&
                    method.parameterTypes.any { Bitmap::class.java.isAssignableFrom(it) } &&
                    (Bitmap::class.java.isAssignableFrom(method.returnType) ||
                     method.returnType.name.contains("WatermarkRemover"))
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

    /**
     * Source-level white-bar interception.
     *
     * Fe/a.b(pe/o, String) is the blit method that draws each watermark element. Before blitting
     * the element's bitmap, it checks Fe/b.a (an int field whose upper byte encodes a color);
     * when that byte is non-zero, it calls Canvas.drawColor() — that is the solid white (or
     * tinted) strip the camera paints behind the watermark.
     *
     * Hooking b() and clearing Fe/b.a in beforeHookedMethod prevents the drawColor call,
     * so the element's transparent background is never filled with a solid color. The
     * element's own bitmap (glyphs, logos) is still blitted normally; only the backing fill
     * is suppressed.
     *
     * On 6.4.x the Fe/b data is stored in Fe/a.i (field "i" of type Fe/b). We reflect into
     * the receiver (Fe/a instance) to find and zero the Fe/b.a field.
     */
    private fun hookBlitMethod(classLoader: ClassLoader, className: String, methodName: String) {
        val cls = findClass(className, classLoader) ?: run {
            log("hookBlit: class $className not found")
            return
        }
        // Camera 6.4 signature from the decompiled APK: b(Lpe/o;Ljava/lang/String;)V.
        val target = cls.declaredMethods.firstOrNull { m ->
            m.name == methodName && m.parameterTypes.size == 2 &&
                    m.parameterTypes[0].name == "pe.o" &&
                    m.parameterTypes[1] == String::class.java
        }
        if (target == null) {
            log("hookBlit: method $methodName not found in $className; candidates=" +
                    cls.declaredMethods.joinToString { "${it.name}/${it.parameterTypes.size}" })
            return
        }
        if (!hookedComposites.add(cls.name + "#" + methodName + "-blit")) return
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    val receiver = p.thisObject ?: return
                    // Walk declared fields to find the Fe/b data object (field "i" on 6.4.x).
                    val dataField = receiver.javaClass.declaredFields.firstOrNull { f ->
                        f.name == "i" && f.type.name == "Fe.b"
                    }
                    if (dataField != null) {
                        dataField.isAccessible = true
                        val dataObj = dataField.get(receiver) ?: return
                        // Fe/b.a is the fill color; do not mutate unrelated integer state.
                        val colorField = dataObj.javaClass.declaredFields.firstOrNull { f ->
                            f.name == "a" && f.type == Int::class.javaPrimitiveType
                        }
                        if (colorField != null) {
                            colorField.isAccessible = true
                            val orig = colorField.getInt(dataObj)
                            if (orig != 0) {
                                colorField.setInt(dataObj, 0)
                                LFLogger.v("hookBlit: zeroed Fe/b.a color=0x${orig.toString(16)} -> 0")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    LFLogger.w("hookBlit before failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        })
        log("hooked blit method $className->$methodName (source-level white-bar suppression)")
    }

    /**
     * Unwraps the composite result to a Bitmap. On 6.2.x/6.7.x the result is already
     * a Bitmap. On 6.4.x the composite returns a `WatermarkRemover$b` wrapper — scan
     * its declared fields for the first Bitmap.
     */
    private fun unwrapBitmap(result: Any?): Bitmap? {
        if (result == null) return null
        if (result is Bitmap) return result
        // Wrapper object — reflectively find the Bitmap field.
        return try {
            result.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                if (Bitmap::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    f.get(result) as? Bitmap
                } else null
            }
        } catch (t: Throwable) {
            log("unwrapBitmap failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
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

        // This legacy renderer may only repair a watermark when the layout is proven to sit
        // entirely in the camera-appended fill strip. On-photo watermark pixels cannot be
        // reconstructed from guessed Bitmap arguments, so this path now refuses those cases.
        val isStripLayout = photoBottom >= 0 && plate.top >= photoBottom + STRIP_PLATE_GAP_MIN
        var eraseMode = "none"
        if (!isStripLayout) {
            log("skip: refusing destructive over-photo erase without a verified pristine source")
            return
        }
        val eraseTopY = photoBottom.coerceIn(0, h)
        val eraseRowsGlobal = h - eraseTopY
        if (eraseRowsGlobal > 0) {
            val strip = IntArray(w * eraseRowsGlobal)
            out.getPixels(strip, 0, w, 0, eraseTopY, w, eraseRowsGlobal)
            val fill = CapsuleRenderer.fillColorFromStripTop(strip, w)
            val repaired = fill != null && CapsuleRenderer.fillStrip(strip, w, eraseRowsGlobal, fill)
            if (repaired) {
                out.setPixels(strip, 0, w, 0, eraseTopY, w, eraseRowsGlobal)
                eraseMode = "verified-strip-fill"
            }
        }
        log("legacy eradicate: mode=$eraseMode y=$eraseTopY..${eraseTopY + eraseRowsGlobal} photoBottom=$photoBottom")

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
            logoPreset = ConfigStore.capsuleLogoPreset(),
            logoPosition = ConfigStore.capsuleLogoPosition(),
        )
        val meta = activeCapture.get()?.meta ?: CapsuleRenderer.CaptureMeta(0L, null, null)
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
        val cornerR = (CapsuleRenderer.CORNER_RADIUS_REF * metrics.unitPx)
            .coerceAtLeast(1f)
            .coerceAtMost(capsuleH * 0.5f)
        val protoCapsule = PanelRect(0f, 0f, capsuleW, capsuleH, cornerR)
        val cropParams = GlassConfig.params()
        val padPx = maxOf(
            24f,
            PANEL_CROP_PAD_FRACTION * minOf(capsuleW, capsuleH),
            LiquidGlassOptics.requiredPanelPaddingPx(protoCapsule, cropParams).toFloat(),
        ).coerceAtMost(capsuleH * 0.5f)
        // Placement cap. The capsule's bottom edge sits at [placementCap - bottomMargin],
        // so the capsule is always inside the photograph. The elevated baseline keeps it clear
        // of a stock plate near the lower edge while matching the live-preview composition.
        val placementCap = anchorY
        // Keep the same elevated baseline as the direct post-composition capture path.
        val bottomMargin = (shortSide * (CapsuleRenderer.BOX_INSET_REF / 1080f))
            .coerceIn(4f, shortSide * 0.18f)
        val capsuleBottom = (placementCap - bottomMargin).coerceAtLeast(capsuleH + 4f)
        val capsuleTop = capsuleBottom - capsuleH
        val horizontalInset = (w * CapsuleRenderer.HORIZONTAL_EDGE_INSET_FRACTION).coerceAtLeast(1f)
        val capsuleLeft = when (opts.position) {
            "left" -> horizontalInset
            "right" -> w - horizontalInset - capsuleW
            else -> (w - capsuleW) * 0.5f
        }.coerceIn(0f, (w - capsuleW).coerceAtLeast(0f))
        val capsule = PanelRect(
            left = capsuleLeft,
            top = capsuleTop,
            width = capsuleW,
            height = capsuleH,
            cornerRadiusPx = cornerR,
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
        // The reference keeps its labels white even over bright water/sky; a restrained
        // shadow maintains contrast without switching to dark text halfway through a capsule.
        val textLayer = CapsuleRenderer.drawTextLayer(
            cw2, ch2, localCapsule, metrics, CapsuleRenderer.TEXT_COLOR_WHITE,
        )
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

    /** Delegates to the full logging system with no rate limiting. */
    private fun log(message: String) {
        LFLogger.d(message)
        XposedBridge.log("$TAG: $message")
    }
}