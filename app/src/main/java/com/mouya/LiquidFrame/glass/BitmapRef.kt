package com.mouya.LiquidFrame.glass

/**
 * The one thing [LiquidGlassRenderer] needs from a bitmap.
 *
 * Keeping the renderer on this interface rather than on `android.graphics.Bitmap` means the
 * optics can be unit-tested on the JVM without an Android runtime, and it keeps the
 * reflection-facing code in `WatermarkHooks` free of rendering concerns.
 */
interface BitmapRef {
    val width: Int
    val height: Int
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int)
    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int)
}

/** Plain array backed implementation, used by tests and as a scratch target. */
class IntArrayBitmap(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray = IntArray(width * height),
) : BitmapRef {

    init {
        require(pixels.size == width * height) { "pixel buffer must be width * height" }
    }

    fun pixelAt(x: Int, y: Int): Int = pixels[y * width + x]

    override fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (row in 0 until h) {
            System.arraycopy(this.pixels, (y + row) * width + x, pixels, offset + row * stride, w)
        }
    }

    override fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (row in 0 until h) {
            System.arraycopy(pixels, offset + row * stride, this.pixels, (y + row) * width + x, w)
        }
    }

    companion object {
        fun fill(width: Int, height: Int, color: Int): IntArrayBitmap =
            IntArrayBitmap(width, height, IntArray(width * height) { color })
    }
}
