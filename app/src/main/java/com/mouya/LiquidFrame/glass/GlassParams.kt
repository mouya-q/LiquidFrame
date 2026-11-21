package com.mouya.LiquidFrame.glass

data class GlassParams(

    val refractionHeightFraction: Float = 0.20f,

    val refractionAmountFraction: Float = 0.34f,

    val cornerRadiusFraction: Float = 0.5f,

    val blurFraction: Float = 0.08f,

    val vibrancy: Float = 1f,
    val vibrancyBrightness: Float = 0f,

    val depthEffect: Float = 1f,

    val chromaticAberration: Boolean = true,
    val dispersionIntensity: Float = 0.18f,

    val tintColor: Int = 0xFFFFFFFF.toInt(),
    val tintHueWeight: Float = 0.75f,

    val tintAlpha: Float = 0.018f,

    val surfaceAlpha: Float = 0.022f,

    val interiorLift: Float = 0f,

    val preserveContent: Boolean = true,

    val highlightGain: Float = 1f,
    val highlightAngleDeg: Float = 45f,
    val highlightFalloff: Float = 1f,

    val rimAlpha: Float = 0.22f,

    val rimWidthFraction: Float = 0.16f,

    val rimBlurFraction: Float = 0.24f,

    val rimAmbient: Float = 0.18f,

    val innerShadowAlpha: Float = 0f,
    val innerShadowRadiusFraction: Float = 0.10f,
    val innerShadowOffsetXFraction: Float = 0f,
    val innerShadowOffsetYFraction: Float = 1f,

    val adaptive: Boolean = true,

    val watermarkHeightDp: Float = 35f,

    val watermarkLengthFactor: Float = 1.0f,

    val watermarkOffsetX: Float = 0f,

    val watermarkOffsetY: Float = 1.0f,

    val deviceTextScale: Float = 1.0f,

    val metaTextScale: Float = 1.0f,

    val metaLineSpacing: Float = -0.18f,

    val logoSizeScale: Float = 1.0f,

    val logoPosition: String = "MIDDLE",

    val showCaptureLine: Boolean = true,

    val showTimestampLine: Boolean = true,

    val textColorInvert: Boolean = false,

    val textBold: Boolean = false,
) {

    companion object {

        val Default = GlassParams()

    }
}
