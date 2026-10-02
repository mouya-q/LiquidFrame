// Copyright 2026, LiquidFrame contributors
// SPDX-License-Identifier: Apache-2.0

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

/**
 * The reference's `vibrancy()`: a saturation lift on the captured backdrop.
 *
 * Real glass does not just blur what is behind it, it concentrates colour. Without this the
 * refracted backdrop reads as washed-out grey next to the saturated content around it, and the
 * material loses the "wet" quality that makes it look like glass rather than fog.
 */
fun BackdropEffectScope.vibrancy(saturation: Float = 1.5f, brightness: Float = 0f) {
    colorControls(brightness = brightness, contrast = 1f, saturation = saturation)
}

/**
 * A complete liquid glass material.
 *
 * One object rather than a pile of parameters at each call site, because a glass surface only
 * looks right when its quantities are in proportion to each other: the refraction band, the
 * displacement and the rim all scale with the surface's height. [forHeight] derives a consistent
 * set from a single number, and `copy` lets a screen vary one axis (say dispersion) without
 * losing that consistency.
 */
@Immutable
data class GlassSpec(
    /** Corner radius of the surface. */
    val cornerRadius: Dp = 24f.dp,
    /** Backdrop blur radius. Keep small: heavy blur destroys the detail the lens bends. */
    val blurRadius: Dp = 4f.dp,
    /** Thickness of the refracting band along the silhouette. */
    val refractionHeight: Dp = 16f.dp,
    /** Peak displacement at the silhouette. */
    val refractionAmount: Dp = 24f.dp,
    /** Lean the normal toward the centre so the glass reads as a solid dome. */
    val depthEffect: Boolean = true,
    /** Strength of the rim's spectral split; `0` disables it. */
    val chromaticAberration: Float = 0.2f,
    /** Backdrop saturation lift. */
    val vibrancy: Float = 1.5f,
    /** Extra brightness on the refracted backdrop, -1..1. */
    val vibrancyBrightness: Float = 0f,
    /**
     * The translucent body colour painted over the refracted backdrop.
     *
     * This is the readability knob: too transparent and labels disappear into a busy backdrop,
     * too opaque and the lens stops being visible. Around 0.3-0.5 alpha is the Apple balance.
     */
    val tint: Color = Color.White.copy(alpha = 0.34f),
    /**
     * Rim shading model. miuix's [BloomStroke] builds a hemispheric normal along the rounded edge
     * and lights it from two directions, which is the same specular-rim model Apple's material
     * uses, so it is the default rather than a hand-rolled highlight.
     */
    val rimStyle: HighlightStyle = HighlightStyle.Default,
    /** Rim stroke band width. */
    val rimWidth: Dp = 0.8.dp,
    /** Overall rim opacity; `0` removes the highlight entirely. */
    val rimAlpha: Float = 1f,
) {

    /** Corner shape derived from [cornerRadius]. */
    val shape: Shape get() = RoundedCornerShape(cornerRadius)

    companion object {

        /**
         * A proportionally consistent material for a surface of the given height.
         *
         * The ratios come from the reference's own Apple comparison, where a 300 px glass uses a
         * 20 px band with a 60 px displacement, and from miuix's liquid navigation bar, which
         * pairs a 64 dp bar with a 24 dp band and a 24 dp amount.
         */
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

        /** A compact capsule, tuned for a 48-64 dp bar or button. */
        val Pill = GlassSpec.forHeight(56.dp, cornerRadius = 28.dp)

        /** A roomier surface for cards and sheets. */
        val Card = GlassSpec.forHeight(120.dp, cornerRadius = 28.dp)

        /** Deliberately showy, for the dispersion comparison in the gallery. */
        val Showy = Card.copy(
            chromaticAberration = 0.45f,
            refractionHeight = 22.dp,
            refractionAmount = 34.dp,
        )

        /** No lens at all: plain frosted glass, the baseline the lens is judged against. */
        val FrostedOnly = Card.copy(
            refractionHeight = 0.dp,
            refractionAmount = 0.dp,
            chromaticAberration = 0f,
        )
    }
}

/**
 * Applies [spec] as a live liquid glass material over [backdrop].
 *
 * Call this on a composable that sits *above* the captured backdrop in the draw order. The
 * surface stays screen-aligned: the lens samples the backdrop rather than scaling it, so the
 * content behind the glass keeps its own size while the rim magnifies it.
 *
 * @param backdrop the captured backdrop, normally from `rememberLayerBackdrop()`
 * @param spec the material
 * @param enabled when false the composable draws as if the modifier were absent, which is the
 *   graceful path on devices without RuntimeShader support
 * @param onDrawSurface extra drawing between the refracted backdrop and the content, for tint
 *   gradients or scrims that should ride inside the glass
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    spec: GlassSpec,
    enabled: Boolean = true,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { spec.shape },
    effects = {
        // The lens samples up to `refractionAmount` outside its bounds and the blur adds its own
        // reach, so the recording is grown before blur() bakes its sampling clamp from the
        // padding. Raising it afterwards still works but re-runs the whole effect block.
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
