package com.mouya.LiquidFrame.dex

import java.util.zip.ZipFile

/**
 * A declared method as it appears in a DEX file.
 *
 * [params] and [returnType] are DEX type descriptors (`Landroid/graphics/Bitmap;`, `F`, `V`, ...),
 * so a full signature is `name(paramTypes)returnType` — the same shape `dexdump` prints.
 */
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

/**
 * One `class_def_item` plus the members listed in its `class_data_item`.
 *
 * [instanceFieldTypes] keeps only non-static fields **in declared order**, which is what the
 * capture-record matcher depends on: it looks for the contiguous type run `S,F,J,I` and then
 * addresses the fields by that index. Obfuscators rename fields freely between camera builds
 * (6.4 used `f,g,h,i,j,k`; 6.7 uses `f,g,h,i,l,m`) but the field *order and types* survive,
 * so index-based reading is the portable access path.
 */
class DexClass(
    val name: String,
    val superName: String?,
    val methods: List<DexMethodRef>,
    val fieldTypes: List<String>,
    val instanceFieldTypes: List<String> = fieldTypes,
)

/**
 * A minimal DEX reader.
 *
 * DexKit was removed because its Kotlin metadata could not be resolved by AGP 9's built-in Kotlin
 * support, and with it went the ability to discover obfuscated camera classes — the module fell
 * back to names (`pe.o`, `Fe.a->j`) that only exist in one build of `com.android.camera`.
 *
 * This reader parses just enough of the format to recover structure (class name, superclass,
 * declared method signatures, declared field types), which is all the discovery heuristics need.
 * It is pure JVM code with no dependencies, so it cannot break the build again.
 *
 * Reference: https://source.android.com/docs/core/runtime/dex-format
 */
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

    // ---------------------------------------------------------------------------------------
    // class_data_item
    // ---------------------------------------------------------------------------------------

    /**
     * Parses a `class_data_item`.
     *
     * Field and method indices are stored as **deltas** and must be accumulated
     * (`idx += diff`); assigning the raw delta yields plausible-looking but wrong members for
     * every class after the first. Returns the non-static field types in declared order.
     */
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
            uleb(c) // access flags
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
            uleb(c) // code_off
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

    // ---------------------------------------------------------------------------------------
    // primitives
    // ---------------------------------------------------------------------------------------

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

    /** `string_data_item`: uleb128 utf16 length, then modified UTF-8, then a NUL. */
    private fun readString(off: Int): String? {
        val c = Cursor(off)
        uleb(c) // utf16 length, unused
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

/** Structural shapes the watermark pipeline is recognised by. */
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

    /** `Lpe/o;`-style wrapper: new(Bitmap), holds a Canvas, draws rects with a Paint. */
    val DRAW_RECT_PARAMS = listOf(FLOAT, FLOAT, FLOAT, FLOAT, PAINT)

    /**
     * `LFe/a;->j`-style composite (6.7.x): static, takes the photo Bitmap, returns the merged Bitmap.
     * Full signature: (Bitmap, ColorSpace, I, I, String, I) -> Bitmap
     */
    val COMPOSITE_PARAMS_67 = listOf(BITMAP, COLOR_SPACE, INT, INT, STRING, INT)

    /**
     * `LCd/a;->i`-style composite (6.2.x): static, takes the photo Bitmap, returns the merged Bitmap.
     * Full signature: (Cd/a, Bitmap, ColorSpace, I) -> Bitmap
     * The first parameter is the composite's own type (self-reference), not in the 6.7.x signature.
     */
    val COMPOSITE_PARAMS_62 = listOf(BITMAP, COLOR_SPACE, INT)

    class Found {
        /** Class names of candidate canvas wrappers, most specific first. */
        val canvasWrappers = LinkedHashSet<String>()

        /** Class names of candidate composite holders. */
        val compositeClasses = LinkedHashSet<String>()

        /**
         * Classes whose instance fields contain the capture-metadata type run `S,F,J,I`
         * (focal, aperture, exposure-ns, iso) followed by a `J` and a `Location`.
         *
         * This is the portable stand-in for the 6.4 `la/a` record: the letters change between
         * builds, the type sequence does not.
         */
        val captureRecords = LinkedHashSet<String>()

        /** Class name → the record's own type descriptor, for receiver matching. */
        val captureRecordTypes = LinkedHashMap<String, String>()

        /** Class name → field-index plan resolved while scanning, so lookups stay allocation-free. */
        val recordClassesFirstPlan = LinkedHashMap<String, RecordPlan>()

        /**
         * Methods shaped `f(<record>, Z, I)` — the call that wraps the whole watermark encode.
         *
         * 6.4: `E5/b.h(la/a, Z, I) -> la/f`
         * 6.7: `W8/d.f(Ki/a, Z, I) -> Ki/f`
         *
         * Stored as (declaring class, method name, parameter count) so the runtime side can
         * resolve it with one `Class.forName` instead of enumerating the class loader.
         */
        val captureReceivers = ArrayList<CaptureReceiver>()

        /**
         * Bitmap-to-capture-frame encoders. On Camera 6.7 the watermark result is converted
         * into the final `Ki/f` frame by `Mi/b.a|b|c`; intercepting these methods is the last
         * safe point before YUV/JPEG packing and therefore after Xiaomi has finished composing
         * its own watermark layers.
         */
        val frameEncoders = ArrayList<FrameEncoder>()

        /** Adds a receiver once, keyed by owner#method, so the two-pass scan cannot duplicate. */
        fun addReceiver(receiver: CaptureReceiver) {
            if (captureReceivers.none { it.owner == receiver.owner && it.method == receiver.method }) {
                captureReceivers.add(receiver)
            }
        }

        fun addFrameEncoder(encoder: FrameEncoder) {
            if (frameEncoders.none { it.owner == encoder.owner }) frameEncoders.add(encoder)
        }
    }

    /**
     * The fingerprint of a capture-metadata record, in field *declaration order*.
     *
     * focal(S), aperture(F), exposure-nanoseconds(J), iso(I). 6.4 spelled these `f,g,h,i`;
     * 6.7 spells them `f,g,h,i` too, but the fields that follow them moved (`j,k` → `l,m`),
     * which is exactly why the reader addresses them by type-run index rather than by name.
     */
    val CAPTURE_RUN = listOf("S", "F", "J", "I")

    /**
     * Scans every `classes*.dex` inside [archives] and collects structural candidates.
     *
     * DEX byte arrays are released one at a time. Compact class/member summaries are retained
     * until the cross-DEX receiver and frame-encoder matchers have completed.
     */
    fun scan(archives: List<String>): Found {
        val found = Found()
        val allClasses = ArrayList<DexClass>(36_000)
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
                            android.util.Log.w("LiquidFrame", "DexScanner: failed to read ${entry.name}: ${t.javaClass.simpleName}: ${t.message}")
                            continue
                        }
                        scanDex(bytes, found, allClasses)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("LiquidFrame", "DexScanner: archive $archive unreadable: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        // The record and the method that consumes it can live in different dex files (6.7 puts
        // the record in classes5 and the receiver in classes4), so receivers are matched in a
        // second pass once every record is known.
        matchFrameEncoders(allClasses, found)
        matchCaptureReceivers(allClasses, found)
        return found
    }

    /**
     * Second pass: find `f(<record>, Z, I)` now that the record types are known.
     *
     * A separate pass is required rather than convenience: within one dex file the record may
     * be defined after the class that consumes it, and the record set is only complete once all
     * dex files have been scanned.
     */
    private fun matchCaptureReceivers(classes: List<DexClass>, found: Found) {
        if (found.captureRecords.isEmpty()) return
        val recordTypes = found.captureRecordTypes.values.toHashSet()
        val frameTypes = found.frameEncoders.map { it.wrapperType }.toHashSet()
        for (cls in classes) {
            for (m in cls.methods) {
                val recordIndex = m.params.indexOfFirst { it in recordTypes }
                if (recordIndex < 0 || m.returnType == VOID) continue
                val exactLegacyShape = m.params.size == 3 && recordIndex == 0 &&
                        m.params[1] == BOOLEAN && m.params[2] == INT
                // New camera builds may insert a parameter or move the record away from arg 0.
                // A matching frame-wrapper return type ties the broader candidate to the actual
                // capture processor rather than any helper that happens to read the metadata.
                val verifiedFrameShape = m.returnType in frameTypes && m.params.size in 1..6
                if (exactLegacyShape || verifiedFrameShape) {
                    found.addReceiver(CaptureReceiver(cls.name, m.name, m.params.size, recordIndex))
                }
            }
        }
    }

    /**
     * Finds the final Bitmap -> frame-wrapper adapters without depending on obfuscated owner
     * names. A candidate wrapper carries one image payload object plus at least three integer
     * dimensions/rotation fields. Its owner must expose at least two static Bitmap adapters to
     * that wrapper and also consume the capture record alongside a Xiaomi watermark type.
     * This conjunction is intentionally conservative: on an unknown camera build we prefer
     * leaving the stock result intact over drawing onto an arbitrary image helper.
     */
    private fun matchFrameEncoders(classes: List<DexClass>, found: Found) {
        if (found.captureRecords.isEmpty()) return
        val recordTypes = found.captureRecordTypes.values.toSet()
        val wrappers = classes.filter { cls ->
            cls.instanceFieldTypes.count { it == INT } >= 3 &&
                cls.instanceFieldTypes.any { it.startsWith("L") && it.endsWith(";") } &&
                cls.methods.any { method ->
                    method.isConstructor && method.params.size >= 4 &&
                        method.params.lastOrNull() == INT &&
                        method.params.count { it == INT } >= 2
                }
        }.map { it.name }.toHashSet()
        if (wrappers.isEmpty()) return

        for (cls in classes) {
            val candidates = cls.methods.filter { method ->
                method.isStatic && method.returnType in wrappers && BITMAP in method.params
            }
            if (candidates.size < 2) continue
            val consumesCaptureAndWatermark = cls.methods.any { method ->
                method.params.any { it in recordTypes } &&
                    method.params.any { it.contains("/watermark/") || it.contains("cam/watermark") }
            }
            if (!consumesCaptureAndWatermark) continue
            val grouped = candidates.groupBy { it.returnType }
            val best = grouped.entries.maxByOrNull { (_, methods) ->
                methods.size * 10 + methods.count { it.params.size <= 2 }
            } ?: continue
            val encoders = best.value.mapNotNull { method ->
                val index = method.params.indexOf(BITMAP)
                if (index < 0) null else FrameEncoderMethod(method.name, method.params.size, index)
            }
            if (encoders.size >= 2) {
                found.addFrameEncoder(FrameEncoder(cls.name, best.key, encoders))
            }
        }
    }

    private fun scanDex(bytes: ByteArray, found: Found, allClasses: MutableList<DexClass>) {
        val dex = DexFile(bytes)
        if (!dex.isDex()) return
        val parsed = dex.classes()
        allClasses.addAll(parsed)
        for (cls in parsed) {
            if (isCanvasWrapper(cls)) found.canvasWrappers.add(cls.name)
            if (hasComposite(cls)) found.compositeClasses.add(cls.name)
            if (isCaptureRecord(cls)) {
                found.captureRecords.add(cls.name)
                // DEX type descriptor form: Lcom/...; — callers see it in proto type lists.
                found.captureRecordTypes[cls.name] = cls.name
                recordPlan(cls.instanceFieldTypes)?.let { found.recordClassesFirstPlan[cls.name] = it }
            }
        }
    }

    /**
     * A canvas wrapper is recognised by the conjunction of all three traits, which is what keeps
     * the heuristic from matching unrelated drawing helpers.
     */
    private fun isCanvasWrapper(cls: DexClass): Boolean {
        var hasBitmapCtor = false
        var hasDrawRect = false
        for (m in cls.methods) {
            if (m.isConstructor && m.params.size == 1 && m.params[0] == BITMAP) hasBitmapCtor = true
            if (m.matches(DRAW_RECT_PARAMS, VOID)) hasDrawRect = true
        }
        return hasBitmapCtor && hasDrawRect && cls.fieldTypes.contains(CANVAS)
    }

    /**
     * A composite is recognised by: takes a Bitmap parameter, and either
     * returns a Bitmap (6.2.x/6.7.x style — both static and instance) or returns a wrapper
     * object whose name contains "WatermarkRemover" (6.4.x style — `Fe/a->j` returns
     * `WatermarkRemover$b` instead of the raw Bitmap). The ColorSpace parameter was dropped in
     * 6.4.x, so requiring it would miss the new signature entirely.
     *
     * Note: 6.7.x changed the watermark factory from static to instance method; this matcher
     * no longer requires `isStatic` to accommodate that change.
     */
    private fun hasComposite(cls: DexClass): Boolean =
        cls.methods.any { m ->
            BITMAP in m.params &&
            (m.returnType == BITMAP || m.returnType.contains("WatermarkRemover"))
        }

    /**
     * A capture-metadata record: instance fields containing the contiguous run
     * `S,F,J,I` (focal, aperture, exposure-ns, iso), a later `J` (capture time)
     * and a later `Location` (GPS).
     *
     * Verified unique on both known builds:
     *   6.4 → la/a (fields f,g,h,i + j:J + k:Location)
     *   6.7 → Ki/a (fields f,g,h,i + l:J + m:Location)
     */
    private fun isCaptureRecord(cls: DexClass): Boolean =
        recordPlan(cls.instanceFieldTypes) != null

    /**
     * Locates the metadata fields inside a record's instance-field type list.
     *
     * Returns indices into the instance field list (declaration order), or null when
     * the class is not a record. Anchoring on the contiguous `S,F,J,I` run — rather
     * than "first int", which 6.7 breaks by placing an unrelated `I` at index 2 —
     * is what keeps the mapping stable across builds.
     */
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
                    sourceBitmap = instanceTypes.indexOf(BITMAP).takeIf { it >= 0 },
                )
            }
            return null
        }
        return null
    }

    /** Field indices (into the instance field list in declared order) for a capture record. */
    data class RecordPlan(
        val focal: Int,
        val aperture: Int,
        val exposure: Int,
        val iso: Int,
        val time: Int,
        val location: Int,
        /** Pristine source image, when the camera stores it on the typed capture record. */
        val sourceBitmap: Int? = null,
    )

    /** A method shaped `f(<record>, Z, I)`, resolved structurally from the DEX. */
    data class CaptureReceiver(
        val owner: String,
        val method: String,
        val paramCount: Int,
        val recordParamIndex: Int = 0,
    )

    data class FrameEncoder(
        val owner: String,
        val wrapperType: String,
        val methods: List<FrameEncoderMethod>,
    )

    data class FrameEncoderMethod(
        val name: String,
        val paramCount: Int,
        val bitmapParamIndex: Int,
    )

    private const val LOCATION = "Landroid/location/Location;"
    private const val MAX_RECORD_FIELDS = 40
}