package com.mouya.LiquidFrame

import com.mouya.LiquidFrame.glass.GlassParams

object GlassConfig {

    private const val TAG = "LiquidFrame"

    var masterEnabled: Boolean = true
        private set

    var adaptiveGlass: Boolean = true
        private set

    private var cachedParams: GlassParams = GlassParams.Default

    fun init(classLoader: ClassLoader) {
        refresh(force = true)
    }

    fun refresh(force: Boolean = false) {
        if (force) ConfigStore.load() else ConfigStore.loadIfStale()
        masterEnabled = ConfigStore.isEnabled()
        cachedParams = ConfigStore.glassParams()
        adaptiveGlass = cachedParams.adaptive

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

    fun params(): GlassParams {
        refresh()
        return cachedParams
    }
}
