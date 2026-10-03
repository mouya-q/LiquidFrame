package com.mouya.LiquidFrame

import android.content.Context
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/**
 * Helper for discovering obfuscated classes and methods using DexKit signatures.
 * This replaces hardcoded class names like "pe.o" or "Fe.a" with robust queries.
 */
object DexKitHelper {

    private var bridge: DexKitBridge? = null

    /**
     * Initialize the bridge. Must be called once per process load.
     */
    fun init(context: Context) {
        if (bridge != null) return
        try {
            val apkPath = context.applicationInfo.sourceDir
            bridge = DexKitBridge.create(apkPath)
        } catch (e: Throwable) {
            XposedBridge.log("LiquidFrame: DexKit init failed: ${e.message}")
        }
    }

    /**
     * Find the canvas wrapper class (formerly "pe.o").
     * Signature: has a constructor taking a single Bitmap argument.
     */
    private var cachedClassLoader: ClassLoader? = null

    fun setClassLoader(loader: ClassLoader) {
        cachedClassLoader = loader
    }

    fun findCanvasWrapper(): Class<*>? {
        val b = bridge ?: return null
        val loader = cachedClassLoader ?: return null
        return try {
            val results = b.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create().usingStrings("Bitmap")
                )
            )
            // Filter for classes with a Bitmap constructor
            results.firstOrNull { c ->
                c.getInstance(loader)?.declaredConstructors?.any { ctor ->
                    ctor.parameterTypes.size == 1 && 
                    android.graphics.Bitmap::class.java.isAssignableFrom(ctor.parameterTypes[0])
                } == true
            }?.getInstance(loader)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Find the composite method (formerly "Fe.a->j").
     * Signature: returns a Bitmap, takes a Bitmap as first arg, and has 6 total params.
     */
    fun findCompositeMethod(loader: ClassLoader): Method? {
        val b = bridge ?: return null
        return try {
            val results = b.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .returnType("android.graphics.Bitmap")
                        .paramCount(6)
                )
            )
            // Filter for methods where the first parameter is a Bitmap
            results.firstOrNull { m ->
                try {
                    m.getMethodInstance(loader)?.parameterTypes?.get(0) == android.graphics.Bitmap::class.java
                } catch (e: Throwable) {
                    false
                }
            }?.getMethodInstance(loader)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Find the drawRect wrapper method (formerly "pe.o->h").
     * Signature: void, 5 params (4 floats, 1 Paint).
     */
    fun findDrawRectMethod(canvasWrapperClass: Class<*>): Method? {
        return try {
            canvasWrapperClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 5 &&
                m.parameterTypes[0] == Float::class.javaPrimitiveType &&
                m.parameterTypes[4].name.contains("Paint")
            }
        } catch (e: Throwable) {
            null
        }
    }
}