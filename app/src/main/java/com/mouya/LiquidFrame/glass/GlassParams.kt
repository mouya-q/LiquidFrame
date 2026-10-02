package com.mouya.LiquidFrame.glass

/**
 * Every knob of the liquid glass material in one place.
 *
 * Defaults follow the reference implementation
 * (Kyant0/AndroidLiquidGlass, `backdrop` module, Apache-2.0) as its own components use it:
 *
 *   vibrancy()                -> saturation 1.5, luma weights 0.213/0.715/0.072
 *   blur(2.dp)                -> backdrop blur before the lens (documented order)
 *   lens(12.dp, 24.dp)        -> refraction height and amount; amount is sent negated
 *   HighlightStyle.Default    -> white stroke 0.5.dp, blur width/2, angle 45 deg,
 *                                falloff 1, BlendMode.Plus
 *   InnerShadow.Default       -> black 0.15, radius 24.dp, offset (0, +radius)
 *
 * Where a value is given as a fraction rather than in dp it is because a photo watermark has
 * no density to resolve against: the panel is only ~40 px tall on a 4000 px frame, so the
 * material is scaled to the panel instead of to the screen. A control of that pixel height
 * would get a refraction band of roughly a third of its height from the reference's own
 * component values, which is what [refractionHeightFraction] encodes.
 */
data class GlassParams(

    // ---- geometry -----------------------------------------------------------------------

    /**
     * Refraction band height, as a fraction of the panel's short side.
     *
     * Calibrated against the reference project's own Apple comparison, where a 300 px glass
     * uses `inputInnerRefractionHeight = 20`: a band of ~7% of the panel.
     */
    val refractionHeightFraction: Float = 0.18f,

    /**
     * Peak displacement, as a fraction of the refraction band.
     *
     * The same comparison uses `inputInnerRefractionAmount = -60` with a height of 20, so
     * the lens pulls the backdrop three band-widths inward at the silhouette. That ratio is
     * what gives the material its strong edge magnification.
     */
    val refractionAmountFraction: Float = 3.0f,

    /** Corner radius as a fraction of the panel's short side, when it cannot be measured. */
    val cornerRadiusFraction: Float = 0.34f,

    // ---- backdrop -----------------------------------------------------------------------

    /** Gaussian blur before the lens, as a fraction of the refraction band. */
    val blurFraction: Float = 0.09f,

    /** `vibrancy()`. */
    val vibrancy: Float = 1.5f,
    val vibrancyBrightness: Float = 0.03f,

    // ---- lens ---------------------------------------------------------------------------

    /** Lean the surface normal toward the panel centre; the reference's `depthEffect`. */
    val depthEffect: Float = 1f,

    /** Split refraction into the reference's seven-tap spectrum. */
    val chromaticAberration: Boolean = true,
    val dispersionIntensity: Float = 1f,

    // ---- surface ------------------------------------------------------------------------

    /** Hue tint colour, applied with hue blending at [tintHueWeight]. */
    val tintColor: Int = 0xFFFFFFFF.toInt(),
    val tintHueWeight: Float = 0.75f,

    /** Alpha of the tint's normal-blend pass. */
    val tintAlpha: Float = 0.10f,

    /** Additional flat white veil; the reference's `surfaceColor`. */
    val surfaceAlpha: Float = 0.06f,

    /**
     * Additive frosted lift applied across the whole panel, in 0-255 units.
     *
     * Apple's material is not transparent glass: it keeps a faint luminous frost so white
     * labels stay legible over any photo. Without this the same photo content that makes the
     * refraction convincing also swallows the labels.
     */
    val interiorLift: Float = 10f,

    /**
     * Keep the camera's own labels on top of the material.
     *
     * Only applies when the panel is a flat baked background rather than a blurred copy of the
     * photo; a photographic panel has no flat tone to tell a label from the background, and its
     * own texture is what makes the lens readable anyway. The optics decide this for themselves
     * by measuring how much of the panel actually sits at its dominant tone.
     */
    val preserveContent: Boolean = true,

    /** Extra additive rim strength on top of [rimAlpha]. */
    val highlightGain: Float = 1f,
    val highlightAngleDeg: Float = 45f,
    val highlightFalloff: Float = 1f,

    // ---- rim highlight ------------------------------------------------------------------

    /** The reference's `Highlight` modifier: 0.5.dp white stroke at [rimAlpha]. */
    val rimAlpha: Float = 0.50f,

    /** Stroke width, as a fraction of the refraction band. */
    val rimWidthFraction: Float = 0.22f,

    /** Stroke blur (sigma), as a fraction of the refraction band. */
    val rimBlurFraction: Float = 0.20f,

    /**
     * Mix between an all-round rim ([rimAmbient], which is what a blurred stroke alone
     * gives) and the direction-weighted rim from the reference's `Default` highlight
     * shader. The material needs both: the stroke is what makes the edge read as glass, the
     * direction is what makes the light look like it comes from somewhere.
     */
    val rimAmbient: Float = 0.45f,

    // ---- inner shadow -------------------------------------------------------------------

    val innerShadowAlpha: Float = 0.15f,
    val innerShadowRadiusFraction: Float = 0.10f,
    val innerShadowOffsetXFraction: Float = 0f,

    /** +1 puts the shadow along the top edge, matching `InnerShadow.Default`'s offset. */
    val innerShadowOffsetYFraction: Float = 1f,

    // ---- adaptive -----------------------------------------------------------------------

    /**
     * Scale tint, veil and rim against the backdrop's mean luminance. Apple's material
     * thins out over a bright backdrop and densifies over a dark one, which is also what
     * keeps white watermark text legible on both.
     */
    val adaptive: Boolean = true,
) {

    /**
     * The inner shadow grows on bright scenes, where a light panel needs the anchor, and
     * eases off on dark ones, where it would only muddy the glass.
     */
    fun innerShadowAdaptiveGain(adaptiveScale: Float): Float =
        (0.75f + 0.45f * adaptiveScale).coerceIn(0.6f, 1.5f)

    companion object {

        val Default = GlassParams()

        /** Bright scene: a thinner, brighter, more transparent material. */
        val BrightScene = Default.copy(
            tintAlpha = 0.14f,
            surfaceAlpha = 0.09f,
            rimAlpha = 0.56f,
            innerShadowAlpha = 0.12f,
            adaptive = false,
        )

        /** Dark scene: denser glass, softer rim, stronger anchor. */
        val DarkScene = Default.copy(
            tintAlpha = 0.05f,
            surfaceAlpha = 0.03f,
            rimAlpha = 0.40f,
            innerShadowAlpha = 0.22f,
            adaptive = false,
        )
    }
}
