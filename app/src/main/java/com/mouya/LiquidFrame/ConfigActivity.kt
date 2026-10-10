package com.mouya.LiquidFrame

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mouya.LiquidFrame.glass.GlassParams
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.PanelRect
import com.mouya.LiquidFrame.ui.GlassCard
import com.mouya.LiquidFrame.ui.LiquidBackdropProvider
import com.mouya.LiquidFrame.ui.LiquidColors
import com.mouya.LiquidFrame.ui.LiquidPage
import com.mouya.LiquidFrame.ui.LiquidToggleRow
import com.mouya.LiquidFrame.ui.LiquidSliderRow
import com.mouya.LiquidFrame.ui.SettingDivider
import com.mouya.LiquidFrame.ui.SettingRow
import com.mouya.LiquidFrame.ui.SettingSection
import com.mouya.LiquidFrame.ui.AboutPage
import com.mouya.LiquidFrame.ui.GlassBottomBar
import com.mouya.LiquidFrame.ui.bgPrimary
import com.mouya.LiquidFrame.ui.isDark
import com.mouya.LiquidFrame.ui.liquidGlassSurface
import com.mouya.LiquidFrame.ui.textPrimary
import com.mouya.LiquidFrame.ui.textSecondary
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ConfigActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LiquidFrame-Preview").apply { isDaemon = true }
    }
    private val previewGeneration = AtomicLong(0L)
    private var previewRunnable: Runnable? = null
    private var basePhoto: Bitmap? = null
    private var backdropSource = "内置背景 background-a.jpg"
    private var backdropRevision = mutableIntStateOf(0)

    private val pickBackdrop = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) loadBackdrop(uri)
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

        basePhoto = loadAssetBackdrop() ?: buildSamplePhoto(PREVIEW_W, PREVIEW_H)
        setContent {
            val colors = if (isDark()) darkColorScheme(
                primary = LiquidColors.blue,
                background = LiquidColors.darkBg,
                surface = LiquidColors.darkSurface,
                onBackground = LiquidColors.darkTextPrimary,
                onSurface = LiquidColors.darkTextPrimary,
            ) else lightColorScheme(
                primary = LiquidColors.blue,
                background = LiquidColors.lightBg,
                surface = LiquidColors.lightSurface,
                onBackground = LiquidColors.lightTextPrimary,
                onSurface = LiquidColors.lightTextPrimary,
            )
            MaterialTheme(colorScheme = colors) { LiquidFrameDashboard() }
        }
    }

    override fun onDestroy() {
        previewGeneration.incrementAndGet()
        previewRunnable?.let(mainHandler::removeCallbacks)
        mainHandler.removeCallbacksAndMessages(null)
        previewExecutor.shutdownNow()
        basePhoto?.recycle()
        basePhoto = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Backdrop picker
    // ---------------------------------------------------------------------------------------

    private fun chooseBackdrop() {
        pickBackdrop.launch(arrayOf("image/*"))
    }

    private fun loadBackdrop(uri: Uri) {
        val decoded = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Throwable) {
            null
        } ?: run {
            Toast.makeText(this, "无法读取这张图片", Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = centreCrop(decoded, PREVIEW_W, PREVIEW_H)
        basePhoto?.recycle()
        basePhoto = cropped
        backdropSource = "已选择照片"
        backdropRevision.intValue++
    }

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
        return try {
            Bitmap.createBitmap(scaled, x, y, targetW, targetH).also {
                if (it !== scaled) scaled.recycle()
            }
        } catch (_: Throwable) {
            scaled
        }
    }

    // ---------------------------------------------------------------------------------------
    // Dashboard
    // ---------------------------------------------------------------------------------------

    @Composable
    private fun LiquidFrameDashboard() {
        val context = LocalContext.current
        val scroll = rememberScrollState()

        var currentPage by remember { mutableStateOf(LiquidPage.Home) }

        var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var previewResult by remember { mutableStateOf("正在准备预览") }
        var previewSource by remember { mutableStateOf(backdropSource) }
        var writeStatus by remember { mutableStateOf(ConfigStore.lastWriteStatus()) }
        var logText by remember { mutableStateOf(LogHelper.readLog()) }
        val initial = remember { ConfigStore.glassParams() }

        var enabled by remember { mutableStateOf(ConfigStore.isEnabled()) }
        var adaptive by remember { mutableStateOf(initial.adaptive) }
        var preserveContent by remember { mutableStateOf(initial.preserveContent) }
        var dispersion by remember { mutableStateOf(initial.chromaticAberration) }
        var band by remember { mutableFloatStateOf(initial.refractionHeightFraction) }
        var amount by remember { mutableFloatStateOf(initial.refractionAmountFraction) }
        var blur by remember { mutableFloatStateOf(initial.blurFraction) }
        var lift by remember { mutableFloatStateOf(initial.interiorLift) }
        var tint by remember { mutableFloatStateOf(initial.tintAlpha) }
        var rim by remember { mutableFloatStateOf(initial.rimAlpha) }
        var angle by remember { mutableFloatStateOf(initial.highlightAngleDeg) }
        var depth by remember { mutableFloatStateOf(initial.depthEffect) }

        val currentBackdropRevision = backdropRevision.intValue
        LaunchedEffect(currentBackdropRevision) {
            previewSource = backdropSource
            schedulePreview { bitmap, result ->
                previewBitmap = bitmap
                previewResult = result
                logText = LogHelper.readLog()
            }
        }

        // One debounced write for the whole editor. Dragging a slider no longer forks `su` or
        // rewrites the shared configuration file dozens of times per second.
        LaunchedEffect(
            enabled, adaptive, preserveContent, dispersion,
            band, amount, blur, lift, tint, rim, angle, depth,
        ) {
            kotlinx.coroutines.delay(180)
            val values = ConfigStore.snapshot().apply {
                this[ConfigStore.KEY_ENABLED] = enabled.toString()
                this[ConfigStore.KEY_ADAPTIVE] = adaptive.toString()
                this[ConfigStore.KEY_PRESERVE] = preserveContent.toString()
                this[ConfigStore.KEY_DISPERSION] = dispersion.toString()
                this[ConfigStore.KEY_BAND] = band.toString()
                this[ConfigStore.KEY_AMOUNT] = amount.toString()
                this[ConfigStore.KEY_BLUR] = blur.toString()
                this[ConfigStore.KEY_LIFT] = lift.toString()
                this[ConfigStore.KEY_TINT] = tint.toString()
                this[ConfigStore.KEY_RIM] = rim.toString()
                // The inner shadow is gone from the UI. `KEY_SHADOW` is deliberately no longer
                // written, so a stale `inner_shadow_alpha` in the shared file is simply ignored
                // by the renderer instead of being re-saved on every unrelated slider change.
                this[ConfigStore.KEY_ANGLE] = angle.toString()
                this[ConfigStore.KEY_DEPTH] = depth.toString()
            }
            val status = withContext(Dispatchers.IO) {
                ConfigStore.save(values)
                ConfigStore.lastWriteStatus()
            }
            writeStatus = status
            schedulePreview { bitmap, result ->
                previewBitmap = bitmap
                previewResult = result
                logText = LogHelper.readLog()
            }
        }

        fun resetDefaults() {
            val d = GlassParams.Default
            enabled = true
            adaptive = d.adaptive
            preserveContent = d.preserveContent
            dispersion = d.chromaticAberration
            band = d.refractionHeightFraction
            amount = d.refractionAmountFraction
            blur = d.blurFraction
            lift = d.interiorLift
            tint = d.tintAlpha
            rim = d.rimAlpha
            angle = d.highlightAngleDeg
            depth = d.depthEffect
        }

        Box(Modifier.fillMaxSize().background(bgPrimary())) {
            LiquidBackdropProvider(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    when (currentPage) {
                        LiquidPage.Home -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll)
                                .padding(PaddingValues(start = 18.dp, end = 18.dp, top = 54.dp, bottom = 100.dp)),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text("LiquidFrame", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = textPrimary())
                                }
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(if (enabled) LiquidColors.success.copy(alpha = 0.14f) else LiquidColors.warning.copy(alpha = 0.14f))
                                        .padding(horizontal = 11.dp, vertical = 7.dp),
                                ) {
                                    Text(if (enabled) "已启用" else "已停用", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) LiquidColors.success else LiquidColors.warning)
                                }
                            }

                            GlassCard {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(PREVIEW_W.toFloat() / PREVIEW_H.toFloat())
                                        .clip(RoundedCornerShape(16.dp)),
                                ) {
                                    val bmp = previewBitmap
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "LiquidFrame 水印预览",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    Box(
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .padding(10.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(ComposeColor.Black.copy(alpha = 0.42f))
                                            .padding(horizontal = 9.dp, vertical = 6.dp),
                                    ) {
                                        Text("实时预览", color = ComposeColor.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(previewSource, fontSize = 12.sp, color = textSecondary())
                                        Text(previewResult, fontSize = 11.sp, color = textSecondary(), modifier = Modifier.padding(top = 2.dp))
                                    }
                                    androidx.compose.material3.TextButton(onClick = ::chooseBackdrop) {
                                        Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("换一张", modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                            }

                            SettingSection("核心") {
                                LiquidToggleRow(
                                    "启用 LiquidFrame",
                                    enabled,
                                    { enabled = !enabled },
                                    "下一次拍照开始生效",
                                )
                            }

                            SettingSection("显示") {
                                LiquidToggleRow("自适应明暗", adaptive, { adaptive = !adaptive }, "按画面亮度自动调整玻璃存在感")
                                SettingDivider()
                                LiquidToggleRow("保留原有文字", preserveContent, { preserveContent = !preserveContent }, "只替换背景，不改相机自己的字与 Logo")
                                SettingDivider()
                                LiquidToggleRow("色散", dispersion, { dispersion = !dispersion }, "开启边缘的轻微彩色折射")
                            }

                            SettingSection("维护") {
                                SettingRow(
                                    title = "恢复默认参数",
                                    subtitle = "回到项目出厂的材质比例",
                                    trailing = {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null, tint = LiquidColors.blue, modifier = Modifier.size(20.dp))
                                    },
                                    onClick = ::resetDefaults,
                                )
                                SettingDivider()
                                SettingRow(
                                    title = "复制诊断日志",
                                    subtitle = "包含最近一次检测到的面板与渲染结果",
                                    trailing = {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = LiquidColors.blue, modifier = Modifier.size(20.dp))
                                    },
                                    onClick = {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText(
                                                "LiquidFrame Log",
                                                "write: $writeStatus\nbackdrop: $backdropSource\npreview: $previewResult\n\n$logText",
                                            ),
                                        )
                                        Toast.makeText(context, "诊断日志已复制", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            }

                            Text(
                                "配置写入：$writeStatus",
                                fontSize = 11.sp,
                                color = if (writeStatus.startsWith("写入失败")) LiquidColors.error else textSecondary(),
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        LiquidPage.Glass -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll)
                                .padding(PaddingValues(start = 18.dp, end = 18.dp, top = 54.dp, bottom = 100.dp)),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Text(
                                "玻璃参数",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary(),
                            )
                            SettingSection("材质") {
                                LiquidSliderRow("折射带", band, 0f..0.45f, "")
                                    { band = it }
                                SettingDivider()
                                LiquidSliderRow("折射强度", amount, 0f..5f, "", valueLabel = String.format(java.util.Locale.ROOT, "%.1f×", amount))
                                    { amount = it }
                                SettingDivider()
                                LiquidSliderRow("背景模糊", blur, 0f..1.5f, "")
                                    { blur = it }
                                SettingDivider()
                                LiquidSliderRow("内部提亮", lift, 0f..48f, "")
                                    { lift = it }
                                SettingDivider()
                                LiquidSliderRow("着色", tint, 0f..0.6f, "")
                                    { tint = it }
                                SettingDivider()
                                LiquidSliderRow("边缘高光", rim, 0f..1.2f, "")
                                    { rim = it }
                            }
                            SettingSection("细节") {
                                LiquidSliderRow("高光方向", angle, 0f..360f, "°", valueLabel = "${angle.roundToInt()}°")
                                    { angle = it }
                                SettingDivider()
                                LiquidSliderRow("立体感", depth, 0f..1f, "")
                                    { depth = it }
                            }
                        }

                        LiquidPage.About -> AboutPage(
                            versionName = "1.6.0",
                            versionCode = 6,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Three-tab liquid glass bottom bar
                    GlassBottomBar(
                        currentPage = currentPage,
                        onPageSelected = { currentPage = it },
                        enabled = enabled,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    )
                }
            }
        }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Preview rendering
    // ---------------------------------------------------------------------------------------

    private fun schedulePreview(callback: (Bitmap, String) -> Unit) {
        previewRunnable?.let(mainHandler::removeCallbacks)
        val scheduled = Runnable {
            val generation = previewGeneration.incrementAndGet()
            val source = basePhoto ?: return@Runnable
            val copy = try { source.copy(Bitmap.Config.ARGB_8888, true) } catch (_: Throwable) { null } ?: return@Runnable
            val params = ConfigStore.glassParams()
            previewExecutor.execute {
                val outcome = renderPreview(copy, params)
                mainHandler.post {
                    if (generation != previewGeneration.get() || isFinishing || isDestroyed) {
                        copy.recycle()
                        return@post
                    }
                    callback(copy, outcome)
                }
            }
        }
        previewRunnable = scheduled
        mainHandler.postDelayed(scheduled, 90L)
    }

    private fun renderPreview(bitmap: Bitmap, params: GlassParams): String {
        // Geometry measured from the iPhone liquid-glass reference (832x624 working copy):
        // capsule top=533 bottom=615 -> h=82 = 0.0986 x width, bottom margin 8 = 0.013 x
        // height, full capsule radius = h/2. No white is ever drawn: the glass is rendered
        // straight onto the photo, so no white can remain underneath it.
        val panelH = (bitmap.width * PREVIEW_PANEL_H_FRACTION).toInt().coerceAtLeast(48)
        val side = (bitmap.width * PREVIEW_SIDE_FRACTION).toInt()
        val bottomMargin = (bitmap.height * PREVIEW_BOTTOM_FRACTION).toInt()
        val panel = PanelRect(
            left = side.toFloat(),
            top = (bitmap.height - bottomMargin - panelH).toFloat(),
            width = (bitmap.width - side * 2).toFloat(),
            height = panelH.toFloat(),
            cornerRadiusPx = panelH * 0.5f,
        )
        val result = try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val r = LiquidGlassOptics.renderPanel(
                pixels, bitmap.width, bitmap.height, panel, params,
                // The preview photo is untouched: there is no camera-baked plate inside the
                // panel, the panel's pixels *are* the scene. So the glass samples them directly.
                panelIsOpaquePlate = false,
            )
            if (!r.startsWith("skip")) bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            r
        } catch (t: Throwable) {
            "预览失败：${t.javaClass.simpleName}"
        }
        // No watermark text is drawn here on purpose.
        //
        // The camera draws its own labels into the photo, and the renderer already keeps them
        // (that is the `preserveContent` mask). Faking a label on top of a synthetic photo made
        // the settings screen look faithful while being nothing like the real thing: the model
        // string came from the device, not from the watermark actually selected, its position was
        // a guess, and it was a hardcoded dark grey rather than the camera's own ink. So a
        // preview with text and a photo without it could disagree in wording, placement, colour
        // and size at the same time — which is exactly the "预览和实际拍出来不一致" report.
        //
        // The preview now shows the material and nothing else, so what it shows about the glass
        // is true; the labels are the camera's, and only the camera can show them.
        return result
    }

    // ---------------------------------------------------------------------------------------
    // Asset backdrop
    // ---------------------------------------------------------------------------------------

    /** Load the built-in preview background from assets, or null if it cannot be decoded. */
    private fun loadAssetBackdrop(): Bitmap? {
        return try {
            assets.open("background-a.jpg").use { stream ->
                val decoded = BitmapFactory.decodeStream(stream)
                if (decoded != null) {
                    val cropped = centreCrop(decoded, PREVIEW_W, PREVIEW_H)
                    if (decoded !== cropped) decoded.recycle()
                    // NOTE: no white panel is drawn here. The photo stays pure; the glass is
                    // rendered directly onto it in renderPreview, so no white can remain.
                    cropped
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Synthetic sample
    // ---------------------------------------------------------------------------------------

    /**
     * Fallback scene used only when the bundled preview asset cannot be decoded. It contains no
     * panel and no text on purpose: the glass must sample the photograph itself, and the label is
     * drawn afterwards from whatever the installed camera reports. Painting a white plate here was
     * precisely what made the preview come out grey instead of glassy.
     */
    private fun buildSamplePhoto(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            paint.color = Color.rgb((58 + 96 * t).toInt(), (110 + 66 * t).toInt(), (168 - 40 * t).toInt())
            canvas.drawRect(0f, y.toFloat(), w.toFloat(), y + 1f, paint)
        }
        paint.color = Color.rgb(255, 226, 158)
        canvas.drawCircle(w * 0.74f, h * 0.24f, h * 0.075f, paint)
        paint.strokeWidth = 1f
        for (i in -h until w step 11) {
            paint.color = if ((i / 11) % 2 == 0) Color.argb(46, 255, 255, 255) else Color.argb(30, 12, 18, 34)
            canvas.drawLine(i.toFloat(), 0f, (i + h).toFloat(), h.toFloat(), paint)
        }
        return bitmap
    }

    private companion object {
        const val PREVIEW_W = 900
        const val PREVIEW_H = 420

        // Geometry measured from the iPhone liquid-glass reference photo the user supplied
        // (3326 px wide original, 832x624 working copy):
        //   capsule top=533, bottom=615  -> height 82 px = 0.0986 x width
        //   bottom margin 8 px           -> 0.0128 x height
        //   side margin ~3 px            -> 0.004 x width (the capsule runs almost edge to edge)
        //   corner radius = height / 2   -> full capsule
        const val PREVIEW_PANEL_H_FRACTION = 0.0986f
        const val PREVIEW_SIDE_FRACTION = 0.004f
        const val PREVIEW_BOTTOM_FRACTION = 0.0128f
    }
}