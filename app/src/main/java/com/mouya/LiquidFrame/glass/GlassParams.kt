package com.mouya.LiquidFrame.glass

/**
 * Every knob of the liquid glass material in one place.
 *
 * Defaults follow the reference implementation (Apache-2.0, attributed in the
 * `NOTICE` file) as its own components use it:
 *
 *   vibrancy()                -> saturation 1.5, luma weights 0.213/0.715/0.072
 *   blur(2.dp)                -> backdrop blur before the lens (documented order)
 *   lens(12.dp, 24.dp)        -> refraction height and amount; amount is sent negated
 *   HighlightStyle.Default    -> white stroke 0.5.dp, blur width/2, angle 45 deg,
 *                                falloff 1, BlendMode.Plus
 *   InnerShadow.Default       -> black 0.15, radius 24.dp, offset (0, +radius)
 *
 * Where a value is given as a fraction rather than in dp it is because a photo watermark has
 * no display density to resolve against. The material is scaled to the rendered capsule rather
 * than to the camera screen, keeping its appearance consistent across output resolutions.
 */
data class GlassParams(

    // ---- geometry -----------------------------------------------------------------------

    /**
     * Refraction band height, as a fraction of the panel's short side.
     *
     * Calibrated against the reference project's own Apple comparison, where a 300 px glass
     * uses `inputInnerRefractionHeight = 20`: a band of ~7% of the panel.
     */
    val refractionHeightFraction: Float = 0.08f,

    /**
     * Peak displacement, as a fraction of the refraction band.
     *
     * The watermark capsule is much wider than tall, so the reference look uses a gentle
     * displacement instead of the exaggerated lens bending produced by values near 3.0.
     */
    val refractionAmountFraction: Float = 1.2f,

    /** Corner radius as a fraction of the panel's short side, when it cannot be measured. */
    val cornerRadiusFraction: Float = 0.5f,

    // ---- backdrop -----------------------------------------------------------------------

    /** Gaussian blur before the lens, as a fraction of the refraction band.
     *  Set to 0 per VitreMark defaults (blurRadiusDp = 0). */
    val blurFraction: Float = 0f,

    /** `vibrancy()`. Disabled by default per VitreMark (vibrancyEnabled = false). */
    val vibrancy: Float = 1f,
    val vibrancyBrightness: Float = 0f,

    // ---- lens ---------------------------------------------------------------------------

    /** Lean the surface normal toward the panel centre; the reference's `depthEffect`. */
    val depthEffect: Float = 1f,

    /** Split refraction into the reference's seven-tap spectrum.
     *  Disabled by default per VitreMark (chromaticAberration = false). */
    val chromaticAberration: Boolean = false,
    val dispersionIntensity: Float = 1f,

    // ---- surface ------------------------------------------------------------------------

    /** Hue tint colour, applied with hue blending at [tintHueWeight]. */
    val tintColor: Int = 0xFFFFFFFF.toInt(),
    val tintHueWeight: Float = 0.75f,

    /** Alpha of the tint's normal-blend pass. */
    val tintAlpha: Float = 0.08f,

    /** Additional flat white veil; the reference's `surfaceColor`.
     * Set to 0.15 per the iOS reference analysis (background transparency ≈ 15%). */
    val surfaceAlpha: Float = 0.15f,

    /**
     * Additive frosted lift applied across the whole panel, in 0-255 units.
     *
     * Apple's material is not transparent glass: it keeps a faint luminous frost so white
     * labels stay legible over any photo. Without this the same photo content that makes the
     * refraction convincing also swallows the labels.
     *
     * 10 -> 6: the stacked veil (tint + surface + lift ~= 19%) read as a grey slab on
     * the lower half of a flat capsule, where there is no top highlight to balance it.
     */
    val interiorLift: Float = 6f,

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

    /** The reference's `Highlight` modifier: 0.5.dp white stroke at [rimAlpha].
     * Set to 0.30 per the iOS reference analysis (edge highlight opacity ≈ 30%). */
    val rimAlpha: Float = 0.30f,

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

    /** Disabled: the inner shadow produced a dark band that read as a grey slab on
     *  flat capsules. Removed per user request. */
    val innerShadowAlpha: Float = 0f,
    val innerShadowRadiusFraction: Float = 0.10f,
    val innerShadowOffsetXFraction: Float = 0f,
    val innerShadowOffsetYFraction: Float = 1f,

    // ---- adaptive -----------------------------------------------------------------------

    /**
     * Scale tint, veil and rim against the backdrop's mean luminance. Apple's material
     * thins out over a bright backdrop and densifies over a dark one, which is also what
     * keeps white watermark text legible on both.
     */
    val adaptive: Boolean = true,

    // ---- VitreMark-aligned watermark layout parameters ----------------------------------

    /** Watermark height in dp (VitreMark default: 35dp). */
    val watermarkHeightDp: Float = 35f,

    /** Watermark length factor relative to image width (VitreMark default: 1.0 = full width). */
    val watermarkLengthFactor: Float = 1.0f,

    /** Watermark horizontal offset in dp (VitreMark default: 0). */
    val watermarkOffsetX: Float = 0f,

    /** Watermark vertical offset factor (VitreMark default: 1.0 = bottom edge). */
    val watermarkOffsetY: Float = 1.0f,

    /** Device name text scale (VitreMark default: 1.0, range 0.5–2.0). */
    val deviceTextScale: Float = 1.0f,

    /** Metadata text scale (VitreMark default: 1.0, range 0.5–2.0). */
    val metaTextScale: Float = 1.0f,

    /** Metadata line spacing multiplier (VitreMark default: -0.18, range -1.0–1.0). */
    val metaLineSpacing: Float = -0.18f,

    /** Logo size scale (VitreMark default: 1.0, range 0.01–2.0). */
    val logoSizeScale: Float = 1.0f,

    /** Logo position: "FRONT", "MIDDLE", or "BACK" (VitreMark default: "MIDDLE"). */
    val logoPosition: String = "MIDDLE",

    /** Show capture (EXIF) line (VitreMark default: true). */
    val showCaptureLine: Boolean = true,

    /** Show timestamp line (VitreMark default: true). */
    val showTimestampLine: Boolean = true,

    /** Invert text colour (white→black) (VitreMark default: false). */
    val textColorInvert: Boolean = false,

    /** Bold text (VitreMark default: false). */
    val textBold: Boolean = false,
) {

    companion object {

        val Default = GlassParams()

        // `BrightScene` and `DarkScene` were removed. They used to be what
        // `WatermarkHooks.resolveParams()` returned whenever adaptive mode was on, which meant
        // every user setting was discarded for every shot. Adaptive mode now scales the user's
        // own tint/surface/rim by the measured brightness, so a preset has nothing left to do.
        // They are removed rather than left as dead constants so the next preset cannot quietly
        // reintroduce that bug.
    }
}