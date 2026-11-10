package com.mouya.LiquidFrame

import android.content.Context
import android.content.pm.ApplicationInfo
import com.mouya.LiquidFrame.dex.DexScan
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * Discovers the obfuscated watermark classes inside the host (camera) app.
 *
 * DexKit was dropped because its Kotlin metadata could not be resolved by AGP 9's built-in Kotlin
 * support, which left the module with hardcoded names (`pe.o`, `Fe.a->j`) that only exist in one
 * build of `com.android.camera`. [DexScan] replaces it with a dependency-free structural scan of
 * the host APK's own dex files, so the hook survives camera updates.
 *
 * Discovery is a hint, never a requirement: every lookup returns null on failure and
 * [WatermarkHooks] falls back to the known names.
 */
object DexKitHelper {

    private const val TAG = "LiquidFrame"

    /** How long [initDeferred] waits for the host Application to exist, in ms. */
    private const val CONTEXT_WAIT_MS = 30_000L

    private var classLoader: ClassLoader? = null

    /** Populated by [init] on a background thread; read by the lookups once ready. */
    @Volatile
    private var scanResult: DexScan.Found? = null

    @Volatile
    private var scanStarted = false

    /** Names verified to exist in the running process, best candidate first. */
    @Volatile
    private var canvasWrapperName: String? = null

    @Volatile
    private var compositeClassName: String? = null

    /**
     * The capture-metadata record resolved by structure (see [DexScan.recordPlan]), plus the
     * field indices inside it.
     *
     * This replaces the old hardcoded `la.a` + field letters `f/g/h/i/j/k`. Those letters are
     * per-build: 6.7 moved the capture time from `j` to `l` and the Location from `k` to `m`
     * while keeping every type in the same order, so reading by index is the only mapping that
     * survives an update.
     */
    @Volatile
    private var captureRecordName: String? = null

    @Volatile
    private var capturePlan: DexScan.RecordPlan? = null

    /** Declaring class of the record-consuming method, as a DEX descriptor (`LW8/d;`). */
    @Volatile
    private var captureReceiverOwner: String? = null

    /** Method name of the record-consuming method (`f` on 6.7, `h` on 6.4). */
    @Volatile
    private var captureReceiverMethod: String? = null

    /** Parameter count of the record-consuming method (3 on both known builds). */
    @Volatile
    private var captureReceiverParams: Int? = null

    fun init(context: Context) {
        if (scanStarted) return
        scanStarted = true

        // Dex parsing touches hundreds of megabytes of APK, so it must never run on the main
        // thread: the camera is starting up and an ANR here would be fatal for the shot.
        val thread = Thread({ runScan(context) }, "LiquidFrame-DexScan")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    /**
     * Same as [init], but for the (usual) case where `handleLoadPackage` runs before the host
     * Application exists. Waits for `ActivityThread.currentApplication()` to appear, then scans.
     */
    fun initDeferred() {
        if (scanStarted) return
        scanStarted = true

        val thread = Thread({
            val context = awaitApplication(CONTEXT_WAIT_MS)
            if (context == null) {
                log("no application context; using fallback hook names")
                scanResult = DexScan.Found()
                return@Thread
            }
            runScan(context)
        }, "LiquidFrame-DexScan")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    private fun awaitApplication(timeoutMs: Long): Context? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentApplication()?.let { return it }
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return currentApplication()
    }

    private fun currentApplication(): Context? = try {
        XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", classLoader),
            "currentApplication"
        ) as? Context
    } catch (t: Throwable) {
        log("currentApplication failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    fun setClassLoader(loader: ClassLoader) {
        classLoader = loader
    }

    /**
     * Block briefly for the scan to land. [WatermarkHooks] installs its hooks during
     * `handleLoadPackage`, which is far too early for a full APK scan, so this waits at most
     * [timeoutMs] and otherwise lets the caller proceed with the fallback path.
     */
    fun awaitDiscovery(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasAnything()) return true
            if (scanResult != null) return hasAnything()
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return hasAnything()
    }

    /**
     * True when the scan produced at least one name worth re-running the hooks with.
     *
     * The capture record counts on its own: on a build whose watermark-factory class is not the
     * literal `b` (or whose canvas wrapper shape changed) the record is still the thing that
     * turns the EXIF line on, and returning false here would skip the retry entirely.
     */
    private fun hasAnything(): Boolean =
        canvasWrapperName != null || compositeClassName != null || captureRecordName != null

    private fun runScan(context: Context) {
        try {
            val apks = apkPaths(context)
            if (apks.isEmpty()) {
                log("no apk paths; using fallback hook names")
                scanResult = DexScan.Found()
                return
            }
            val started = System.currentTimeMillis()
            val found = DexScan.scan(apks)
            scanResult = found
            log(
                "dex scan: ${apks.size} apk(s), " +
                        "${found.canvasWrappers.size} wrapper candidate(s), " +
                        "${found.compositeClasses.size} composite candidate(s) in " +
                        "${System.currentTimeMillis() - started}ms"
            )

            canvasWrapperName = pick(found.canvasWrappers)
            compositeClassName = pick(found.compositeClasses)

            // The record must actually load in this process: the DEX may be a split that is not
            // installed for this device ABI. Resolving it here also fixes the plan indexes.
            captureRecordName = pick(found.captureRecords)?.also { name ->
                capturePlan = found.recordClassesFirstPlan[name]
            }

            // Prefer a receiver whose owner actually loads; a candidate that lives in an
            // uninstalled split would only fail later, inside the camera's capture path.
            val receiver = found.captureReceivers.firstOrNull { candidate ->
                val loader = classLoader
                loader != null && load(candidate.owner, loader) != null
            }
            captureReceiverOwner = receiver?.owner
            captureReceiverMethod = receiver?.method
            captureReceiverParams = receiver?.paramCount

            val receiverLabel = if (receiver != null) {
                receiver.owner + "#" + receiver.method
            } else {
                "none"
            }
            val planLabel = capturePlan?.let { p ->
                "focal=" + p.focal + ",aperture=" + p.aperture +
                        ",exposure=" + p.exposure + ",iso=" + p.iso +
                        ",time=" + p.time + ",loc=" + p.location
            } ?: "none"
            log(
                "resolved wrapper=" + (canvasWrapperName ?: "none") +
                        " composite=" + (compositeClassName ?: "none") +
                        " captureRecord=" + (captureRecordName ?: "none") +
                        " receiver=" + receiverLabel +
                        " plan=" + planLabel
            )
        } catch (t: Throwable) {
            log("dex scan failed: ${t.javaClass.simpleName}: ${t.message}")
            scanResult = DexScan.Found()
        }
    }

    /** Candidate classes are only accepted once the class actually loads in the host process. */
    private fun pick(names: Collection<String>): String? {
        val loader = classLoader ?: return null
        for (name in names) {
            if (load(name, loader) != null) return name
        }
        return null
    }

    private fun load(name: String, loader: ClassLoader): Class<*>? = try {
        // DexScan returns DEX type descriptors (Lpe/o;), but Class.forName
        // needs the Java binary name (pe.o). Strip the L prefix and ; suffix.
        val javaName = if (name.startsWith("L") && name.endsWith(";") && name.length > 2) {
            name.substring(1, name.length - 1).replace('/', '.')
        } else {
            name
        }
        Class.forName(javaName, false, loader)
    } catch (t: Throwable) {
        log("class not found: $name: ${t.javaClass.simpleName}")
        null
    }

    /** The host app's own code: base APK plus every split. */
    private fun apkPaths(context: Context): List<String> {
        val info: ApplicationInfo = context.applicationInfo
        val out = LinkedHashSet<String>()
        info.sourceDir?.let { out.add(it) }
        info.publicSourceDir?.let { out.add(it) }
        try {
            info.splitSourceDirs?.forEach { it?.let(out::add) }
            info.splitPublicSourceDirs?.forEach { it?.let(out::add) }
        } catch (t: Throwable) {
            log("split apk paths failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return out.filter { it.isNotEmpty() }.toList()
    }

    // ---------------------------------------------------------------------------------------
    // Lookups used by WatermarkHooks
    // ---------------------------------------------------------------------------------------

    fun findCanvasWrapper(): Class<*>? {
        val loader = classLoader ?: return null
        val name = canvasWrapperName ?: return null
        return load(name, loader)
    }

    /**
     * The structurally discovered capture-metadata record, or null when the scan has not
     * finished, found nothing, or the class does not load in this process.
     */
    fun findCaptureRecord(): Class<*>? {
        val loader = classLoader ?: return null
        val name = captureRecordName ?: return null
        return load(name, loader)
    }

    /** Field indices inside [findCaptureRecord], or null before discovery completes. */
    fun captureRecordPlan(): DexScan.RecordPlan? = capturePlan

    /**
     * Finds the method that consumes the capture record, i.e. `f(<record>, Z, I)`.
     *
     * 6.4 declared this as `E5/b.h(la/a, Z, I) -> la/f`; 6.7 declares it as
     * `W8/d.f(Ki/a, Z, I) -> Ki/f`. The return type is a per-build wrapper, so it is not
     * constrained — the parameter shape plus "first parameter is the record" is enough, and
     * the caller verifies that only one candidate exists.
     */
    fun findCaptureReceiver(loader: ClassLoader): Method? {
        // Preferred path: the DEX scan already located the declaring class and method name, so
        // nothing has to be enumerated at runtime (walking every loaded class is far too costly
        // inside a camera process, and reflection on unloaded classes would trigger class init).
        val owner = captureReceiverOwner
        val methodName = captureReceiverMethod
        val paramCount = captureReceiverParams
        if (owner != null && methodName != null && paramCount != null) {
            val cls = load(owner, loader)
            if (cls != null) {
                val record = findCaptureRecord()
                cls.declaredMethods.firstOrNull { m ->
                    m.name == methodName &&
                            m.parameterTypes.size == paramCount &&
                            (record == null || m.parameterTypes[0] == record)
                }?.let { return it }
            }
        }

        // Fallback: the record's own declared methods. Some builds put the encoder beside it.
        val record = findCaptureRecord() ?: return null
        val boolType = Boolean::class.javaPrimitiveType ?: return null
        val intType = Int::class.javaPrimitiveType ?: return null
        return record.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 3 &&
                    m.parameterTypes[0] == record &&
                    m.parameterTypes[1] == boolType &&
                    m.parameterTypes[2] == intType
        }
    }

    fun findCompositeMethod(loader: ClassLoader): Method? {
        val name = compositeClassName ?: return null
        val cls = load(name, loader) ?: return null
        return findCompositeMethodIn(cls)
    }

    fun findDrawRectMethod(canvasWrapperClass: Class<*>): Method? {
        val floatType = Float::class.javaPrimitiveType ?: return null
        return canvasWrapperClass.declaredMethods.firstOrNull { method ->
            method.parameterTypes.size == 5 &&
                    method.parameterTypes[0] == floatType &&
                    method.parameterTypes[1] == floatType &&
                    method.parameterTypes[2] == floatType &&
                    method.parameterTypes[3] == floatType &&
                    android.graphics.Paint::class.java.isAssignableFrom(method.parameterTypes[4])
        }
    }

    private fun findCompositeMethodIn(cls: Class<*>): Method? = cls.declaredMethods.firstOrNull { method ->
        // Flexible: match any method returning Bitmap that takes a Bitmap parameter.
        // This works for both 6.4.x (static) and 6.7.x (instance) watermark factories.
        android.graphics.Bitmap::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.any { android.graphics.Bitmap::class.java.isAssignableFrom(it) }
    }

    private fun log(message: String) {
        XposedBridge.log("$TAG: $message")
        LogHelper.log(TAG, message)
    }
}