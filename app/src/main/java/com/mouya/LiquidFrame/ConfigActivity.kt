package com.mouya.LiquidFrame

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mouya.LiquidFrame.glass.GlassParams
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.PanelRect
import com.mouya.LiquidFrame.ui.AboutPage
import com.mouya.LiquidFrame.ui.GlassBottomBar
import com.mouya.LiquidFrame.ui.GlassCard
import com.mouya.LiquidFrame.ui.LiquidBackdropProvider
import com.mouya.LiquidFrame.ui.LiquidColors
import com.mouya.LiquidFrame.ui.LiquidPage
import com.mouya.LiquidFrame.ui.LiquidSliderRow
import com.mouya.LiquidFrame.ui.LiquidToggleRow
import com.mouya.LiquidFrame.ui.SettingDivider
import com.mouya.LiquidFrame.ui.SettingRow
import com.mouya.LiquidFrame.ui.SettingSection
import com.mouya.LiquidFrame.ui.StatusPill
import com.mouya.LiquidFrame.ui.bgPrimary
import com.mouya.LiquidFrame.ui.isDark
import com.mouya.LiquidFrame.ui.textPrimary
import com.mouya.LiquidFrame.ui.textSecondary
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ConfigActivity : ComponentActivity() {

    private companion object {
        const val TAG = "LiquidFrame"
        const val PREVIEW_W = 900
        const val PREVIEW_H = 420

        const val PREVIEW_PANEL_H_FRACTION = 0.065f
        const val PREVIEW_SIDE_FRACTION = 0.05f
        const val PREVIEW_BOTTOM_FRACTION = 0.028f
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LiquidFrame-Preview").apply { isDaemon = true }
    }
    private val previewGeneration = AtomicLong(0L)
    private val backdropLoadGeneration = AtomicLong(0L)
    private var previewRunnable: Runnable? = null
    private var basePhoto: Bitmap? = null
    private var displayedPreviewBitmap: Bitmap? = null
    private var backdropSource = "内置背景 background-a.jpg"
    private var backdropRevision = mutableIntStateOf(0)

    private val pickBackdrop = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) loadBackdrop(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigStore.init(this)
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
        displayedPreviewBitmap?.let { if (!it.isRecycled) it.recycle() }
        displayedPreviewBitmap = null
        basePhoto?.let { if (!it.isRecycled) it.recycle() }
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
        // Opening a user image is a preview task, not UI work. Read bounds first and
        // decode only enough pixels for the 900x420 preview; this avoids decoding a
        // 50–100 MP camera photo on the main thread only to shrink it immediately.
        val generation = backdropLoadGeneration.incrementAndGet()
        previewExecutor.execute {
            val decoded = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= PREVIEW_W &&
                        bounds.outHeight / (sample * 2) >= PREVIEW_H
                    ) {
                        sample *= 2
                    }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "loadBackdrop decode failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            val cropped = decoded?.let { centreCrop(it, PREVIEW_W, PREVIEW_H) }
            mainHandler.post {
                if (generation != backdropLoadGeneration.get() || isFinishing || isDestroyed) {
                    cropped?.recycle()
                    return@post
                }
                if (cropped == null) {
                    Toast.makeText(this, "无法读取这张图片", Toast.LENGTH_SHORT).show()
                    return@post
                }
                basePhoto?.recycle()
                basePhoto = cropped
                backdropSource = "已选择照片"
                backdropRevision.intValue++
            }
        }
    }

    private fun centreCrop(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (source.width == targetW && source.height == targetH) return source
        val scale = maxOf(targetW.toFloat() / source.width, targetH.toFloat() / source.height)
        val matrix = Matrix().apply { postScale(scale, scale) }
        val scaled = try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (t: Throwable) {
            Log.w(TAG, "centreCrop scale failed: ${t.javaClass.simpleName}")
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
        } catch (t: Throwable) {
            Log.w(TAG, "centreCrop crop failed: ${t.javaClass.simpleName}")
            scaled
        }
    }

    // ---------------------------------------------------------------------------------------
    // Dashboard
    // ---------------------------------------------------------------------------------------

    @Composable
    private fun LiquidFrameDashboard() {
        val context = LocalContext.current
        val homeScroll = rememberScrollState()
        val glassScroll = rememberScrollState()

        var currentPage by remember { mutableStateOf(LiquidPage.Home) }

        var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var previewResult by remember { mutableStateOf("正在准备预览") }
        var previewSource by remember { mutableStateOf(backdropSource) }
        var writeStatus by remember { mutableStateOf(ConfigStore.lastWriteStatus()) }
        val initial = remember { ConfigStore.glassParams() }

        var enabled by remember { mutableStateOf(ConfigStore.isEnabled()) }
        var capsuleEnabled by remember { mutableStateOf(ConfigStore.capsuleEnabled()) }
        var capsulePos by remember { mutableStateOf(ConfigStore.capsulePos()) }
        var capsuleModel by remember { mutableStateOf(ConfigStore.capsuleModel()) }
        var capsuleModelEnabled by remember { mutableStateOf(capsuleModel.isNotEmpty()) }
        var capsuleShowDatetime by remember { mutableStateOf(ConfigStore.capsuleShowDatetime()) }
        var capsuleShowExif by remember { mutableStateOf(ConfigStore.capsuleShowExif()) }
        var capsuleShowGps by remember { mutableStateOf(ConfigStore.capsuleShowGps()) }
        var capsuleShowLogo by remember { mutableStateOf(ConfigStore.capsuleShowLogo()) }
        var capsuleScale by remember { mutableStateOf(ConfigStore.capsuleScale()) }
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
                replaceDisplayedPreview(bitmap)
                previewBitmap = bitmap
                previewResult = result
            }
        }

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
                this[ConfigStore.KEY_ANGLE] = angle.toString()
                this[ConfigStore.KEY_DEPTH] = depth.toString()
            }
            val status = withContext(Dispatchers.IO) {
                ConfigStore.save(values)
                ConfigStore.lastWriteStatus()
            }
            writeStatus = status
            schedulePreview { bitmap, result ->
                replaceDisplayedPreview(bitmap)
                previewBitmap = bitmap
                previewResult = result
            }
        }

        // Capsule settings write through the same store but do not re-run the
        // preview: the preview pipeline is the legacy watermark panel, so a
        // capsule edit would only burn a render for an identical image.
        LaunchedEffect(
            capsuleEnabled, capsulePos, capsuleModel, capsuleModelEnabled,
            capsuleShowDatetime, capsuleShowExif, capsuleShowGps, capsuleShowLogo, capsuleScale,
        ) {
            kotlinx.coroutines.delay(180)
            val effectiveModel = if (capsuleModelEnabled) capsuleModel else ""
            val values = ConfigStore.snapshot().apply {
                this[ConfigStore.KEY_CAPSULE_ENABLED] = capsuleEnabled.toString()
                this[ConfigStore.KEY_CAPSULE_POS] = capsulePos
                this[ConfigStore.KEY_CAPSULE_MODEL] = effectiveModel
                this[ConfigStore.KEY_CAPSULE_DATETIME] = capsuleShowDatetime.toString()
                this[ConfigStore.KEY_CAPSULE_EXIF] = capsuleShowExif.toString()
                this[ConfigStore.KEY_CAPSULE_GPS] = capsuleShowGps.toString()
                this[ConfigStore.KEY_CAPSULE_LOGO] = capsuleShowLogo.toString()
                this[ConfigStore.KEY_CAPSULE_SCALE] = capsuleScale.toString()
            }
            writeStatus = withContext(Dispatchers.IO) {
                ConfigStore.save(values)
                ConfigStore.lastWriteStatus()
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
            capsuleEnabled = true
            capsulePos = "center"
            capsuleModel = ""
            capsuleModelEnabled = false
            capsuleShowDatetime = true
            capsuleShowExif = true
            capsuleShowGps = true
            capsuleShowLogo = true
            capsuleScale = 1f
        }

        Box(Modifier.fillMaxSize()) {
            LiquidBackdropProvider(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    when (currentPage) {
                        // ---- Home page: enable toggle, display options, maintenance ----
                        LiquidPage.Home -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(homeScroll)
                                .padding(PaddingValues(start = 18.dp, end = 18.dp, top = 54.dp, bottom = 132.dp)),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_liquidframe),
                                        contentDescription = null,
                                        modifier = Modifier.size(44.dp),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text("liquidFrame", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = textPrimary())
                                        Text(
                                            "CAMERA MATERIAL",
                                            fontSize = 9.sp,
                                            letterSpacing = 1.25.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = textSecondary(),
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    StatusPill(
                                        text = if (enabled) "已启用" else "已停用",
                                        color = if (enabled) LiquidColors.success else LiquidColors.warning,
                                    )
                                    IconButton(
                                        onClick = {
                                            // Restart the camera so the next capture picks up the
                                            // current module state. Primary path: an explicit
                                            // broadcast to the camera's kill switch (registered in
                                            // LiquidFrameHook); fallback: forceStopPackage via
                                            // reflection (a @hide API, usually denied to a plain app
                                            // process, hence only a best-effort second path).
                                            val pm = context.packageManager
                                            val restart = Intent("com.mouya.LiquidFrame.ACTION_RESTART_CAMERA")
                                                .setPackage("com.android.camera")
                                            try {
                                                context.sendBroadcast(restart)
                                            } catch (t: Exception) {
                                                Log.w(TAG, "restart broadcast failed: ${t.javaClass.simpleName}")
                                            }
                                            for (pkg in arrayOf("com.android.camera", "com.miui.camera")) {
                                                try {
                                                    pm.getApplicationInfo(pkg, 0)
                                                    pm.javaClass
                                                        .getMethod("forceStopPackage", String::class.java)
                                                        .invoke(pm, pkg)
                                                } catch (t: Exception) {
                                                    Log.w(TAG, "forceStopPackage $pkg failed: ${t.javaClass.simpleName}")
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Refresh,
                                            contentDescription = "重启相机",
                                            tint = LiquidColors.blue,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }

                            SettingSection("核心") {
                                LiquidToggleRow(
                                    "启用 liquidFrame",
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

                            SettingSection("水印胶囊") {
                                LiquidToggleRow(
                                    "重建胶囊",
                                    capsuleEnabled,
                                    { capsuleEnabled = !capsuleEnabled },
                                    "拍照时擦除原水印，在照片内重建一枚液态玻璃胶囊",
                                )
                                SettingDivider()
                                LiquidToggleRow("徕卡标", capsuleShowLogo, { capsuleShowLogo = !capsuleShowLogo }, "胶囊中央显示徕卡标")
                                SettingDivider()
                                LiquidToggleRow(
                                    "自定义设备名称",
                                    capsuleModelEnabled,
                                    { capsuleModelEnabled = !capsuleModelEnabled },
                                    "使用自定义文字替代系统机型名",
                                )
                                if (capsuleModelEnabled) {
                                    OutlinedTextField(
                                        value = capsuleModel,
                                        onValueChange = { if (it.length <= 32) capsuleModel = it },
                                        singleLine = true,
                                        label = { Text("设备名称", fontSize = 13.sp, color = textSecondary()) },
                                        placeholder = { Text("输入自定义机型文字", fontSize = 13.sp, color = textSecondary()) },
                                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                                    )
                                }
                                SettingDivider()
                                LiquidToggleRow("日期时间", capsuleShowDatetime, { capsuleShowDatetime = !capsuleShowDatetime }, "左列第二行：拍摄时间")
                                SettingDivider()
                                LiquidToggleRow("相机参数", capsuleShowExif, { capsuleShowExif = !capsuleShowExif }, "右列第一行：焦距、光圈、快门、ISO")
                                SettingDivider()
                                LiquidToggleRow("位置信息", capsuleShowGps, { capsuleShowGps = !capsuleShowGps }, "右列第二行：GPS 度分秒坐标")
                                SettingDivider()
                                LiquidSliderRow(
                                    "胶囊大小",
                                    capsuleScale,
                                    0.6f..2.0f,
                                    "",
                                    valueLabel = String.format(java.util.Locale.ROOT, "%.2f×", capsuleScale),
                                ) { capsuleScale = it }
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
                                    subtitle = "包含写入状态与渲染结果",
                                    trailing = {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = LiquidColors.blue, modifier = Modifier.size(20.dp))
                                    },
                                    onClick = {
                                        val diagnostic = buildString {
                                            appendLine("LiquidFrame Diagnostic Log")
                                            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                                            appendLine("写入状态: $writeStatus")
                                            appendLine("预览: $previewResult")
                                            appendLine("背景: $previewSource")
                                            appendLine()
                                            appendLine("--- 参数 ---")
                                            appendLine("enabled=$enabled")
                                            appendLine("adaptive=$adaptive")
                                            appendLine("preserveContent=$preserveContent")
                                            appendLine("band=$band")
                                            appendLine("amount=$amount")
                                            appendLine("blur=$blur")
                                            appendLine("lift=$lift")
                                            appendLine("tint=$tint")
                                            appendLine("rim=$rim")
                                            appendLine("angle=$angle")
                                            appendLine("depth=$depth")
                                            appendLine("dispersion=$dispersion")
                                            appendLine("--- 水印胶囊 ---")
                                            appendLine("capsuleEnabled=$capsuleEnabled")
                                            appendLine("capsulePos=$capsulePos")
                                            appendLine("capsuleModel=${if (capsuleModelEnabled) capsuleModel else "(disabled)"}")
                                            appendLine("capsuleDatetime=$capsuleShowDatetime")
                                            appendLine("capsuleExif=$capsuleShowExif")
                                            appendLine("capsuleGps=$capsuleShowGps")
                                            appendLine("capsuleLogo=$capsuleShowLogo")
                                            appendLine("capsuleScale=$capsuleScale")
                                        }
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("LiquidFrame Log", diagnostic),
                                        )
                                        Toast.makeText(context, "诊断日志已复制", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            }

                            Text(
                                writeStatus,
                                fontSize = 11.sp,
                                color = if (writeStatus.startsWith("写入失败")) LiquidColors.error else textSecondary(),
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        // ---- Glass params page: preview at top, sliders below ----
                        LiquidPage.Glass -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(glassScroll)
                                .padding(PaddingValues(start = 18.dp, end = 18.dp, top = 54.dp, bottom = 132.dp)),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Text(
                                "玻璃参数",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary(),
                            )

                            // Preview moved here from the home page
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

                            SettingSection("材质") {
                                LiquidSliderRow("折射带", band, 0f..0.45f, "")
                                    { band = it }
                                SettingDivider()
                                LiquidSliderRow("折射强度", amount, 0f..4f, "", valueLabel = String.format(java.util.Locale.ROOT, "%.1f×", amount))
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
                             versionName = BuildConfig.VERSION_NAME,
                             versionCode = BuildConfig.VERSION_CODE,
                             modifier = Modifier.fillMaxSize(),
                         )
                     }
                    GlassBottomBar(
                        currentPage = currentPage,
                        onPageSelected = { currentPage = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // Edge-to-edge leaves the physical screen edge under this
                            // box: reserve the gesture/nav inset, then float the capsule
                            // clear of both the edge and the page content.
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Preview rendering
    // ---------------------------------------------------------------------------------------

    private fun replaceDisplayedPreview(bitmap: Bitmap) {
        val previous = displayedPreviewBitmap
        displayedPreviewBitmap = bitmap
        if (previous != null && previous !== bitmap && !previous.isRecycled) {
            previous.recycle()
        }
    }

    private fun schedulePreview(callback: (Bitmap, String) -> Unit) {
        previewRunnable?.let(mainHandler::removeCallbacks)
        val scheduled = Runnable {
            val generation = previewGeneration.incrementAndGet()
            val source = basePhoto
            if (source == null || source.isRecycled) return@Runnable
            val copy = try { source.copy(Bitmap.Config.ARGB_8888, true) } catch (t: Throwable) {
                Log.w(TAG, "schedulePreview copy failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            } ?: return@Runnable
            if (copy.width <= 0 || copy.height <= 0) {
                copy.recycle()
                return@Runnable
            }
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
                panelIsOpaquePlate = false,
            )
            if (!r.startsWith("skip")) bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            r
        } catch (t: Throwable) {
            "预览失败：${t.javaClass.simpleName}"
        }
        return result
    }

    // ---------------------------------------------------------------------------------------
    // Asset backdrop
    // ---------------------------------------------------------------------------------------

    private fun loadAssetBackdrop(): Bitmap? {
        return try {
            assets.open("background-a.jpg").use { stream ->
                val decoded = BitmapFactory.decodeStream(stream)
                if (decoded != null) {
                    val cropped = centreCrop(decoded, PREVIEW_W, PREVIEW_H)
                    if (decoded !== cropped) decoded.recycle()
                    cropped
                } else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadAssetBackdrop failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Synthetic sample
    // ---------------------------------------------------------------------------------------

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
}