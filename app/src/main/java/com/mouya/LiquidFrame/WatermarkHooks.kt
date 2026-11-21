package com.mouya.LiquidFrame

import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
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
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.Locale
object WatermarkHooks {

    private const val TAG = "LiquidFrame"

    private const val MIN_PANEL_WIDTH = 32

    private const val MAX_PANEL_WIDTH_FRACTION = 0.92f

    private const val MIN_PANEL_WIDTH_FRACTION = 0.12f

    private const val MAX_PANEL_HEIGHT_FRACTION = 0.45f

    private const val MAX_PANELS = 8

    private const val SCAN_WORK_WIDTH = 192

    private const val RENDER_TIME_BUDGET_MS = 1200L

    private const val PANEL_CROP_PAD_FRACTION = 0.55f

    private const val STRIP_PLATE_GAP_MIN = 12

    private class Frame {
        val panels = ArrayList<PanelRect>(MAX_PANELS)
    }

    private val frames = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, Frame>()
    )

    private val framesByBitmap = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Bitmap, Frame>()
    )

    private val hookedClasses = java.util.Collections.synchronizedSet(HashSet<String>())

    private val hookedComposites = java.util.Collections.synchronizedSet(HashSet<String>())

    private data class CaptureState(
        val meta: CapsuleRenderer.CaptureMeta,
        var capsuleRendered: Boolean = false,

        var renderedWidth: Int = 0,

        var renderedHeight: Int = 0,

        val guardedBitmaps: MutableSet<Bitmap> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>()),
    )

    // Keep metadata scoped to the current capture thread.
    private val activeCapture = ThreadLocal<CaptureState?>()
    private val captureStack = ThreadLocal.withInitial { ArrayList<CaptureState?>() }
    private val hookedCaptureMethods = java.util.Collections.synchronizedSet(HashSet<String>())

    private val glassEncodedOutputs = LinkedHashSet<Long>(32)

    private val glassBitmaps = java.util.Collections.synchronizedMap(
        java.util.IdentityHashMap<Bitmap, Long>()
    )

    @Volatile
    private var lastCapture: MetaStamp? = null

    private class MetaStamp(val meta: CapsuleRenderer.CaptureMeta, val at: Long)

    private const val META_FRESH_MS = 30_000L

    private const val VERIFIER_JPEG_QUALITY = 95

    private const val VERIFIER_MIN_JPEG_BYTES = 48 * 1024

    private const val DISCOVERY_WAIT_MS = 45_000L

    @Volatile
    private var cachedLogo: Bitmap? = null
    @Volatile
    private var cachedLogoPreset: String = ""
    private val logoLock = Any()

    private fun loadLogo(): Bitmap? {

        val presetName = ConfigStore.capsuleLogoPreset()

        if (cachedLogoPreset != presetName) {
            synchronized(logoLock) {
                if (cachedLogoPreset != presetName) {

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

                val preset = LogoPreset.fromName(presetName)
                if (preset == LogoPreset.None) {
                    log("loadLogo: preset is None, skipping")
                    return null
                }

                val apkPath = resolveModuleApkPath()
                if (apkPath == null) {
                    log("loadLogo: module APK path not found")
                    return null
                }
                val apk = java.io.File(apkPath)
                if (!apk.isFile) {
                    log("loadLogo: module APK file does not exist: $apkPath")
                    return null
                }
                val bmp: Bitmap? = java.util.zip.ZipFile(apk).use { z ->
                    fun decodeAsset(name: String): Bitmap? {
                        val entry = z.getEntry("assets/$name") ?: return null
                        return z.getInputStream(entry).use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    }
                    fun decodeDrawable(name: String): Bitmap? {

                        fun moduleResources(): android.content.res.Resources? {
                            val pkgContext = try {
                                Class.forName("android.app.ActivityThread")
                                    .getMethod("currentApplication")
                                    .invoke(null) as? android.content.Context
                            } catch (t: Throwable) { null }
                            val ctx = pkgContext?.createPackageContext(
                                "com.mouya.LiquidFrame",
                                android.content.Context.CONTEXT_IGNORE_SECURITY,
                            )
                            if (ctx != null) return ctx.resources
                            return try {
                                val assets = android.content.res.AssetManager::class.java
                                    .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                                assets.javaClass.getMethod("addAssetPath", String::class.java)
                                    .invoke(assets, apkPath)
                                val system = android.content.res.Resources.getSystem()
                                android.content.res.Resources(assets, system.displayMetrics, system.configuration)
                            } catch (t: Throwable) { null }
                        }
                        return try {
                            val moduleRes = moduleResources() ?: return null
                            val resourceName = name.substringAfterLast('/').substringBeforeLast('.')
                            val id = moduleRes.getIdentifier(resourceName, "drawable", "com.mouya.LiquidFrame")
                            if (id == 0) return null
                            val drawable = moduleRes.getDrawable(id, null)
                            val w = drawable.intrinsicWidth.coerceAtLeast(64)
                            val h = drawable.intrinsicHeight.coerceAtLeast(64)
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                                val canvas = Canvas(bmp)
                                drawable.setBounds(0, 0, w, h)
                                drawable.draw(canvas)
                            }
                        } catch (t: Throwable) {
                            log("decode drawable failed: ${t.javaClass.simpleName}: ${t.message}")
                            null
                        }
                    }

                    when (preset) {
                        LogoPreset.Leica -> decodeAsset("leica_logo.png")
                        LogoPreset.LeicaWhite -> decodeAsset("leica_wordmark_white.png") ?: decodeAsset("leica_logo.png")
                        LogoPreset.Apple -> decodeDrawable("drawable/logo_apple.xml")
                        else -> {

                            decodeAsset("leica_wordmark_white.png") ?: decodeAsset("leica_logo.png")
                        }
                    }
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

    private fun resolveModuleApkPath(): String? {

        try {
            val m = XposedBridge::class.java.getMethod("getModulePath")
            val p = m.invoke(null) as? String
            if (p != null && java.io.File(p).isFile) return p
        } catch (e: Throwable) {                           }

        try {
            val res = javaClass.classLoader?.getResource("assets/leica_logo.png")
            if (res != null) {
                val path = extractApkFromUrl(res.toString())
                if (path != null) return path
            }
        } catch (e: Throwable) {                }

        try {
            val pd = javaClass.protectionDomain?.codeSource
            if (pd != null) {
                val loc = pd.location
                if (loc != null) {
                    val p = loc.path
                    if (p != null && java.io.File(p).isFile) return p
                }
            }
        } catch (e: Throwable) {                }

        try {
            val tcl = Thread.currentThread().contextClassLoader
            val res = tcl?.getResource("assets/leica_logo.png")
            if (res != null) {
                val path = extractApkFromUrl(res.toString())
                if (path != null) return path
            }
        } catch (e: Throwable) {                }

        try {
            val pkg = "com.mouya.LiquidFrame"
            val modDir = java.io.File("/data/adb/modules")
            if (modDir.isDirectory) {
                val propFile = java.io.File(modDir, "liquidframe/module.prop")
                if (!propFile.isFile) {

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
        } catch (e: Throwable) {                          }

        return null
    }

    private fun extractApkFromUrl(url: String): String? {

        val prefix = "file:"
        val idx = url.indexOf(prefix)
        if (idx < 0) return null
        val rest = url.substring(idx + prefix.length)
        val bang = rest.indexOf("!/")
        val path = if (bang >= 0) rest.substring(0, bang) else rest

        return try { java.io.File(path).absolutePath } catch (t: Throwable) { null }
    }

    fun install(param: XC_LoadPackage.LoadPackageParam) {
        log("install: begin, packageName=${param.packageName}")
        DexKitHelper.setClassLoader(param.classLoader)
        DexKitHelper.initDeferred()
        installCapturePipeline(param.classLoader)

        startLateDiscovery(param.classLoader)
        log("install: complete")
    }

    private fun installCapturePipeline(loader: ClassLoader) {
        installTypedPipelineHooks(loader)
    }

    private fun hookCaptureContext(loader: ClassLoader) {
        if (hookCaptureContextStructural(loader)) return
        hookCaptureContext64(loader)
    }

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
        installCaptureHook(target, plan, "structural ${record.simpleName}")
        return true
    }

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

    private fun installCaptureHook(
        target: Method,
        plan: DexScan.RecordPlan?,
        label: String,
    ) {
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val context = p.args?.getOrNull(0) ?: return
                val stack = captureStack.get()
                stack.add(activeCapture.get())
                activeCapture.set(CaptureState(readCaptureMeta(context, plan)))
            }

            override fun afterHookedMethod(p: MethodHookParam) {
                val stack = captureStack.get()
                val previous = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else null
                activeCapture.set(previous)
                if (stack.isEmpty()) captureStack.remove()
            }
        })
        log("hooked capture metadata [$label]: ${target.declaringClass.name}#${target.name} " +
                "params=${target.parameterTypes.size} plan=${if (plan != null) "indexed" else "names"}")
    }

    private fun readCaptureMeta(context: Any, plan: DexScan.RecordPlan?): CapsuleRenderer.CaptureMeta {

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

    private fun selectFactoryMethod(methods: Array<Method>): Method? {
        val qualifying = methods.filter { m ->
            m.returnType == Bitmap::class.java &&
                    m.parameterTypes.any { it == Bitmap::class.java } &&
                    m.parameterTypes.any { it == android.content.Context::class.java }
        }
        if (qualifying.isEmpty()) return null
        return qualifying.firstOrNull { it.name == "c" } ?: qualifying.first()
    }

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

                val meta = state?.meta ?: CapsuleRenderer.CaptureMeta(0L, null, null)
                val source = p.args?.getOrNull(bitmapParamIndex) as? Bitmap ?: return
                if (source.isRecycled || source.width < 64 || source.height < 64) return

                if (state?.capsuleRendered == true) {
                    p.result = source
                    return
                }
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
                        state?.capsuleRendered = true
                        state?.renderedWidth = output.width
                        state?.renderedHeight = output.height
                        p.result = output
                        log("watermark factory: stock watermark replaced on ${output.width}x${output.height}")
                    } else if (output !== source) {
                        output.recycle()
                    }
                } catch (t: Throwable) {
                    if (output !== source && !output.isRecycled) output.recycle()
                    log("watermark replacement failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        })
    }

    private fun copyForRendering(source: Bitmap): Bitmap? = try {
        source.copy(Bitmap.Config.ARGB_8888, true)
    } catch (_: Throwable) { null }

    private fun hookFinalJpegEncoder(loader: ClassLoader) {
        val cls = findClass("com.xiaomi.gl.texture.Jpeg", loader) ?: run {
            log("final jpeg guard: com.xiaomi.gl.texture.Jpeg absent on this build")
            return
        }

        val targets = cls.declaredMethods.filter { m ->
            (m.name == "bitmapToJpeg" || m.name == "bitmapToJpegBuffer") &&
                    Modifier.isStatic(m.modifiers) &&
                    m.parameterTypes.firstOrNull() == Bitmap::class.java &&
                    (m.returnType == ByteArray::class.java || m.returnType == ByteBuffer::class.java)
        }
        if (targets.isEmpty()) {
            log("final jpeg guard: no static bitmapToJpeg/bitmapToJpegBuffer(Bitmap, ...) on ${cls.name}")
            return
        }
        for (target in targets) {
            val key = "${target.declaringClass.name}#${target.name}(final-jpeg)"
            if (!hookedCaptureMethods.add(key)) continue
            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val state = activeCapture.get()
                    GlassConfig.refresh()
                    if (!GlassConfig.masterEnabled || !ConfigStore.capsuleEnabled()) return
                    val bitmap = p.args?.getOrNull(0) as? Bitmap ?: return
                    if (bitmap.isRecycled || bitmap.width < 256 || bitmap.height < 256) return

                    val alreadyGlass = synchronized(glassBitmaps) { bitmap in glassBitmaps } ||
                            state?.guardedBitmaps?.let { set ->
                                synchronized(set) { bitmap in set }
                            } == true
                    if (alreadyGlass) return
                    val meta = state?.meta ?: run {

                        val stamp = lastCapture ?: return
                        if (SystemClock.elapsedRealtime() - stamp.at > META_FRESH_MS) return
                        stamp.meta
                    }
                    if (!bitmap.isMutable || bitmap.config == Bitmap.Config.HARDWARE) {
                        log("final jpeg guard: bitmap is immutable/hardware; cannot burn in place")
                        return
                    }
                    if (state != null && !compatibleAspect(state.renderedWidth, state.renderedHeight, bitmap)) return
                    try {
                        if (renderCaptureCapsule(bitmap, meta)) {
                            state?.let { s ->
                                synchronized(s.guardedBitmaps) { s.guardedBitmaps.add(bitmap) }
                            }
                            log("final jpeg guard: glass burned into ${bitmap.width}x${bitmap.height} " +
                                    "immediately before encoding")
                        }
                    } catch (t: Throwable) {
                        log("final jpeg guard failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {

                    try {
                        val bitmap = p.args?.getOrNull(0) as? Bitmap
                        val glass = bitmap != null && synchronized(glassBitmaps) { glassBitmaps.containsKey(bitmap) }
                        if (!glass) return
                        when (val out = p.result) {
                            is ByteArray -> registerGlassOutput(out)
                            is ByteBuffer -> if (out.hasArray()) registerGlassOutput(out.array())
                        }
                    } catch (_: Throwable) {
                    }
                }
            })
            log("final jpeg guard: hooked ${target.name}${target.parameterTypes.map { it.simpleName }}" +
                    " -> ${target.returnType.simpleName}")
        }
    }

    private fun hookCloudWatermarkAttribute(loader: ClassLoader) {
        val cls = findClass("com.xiaomi.camera.bean.CloudWmAttribute", loader) ?: run {
            log("native watermark off: CloudWmAttribute absent on this build")
            return
        }
        val key = cls.name + "#<init>(native-wm-off)"
        if (!hookedCaptureMethods.add(key)) return
        var hooked = false
        for (ctor in cls.declaredConstructors) {
            if (ctor.parameterTypes.size != 2) continue
            if (ctor.parameterTypes[0] != String::class.java) continue
            if (ctor.parameterTypes[1] != ByteArray::class.java) continue
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    try {
                        GlassConfig.refresh()
                        if (!GlassConfig.masterEnabled || !ConfigStore.capsuleEnabled()) return
                        val self = p.thisObject ?: return

                        val configField = cls.getDeclaredField("mUserConfigData").apply {
                            isAccessible = true
                        }
                        val idField = cls.getDeclaredField("mWatermarkId").apply {
                            isAccessible = true
                        }
                        configField.set(self, ByteArray(0))
                        idField.set(self, "")
                    } catch (t: Throwable) {
                        log("native watermark off failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            })
            hooked = true
        }
        if (hooked) log("native watermark off: CloudWmAttribute(String,[B) neutralised — the MIVI engine now finishes photos clean")
    }

    private fun hookJpegLandingVerifier(loader: ClassLoader) {
        val task = DexKitHelper.findTaskDataClass(loader) ?: run {
            log("landing verifier: task data class not discovered on this build")
            return
        }
        val iface = DexKitHelper.findDataInterface(loader) ?: run {
            log("landing verifier: data interface not discovered on this build")
            return
        }
        val byteImpl = DexKitHelper.findByteDataImpl(loader)
        val setter = DexKitHelper.findTaskJpegSetter(loader)
        val filler = DexKitHelper.findTaskJpegFiller(loader)
        if (setter == null && filler == null) {
            log("landing verifier: neither landing method resolved on ${task.name}")
            return
        }
        for ((target, label) in listOfNotNull(
            setter?.to("${task.name}#setter(landing-verifier)"),
            filler?.to("${task.name}#filler(landing-verifier)"),
        )) {
            if (!hookedCaptureMethods.add(label)) continue
            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        val data = p.args?.getOrNull(0) ?: return
                        if (!iface.isInstance(data)) return
                        GlassConfig.refresh()
                        if (!GlassConfig.masterEnabled || !ConfigStore.capsuleEnabled()) return
                        val bytes = readDataBytes(data) ?: return
                        val patched = patchIncomingJpeg(bytes) ?: return
                        val replacement = wrapPatchedBytes(byteImpl, patched)
                        if (replacement != null) {
                            p.args[0] = replacement
                        }
                    } catch (t: Throwable) {
                        log("landing verifier failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            })
            log("landing verifier: hooked ${target.declaringClass.name}#${target.name} " +
                    "(${target.parameterTypes.map { it.simpleName }})")
        }
    }

    private fun patchIncomingJpeg(bytes: ByteArray): ByteArray? {
        if (bytes.size < VERIFIER_MIN_JPEG_BYTES) return null

        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        if (hasRotationTag(bytes)) return null
        if (isRegisteredGlassOutput(bytes)) return null
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        } catch (t: Throwable) {
            log("landing verifier: decode failed: ${t.javaClass.simpleName}")
            return null
        }
        try {
            if (bitmap.width < 512 || bitmap.height < 512) return null
            if (!renderCaptureCapsule(bitmap, verifierMeta())) return null
            val out = ByteArrayOutputStream((bytes.size * 11) / 10)
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, VERIFIER_JPEG_QUALITY, out)) return null
            val patched = out.toByteArray()
            registerGlassOutput(patched)
            log("landing verifier: capsule burned into ${bitmap.width}x${bitmap.height} " +
                    "engine jpeg (${bytes.size} -> ${patched.size} bytes)")
            return patched
        } catch (t: Throwable) {
            log("landing verifier render failed: ${t.javaClass.simpleName}: ${t.message}")
            return null
        } finally {
            bitmap.recycle()
        }
    }

    private fun hasRotationTag(bytes: ByteArray): Boolean = try {
        val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
        when (
            exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            android.media.ExifInterface.ORIENTATION_NORMAL,
            android.media.ExifInterface.ORIENTATION_UNDEFINED -> false
            else -> true
        }
    } catch (_: Throwable) {
        false
    }

    private val dataBytesReaders =
        java.util.Collections.synchronizedMap(HashMap<Class<*>, Method?>())

    private fun readDataBytes(data: Any): ByteArray? {
        val cls = data.javaClass
        val cached = dataBytesReaders[cls]
        if (cached != null) {
            return try {
                cached.invoke(data) as? ByteArray
            } catch (_: Throwable) {
                null
            }
        }

        val method = cls.methods.firstOrNull { m ->
            m.parameterTypes.isEmpty() && m.returnType == ByteArray::class.java
        }
        dataBytesReaders[cls] = method
        return try {
            method?.invoke(data) as? ByteArray
        } catch (_: Throwable) {
            null
        }
    }

    private fun wrapPatchedBytes(impl: Class<*>?, bytes: ByteArray): Any? {
        if (impl == null) return null
        return try {
            impl.getDeclaredConstructor(ByteArray::class.java).newInstance(bytes)
        } catch (t: Throwable) {
            log("landing verifier: could not wrap patched bytes: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun hookBitmapEncodeHelpers(loader: ClassLoader) {
        val helpers = DexKitHelper.findBitmapEncodeHelpers(loader)
        if (helpers.isEmpty()) {
            log("bitmap encode helpers: none discovered on this build")
            return
        }
        for (target in helpers) {
            val key = "${target.declaringClass.name}#${target.name}(encode-helper)"
            if (!hookedCaptureMethods.add(key)) continue
            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        GlassConfig.refresh()
                        if (!GlassConfig.masterEnabled || !ConfigStore.capsuleEnabled()) return
                        val bitmap = p.args?.getOrNull(1) as? Bitmap ?: return
                        if (bitmap.isRecycled || bitmap.width < 256 || bitmap.height < 256) return
                        if (synchronized(glassBitmaps) { glassBitmaps.containsKey(bitmap) }) return
                        val stamp = lastCapture ?: return
                        if (SystemClock.elapsedRealtime() - stamp.at > META_FRESH_MS) return
                        if (!bitmap.isMutable || bitmap.config == Bitmap.Config.HARDWARE) return
                        if (renderCaptureCapsule(bitmap, stamp.meta)) {
                            log("encode helper: capsule burned into ${bitmap.width}x${bitmap.height} " +
                                    "before ${target.declaringClass.simpleName}#${target.name}")
                        }
                    } catch (t: Throwable) {
                        log("encode helper failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    try {
                        val bitmap = p.args?.getOrNull(1) as? Bitmap ?: return
                        if (!synchronized(glassBitmaps) { glassBitmaps.containsKey(bitmap) }) return
                        (readDataBytes(p.result))?.let { registerGlassOutput(it) }
                    } catch (_: Throwable) {
                    }
                }
            })
            log("encode helper: hooked ${target.declaringClass.name}#${target.name}")
        }
    }

    private fun hookLiveRecorderSnapshots(loader: ClassLoader) {
        val snapshots = DexKitHelper.findRecorderSnapshots(loader)
        if (snapshots.isEmpty()) {
            log("live recorder: snapshot method not discovered on this build")
            return
        }
        for (target in snapshots) {
            val key = "${target.declaringClass.name}#${target.name}(live-recorder)"
            if (!hookedCaptureMethods.add(key)) continue
            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        GlassConfig.refresh()
                        if (!GlassConfig.masterEnabled || !ConfigStore.capsuleEnabled()) return
                        val id = p.args?.getOrNull(6) as? String ?: return
                        if (id.isEmpty()) return
                        p.args[6] = ""
                        log("live recorder: neutralised watermark id '${id.take(48)}' on " +
                                "${target.declaringClass.simpleName}#${target.name}")
                    } catch (t: Throwable) {
                        log("live recorder neutralise failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            })
            log("live recorder: hooked ${target.declaringClass.name}#${target.name}")
        }
    }

    private fun compatibleAspect(sourceWidth: Int, sourceHeight: Int, output: Bitmap): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) return true
        if ((sourceWidth > sourceHeight) != (output.width > output.height)) return false
        val sourceRatio = sourceWidth.toDouble() / sourceHeight
        val outputRatio = output.width.toDouble() / output.height.coerceAtLeast(1)
        return kotlin.math.abs(sourceRatio - outputRatio) / maxOf(sourceRatio, outputRatio) <= 0.025
    }

    private fun renderCaptureCapsule(out: Bitmap, meta: CapsuleRenderer.CaptureMeta): Boolean {
        val logo = if (ConfigStore.capsuleShowLogo()) loadLogo() else null
        val rendered = renderCapsuleInternal(out, meta, logo)
        if (rendered || logo == null) return rendered

        log("capsule render failed with logo '${ConfigStore.capsuleLogoPreset()}', retrying without it")
        return renderCapsuleInternal(out, meta, null)
    }

    private fun renderCapsuleInternal(out: Bitmap, meta: CapsuleRenderer.CaptureMeta, logo: Bitmap?): Boolean {
        val started = SystemClock.elapsedRealtime()
        val w = out.width
        val h = out.height
        val shortSide = minOf(w, h)
        val showLogo = logo != null
        val opts = CapsuleRenderer.Options(
            modelOverride = ConfigStore.capsuleModel(),
            position = ConfigStore.capsulePos(),
            showDateTime = ConfigStore.capsuleShowDatetime(),
            showExif = ConfigStore.capsuleShowExif(),
            showGps = ConfigStore.capsuleShowGps(),
            scale = ConfigStore.capsuleScale(),
            showLogo = showLogo,
            logo = logo,
            logoPreset = ConfigStore.capsuleLogoPreset(),
            logoPosition = ConfigStore.capsuleLogoPosition(),
        )
        val lines = CapsuleRenderer.buildLines(opts, meta, shortSide)
        if (lines.isEmpty()) return false
        val metrics = CapsuleRenderer.measureCapsule(w, shortSide, opts.scale, lines, opts) ?: return false
        val capsuleW = metrics.capsuleWidth
        val capsuleH = metrics.capsuleHeight
        if (capsuleW >= w || capsuleH >= h) return false

        val bottomMargin = (shortSide * 0.016f).coerceIn(4f, shortSide * 0.06f)
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
        recordGlassRender(out, meta)
        log("direct capsule: ${f(capsuleW)}x${f(capsuleH)} at ${f(capsuleLeft)},${f(capsuleTop)} " +
                "lines=${lines.size} ${SystemClock.elapsedRealtime() - started}ms -> $result")
        return true
    }

    private fun recordGlassRender(bitmap: Bitmap, meta: CapsuleRenderer.CaptureMeta) {
        try {
            synchronized(glassBitmaps) {
                glassBitmaps.put(bitmap, SystemClock.elapsedRealtime())
                if (glassBitmaps.size > 16) {
                    val iter = glassBitmaps.entries.iterator()
                    while (glassBitmaps.size > 16 && iter.hasNext()) {
                        iter.next()
                        iter.remove()
                    }
                }
            }
            lastCapture = MetaStamp(meta, SystemClock.elapsedRealtime())
        } catch (_: Throwable) {
        }
    }

    private fun verifierMeta(): CapsuleRenderer.CaptureMeta {
        val stamp = lastCapture
        if (stamp != null && SystemClock.elapsedRealtime() - stamp.at <= META_FRESH_MS) {
            return stamp.meta
        }
        return CapsuleRenderer.CaptureMeta(0L, null, null)
    }

    private fun jpegIdentity(bytes: ByteArray): Long {
        var h = 0xcbf29ce484222325UL
        var i = 0
        val step = if (bytes.size > 1 shl 20) 7 else 1
        while (i < bytes.size) {
            h = h xor (bytes[i].toULong() and 0xFFUL)
            h *= 0x100000001b3UL
            i += step
        }
        return h.toLong()
    }

    private fun registerGlassOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(glassEncodedOutputs) {
            glassEncodedOutputs.add(jpegIdentity(bytes))
            while (glassEncodedOutputs.size > 64) {
                glassEncodedOutputs.remove(glassEncodedOutputs.first())
            }
        }
    }

    private fun isRegisteredGlassOutput(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        synchronized(glassEncodedOutputs) { return glassEncodedOutputs.contains(jpegIdentity(bytes)) }
    }

    private fun startLateDiscovery(loader: ClassLoader) {
        val thread = Thread({
            val found = DexKitHelper.awaitDiscovery(DISCOVERY_WAIT_MS)
            if (!found) {
                log("late discovery: scan produced no usable names within ${DISCOVERY_WAIT_MS}ms; " +
                        "the literal hook targets remain the only ones installed")
                return@Thread
            }
            try {

                hookCaptureContext(loader)
                hookWatermarkBitmapFactory(loader)
                hookFinalJpegEncoder(loader)
                hookCloudWatermarkAttribute(loader)
                hookJpegLandingVerifier(loader)
                hookBitmapEncodeHelpers(loader)
                hookLiveRecorderSnapshots(loader)
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

    private fun installTypedPipelineHooks(loader: ClassLoader) {
        log("=== hook install begin ===")
        log("host=${hostFingerprint()}")
        hookCaptureContext(loader)
        hookWatermarkBitmapFactory(loader)
        hookFinalJpegEncoder(loader)
        hookCloudWatermarkAttribute(loader)
        hookJpegLandingVerifier(loader)
        hookBitmapEncodeHelpers(loader)
        hookLiveRecorderSnapshots(loader)

        val captureOk = hookedCaptureMethods.any {
            it.contains("(structural)") || it.contains("(la.a;ZI)")
        }
        val factoryOk = hookedCaptureMethods.any { it.contains("bitmapIdx=") }
        val finalGuardOk = hookedCaptureMethods.any { it.contains("(final-jpeg)") }
        val nativeOffOk = hookedCaptureMethods.any { it.contains("(native-wm-off)") }
        val verifierOk = hookedCaptureMethods.any { it.contains("(landing-verifier)") }
        val liveRecorderOk = hookedCaptureMethods.any { it.contains("(live-recorder)") }
        log(
            "=== hook install summary: capture=${if (captureOk) "OK" else "MISSING"} " +
                    "factory=${if (factoryOk) "OK" else "MISSING"} " +
                    "finalGuard=${if (finalGuardOk) "OK" else "MISSING"} " +
                    "nativeOff=${if (nativeOffOk) "OK" else "MISSING"} " +
                    "landingVerifier=${if (verifierOk) "OK" else "PENDING"} " +
                    "liveRecorder=${if (liveRecorderOk) "OK" else "PENDING"} " +
                    "total=${hookedCaptureMethods.size} ==="
        )
        when {
            captureOk && factoryOk -> log("status: full pipeline hooked (EXIF/GPS available)")
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

    private fun hostFingerprint(): String = try {
        val cls = Class.forName("android.os.Build")
        val model = cls.getField("MODEL").get(null) as? String ?: "?"
        val sdk = cls.getField("SDK_INT").get(null) as? Int ?: -1
        "$model/api$sdk"
    } catch (_: Throwable) { "unknown" }

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

                val out = unwrapBitmap(p.result) ?: run {
                    log("composite result is not a bitmap: ${p.result?.javaClass?.name}")
                    return
                }
                val frame = framesByBitmap.remove(out)

                GlassConfig.refresh()
                if (!GlassConfig.masterEnabled) return
                if (out.isRecycled) return
                if (!out.isMutable) {
                    log("skip: composite bitmap not mutable (${out.width}x${out.height})")
                    return
                }

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

    private fun hookBlitMethod(classLoader: ClassLoader, className: String, methodName: String) {
        val cls = findClass(className, classLoader) ?: run {
            log("hookBlit: class $className not found")
            return
        }

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

                    val dataField = receiver.javaClass.declaredFields.firstOrNull { f ->
                        f.name == "i" && f.type.name == "Fe.b"
                    }
                    if (dataField != null) {
                        dataField.isAccessible = true
                        val dataObj = dataField.get(receiver) ?: return

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

    private fun unwrapBitmap(result: Any?): Bitmap? {
        if (result == null) return null
        if (result is Bitmap) return result

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

    private fun absoluteRect(
        canvas: Canvas?, x0: Float, y0: Float, x1: Float, y1: Float,
    ): RectF {
        val local = RectF(x0, y0, x1, y1)
        if (canvas == null) return local
        return try {
            val out = RectF()
            val matrix = Matrix()

            @Suppress("DEPRECATION")
            canvas.getMatrix(matrix)
            matrix.mapRect(out, local)
            if (out.width() <= 0f || out.height() <= 0f) local else out
        } catch (t: Throwable) {
            log("absoluteRect matrix mapping failed: ${t.javaClass.simpleName}")
            local
        }
    }

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

    private fun shaderRadius(shader: Shader, width: Float, height: Float): Float {
        val shortSide = minOf(width, height)

        return (shortSide * 0.5f).coerceIn(0f, shortSide * 0.5f)
    }

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

        if (ConfigStore.capsuleEnabled()) {
            try {
                processCapsule(out, w, h, resolved, srcPhoto, System.currentTimeMillis(), thisObj, args)
            } catch (t: Throwable) {
                log("capsule failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            return
        }

        val photoBottom = detectPhotoBottom(out, w, h, resolved)
        val overlayPanel = if (photoBottom >= 0 && resolved.bottom > photoBottom) {

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

        val localPanel = PanelRect(
            left = overlayPanel.left - left,
            top = overlayPanel.top - top,
            width = overlayPanel.width,
            height = overlayPanel.height,
            cornerRadiusPx = overlayPanel.cornerRadiusPx,
        )

        val params = resolveParams(pixels, cw, ch, localPanel)

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

                sx = left; sy = top; sw = cw; sh = ch
            } else {

                val sxw = (cw.toLong() * srcPhoto.width / w).toInt().coerceAtLeast(1)
                val sxh = (ch.toLong() * srcPhoto.height / h).toInt().coerceAtLeast(1)
                sx = (left.toLong() * srcPhoto.width / w).toInt().coerceIn(0, srcPhoto.width - 1)
                sy = (top.toLong() * srcPhoto.height / h).toInt().coerceIn(0, srcPhoto.height - 1)
                sw = sxw.coerceAtMost(srcPhoto.width - sx)
                sh = sxh.coerceAtMost(srcPhoto.height - sy)
            }
            if (sw > 0 && sh > 0) {
                try {

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

                    photoLeft = 0
                    photoTop = 0
                    photoW = cw
                    photoH = ch
                } catch (t: Throwable) {
                    log("photo pixel read failed: ${t.javaClass.simpleName}: ${t.message}")

                }
            }
        }

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

            val s = w.toFloat() / srcPhoto.width.toFloat()
            val candidate = (srcPhoto.height.toFloat() * s).toInt()
            if (candidate in 8 until h) photoBottom = candidate
        }
        val anchorY = if (photoBottom >= 0) photoBottom.toFloat() else h.toFloat()

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
        val inset = (CapsuleRenderer.BOX_INSET_REF * metrics.unitPx)
            .coerceIn(4f, shortSide * 0.12f)

        val placementCap = anchorY

        val bottomMargin = (minOf(w, h) * 0.016f).coerceIn(4f, shortSide * 0.06f)
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

        val cropL = (capsule.left - padPx).toInt().coerceIn(0, w - 1)
        val cropT = (capsule.top - padPx).toInt().coerceIn(0, h - 1)
        val cropR = (capsule.right + padPx).toInt().coerceIn(cropL + 1, w)
        val cropB0 = (capsule.bottom + padPx).toInt().coerceIn(cropT + 1, h)

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

        val result = LiquidGlassOptics.renderPanel(
            pixels, cw2, ch2, localCapsule, params.copy(preserveContent = false),
            panelIsOpaquePlate = false,
        )
        if (result.startsWith("skip")) {
            log("capsule render: $result")
            return
        }

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

    private fun pickPanel(candidates: List<PanelRect>, w: Int, h: Int): PanelRect? {
        val eligible = candidates.filter { rect ->
            rect.width >= MIN_PANEL_WIDTH &&
                    rect.width >= w * MIN_PANEL_WIDTH_FRACTION &&
                    rect.width <= w * MAX_PANEL_WIDTH_FRACTION &&
                    plausible(rect, w, h)
        }
        if (eligible.isEmpty()) return null
        if (eligible.size == 1) return eligible[0]

        val left = eligible.minOf { it.left }
        val top = eligible.minOf { it.top }
        val right = eligible.maxOf { it.right }
        val bottom = eligible.maxOf { it.bottom }
        val merged = PanelRect(
            left = left,
            top = top,
            width = right - left,
            height = bottom - top,

            cornerRadiusPx = eligible.maxOf { it.cornerRadiusPx },
        )

        if (merged.width > w * MAX_PANEL_WIDTH_FRACTION ||
            merged.height > h * MAX_PANEL_HEIGHT_FRACTION
        ) {
            log("panel union too large (${f(merged.width)}x${f(merged.height)} in ${w}x$h); declining")
            return null
        }
        return merged
    }

    private fun plausible(rect: PanelRect, w: Int, h: Int): Boolean {
        if (rect.left < -2f || rect.top < -2f) return false
        if (rect.width > w || rect.height > h) return false
        if (rect.right > w + 2f || rect.bottom > h + 2f) return false

        return rect.height >= 6f && rect.height <= h * 0.6f
    }

    private fun resolveParams(pixels: IntArray, w: Int, h: Int, panel: PanelRect): GlassParams =
        com.mouya.LiquidFrame.glass.GlassSceneTuner.resolveParams(pixels, w, h, panel, GlassConfig.params())

    private fun scanPanel(out: Bitmap, w: Int, h: Int): PanelRect? {
        val step = maxOf(1, w / SCAN_WORK_WIDTH)
        val sw = w / step
        val sh = h / step
        if (sw < 8 || sh < 8) return null
        return try {

            val grid = IntArray(sw * sh)
            val row = IntArray(w)
            for (sy in 0 until sh) {
                val y = sy * step
                out.getPixels(row, 0, w, 0, y, w, 1)
                val base = sy * sw
                for (sx in 0 until sw) grid[base + sx] = row[sx * step]
            }
            val rect = PanelScan.find(grid, sw, sh) ?: return null

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

    private fun detectPhotoBottom(out: Bitmap, w: Int, h: Int, panel: PanelRect): Int {

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

            if (variance > 4.0 || mean < 240.0) {
                return y
            }
        }

        return -1
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun log(message: String) {
        LFLogger.d(message)
        XposedBridge.log("$TAG: $message")
    }
}
