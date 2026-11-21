package com.mouya.LiquidFrame.glass

enum class LogoPreset(
    val displayName: String,

    val aspectCompensation: Float,

    val verticalAlignment: Float,

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

        fun fromName(name: String?): LogoPreset {
            if (name == null) return LeicaWhite
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LeicaWhite
        }
    }
}

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
