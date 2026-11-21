package com.mouya.LiquidFrame

import android.content.Context
import android.content.pm.ApplicationInfo
import com.mouya.LiquidFrame.dex.DexScan
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

object DexKitHelper {

    private const val TAG = "LiquidFrame"

    private const val CONTEXT_WAIT_MS = 30_000L

    private var classLoader: ClassLoader? = null

    @Volatile
    private var scanResult: DexScan.Found? = null

    @Volatile
    private var scanStarted = false

    @Volatile
    private var canvasWrapperName: String? = null

    @Volatile
    private var compositeClassName: String? = null

    @Volatile
    private var captureRecordName: String? = null

    @Volatile
    private var capturePlan: DexScan.RecordPlan? = null

    @Volatile
    private var captureReceiverOwner: String? = null

    @Volatile
    private var captureReceiverMethod: String? = null

    @Volatile
    private var captureReceiverParams: Int? = null

    @Volatile
    private var dataInterfaceName: String? = null

    @Volatile
    private var byteDataImplName: String? = null

    @Volatile
    private var taskDataClassName: String? = null

    @Volatile
    private var taskJpegSetterName: String? = null

    @Volatile
    private var taskJpegFillerName: String? = null

    @Volatile
    private var encodeHelperNames: List<Pair<String, String>> = emptyList()

    @Volatile
    private var recorderSnapshotNames: List<Pair<String, String>> = emptyList()

    fun init(context: Context) {
        if (scanStarted) return
        scanStarted = true

        val thread = Thread({ runScan(context) }, "LiquidFrame-DexScan")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

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

            captureRecordName = pick(found.captureRecords)?.also { name ->
                capturePlan = found.recordClassesFirstPlan[name]
            }

            val receiver = found.captureReceivers.firstOrNull { candidate ->
                val loader = classLoader
                loader != null && load(candidate.owner, loader) != null
            }
            captureReceiverOwner = receiver?.owner
            captureReceiverMethod = receiver?.method
            captureReceiverParams = receiver?.paramCount

            val deep = found.deepTargets
            dataInterfaceName = deep.dataInterface
            byteDataImplName = deep.byteDataImpl
            taskDataClassName = deep.taskDataClass
            taskJpegSetterName = deep.taskJpegSetter?.method
            taskJpegFillerName = deep.taskJpegFiller?.method
            encodeHelperNames = deep.bitmapEncodeHelpers.map { it.owner to it.method }
            recorderSnapshotNames = deep.recorderSnapshots.map { it.owner to it.method }
            log(
                "deep targets: dataIface=${deep.dataInterface ?: "none"}" +
                        " byteImpl=${deep.byteDataImpl ?: "none"}" +
                        " task=${deep.taskDataClass ?: "none"}" +
                        " setter=${deep.taskJpegSetter?.method ?: "?"}" +
                        " filler=${deep.taskJpegFiller?.method ?: "?"}" +
                        " encoders=${deep.bitmapEncodeHelpers.size}" +
                        " recorders=${deep.recorderSnapshots.size}"
            )

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

    private fun pick(names: Collection<String>): String? {
        val loader = classLoader ?: return null
        for (name in names) {
            if (load(name, loader) != null) return name
        }
        return null
    }

    private fun load(name: String, loader: ClassLoader): Class<*>? = try {

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

    fun findCanvasWrapper(): Class<*>? {
        val loader = classLoader ?: return null
        val name = canvasWrapperName ?: return null
        return load(name, loader)
    }

    fun findCaptureRecord(): Class<*>? {
        val loader = classLoader ?: return null
        val name = captureRecordName ?: return null
        return load(name, loader)
    }

    fun captureRecordPlan(): DexScan.RecordPlan? = capturePlan

    fun findCaptureReceiver(loader: ClassLoader): Method? {

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

        android.graphics.Bitmap::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.any { android.graphics.Bitmap::class.java.isAssignableFrom(it) }
    }

    fun findDataInterface(loader: ClassLoader): Class<*>? = dataInterfaceName?.let { load(it, loader) }

    fun findByteDataImpl(loader: ClassLoader): Class<*>? = byteDataImplName?.let { load(it, loader) }

    fun findTaskDataClass(loader: ClassLoader): Class<*>? = taskDataClassName?.let { load(it, loader) }

    fun findTaskJpegSetter(loader: ClassLoader): Method? {
        val task = findTaskDataClass(loader) ?: return null
        val name = taskJpegSetterName ?: return null
        val iface = findDataInterface(loader) ?: return null
        return task.declaredMethods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == 1 && m.parameterTypes[0] == iface
        }
    }

    fun findTaskJpegFiller(loader: ClassLoader): Method? {
        val task = findTaskDataClass(loader) ?: return null
        val name = taskJpegFillerName ?: return null
        val iface = findDataInterface(loader) ?: return null
        return task.declaredMethods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == iface && m.parameterTypes[1] == Int::class.javaPrimitiveType
        }
    }

    fun findBitmapEncodeHelpers(loader: ClassLoader): List<Method> {
        val iface = findDataInterface(loader) ?: return emptyList()
        val out = ArrayList<Method>(encodeHelperNames.size)
        for ((owner, name) in encodeHelperNames) {
            val cls = load(owner, loader) ?: continue
            val intType = Int::class.javaPrimitiveType ?: continue
            cls.declaredMethods.firstOrNull { m ->
                m.name == name && m.returnType == iface &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[0] == intType &&
                        android.graphics.Bitmap::class.java.isAssignableFrom(m.parameterTypes[1])
            }?.let { out.add(it) }
        }
        return out
    }

    fun findRecorderSnapshots(loader: ClassLoader): List<Method> {
        val out = ArrayList<Method>(recorderSnapshotNames.size)
        for ((owner, name) in recorderSnapshotNames) {
            val cls = load(owner, loader) ?: continue
            cls.declaredMethods.firstOrNull { m ->
                m.name == name && m.parameterTypes.size == 9 &&
                        m.parameterTypes[6] == java.lang.String::class.java
            }?.let { out.add(it) }
        }
        return out
    }

    private fun log(message: String) {
        XposedBridge.log("$TAG: $message")
        LogHelper.log(TAG, message)
    }
}
