package com.mouya.LiquidFrame.glass

data class PanelRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val cornerRadiusPx: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    init {
        require(width > 0f && height > 0f) { "panel must have positive size, got $width x $height" }
    }
}

internal data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun expand(dx: Int, dy: Int) = CropRect(left - dx, top - dy, right + dx, bottom + dy)

    fun clamp(w: Int, h: Int) = CropRect(
        left.coerceIn(0, w),
        top.coerceIn(0, h),
        right.coerceIn(0, w),
        bottom.coerceIn(0, h),
    )
}
