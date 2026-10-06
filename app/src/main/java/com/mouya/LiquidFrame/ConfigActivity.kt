package com.mouya.LiquidFrame

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mouya.LiquidFrame.glass.GlassParams
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.PanelRect
import com.mouya.LiquidFrame.ui.GlassCard
import com.mouya.LiquidFrame.ui.IOSColors
import com.mouya.LiquidFrame.ui.IOSSettingSliderRow
import com.mouya.LiquidFrame.ui.IOSToggleRow
import com.mouya.LiquidFrame.ui.bgPrimary
import com.mouya.LiquidFrame.ui.isDark
import com.mouya.LiquidFrame.ui.separatorColor
import com.mouya.LiquidFrame.ui.textPrimary
import com.mouya.LiquidFrame.ui.textSecondary
import java.io.File

/**
 * Settings screen.
 *
 * Everything here is a *tuning surface*, so two things matter more than they otherwise would:
 *
 *  - the preview must be the same optics the camera runs, on a real photograph, or the numbers
 *    on the sliders describe a scene nobody will ever shoot;
 *  - every control must leave evidence that it reached the camera process, because a setting
 *    that only moves a Compose state is indistinguishable from one that works — until it
 *    turns out not to.
 */
class ConfigActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private var renderPending = false
    private var basePhoto: Bitmap? = null

    /** Where the preview backdrop came from, so the UI can be honest about it. */
    private var backdropSource = "未加载"

    private val requestMedia = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            loadBackdrop()
        } else {
            backdropSource = "未授权读取图片，使用合成背景"
            loadBackdrop()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigStore.load()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val darkUi = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkUi
            isAppearanceLightNavigationBars = !darkUi
        }

        if (hasMediaPermission()) {
            loadBackdrop()
        } else {
            backdropSource = "正在请求图片读取权限…"
            requestMedia.launch(readMediaPermission())
        }

        setContent {
            MaterialTheme {
                LiquidFrameDashboard()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        basePhoto?.recycle()
        basePhoto = null
    }

    // ---------------------------------------------------------------------------------------
    // Backdrop
    // ---------------------------------------------------------------------------------------

    /**
     * The user's own photograph, at preview resolution.
     *
     * A synthetic scene was the wrong choice for tuning this material: refraction and dispersion
     * are only judgeable against real tonal range and real detail, and a generated gradient
     * hides exactly the artefacts this panel is prone to. The file is read at a reduced sample
     * size so a 12 MP original never lands in memory, then centre-cropped to the panel's aspect.
     *
     * Reading it needs a runtime permission, and the module cannot ship someone's private photo
     * inside a public repository, so the path is discovered at runtime with a visible fallback
     * rather than being baked in as a resource.
     */
    private fun loadBackdrop() {
        val source = findBackdrop() ?: buildSamplePhoto(PREVIEW_W, PREVIEW_H).also {
            backdropSource = "合成背景（未找到 background-a.jpg）"
        }
        basePhoto?.recycle()
        basePhoto = source
        // Trigger the first preview render.
        ui.postDelayed({ schedulePreview { _, _ -> } }, 80L)
    }

    /** Looks for the backdrop in the usual places, newest candidate first. */
    private fun findBackdrop(): Bitmap? {
        val candidates = mutableListOf<File>()
        val roots = buildList {
            getExternalFilesDir(null)?.parentFile?.parentFile?.let { add(it) }
            add(File("/storage/emulated/0"))
        }
        for (root in roots) {
            candidates += File(root, "Pictures/QQ/background-a.jpg")
            candidates += File(root, "Pictures/QQ/background-a.png")
        }
        val file = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return null

        // Two-pass decode: measure first, then sample down to roughly the preview width so the
        // original never gets fully decoded into a bitmap we immediately throw away.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= PREVIEW_W) sample *= 2
        val decoded = try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (_: Throwable) {
            null
        } ?: return null

        val cropped = centreCrop(decoded, PREVIEW_W, PREVIEW_H)
        backdropSource = "真实照片 background-a.jpg"
        return cropped
    }

    /** Scales and centre-crops so the preview matches the panel's proportions exactly. */
    private fun centreCrop(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (source.width == targetW && source.height == targetH) return source
        val scale = maxOf(targetW.toFloat() / source.width, targetH.toFloat() / source.height)
        val matrix = Matrix().apply { postScale(scale, scale) }
        val scaled = try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (_: Throwable) {
            source
        }
        if (scaled !== source) source.recycle()

        val x = ((scaled.width - targetW) / 2).coerceAtLeast(0)
        val y = ((scaled.height - targetH) / 2).coerceAtLeast(0)
        if (x == 0 && y == 0 && scaled.width == targetW && scaled.height == targetH) return scaled
        val out = try {
            Bitmap.createBitmap(scaled, x, y, targetW, targetH)
        } catch (_: Throwable) {
            scaled
        }
        if (out !== scaled) scaled.recycle()
        return out
    }

    private fun hasMediaPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        readMediaPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    private fun readMediaPermission(): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    // ---------------------------------------------------------------------------------------
    // Compose UI
    // ---------------------------------------------------------------------------------------

    @Composable
    private fun LiquidFrameDashboard() {
        val context = LocalContext.current
        val scrollState = rememberScrollState()

        var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var previewResult by remember { mutableStateOf("") }
        var writeStatus by remember { mutableStateOf(ConfigStore.lastWriteStatus()) }
        var logText by remember { mutableStateOf("") }

        // Config state
        var enabled by remember { mutableStateOf(ConfigStore.isEnabled()) }
        var adaptive by remember { mutableStateOf(ConfigStore.glassParams().adaptive) }
        var preserveContent by remember { mutableStateOf(ConfigStore.glassParams().preserveContent) }
        var dispersion by remember { mutableStateOf(ConfigStore.glassParams().chromaticAberration) }
        var band by remember { mutableFloatStateOf(ConfigStore.snapshot()[ConfigStore.KEY_BAND]?.toFloatOrNull() ?: 0.18f) }
        var amount by remember { mutableFloatStateOf(ConfigStore.snapshot()[ConfigStore.KEY_AMOUNT]?.toFloatOrNull() ?: 3.0f) }
        var blur by remember { mutableFloatStateOf(ConfigStore.snapshot()[ConfigStore.KEY_BLUR]?.toFloatOrNull() ?: 0.09f) }
        var lift by remember { mutableFloatStateOf(ConfigStore.snapshot()[ConfigStore.KEY_LIFT]?.toFloatOrNull() ?: 10f) }
        var tint by remember { mutableFloatStateOf(ConfigStore.snapshot()[ConfigStore.KEY_TINT]?.toFloatOrNull() ?: 0.10f) }
        var rim by remember { mutableFloatStateOf(ConfigStore.snapshot()[ConfigStore.KEY_RIM]?.toFloatOrNull() ?: 0.50f) }
        var shadow by remember { mutableFloatStateOf(ConfigStore.snapshot()[ConfigStore.KEY_SHADOW]?.toFloatOrNull() ?: 0.15f) }
        var angle by remember { mutableFloatStateOf(ConfigStore.snapshot()[ConfigStore.KEY_ANGLE]?.toFloatOrNull() ?: 45f) }
        var depth by remember { mutableFloatStateOf(ConfigStore.snapshot()[ConfigStore.KEY_DEPTH]?.toFloatOrNull() ?: 1f) }

        // Every mutation goes through here, so no control can change the screen without also
        // changing the file the camera reads.
        fun commit() {
            val values = ConfigStore.snapshot()
            values[ConfigStore.KEY_ENABLED] = enabled.toString()
            values[ConfigStore.KEY_ADAPTIVE] = adaptive.toString()
            values[ConfigStore.KEY_PRESERVE] = preserveContent.toString()
            values[ConfigStore.KEY_DISPERSION] = dispersion.toString()
            values[ConfigStore.KEY_BAND] = band.toString()
            values[ConfigStore.KEY_AMOUNT] = amount.toString()
            values[ConfigStore.KEY_BLUR] = blur.toString()
            values[ConfigStore.KEY_LIFT] = lift.toString()
            values[ConfigStore.KEY_TINT] = tint.toString()
            values[ConfigStore.KEY_RIM] = rim.toString()
            values[ConfigStore.KEY_SHADOW] = shadow.toString()
            values[ConfigStore.KEY_ANGLE] = angle.toString()
            values[ConfigStore.KEY_DEPTH] = depth.toString()
            ConfigStore.save(values)
            writeStatus = ConfigStore.lastWriteStatus()
            schedulePreview { bitmap, result ->
                previewBitmap = bitmap
                previewResult = result
                logText = LogHelper.readLog()
            }
        }

        LaunchedEffect(Unit) {
            schedulePreview { bitmap, result ->
                previewBitmap = bitmap
                previewResult = result
                logText = LogHelper.readLog()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgPrimary()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 52.dp, bottom = 48.dp)),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "LiquidFrame",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary(),
                )
                Text(
                    "液态玻璃水印 · iOS-style Liquid Glass",
                    fontSize = 14.sp,
                    color = textSecondary(),
                )

                // ---- preview -------------------------------------------------------------
                GlassCard {
                    val bmp = previewBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "液态玻璃水印预览",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(170.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(ComposeColor(0xFF1C1C20)),
                        )
                    }
                    Text(
                        "背景：$backdropSource",
                        fontSize = 12.sp,
                        color = textSecondary(),
                    )
                    if (previewResult.isNotEmpty()) {
                        Text(
                            previewResult,
                            fontSize = 11.sp,
                            color = textSecondary(),
                        )
                    }
                    Text(
                        writeStatus,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (writeStatus.startsWith("写入失败")) ComposeColor(0xFFFF3B30)
                        else IOSColors.green,
                    )
                }

                // ---- switches -----------------------------------------------------------
                GlassCard {
                    IOSToggleRow(
                        label = "启用模块",
                        subtitle = "关闭后相机完全不处理水印",
                        checked = enabled,
                        onToggle = { enabled = !enabled; commit() },
                    )
                    IOSToggleRow(
                        label = "自适应（按画面明暗调整）",
                        subtitle = "亮场景变薄、暗场景变浓",
                        checked = adaptive,
                        onToggle = { adaptive = !adaptive; commit() },
                    )
                    IOSToggleRow(
                        label = "保留相机原有文字",
                        checked = preserveContent,
                        onToggle = { preserveContent = !preserveContent; commit() },
                    )
                    IOSToggleRow(
                        label = "色散（边缘彩边）",
                        subtitle = "七段光谱折射",
                        checked = dispersion,
                        onToggle = { dispersion = !dispersion; commit() },
                    )
                }

                // ---- sliders ------------------------------------------------------------
                GlassCard {
                    IOSSettingSliderRow("折射带高度", band, 0f..0.45f, "", onValueChange = { band = it; commit() })
                    IOSSettingSliderRow("折射强度", amount, 0f..5f, "", onValueChange = { amount = it; commit() })
                    IOSSettingSliderRow("背景模糊", blur, 0f..1.5f, "", onValueChange = { blur = it; commit() })
                    IOSSettingSliderRow("内部提亮", lift, 0f..48f, "", onValueChange = { lift = it; commit() })
                    IOSSettingSliderRow("着色", tint, 0f..0.6f, "", onValueChange = { tint = it; commit() })
                    IOSSettingSliderRow("边缘高光", rim, 0f..1.2f, "", onValueChange = { rim = it; commit() })
                    IOSSettingSliderRow("内阴影", shadow, 0f..0.6f, "", onValueChange = { shadow = it; commit() })
                    IOSSettingSliderRow("高光方向", angle, 0f..360f, "°", onValueChange = { angle = it; commit() })
                    IOSSettingSliderRow("立体感", depth, 0f..1f, "", onValueChange = { depth = it; commit() })
                }

                // ---- actions ------------------------------------------------------------
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            val d = GlassParams.Default
                            enabled = true; adaptive = d.adaptive; preserveContent = d.preserveContent
                            dispersion = d.chromaticAberration; band = d.refractionHeightFraction
                            amount = d.refractionAmountFraction; blur = d.blurFraction; lift = d.interiorLift
                            tint = d.tintAlpha; rim = d.rimAlpha; shadow = d.innerShadowAlpha
                            angle = d.highlightAngleDeg; depth = d.depthEffect
                            commit()
                        },
                        modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = IOSColors.blue.copy(alpha = 0.15f)),
                    ) {
                        Text("恢复默认", color = IOSColors.blue, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    "LiquidFrame Log",
                                    "write: $writeStatus\nbackdrop: $backdropSource\n\n${LogHelper.readLog()}",
                                ),
                            )
                            Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = IOSColors.blue.copy(alpha = 0.15f)),
                    ) {
                        Text("复制日志", color = IOSColors.blue, fontWeight = FontWeight.SemiBold)
                    }
                }

                // ---- log ----------------------------------------------------------------
                GlassCard {
                    Text("运行日志", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textPrimary())
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (isDark()) ComposeColor(0xFF0F0F10) else ComposeColor(0xFFF4F4F6))
                            .padding(8.dp),
                    ) {
                        Text(
                            logText.ifEmpty { "暂无日志\n拍照后此处会显示处理信息" },
                            fontSize = 11.sp,
                            color = if (isDark()) ComposeColor(0xFFCCCCCC) else ComposeColor(0xFF1C1C1E),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Preview rendering (same optics as the hook)
    // ---------------------------------------------------------------------------------------

    private fun schedulePreview(callback: (Bitmap, String) -> Unit) {
        if (renderPending) return
        renderPending = true
        ui.postDelayed({
            renderPending = false
            renderPreview(callback)
        }, 60L)
    }

    private fun renderPreview(callback: (Bitmap, String) -> Unit) {
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
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
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

        callback(bitmap, outcome)
    }

    // ---------------------------------------------------------------------------------------
    // Fallback scene, used only when the real photo cannot be read
    // ---------------------------------------------------------------------------------------

    private fun buildSamplePhoto(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (y in 0 until h) {
            val t = y.toFloat() / h
            paint.color = Color.rgb(
                (58 + 96 * t).toInt(),
                (110 + 66 * t).toInt(),
                (168 - 40 * t).toInt(),
            )
            canvas.drawRect(0f, y.toFloat(), w.toFloat(), y + 1f, paint)
        }

        paint.color = Color.rgb(255, 226, 158)
        canvas.drawCircle(w * 0.74f, h * 0.24f, h * 0.075f, paint)

        paint.strokeWidth = 1f
        for (i in -h until w step 11) {
            paint.color = if ((i / 11) % 2 == 0) Color.argb(46, 255, 255, 255)
            else Color.argb(30, 12, 18, 34)
            canvas.drawLine(i.toFloat(), 0f, (i + h).toFloat(), h.toFloat(), paint)
        }

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

        return bitmap
    }

    private companion object {
        const val PREVIEW_W = 900
        const val PREVIEW_H = 420
        const val PREVIEW_MARGIN = 34
        const val PREVIEW_PANEL_H = 62
    }
}