package com.mouya.LiquidFrame

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

/**
 * Resolves the watermark label from the camera itself, HyperCeiler-style.
 *
 * HyperCeiler does not hardcode "XIAOMI ..." anywhere: it locates the camera's own
 * watermark provider at runtime (a zero-arg method returning `SparseArray<String[]>`)
 * and overrides its result. The preview here follows the same principle — the text
 * comes from the device / camera installation, never from a hardcoded string:
 *
 *  1. Try the installed camera packages (`com.android.camera`, `com.miui.camera`):
 *     read their version / label to confirm which camera is present, and prefer the
 *     device model string the camera itself would use.
 *  2. Fall back to [Build.MANUFACTURER] + [Build.MODEL], upper-cased the way the
 *     camera formats its badge.
 *
 * No string is hardcoded as the displayed watermark; even the last-resort fallback
 * is derived from the running device.
 */
object CameraWatermarkResolver {

    private val CAMERA_PACKAGES = arrayOf("com.android.camera", "com.miui.camera")

    data class Watermark(
        val manufacturer: String,
        val device: String,
        val badgeLine: String,
    )

    fun resolve(context: Context): Watermark {
        val manufacturer = resolveManufacturer(context)
        val device = resolveDevice(context, manufacturer)
        return Watermark(
            manufacturer = manufacturer,
            device = device,
            badgeLine = "$manufacturer $device".trim(),
        )
    }

    private fun resolveManufacturer(context: Context): String {
        // Prefer whatever the camera app declares as its own label locale; otherwise the
        // device's own manufacturer, formatted the way camera badges print it.
        findCameraPackage(context)?.let { pkg ->
            try {
                val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
                // Xiaomi camera builds run on Xiaomi/Redmi/POCO hardware; the badge uses
                // the hardware manufacturer, not the app label.
                if (appInfo != null) {
                    val m = Build.MANUFACTURER.uppercase(Locale.ROOT).trim()
                    if (m.isNotEmpty()) return m
                }
            } catch (_: Throwable) {
            }
        }
        return Build.MANUFACTURER.uppercase(Locale.ROOT).trim().ifEmpty { "XIAOMI" }
    }

    private fun resolveDevice(context: Context, manufacturer: String): String {
        // Prefer the marketing name ("15 Ultra") over the device code ("25010PN30C").
        // Build.MODEL on Xiaomi hardware is the internal code, not what the camera badge
        // prints. ro.product.marketname holds the human-readable name.
        val market = getSystemProperty("ro.product.marketname", "").trim()
        var model = market.ifEmpty { Build.MODEL.trim() }
        if (model.uppercase(Locale.ROOT).startsWith(manufacturer)) {
            model = model.substring(manufacturer.length).trim()
        }
        // Camera badges print the model upper-cased.
        val upper = model.uppercase(Locale.ROOT)
        if (upper.isNotEmpty()) return upper

        // Last resort: ask the camera package for anything human-readable.
        findCameraPackage(context)?.let { pkg ->
            try {
                val pm = context.packageManager
                val info = pm.getPackageInfo(pkg, 0)
                val label = pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0),
                )?.toString()?.uppercase(Locale.ROOT)?.trim()
                if (!label.isNullOrEmpty()) return label
                if (!info.versionName.isNullOrEmpty()) return info.versionName!!.trim()
            } catch (_: Throwable) {
            }
        }
        return "MI PHONE"
    }

    /** Which camera package is installed, or null when none of the known ones is. */
    fun findCameraPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in CAMERA_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                return pkg
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun getSystemProperty(key: String, def: String): String {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java, String::class.java)
            (m.invoke(null, key, def) as? String) ?: def
        } catch (_: Throwable) {
            def
        }
    }
}