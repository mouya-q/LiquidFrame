package com.mouya.LiquidFrame

import android.os.SystemClock
import com.mouya.LiquidFrame.glass.GlassParams
import java.io.File
import java.util.Locale

/**
 * Settings store.
 *
 * The config UI lives in this module's own process; the material is applied inside the camera
 * process. The two never share memory, so settings travel through a plain text file in a
 * directory both can reach.
 *
 * Values are stored one `key=value` per line so a partial or hand-edited file still loads, and
 * a version tag lets the format change later without breaking an upgrade.
 *
 * ## Why this file has to escalate
 *
 * `/data/local/tmp` is `shell:shell` with mode 771. The camera can *read* it, but a normal
 * application cannot even create a file there — so the first version of this class silently
 * dropped every setting on the floor: `save()` caught the `IOException` and the camera went on
 * reading a file that never existed. That is exactly the "I toggled it and nothing happened"
 * symptom, and it stayed invisible because the write path reported nothing either.
 *
 * So the writer no longer assumes it can write there. It tries the plain write first (fast, and
 * sufficient once the directory has been relaxed), falls back to escalating through `su` for
 * the first successful write, and then relaxes the mode so later writes do not pay for a
 * `su` fork per drag. Every path reports what it actually did through [lastWriteStatus], which
 * the settings screen surfaces instead of pretending.
 */
object ConfigStore {

    /** Shared location: readable and writable by the module and by the camera app on a rooted
     *  device, and already used for this module's log. */
    private const val DIR = "/data/local/tmp"

    private const val FILE_NAME = "lf_config.txt"
    private const val VERSION = 1

    /** How long the camera process may cache settings before re-reading them. */
    private const val RELOAD_INTERVAL_MS = 2_000L

    /** One successful escalation is enough; do not re-probe `su` on every write. */
    private const val ESCALATION_RETRY_MS = 30_000L

    private val file: File get() = File(DIR, FILE_NAME)

    @Volatile
    private var lastLoadedMs = 0L

    @Volatile
    private var cached: MutableMap<String, String> = LinkedHashMap()

    @Volatile
    private var escalationCheckedMs = 0L

    @Volatile
    private var lastWriteStatus: String = "尚未写入"

    @Volatile
    private var lastWriteMs: Long = 0L

    /** What the most recent [save] actually managed to do, for the settings screen to show. */
    fun lastWriteStatus(): String = lastWriteStatus

    /** Wall-clock of the most recent successful [save]. */
    fun lastWriteMs(): Long = lastWriteMs

    // ---------------------------------------------------------------------------------------
    // Load / save
    // ---------------------------------------------------------------------------------------

    /** Reads settings from disk, rate limited so the camera can call it on every capture. */
    fun loadIfStale() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLoadedMs < RELOAD_INTERVAL_MS) return
        load()
    }

    fun load() {
        lastLoadedMs = SystemClock.uptimeMillis()
        cached = try {
            if (!file.exists()) LinkedHashMap()
            else parse(file.readText())
        } catch (_: Throwable) {
            LinkedHashMap()
        }
    }

    private fun parse(text: String): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach
            map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
        }
        return map
    }

    fun save(values: Map<String, String>) {
        cached = LinkedHashMap(values)
        val text = buildString {
            appendLine("# LiquidFrame settings")
            appendLine("version=$VERSION")
            values.toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
        }

        val mode = writeText(text)
        lastWriteStatus = when (mode) {
            WRITE_OK -> "已写入 ${FILE_NAME} · ${sizeOf(text)} B"
            WRITE_ROOT -> "已写入 ${FILE_NAME} (su) · ${sizeOf(text)} B"
            WRITE_FAIL -> "写入失败：${FILE_NAME} 无写权限"
            else -> "写入失败"
        }
        if (mode == WRITE_OK || mode == WRITE_ROOT) lastWriteMs = System.currentTimeMillis()
        LogHelper.log(TAG, "save -> $lastWriteStatus")
        lastLoadedMs = SystemClock.uptimeMillis()
    }

    /**
     * Writes the file, escalating only when the plain path is refused.
     *
     * The order matters: a plain write costs nothing, and on a device where the directory has
     * already been relaxed it stays that way. Only when it fails does the module ask for root,
     * and having got it, it relaxes the directory once so a slider drag does not fork `su`
     * per frame.
     */
    private fun writeText(text: String): Int {
        val plain = try {
            if (!file.parentFile?.exists()!!) file.parentFile?.mkdirs()
            file.writeText(text)
            true
        } catch (_: Throwable) {
            false
        }
        if (plain) return WRITE_OK

        // Escalate, but not on every single write: a drag can produce dozens per second.
        val now = SystemClock.uptimeMillis()
        val alreadyTried = now - escalationCheckedMs < ESCALATION_RETRY_MS
        escalationCheckedMs = now
        if (alreadyTried && lastWriteMs == 0L) return WRITE_FAIL

        val script = buildString {
            append("mkdir -p ").append(shellQuote(DIR))
            append(" && chmod 777 ").append(shellQuote(DIR))
            append(" && cat > ").append(shellQuote(file.absolutePath))
            append(" <<'LF_EOF'\n").append(text).append("\nLF_EOF\n")
            append("chmod 666 ").append(shellQuote(file.absolutePath)).append('\n')
        }
        val ok = try {
            runSu(script) == 0
        } catch (_: Throwable) {
            false
        }
        if (!ok) return WRITE_FAIL

        // The relaxed directory should let the next write go through without root; verify by
        // reading the file back so the status we report is measured, not assumed.
        val readBack = try {
            file.exists() && parse(file.readText()).containsKey(KEY_ENABLED)
        } catch (_: Throwable) {
            false
        }
        return if (readBack) WRITE_ROOT else WRITE_FAIL
    }

    /** Runs a shell script as root, returning its exit status. */
    private fun runSu(script: String): Int {
        val candidates = listOf("su", "/system/bin/su", "/system/xbin/su")
        for (binary in candidates) {
            try {
                val process = ProcessBuilder(binary, "-c", script).redirectErrorStream(true).start()
                process.outputStream.close()
                process.inputStream.close()
                return process.waitFor()
            } catch (_: Throwable) {
                // Try the next binary.
            }
        }
        return -1
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun sizeOf(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    // ---------------------------------------------------------------------------------------
    // Typed accessors
    // ---------------------------------------------------------------------------------------

    private fun bool(key: String, def: Boolean): Boolean =
        when (cached[key]?.lowercase(Locale.US)) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> def
        }

    private fun float(key: String, def: Float): Float =
        cached[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: def

    // ---------------------------------------------------------------------------------------
    // The settings themselves, with their defaults
    // ---------------------------------------------------------------------------------------

    fun isEnabled(): Boolean = bool(KEY_ENABLED, true)

    fun glassParams(): GlassParams {
        val d = GlassParams.Default
        return GlassParams(
            refractionHeightFraction = float(KEY_BAND, d.refractionHeightFraction),
            refractionAmountFraction = float(KEY_AMOUNT, d.refractionAmountFraction),
            cornerRadiusFraction = d.cornerRadiusFraction,
            blurFraction = float(KEY_BLUR, d.blurFraction),
            vibrancy = d.vibrancy,
            vibrancyBrightness = d.vibrancyBrightness,
            depthEffect = float(KEY_DEPTH, d.depthEffect),
            chromaticAberration = bool(KEY_DISPERSION, d.chromaticAberration),
            dispersionIntensity = d.dispersionIntensity,
            tintColor = d.tintColor,
            tintHueWeight = d.tintHueWeight,
            tintAlpha = float(KEY_TINT, d.tintAlpha),
            surfaceAlpha = d.surfaceAlpha,
            interiorLift = float(KEY_LIFT, d.interiorLift),
            highlightGain = d.highlightGain,
            highlightAngleDeg = float(KEY_ANGLE, d.highlightAngleDeg),
            highlightFalloff = d.highlightFalloff,
            rimAlpha = float(KEY_RIM, d.rimAlpha),
            rimWidthFraction = d.rimWidthFraction,
            rimBlurFraction = d.rimBlurFraction,
            rimAmbient = d.rimAmbient,
            innerShadowAlpha = float(KEY_SHADOW, d.innerShadowAlpha),
            innerShadowRadiusFraction = d.innerShadowRadiusFraction,
            innerShadowOffsetXFraction = d.innerShadowOffsetXFraction,
            innerShadowOffsetYFraction = d.innerShadowOffsetYFraction,
            adaptive = bool(KEY_ADAPTIVE, d.adaptive),
            preserveContent = bool(KEY_PRESERVE, d.preserveContent),
        )
    }

    const val KEY_ENABLED = "enabled"
    const val KEY_BAND = "refraction_height_fraction"
    const val KEY_AMOUNT = "refraction_amount_fraction"
    const val KEY_BLUR = "blur_fraction"
    const val KEY_LIFT = "interior_lift"
    const val KEY_TINT = "tint_alpha"
    const val KEY_RIM = "rim_alpha"
    const val KEY_SHADOW = "inner_shadow_alpha"
    const val KEY_DEPTH = "depth_effect"
    const val KEY_ANGLE = "highlight_angle_deg"
    const val KEY_DISPERSION = "chromatic_aberration"
    const val KEY_ADAPTIVE = "adaptive"
    const val KEY_PRESERVE = "preserve_content"

    /** Current values, ready to edit in the UI. */
    fun snapshot(): MutableMap<String, String> {
        val p = glassParams()
        return linkedMapOf(
            KEY_ENABLED to isEnabled().toString(),
            KEY_BAND to p.refractionHeightFraction.toString(),
            KEY_AMOUNT to p.refractionAmountFraction.toString(),
            KEY_BLUR to p.blurFraction.toString(),
            KEY_LIFT to p.interiorLift.toString(),
            KEY_TINT to p.tintAlpha.toString(),
            KEY_RIM to p.rimAlpha.toString(),
            KEY_SHADOW to p.innerShadowAlpha.toString(),
            KEY_DEPTH to p.depthEffect.toString(),
            KEY_ANGLE to p.highlightAngleDeg.toString(),
            KEY_DISPERSION to p.chromaticAberration.toString(),
            KEY_ADAPTIVE to p.adaptive.toString(),
            KEY_PRESERVE to p.preserveContent.toString(),
        )
    }

    private const val TAG = "LiquidFrame"

    private const val WRITE_OK = 0
    private const val WRITE_ROOT = 1
    private const val WRITE_FAIL = 2
}