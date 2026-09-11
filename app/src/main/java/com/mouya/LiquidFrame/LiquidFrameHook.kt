package com.mouya.LiquidFrame

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class LiquidFrameHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.android.camera" && param.packageName != "com.miui.camera") return

        XposedBridge.log("LiquidFrame: Camera loaded (${param.packageName})")

        GlassConfig.init(param.classLoader)
        WatermarkHooks.install(param)
    }
}