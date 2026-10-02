package com.mouya.LiquidFrame

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.mouya.LiquidFrame.glass.AndroidBitmapRef
import com.mouya.LiquidFrame.glass.GlassParams
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.PanelRect

/**
 * Settings, with a live preview of the material.
 *
 * The preview matters more than it looks: the optics run off-device on a synthetic scene here,
 * so the whole material can be judged and tuned without capturing a photo on the phone. Every
 * change is written straight to [ConfigStore], which the camera process re-reads, so a slider
 * takes effect on the next capture without restarting anything.
 */
class ConfigActivity : Activity() {

    private lateinit var preview: ImageView
    private lateinit var logView: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var renderPending = false
    private var basePhoto: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigStore.load()
        basePhoto = buildSamplePhoto(PREVIEW_W, PREVIEW_H)
        setContentView(buildUi())
        updateLogView()
        schedulePreview()
    }

    override fun onResume() {
        super.onResume()
        updateLogView()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        basePhoto?.recycle()
        basePhoto = null
    }

    // ---------------------------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(label("LiquidFrame · 液态玻璃水印", 20f))
        root.addView(note("预览是用同样的算法在本机渲染的，改参数会立刻重画。"))

        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(28, 28, 32))
        }
        root.addView(
            preview,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150)
            ).apply { topMargin = dp(8) },
        )

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = dp(7) },
        )

        // ---- switches ------------------------------------------------------------------
        body.addView(check("启用模块", ConfigStore.KEY_ENABLED, ConfigStore.isEnabled()))
        body.addView(
            check(
                "自适应（按画面明暗调整）", ConfigStore.KEY_ADAPTIVE,
                ConfigStore.glassParams().adaptive,
            )
        )
        body.addView(
            check(
                "保留相机原有文字", "preserve_content",
                ConfigStore.glassParams().preserveContent,
            )
        )
        body.addView(
            check(
                "色散（边缘彩边）", ConfigStore.KEY_DISPERSION,
                ConfigStore.glassParams().chromaticAberration,
            )
        )

        // ---- sliders -------------------------------------------------------------------
        body.addView(slider("折射带高度", ConfigStore.KEY_BAND, 0f, 0.45f, 0.01f))
        body.addView(slider("折射强度", ConfigStore.KEY_AMOUNT, 0f, 5f, 0.05f))
        body.addView(slider("背景模糊", ConfigStore.KEY_BLUR, 0f, 1.5f, 0.01f))
        body.addView(slider("内部提亮", ConfigStore.KEY_LIFT, 0f, 48f, 1f))
        body.addView(slider("着色", ConfigStore.KEY_TINT, 0f, 0.6f, 0.01f))
        body.addView(slider("边缘高光", ConfigStore.KEY_RIM, 0f, 1.2f, 0.01f))
        body.addView(slider("内阴影", ConfigStore.KEY_SHADOW, 0f, 0.6f, 0.01f))
        body.addView(slider("高光方向", ConfigStore.KEY_ANGLE, 0f, 360f, 1f))
        body.addView(slider("立体感", ConfigStore.KEY_DEPTH, 0f, 1f, 0.05f))

        body.addView(button("恢复默认") {
            ConfigStore.save(defaults())
            recreate()
        })
        body.addView(button("复制日志") { copyLog() })

        body.addView(label("运行日志", 15f).apply { setPadding(0, dp(18), 0, dp(6)) })
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(244, 244, 246))
            setTextColor(Color.rgb(28, 28, 32))
        }
        body.addView(logView)

        return root
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(24, 24, 28))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.rgb(110, 110, 118))
        setPadding(0, dp(4), 0, 0)
    }

    private fun check(title: String, key: String, initial: Boolean): View {
        val box = CheckBox(this).apply {
            text = title
            textSize = 14f
            isChecked = initial
            setPadding(0, dp(6), 0, dp(2))
            setOnCheckedChangeListener { _, value ->
                val values = ConfigStore.snapshot()
                values[key] = value.toString()
                ConfigStore.save(values)
                schedulePreview()
            }
        }
        return box
    }

    private fun slider(title: String, key: String, min: Float, max: Float, step: Float): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val current = ConfigStore.snapshot()[key]?.toFloatOrNull() ?: min
        val steps = ((max - min) / step).toInt().coerceAtLeast(1)

        val caption = TextView(this).apply {
            textSize = 14f
            text = "$title: ${fmt(current)}"
            setTextColor(Color.rgb(24, 24, 28))
        }
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = (((current - min) / step).toInt()).coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val v = min + value * step
                    caption.text = "$title: ${fmt(v)}"
                    if (!fromUser) return
                    val values = ConfigStore.snapshot()
                    values[key] = v.toString()
                    ConfigStore.save(values)
                    schedulePreview()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        wrap.addView(caption)
        wrap.addView(bar)
        return wrap
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        setOnClickListener { action() }
    }

    private fun defaults(): MutableMap<String, String> {
        val d = GlassParams.Default
        return linkedMapOf(
            ConfigStore.KEY_ENABLED to "true",
            ConfigStore.KEY_BAND to d.refractionHeightFraction.toString(),
            ConfigStore.KEY_AMOUNT to d.refractionAmountFraction.toString(),
            ConfigStore.KEY_BLUR to d.blurFraction.toString(),
            ConfigStore.KEY_LIFT to d.interiorLift.toString(),
            ConfigStore.KEY_TINT to d.tintAlpha.toString(),
            ConfigStore.KEY_RIM to d.rimAlpha.toString(),
            ConfigStore.KEY_SHADOW to d.innerShadowAlpha.toString(),
            ConfigStore.KEY_DEPTH to d.depthEffect.toString(),
            ConfigStore.KEY_ANGLE to d.highlightAngleDeg.toString(),
            ConfigStore.KEY_DISPERSION to d.chromaticAberration.toString(),
            ConfigStore.KEY_ADAPTIVE to d.adaptive.toString(),
            "preserve_content" to d.preserveContent.toString(),
        )
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------------------------------
    // Preview
    // ---------------------------------------------------------------------------------------

    private fun schedulePreview() {
        if (renderPending) return
        renderPending = true
        // Coalesce slider bursts: the optics are fast, but not free.
        ui.postDelayed({
            renderPending = false
            renderPreview()
        }, 60L)
    }

    private fun renderPreview() {
        val source = basePhoto ?: return
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true) ?: return

        val params = ConfigStore.glassParams()
        val panel = PanelRect(
            left = PREVIEW_MARGIN.toFloat(),
            top = (source.height - PREVIEW_MARGIN - PREVIEW_PANEL_H).toFloat(),
            width = (source.width - PREVIEW_MARGIN * 2).toFloat(),
            height = PREVIEW_PANEL_H.toFloat(),
            cornerRadiusPx = PREVIEW_PANEL_H * 0.32f,
        )

        val outcome = try {
            val pixels = pixelsOf(bitmap)
            val result = LiquidGlassOptics.renderPanel(
                pixels = pixels,
                width = bitmap.width,
                height = bitmap.height,
                panel = panel,
                params = params,
            )
            if (!result.startsWith("skip")) {
                bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            result
        } catch (t: Throwable) {
            "error: ${t.javaClass.simpleName}: ${t.message}"
        }

        preview.setImageBitmap(bitmap)
        logView.text = "预览: $outcome\n\n" + LogHelper.readLog()
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    /**
     * A sample frame with a high-frequency, mid-tone scene: fine detail is what makes
     * refraction and dispersion visible, and a flat or near-white backdrop would make the
     * material impossible to judge.
     */
    private fun buildSamplePhoto(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sky.
        for (y in 0 until h) {
            val t = y.toFloat() / h
            paint.color = Color.rgb(
                (58 + 96 * t).toInt(),
                (110 + 66 * t).toInt(),
                (168 - 40 * t).toInt(),
            )
            canvas.drawRect(0f, y.toFloat(), w.toFloat(), y + 1f, paint)
        }

        // A sun with a soft halo: a smooth gradient for the lens to stretch.
        paint.color = Color.rgb(255, 226, 158)
        canvas.drawCircle(w * 0.74f, h * 0.24f, h * 0.075f, paint)

        // Fine diagonal rules and a tiled grid: the detail the refraction has to bend.
        paint.strokeWidth = 1f
        for (i in -h until w step 11) {
            paint.color = if ((i / 11) % 2 == 0) Color.argb(46, 255, 255, 255)
            else Color.argb(30, 12, 18, 34)
            canvas.drawLine(i.toFloat(), 0f, (i + h).toFloat(), h.toFloat(), paint)
        }
        for (x in 0 until w step 37) {
            for (y in 0 until h step 37) {
                paint.color = when (((x / 37) + (y / 37)) % 3) {
                    0 -> Color.argb(120, 240, 96, 76)
                    1 -> Color.argb(110, 36, 122, 226)
                    else -> Color.argb(96, 246, 206, 72)
                }
                canvas.drawCircle(x + 18f, y + 18f, 7.5f, paint)
            }
        }

        // A treeline: dark content that tests label legibility.
        val ridge = Path().apply {
            moveTo(0f, h.toFloat())
            var x = 0f
            while (x <= w) {
                lineTo(x, h * 0.72f + (18 * kotlin.math.sin(x / 26.0)).toFloat())
                x += 4f
            }
            lineTo(w.toFloat(), h.toFloat())
            close()
        }
        paint.color = Color.rgb(22, 34, 28)
        canvas.drawPath(ridge, paint)

        // The watermark background panel the camera would bake, plus its labels.
        val left = PREVIEW_MARGIN
        val top = h - PREVIEW_MARGIN - PREVIEW_PANEL_H
        val radius = PREVIEW_PANEL_H * 0.32f
        paint.color = Color.argb(255, 244, 245, 249)
        canvas.drawRoundRect(
            left.toFloat(), top.toFloat(),
            (w - PREVIEW_MARGIN).toFloat(), (top + PREVIEW_PANEL_H).toFloat(),
            radius, radius, paint,
        )
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = PREVIEW_PANEL_H * 0.30f
        paint.color = Color.rgb(60, 60, 66)
        val baseline = top + PREVIEW_PANEL_H * 0.64f
        canvas.drawText("XIAOMI 15 ULTRA", left + PREVIEW_PANEL_H * 0.42f, baseline, paint)
        val stamp = "2026-10-02 19:30"
        val stampWidth = paint.measureText(stamp)
        canvas.drawText(
            stamp,
            w - PREVIEW_MARGIN - PREVIEW_PANEL_H * 0.42f - stampWidth,
            baseline,
            paint,
        )

        return bitmap
    }

    // ---------------------------------------------------------------------------------------

    private fun updateLogView() {
        val log = LogHelper.readLog()
        logView.text = log.ifEmpty { "暂无日志\n拍照后此处会显示处理信息" }
    }

    private fun copyLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("LiquidFrame Log", LogHelper.readLog())
        )
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREVIEW_W = 900
        const val PREVIEW_H = 420
        const val PREVIEW_MARGIN = 34
        const val PREVIEW_PANEL_H = 62
    }
}
