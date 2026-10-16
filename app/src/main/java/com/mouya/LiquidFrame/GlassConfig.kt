package com.mouya.LiquidFrame

import com.mouya.LiquidFrame.glass.GlassParams

/**
 * The module's view of the current settings, inside the camera process.
 *
 * The config UI runs in the module's own process, so nothing here is shared memory: values are
 * read from [ConfigStore] at a bounded interval, which lets a slider take effect without
 * restarting the camera.
 */
object GlassConfig {

    private const val TAG = "LiquidFrame"

    /** Serve the user's settings. */
    var masterEnabled: Boolean = true
        private set

    /** Let the scene decide tint, veil and rim strength. */
    var adaptiveGlass: Boolean = true
        private set

    private var cachedParams: GlassParams = GlassParams.Default

    fun init(classLoader: ClassLoader) {
        refresh(force = true)
    }

    /** Re-reads settings when the cache has expired. Cheap enough to call per capture. */
    fun refresh(force: Boolean = false) {
        if (force) ConfigStore.load() else ConfigStore.loadIfStale()
        masterEnabled = ConfigStore.isEnabled()
        cachedParams = ConfigStore.glassParams()
        adaptiveGlass = cachedParams.adaptive

        // The settings screen has no way to prove the camera actually received a change other
        // than this line, so every refresh that observes a different `enabled` than the last one
        // is announced. If the user's toggle never shows up here, the file is not being read.
        if (masterEnabled != lastReportedEnabled) {
            lastReportedEnabled = masterEnabled
            LogHelper.log(
                TAG,
                "settings received: enabled=$masterEnabled adaptive=$adaptiveGlass " +
                        "band=${cachedParams.refractionHeightFraction} " +
                        "amount=${cachedParams.refractionAmountFraction} " +
                        "blur=${cachedParams.blurFraction}",
            )
        }
    }

    private var lastReportedEnabled: Boolean? = null

    /** The material parameters for this capture. */
    fun params(): GlassParams {
        refresh()
        return cachedParams
    }
}