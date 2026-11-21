package com.mouya.LiquidFrame.gallery.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.HighlightStyle

fun BackdropEffectScope.vibrancy(saturation: Float = 1.5f, brightness: Float = 0f) {
    colorControls(brightness = brightness, contrast = 1f, saturation = saturation)
}

@Immutable
data class GlassSpec(

    val cornerRadius: Dp = 24f.dp,

    val blurRadius: Dp = 4f.dp,

    val refractionHeight: Dp = 16f.dp,

    val refractionAmount: Dp = 24f.dp,

    val depthEffect: Boolean = true,

    val chromaticAberration: Float = 0.2f,

    val vibrancy: Float = 1.5f,

    val vibrancyBrightness: Float = 0f,

    val tint: Color = Color.White.copy(alpha = 0.34f),

    val rimStyle: HighlightStyle = HighlightStyle.Default,

    val rimWidth: Dp = 0.8.dp,

    val rimAlpha: Float = 1f,
) {

    val shape: Shape get() = RoundedCornerShape(cornerRadius)

    companion object {

        fun forHeight(height: Dp, cornerRadius: Dp = height * 0.34f): GlassSpec {
            val h = height.value
            return GlassSpec(
                cornerRadius = cornerRadius,
                blurRadius = (h * 0.06f).dp,
                refractionHeight = (h * 0.30f).dp,
                refractionAmount = (h * 0.36f).dp,
                depthEffect = true,
                chromaticAberration = 0.2f,
                tint = Color.White.copy(alpha = 0.34f),
            )
        }

        val Pill = GlassSpec.forHeight(56.dp, cornerRadius = 28.dp)

        val Card = GlassSpec.forHeight(120.dp, cornerRadius = 28.dp)

        val Showy = Card.copy(
            chromaticAberration = 0.45f,
            refractionHeight = 22.dp,
            refractionAmount = 34.dp,
        )

        val FrostedOnly = Card.copy(
            refractionHeight = 0.dp,
            refractionAmount = 0.dp,
            chromaticAberration = 0f,
        )
    }
}

fun Modifier.liquidGlass(
    backdrop: Backdrop,
    spec: GlassSpec,
    enabled: Boolean = true,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { spec.shape },
    effects = {

        padding = maxOf(
            padding,
            spec.refractionAmount.value * density * 1.2f + spec.blurRadius.value * density,
        )
        vibrancy(spec.vibrancy, spec.vibrancyBrightness)
        if (spec.blurRadius > 0.dp) {
            blur(spec.blurRadius.value * density, spec.blurRadius.value * density)
        }
        if (spec.refractionHeight > 0.dp && spec.refractionAmount > 0.dp) {
            lens(
                refractionHeight = spec.refractionHeight.value * density,
                refractionAmount = spec.refractionAmount.value * density,
                depthEffect = spec.depthEffect,
                chromaticAberration = spec.chromaticAberration,
            )
        }
    },
    highlight = if (spec.rimAlpha > 0f) {
        { Highlight(width = spec.rimWidth, alpha = spec.rimAlpha, style = spec.rimStyle) }
    } else {
        null
    },
    onDrawSurface = {
        drawRect(spec.tint)
        onDrawSurface?.invoke(this)
    },
    enabled = enabled,
)
