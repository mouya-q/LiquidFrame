package com.mouya.LiquidFrame.glass

/**
 * Logo presets for the watermark capsule.
 *
 * Each preset carries an aspect-ratio compensation factor and a vertical
 * alignment hint so the logo scales correctly inside a fixed-height capsule.
 * The actual bitmap / vector decoding happens in [com.mouya.LiquidFrame.WatermarkHooks.loadLogo],
 * which reads from the module APK's assets/ or res/ paths at runtime.
 */
enum class LogoPreset(
    val displayName: String,
    /** Width-to-height ratio compensation. Applied as height = logoWidth / aspect. */
    val aspectCompensation: Float,
    /** Vertical alignment factor within the capsule. */
    val verticalAlignment: Float,
    /** Whether the logo is monochrome white (true) or colour (false). */
    val isWhite: Boolean,
) {
    Leica(
        "徕卡",
        1.0f,
        1.15f,
        false,
    ),
    LeicaWhite(
        "徕卡白",
        1.0f,
        1.15f,
        true,
    ),
    Apple(
        "苹果",
        0.84232f,
        1.08f,
        true,
    ),
    Xiaomi(
        "小米字标",
        1.0f,
        1.0f,
        true,
    ),
    Hasselblad(
        "哈苏字标",
        2.4f,
        1.0f,
        true,
    ),
    Zeiss(
        "蔡司字标",
        1.8f,
        1.0f,
        true,
    ),
    None(
        "无",
        1.0f,
        1.0f,
        true,
    );

    companion object {
        /**
         * Resolve a logo by name string, with a reference-matching Apple fallback.
         */
        fun fromName(name: String?): LogoPreset {
            if (name == null) return Apple
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Apple
        }
    }
}

/**
 * Where the logo sits relative to the text columns.
 */
enum class LogoPosition(val displayName: String) {
    FRONT("前"),
    MIDDLE("中"),
    BACK("后");

    companion object {
        fun fromName(name: String?): LogoPosition {
            if (name == null) return MIDDLE
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MIDDLE
        }
    }
}