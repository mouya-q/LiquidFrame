package com.mouya.LiquidFrame.glass

import android.graphics.Bitmap

/** Adapts a real Android [Bitmap] to the renderer's [BitmapRef] port. */
class AndroidBitmapRef(private val bitmap: Bitmap) : BitmapRef {

    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        bitmap.getPixels(pixels, offset, stride, x, y, w, h)
    }

    override fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        bitmap.setPixels(pixels, offset, stride, x, y, w, h)
    }
}
