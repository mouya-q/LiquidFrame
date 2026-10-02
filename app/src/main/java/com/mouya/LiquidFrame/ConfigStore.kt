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
 */
object ConfigStore {

    /** Shared location: readable and writable by the module and by the camera app on a rooted
     *  device, and already used for this module's log. */
    private const val DIR = "/data/local/tmp"

    private const val FILE_NAME = "lf_config.txt"
    private const val VERSION = 1

    /** How long the camera process may cache settings before re-reading them. */
    private const val RELOAD_INTERVAL_MS = 2_000L

    private val file: File get() = File(DIR, FILE_NAME)

    @Volatile
    private var lastLoadedMs = 0L

    @Volatile
    private var cached: MutableMap<String, String> = LinkedHashMap()

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
            else {
                val map = LinkedHashMap<String, String>()
                file.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) return@forEach
                    map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                }
                map
            }
        } catch (_: Throwable) {
            LinkedHashMap()
        }
    }

    fun save(values: Map<String, String>) {
        cached = LinkedHashMap(values)
        lastLoadedMs = SystemClock.uptimeMillis()
        try {
            val dir = File(DIR)
            if (!dir.exists()) dir.mkdirs()
            val text = buildString {
                appendLine("# LiquidFrame settings")
                appendLine("version=$VERSION")
                values.toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
            }
            file.writeText(text)
        } catch (_: Throwable) {
            // The camera falls back to defaults if the file cannot be written.
        }
    }

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
}
