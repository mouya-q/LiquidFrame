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

/** One `class_def_item` plus the members listed in its `class_data_item`. */
class DexClass(
    val name: String,
    val superName: String?,
    val methods: List<DexMethodRef>,
    val fieldTypes: List<String>,
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
            if (classDataOff != 0 && classDataOff < data.size) {
                readClassData(classDataOff, methods, fields)
            }
            out.add(
                DexClass(
                    name = typeName(classIdx),
                    superName = if (superIdx == NO_INDEX || superIdx >= typeCount) null else typeName(superIdx),
                    methods = methods,
                    fieldTypes = fields,
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // class_data_item
    // ---------------------------------------------------------------------------------------

    private fun readClassData(off: Int, methods: MutableList<DexMethodRef>, fields: MutableList<String>) {
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
        for (i in 0 until instanceFields) {
            fieldIdx += uleb(c)
            uleb(c)
            fieldTypeName(fieldIdx)?.let { fields.add(it) }
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

    /** `Lpe/o;`-style wrapper: new(Bitmap), holds a Canvas, draws rects with a Paint. */
    val DRAW_RECT_PARAMS = listOf(FLOAT, FLOAT, FLOAT, FLOAT, PAINT)

    /** `LFe/a;->j`-style composite: static, takes the photo Bitmap, returns the merged Bitmap. */
    val COMPOSITE_PARAMS = listOf(BITMAP, COLOR_SPACE, INT, INT, STRING, INT)

    class Found {
        /** Class names of candidate canvas wrappers, most specific first. */
        val canvasWrappers = LinkedHashSet<String>()

        /** Class names of candidate composite holders. */
        val compositeClasses = LinkedHashSet<String>()
    }

    /**
     * Scans every `classes*.dex` inside [archives] and collects structural candidates.
     *
     * Dexes are read one at a time and released immediately, so peak memory stays at one dex even
     * for the camera's six-dex 200 MB APK.
     */
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
                        } catch (_: Throwable) {
                            continue
                        }
                        scanDex(bytes, found)
                    }
                }
            } catch (_: Throwable) {
                // A missing or unreadable archive must not abort the whole discovery pass.
            }
        }
        return found
    }

    private fun scanDex(bytes: ByteArray, found: Found) {
        val dex = DexFile(bytes)
        if (!dex.isDex()) return
        for (cls in dex.classes()) {
            if (cls.name in found.canvasWrappers || cls.name in found.compositeClasses) continue
            if (isCanvasWrapper(cls)) found.canvasWrappers.add(cls.name)
            if (hasComposite(cls)) found.compositeClasses.add(cls.name)
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

    private fun hasComposite(cls: DexClass): Boolean =
        cls.methods.any { it.isStatic && it.matches(COMPOSITE_PARAMS, BITMAP) }
}
