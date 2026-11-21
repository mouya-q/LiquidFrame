package com.mouya.LiquidFrame.dex

import java.util.zip.ZipFile

class DexMethodRef(
    val name: String,
    val params: List<String>,
    val returnType: String,
    val isStatic: Boolean,
) {
    val isConstructor: Boolean get() = name == "<init>"

    val signature: String
        get() = name + "(" + params.joinToString("") + ")" + returnType

    fun matches(paramTypes: List<String>, ret: String): Boolean =
        params == paramTypes && returnType == ret

    override fun toString(): String = signature
}

class DexClass(
    val name: String,
    val superName: String?,
    val methods: List<DexMethodRef>,
    val fieldTypes: List<String>,
    val instanceFieldTypes: List<String> = fieldTypes,
)

class DexFile(private val data: ByteArray) {

    private val stringCache = HashMap<Int, String>(1024)

    private val stringIdsOff get() = u4(0x3C)
    private val typeIdsOff get() = u4(0x44)
    private val protoIdsOff get() = u4(0x4C)
    private val fieldIdsOff get() = u4(0x54)
    private val methodIdsOff get() = u4(0x5C)
    private val classDefsOff get() = u4(0x64)
    private val stringCount get() = u4(0x38)
    private val typeCount get() = u4(0x40)
    private val methodCount get() = u4(0x58)
    private val classCount get() = u4(0x60)

    fun isDex(): Boolean {
        if (data.size < 0x70) return false
        return data[0] == 'd'.code.toByte() && data[1] == 'e'.code.toByte() &&
                data[2] == 'x'.code.toByte() && data[3] == '\n'.code.toByte()
    }

    fun classes(): List<DexClass> {
        if (!isDex()) return emptyList()
        val count = classCount
        val off = classDefsOff
        if (count <= 0 || off <= 0 || off >= data.size) return emptyList()
        val out = ArrayList<DexClass>(minOf(count, 8192))
        for (i in 0 until count) {
            val base = off + i * CLASS_DEF_ITEM
            if (base + CLASS_DEF_ITEM > data.size) break
            val classIdx = u4(base)
            val superIdx = u4(base + 8)
            val classDataOff = u4(base + 24)
            if (classIdx >= typeCount) continue
            val methods = ArrayList<DexMethodRef>(8)
            val fields = ArrayList<String>(4)
            var instanceOnly: List<String> = emptyList()
            if (classDataOff != 0 && classDataOff < data.size) {
                instanceOnly = readClassData(classDataOff, methods, fields)
            }
            out.add(
                DexClass(
                    name = typeName(classIdx),
                    superName = if (superIdx == NO_INDEX || superIdx >= typeCount) null else typeName(superIdx),
                    methods = methods,
                    fieldTypes = fields,
                    instanceFieldTypes = instanceOnly,
                )
            )
        }
        return out
    }

    // DEX member indices are delta-encoded.
    private fun readClassData(
        off: Int,
        methods: MutableList<DexMethodRef>,
        fields: MutableList<String>,
    ): List<String> {
        val c = Cursor(off)
        val staticFields = uleb(c)
        val instanceFields = uleb(c)
        val directMethods = uleb(c)
        val virtualMethods = uleb(c)

        var fieldIdx = 0
        for (i in 0 until staticFields) {
            fieldIdx += uleb(c)
            uleb(c)
        }
        fieldIdx = 0
        val instanceTypes = ArrayList<String>(instanceFields)
        for (i in 0 until instanceFields) {
            fieldIdx += uleb(c)
            uleb(c)
            val t = fieldTypeName(fieldIdx)
            if (t != null) {
                fields.add(t)
                instanceTypes.add(t)
            }
        }

        var methodIdx = 0
        for (i in 0 until directMethods) {
            methodIdx += uleb(c)
            val access = uleb(c)
            uleb(c)
            methodRef(methodIdx, access)?.let { methods.add(it) }
        }
        methodIdx = 0
        for (i in 0 until virtualMethods) {
            methodIdx += uleb(c)
            val access = uleb(c)
            uleb(c)
            methodRef(methodIdx, access)?.let { methods.add(it) }
        }
        return instanceTypes
    }

    private fun methodRef(idx: Int, access: Int): DexMethodRef? {
        if (idx < 0 || idx >= methodCount) return null
        val base = methodIdsOff + idx * METHOD_ID_ITEM
        if (base + METHOD_ID_ITEM > data.size) return null
        val protoIdx = u2(base + 2)
        val nameIdx = u4(base + 4)
        val name = string(nameIdx) ?: return null
        val protoBase = protoIdsOff + protoIdx * PROTO_ID_ITEM
        if (protoBase + PROTO_ID_ITEM > data.size) return null
        val returnTypeIdx = u4(protoBase + 4)
        val paramsOff = u4(protoBase + 8)
        val returnType = if (returnTypeIdx < typeCount) typeName(returnTypeIdx) else "V"
        val params = readTypeList(paramsOff)
        return DexMethodRef(
            name = name,
            params = params,
            returnType = returnType,
            isStatic = (access and ACC_STATIC) != 0,
        )
    }

    private fun fieldTypeName(idx: Int): String? {
        val base = fieldIdsOff + idx * FIELD_ID_ITEM
        if (base + FIELD_ID_ITEM > data.size) return null
        val typeIdx = u2(base + 2)
        return if (typeIdx < typeCount) typeName(typeIdx) else null
    }

    private fun readTypeList(off: Int): List<String> {
        if (off == 0 || off + 4 > data.size) return emptyList()
        val size = u4(off)
        if (size <= 0 || size > 255) return emptyList()
        val out = ArrayList<String>(size)
        for (i in 0 until size) {
            val p = off + 4 + i * 2
            if (p + 2 > data.size) break
            val typeIdx = u2(p)
            out.add(if (typeIdx < typeCount) typeName(typeIdx) else "?")
        }
        return out
    }

    private fun typeName(typeIdx: Int): String {
        val p = typeIdsOff + typeIdx * 4
        if (p + 4 > data.size) return "?"
        return string(u4(p)) ?: "?"
    }

    private fun string(idx: Int): String? {
        stringCache[idx]?.let { return it }
        if (idx < 0 || idx >= stringCount) return null
        val p = stringIdsOff + idx * 4
        if (p + 4 > data.size) return null
        val off = u4(p)
        if (off <= 0 || off >= data.size) return null
        val s = readString(off) ?: return null
        stringCache[idx] = s
        return s
    }

    private fun readString(off: Int): String? {
        val c = Cursor(off)
        uleb(c)
        val sb = StringBuilder(32)
        var p = c.p
        while (p < data.size) {
            val b = data[p++].toInt() and 0xFF
            when {
                b == 0 -> return sb.toString()
                b < 0x80 -> sb.append(b.toChar())
                b and 0xE0 == 0xC0 -> {
                    if (p >= data.size) return sb.toString()
                    val b2 = data[p++].toInt() and 0xFF
                    sb.append((((b and 0x1F) shl 6) or (b2 and 0x3F)).toChar())
                }
                else -> {
                    if (p + 1 >= data.size) return sb.toString()
                    val b2 = data[p++].toInt() and 0xFF
                    val b3 = data[p++].toInt() and 0xFF
                    sb.append((((b and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)).toChar())
                }
            }
        }
        return sb.toString()
    }

    private fun uleb(c: Cursor): Int {
        var result = 0
        var shift = 0
        while (c.p < data.size) {
            val b = data[c.p++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) break
        }
        return result
    }

    private fun u2(p: Int): Int =
        (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)

    private fun u4(p: Int): Int =
        (data[p].toInt() and 0xFF) or
                ((data[p + 1].toInt() and 0xFF) shl 8) or
                ((data[p + 2].toInt() and 0xFF) shl 16) or
                ((data[p + 3].toInt() and 0xFF) shl 24)

    private class Cursor(var p: Int)

    private companion object {
        const val CLASS_DEF_ITEM = 32
        const val METHOD_ID_ITEM = 8
        const val PROTO_ID_ITEM = 12
        const val FIELD_ID_ITEM = 8
        const val ACC_STATIC = 0x8
        const val NO_INDEX = -1
    }
}

object DexScan {

    const val BITMAP = "Landroid/graphics/Bitmap;"
    const val CANVAS = "Landroid/graphics/Canvas;"
    const val PAINT = "Landroid/graphics/Paint;"
    const val COLOR_SPACE = "Landroid/graphics/ColorSpace;"
    const val STRING = "Ljava/lang/String;"
    const val VOID = "V"
    const val INT = "I"
    const val FLOAT = "F"
    const val BOOLEAN = "Z"
    const val LONG = "J"
    const val SHORT = "S"

    val DRAW_RECT_PARAMS = listOf(FLOAT, FLOAT, FLOAT, FLOAT, PAINT)

    val COMPOSITE_PARAMS_67 = listOf(BITMAP, COLOR_SPACE, INT, INT, STRING, INT)

    val COMPOSITE_PARAMS_62 = listOf(BITMAP, COLOR_SPACE, INT)

    class Found {

        val canvasWrappers = LinkedHashSet<String>()

        val compositeClasses = LinkedHashSet<String>()

        val captureRecords = LinkedHashSet<String>()

        val captureRecordTypes = LinkedHashMap<String, String>()

        val recordClassesFirstPlan = LinkedHashMap<String, RecordPlan>()

        val captureReceivers = ArrayList<CaptureReceiver>()

        val dataInterfaceCandidates = LinkedHashSet<String>()

        val byteDataImpls = LinkedHashSet<String>()

        val deepTargets = DeepTargets()

        fun addReceiver(receiver: CaptureReceiver) {
            if (captureReceivers.none { it.owner == receiver.owner && it.method == receiver.method }) {
                captureReceivers.add(receiver)
            }
        }
    }

    class DeepTargets {
        var dataInterface: String? = null
        var byteDataImpl: String? = null
        var taskDataClass: String? = null
        var taskJpegSetter: MethodTarget? = null
        var taskJpegFiller: MethodTarget? = null
        val bitmapEncodeHelpers = ArrayList<MethodTarget>()
        val recorderSnapshots = ArrayList<MethodTarget>()

        fun isResolvable(): Boolean = dataInterface != null
    }

    data class MethodTarget(
        val owner: String,
        val method: String,
    )

    val CAPTURE_RUN = listOf("S", "F", "J", "I")

    fun scan(archives: List<String>): Found {
        val found = Found()
        for (archive in archives) {
            if (found.canvasWrappers.isNotEmpty() && found.compositeClasses.isNotEmpty()) break
            try {
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().asSequence()
                        .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                        .sortedBy { it.name }
                        .toList()
                    for (entry in entries) {
                        val bytes = try {
                            zip.getInputStream(entry).use { it.readBytes() }
                        } catch (t: Throwable) {
                            android.util.Log.w("LiquidFrame", "DexScanner: failed to read ${entry.name}: ${t.javaClass.simpleName}: ${t.message}")
                            continue
                        }
                        scanDex(bytes, found)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("LiquidFrame", "DexScanner: archive $archive unreadable: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        matchCaptureReceivers(archives, found)
        matchDeepTargets(archives, found)
        return found
    }

    private fun matchDeepTargets(archives: List<String>, found: Found) {
        val ifaceCandidates = found.dataInterfaceCandidates
        if (ifaceCandidates.isEmpty()) return
        for (archive in archives) {
            try {
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().asSequence()
                        .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                        .sortedBy { it.name }
                        .toList()
                    for (entry in entries) {
                        val bytes = try {
                            zip.getInputStream(entry).use { it.readBytes() }
                        } catch (t: Throwable) {
                            continue
                        }
                        val dex = DexFile(bytes)
                        if (!dex.isDex()) continue
                        for (cls in dex.classes()) {
                            val hasRemoverParam = cls.methods.any { m ->
                                m.params.any { it.contains("watermark/WatermarkRemover$") }
                            }
                            if (hasRemoverParam && found.deepTargets.taskDataClass == null) {
                                for (iface in ifaceCandidates) {
                                    val setter = cls.methods.firstOrNull { m ->
                                        m.returnType == VOID && m.params == listOf(iface)
                                    }
                                    val filler = cls.methods.firstOrNull { m ->
                                        m.returnType == VOID && m.params == listOf(iface, INT)
                                    }
                                    if (setter != null && filler != null) {
                                        found.deepTargets.dataInterface = iface
                                        found.deepTargets.taskDataClass = cls.name
                                        found.deepTargets.taskJpegSetter = MethodTarget(cls.name, setter.name)
                                        found.deepTargets.taskJpegFiller = MethodTarget(cls.name, filler.name)
                                        break
                                    }
                                }
                            }

                            for (m in cls.methods) {
                                if (m.returnType !in ifaceCandidates) continue
                                if (m.params != listOf(INT, BITMAP)) continue
                                found.deepTargets.bitmapEncodeHelpers.add(MethodTarget(cls.name, m.name))
                            }

                            for (m in cls.methods) {
                                if (m.params.size != 9) continue
                                if (m.params[2] != "Ljava/lang/Object;") continue
                                if (m.params[6] != STRING) continue
                                if (m.returnType != BOOLEAN) continue
                                if (found.deepTargets.recorderSnapshots.none {
                                        it.owner == cls.name && it.method == m.name
                                    }) {
                                    found.deepTargets.recorderSnapshots.add(MethodTarget(cls.name, m.name))
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("LiquidFrame", "DexScanner: deep pass failed on $archive: ${t.javaClass.simpleName}")
            }
        }

        found.deepTargets.byteDataImpl = found.byteDataImpls.firstOrNull()

    }

    private fun matchCaptureReceivers(archives: List<String>, found: Found) {
        if (found.captureRecords.isEmpty()) return
        val recordTypes = found.captureRecordTypes.values.toHashSet()
        for (archive in archives) {
            if (found.captureReceivers.isNotEmpty()) break
            try {
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().asSequence()
                        .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                        .sortedBy { it.name }
                        .toList()
                    for (entry in entries) {
                        val bytes = try {
                            zip.getInputStream(entry).use { it.readBytes() }
                        } catch (t: Throwable) {
                            continue
                        }
                        val dex = DexFile(bytes)
                        if (!dex.isDex()) continue
                        for (cls in dex.classes()) {
                            for (m in cls.methods) {
                                if (m.params.size != 3) continue
                                if (m.params[0] !in recordTypes) continue
                                if (m.params[1] != BOOLEAN || m.params[2] != INT) continue
                                found.addReceiver(
                                    CaptureReceiver(cls.name, m.name, 3)
                                )
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("LiquidFrame", "DexScanner: receiver pass failed on $archive: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun scanDex(bytes: ByteArray, found: Found) {
        val dex = DexFile(bytes)
        if (!dex.isDex()) return
        for (cls in dex.classes()) {
            if (cls.name in found.canvasWrappers || cls.name in found.compositeClasses) continue
            if (isCanvasWrapper(cls)) found.canvasWrappers.add(cls.name)
            if (hasComposite(cls)) found.compositeClasses.add(cls.name)
            if (isCaptureRecord(cls)) {
                found.captureRecords.add(cls.name)

                found.captureRecordTypes[cls.name] = cls.name
                recordPlan(cls.instanceFieldTypes)?.let { found.recordClassesFirstPlan[cls.name] = it }
            }
            if (isDataInterfaceCandidate(cls)) found.dataInterfaceCandidates.add(cls.name)
            if (isByteDataImpl(cls)) found.byteDataImpls.add(cls.name)
        }
    }

    private fun isDataInterfaceCandidate(cls: DexClass): Boolean {
        val zeroArgReturns = cls.methods.asSequence()
            .filter { it.params.isEmpty() }
            .map { it.returnType }
            .toSet()
        if (!setOf("Z", INPUT_STREAM, BYTE_ARRAY, INT, VOID).all { it in zeroArgReturns }) return false

        if (cls.methods.any { it.isConstructor && it.params == listOf(BYTE_ARRAY) } &&
            cls.instanceFieldTypes.contains(BYTE_ARRAY)
        ) return false
        return true
    }

    private fun isByteDataImpl(cls: DexClass): Boolean {
        val hasBCtor = cls.methods.any { it.isConstructor && it.params == listOf(BYTE_ARRAY) }
        if (!hasBCtor || !cls.instanceFieldTypes.contains(BYTE_ARRAY)) return false
        return cls.methods.any { it.params.isEmpty() && it.returnType == INPUT_STREAM }
    }

    private fun isCanvasWrapper(cls: DexClass): Boolean {
        var hasBitmapCtor = false
        var hasDrawRect = false
        for (m in cls.methods) {
            if (m.isConstructor && m.params.size == 1 && m.params[0] == BITMAP) hasBitmapCtor = true
            if (m.matches(DRAW_RECT_PARAMS, VOID)) hasDrawRect = true
        }
        return hasBitmapCtor && hasDrawRect && cls.fieldTypes.contains(CANVAS)
    }

    private fun hasComposite(cls: DexClass): Boolean =
        cls.methods.any { m ->
            BITMAP in m.params &&
            (m.returnType == BITMAP || m.returnType.contains("WatermarkRemover"))
        }

    private fun isCaptureRecord(cls: DexClass): Boolean =
        recordPlan(cls.instanceFieldTypes) != null

    fun recordPlan(instanceTypes: List<String>): RecordPlan? {
        if (instanceTypes.isEmpty() || instanceTypes.size > MAX_RECORD_FIELDS) return null
        for (anchor in 0..instanceTypes.size - CAPTURE_RUN.size) {
            if (instanceTypes.subList(anchor, anchor + CAPTURE_RUN.size) != CAPTURE_RUN) continue
            var timeIdx = -1
            var locIdx = -1
            for (j in anchor + CAPTURE_RUN.size until instanceTypes.size) {
                if (timeIdx < 0 && instanceTypes[j] == "J") timeIdx = j
                if (locIdx < 0 && instanceTypes[j] == LOCATION) { locIdx = j; break }
            }
            if (timeIdx >= 0 && locIdx >= 0) {
                return RecordPlan(
                    focal = anchor,
                    aperture = anchor + 1,
                    exposure = anchor + 2,
                    iso = anchor + 3,
                    time = timeIdx,
                    location = locIdx,
                )
            }
            return null
        }
        return null
    }

    data class RecordPlan(
        val focal: Int,
        val aperture: Int,
        val exposure: Int,
        val iso: Int,
        val time: Int,
        val location: Int,
    )

    data class CaptureReceiver(
        val owner: String,
        val method: String,
        val paramCount: Int,
    )

    private const val LOCATION = "Landroid/location/Location;"
    private const val MAX_RECORD_FIELDS = 40
    private const val INPUT_STREAM = "Ljava/io/InputStream;"
    private const val BYTE_ARRAY = "[B"
}
