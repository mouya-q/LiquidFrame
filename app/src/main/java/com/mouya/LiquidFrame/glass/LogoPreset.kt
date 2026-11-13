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
        "小米",
        1.0f,
        1.2f,
        false,
    ),
    Hasselblad(
        "哈苏",
        1.2439024f,
        0.94f,
        false,
    ),
    Zeiss(
        "蔡司",
        1.0f,
        1.05f,
        false,
    ),
    None(
        "无",
        1.0f,
        1.0f,
        true,
    );

    companion object {
        /**
         * Resolve a logo by name string, with fallback to LeicaWhite.
         */
        fun fromName(name: String?): LogoPreset {
            if (name == null) return LeicaWhite
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LeicaWhite
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