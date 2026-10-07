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
    } catch (_: Throwable) {
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
            if (canvasWrapperName != null || compositeClassName != null) return true
            if (scanResult != null) return false
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return canvasWrapperName != null || compositeClassName != null
    }

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

            log("resolved wrapper=${canvasWrapperName ?: "none"} composite=${compositeClassName ?: "none"}")
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
        Class.forName(name, false, loader)
    } catch (_: Throwable) {
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
        } catch (_: Throwable) {
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
        android.graphics.Bitmap::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.any { android.graphics.Bitmap::class.java.isAssignableFrom(it) }
    }

    private fun log(message: String) {
        XposedBridge.log("$TAG: $message")
        LogHelper.log(TAG, message)
    }
}