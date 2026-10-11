package com.mouya.LiquidFrame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
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

        // The settings app cannot force-stop the camera (no privileged permission in its
        // manifest), so the camera registers a kill switch instead: the restart button in
        // the settings sends an explicit broadcast and the camera kills itself on receipt.
        // Registration is async because handleLoadPackage usually runs before the host
        // Application exists. ActivityThread is @hide, so it is resolved by name.
        val app = try {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", param.classLoader),
                "currentApplication",
            ) as? Context
        } catch (_: Throwable) {
            null
        }
        app?.let { ctx ->
            try {
                ctx.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            // Only honour the kill switch from the module's own settings app.
                            val senderOk = context.packageManager
                                .getPackagesForUid(getSendingUid())
                                ?.any { it == "com.mouya.LiquidFrame" } == true
                            if (!senderOk) return
                            XposedBridge.log("LiquidFrame: restart camera broadcast received, killing self")
                            Process.killProcess(Process.myPid())
                        }
                    },
                    IntentFilter("com.mouya.LiquidFrame.ACTION_RESTART_CAMERA"),
                    Context.RECEIVER_EXPORTED,
                )
                XposedBridge.log("LiquidFrame: kill-switch receiver registered")
            } catch (t: Throwable) {
                XposedBridge.log("LiquidFrame: receiver registration failed: $t")
            }
        }
    }
}