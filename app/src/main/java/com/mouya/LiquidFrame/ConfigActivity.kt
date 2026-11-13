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
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import com.mouya.LiquidFrame.glass.GlassSceneTuner
import com.mouya.LiquidFrame.glass.CapsuleRenderer
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.LogoPreset
import com.mouya.LiquidFrame.glass.LogoPosition
import com.mouya.LiquidFrame.glass.PanelRect
import com.mouya.LiquidFrame.ui.AboutPage
import com.mouya.LiquidFrame.ui.GlassBottomBar
import com.mouya.LiquidFrame.ui.GlassCard
import com.mouya.LiquidFrame.ui.LiquidBackdropProvider
import com.mouya.LiquidFrame.ui.LiquidColors
import com.mouya.LiquidFrame.ui.LiquidPage
import com.mouya.LiquidFrame.ui.LiquidPageTransition
import com.mouya.LiquidFrame.ui.LiquidSliderRow
import com.mouya.LiquidFrame.ui.LiquidToggleRow
import com.mouya.LiquidFrame.ui.SettingDivider
import com.mouya.LiquidFrame.ui.SettingDropdownRow
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

        // The reference panel is about 12% of the photo's short side, not of its width.
        const val PREVIEW_PANEL_H_FRACTION = 0.12f
        // Asymmetric leading inset matching the reference engine's 4.5% horizontal margin.
        const val PREVIEW_SIDE_FRACTION = 0.045f
        // 18 / 1080 short-side units, matching BOX_INSET_REF in the camera hook.
        const val PREVIEW_BOTTOM_FRACTION = 0.0167f
        const val PREVIEW_WIDTH_FRACTION = 0.91f
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
        var presetMenuExpanded by remember { mutableStateOf(false) }
        var savePresetDialog by remember { mutableStateOf(false) }
        var clearConfigDialog by remember { mutableStateOf(false) }
        var presetName by remember { mutableStateOf("") }
        var presetLabel by remember { mutableStateOf("Edge Clear") }

        var enabled by remember { mutableStateOf(ConfigStore.isEnabled()) }
        var capsuleEnabled by remember { mutableStateOf(ConfigStore.capsuleEnabled()) }
        var capsulePos by remember { mutableStateOf(ConfigStore.capsulePos()) }
        var capsuleModel by remember { mutableStateOf(ConfigStore.capsuleModel()) }
        var capsuleModelEnabled by remember { mutableStateOf(capsuleModel.isNotEmpty()) }
        var capsuleShowDatetime by remember { mutableStateOf(ConfigStore.capsuleShowDatetime()) }
        var capsuleShowExif by remember { mutableStateOf(ConfigStore.capsuleShowExif()) }
        var capsuleShowGps by remember { mutableStateOf(ConfigStore.capsuleShowGps()) }
        var capsuleShowLogo by remember { mutableStateOf(ConfigStore.capsuleShowLogo()) }
        var capsuleLogoPreset by remember { mutableStateOf(ConfigStore.capsuleLogoPreset()) }
        var capsuleLogoPosition by remember { mutableStateOf(ConfigStore.capsuleLogoPosition()) }
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

        // Capsule settings: save config AND re-run preview so the user sees
        // capsule changes (logo, scale, toggles, text) reflected in real time.
        LaunchedEffect(
            capsuleEnabled, capsulePos, capsuleModel, capsuleModelEnabled,
            capsuleShowDatetime, capsuleShowExif, capsuleShowGps, capsuleShowLogo,
            capsuleLogoPreset, capsuleLogoPosition, capsuleScale,
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
                this[ConfigStore.KEY_CAPSULE_LOGO_PRESET] = capsuleLogoPreset
                this[ConfigStore.KEY_CAPSULE_LOGO_POSITION] = capsuleLogoPosition
                this[ConfigStore.KEY_CAPSULE_SCALE] = capsuleScale.toString()
            }
            writeStatus = withContext(Dispatchers.IO) {
                ConfigStore.save(values)
                ConfigStore.lastWriteStatus()
            }
            schedulePreview { bitmap, result ->
                replaceDisplayedPreview(bitmap)
                previewBitmap = bitmap
                previewResult = result
            }
        }

        fun applyPresetValues(values: Map<String, String>) {
            band = values[ConfigStore.KEY_BAND]?.toFloatOrNull() ?: band
            amount = values[ConfigStore.KEY_AMOUNT]?.toFloatOrNull() ?: amount
            blur = values[ConfigStore.KEY_BLUR]?.toFloatOrNull() ?: blur
            lift = values[ConfigStore.KEY_LIFT]?.toFloatOrNull() ?: lift
            tint = values[ConfigStore.KEY_TINT]?.toFloatOrNull() ?: tint
            rim = values[ConfigStore.KEY_RIM]?.toFloatOrNull() ?: rim
            angle = values[ConfigStore.KEY_ANGLE]?.toFloatOrNull() ?: angle
            depth = values[ConfigStore.KEY_DEPTH]?.toFloatOrNull() ?: depth
            adaptive = values[ConfigStore.KEY_ADAPTIVE]?.toBooleanStrictOrNull() ?: adaptive
            preserveContent = values[ConfigStore.KEY_PRESERVE]?.toBooleanStrictOrNull() ?: preserveContent
            dispersion = values[ConfigStore.KEY_DISPERSION]?.toBooleanStrictOrNull() ?: dispersion
        }

        fun applyBuiltInPreset(name: String) {
            val d = GlassParams.Default
            val p = when (name) {
                "Edge Soft" -> d.copy(refractionHeightFraction = 0.16f, refractionAmountFraction = 0.22f, blurFraction = 0.04f, tintAlpha = 0.012f, surfaceAlpha = 0.012f, rimAlpha = 0.16f, rimAmbient = 0.12f)
                "Night Clear" -> d.copy(refractionHeightFraction = 0.22f, refractionAmountFraction = 0.38f, blurFraction = 0.10f, tintAlpha = 0.026f, surfaceAlpha = 0.03f, rimAlpha = 0.24f, rimAmbient = 0.2f)
                else -> d
            }
            applyPresetValues(mapOf(
                ConfigStore.KEY_BAND to p.refractionHeightFraction.toString(),
                ConfigStore.KEY_AMOUNT to p.refractionAmountFraction.toString(),
                ConfigStore.KEY_BLUR to p.blurFraction.toString(),
                ConfigStore.KEY_LIFT to p.interiorLift.toString(),
                ConfigStore.KEY_TINT to p.tintAlpha.toString(),
                ConfigStore.KEY_RIM to p.rimAlpha.toString(),
                ConfigStore.KEY_ANGLE to p.highlightAngleDeg.toString(),
                ConfigStore.KEY_DEPTH to p.depthEffect.toString(),
                ConfigStore.KEY_ADAPTIVE to p.adaptive.toString(),
                ConfigStore.KEY_PRESERVE to p.preserveContent.toString(),
                ConfigStore.KEY_DISPERSION to p.chromaticAberration.toString(),
            ))
            presetLabel = name
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
            capsulePos = "left"
            capsuleModel = ""
            capsuleModelEnabled = false
            capsuleShowDatetime = true
            capsuleShowExif = true
            capsuleShowGps = true
            capsuleShowLogo = true
            capsuleLogoPreset = "Apple"
            capsuleLogoPosition = "MIDDLE"
            capsuleScale = 1f
            presetLabel = "Edge Clear"
        }

        if (clearConfigDialog) {
            AlertDialog(
                onDismissRequest = { clearConfigDialog = false },
                title = { Text("清除所有配置？") },
                text = { Text("将删除应用内配置、/data/local/tmp/lf_config.txt 以及自定义预设。此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = {
                        val ok = ConfigStore.clearPersistentData()
                        Toast.makeText(context, if (ok) "配置已清除" else "清除不完整，请检查 Root 授权", Toast.LENGTH_LONG).show()
                        clearConfigDialog = false
                    }) { Text("清除", color = LiquidColors.error) }
                },
                dismissButton = { TextButton(onClick = { clearConfigDialog = false }) { Text("取消") } },
            )
        }

        if (savePresetDialog) {
            AlertDialog(
                onDismissRequest = { savePresetDialog = false },
                title = { Text("保存材质预设") },
                text = {
                    OutlinedTextField(value = presetName, onValueChange = { presetName = it.take(32) }, singleLine = true, label = { Text("预设名称") })
                },
                confirmButton = {
                    TextButton(onClick = {
                        val values = ConfigStore.snapshot().apply {
                            this[ConfigStore.KEY_BAND] = band.toString(); this[ConfigStore.KEY_AMOUNT] = amount.toString()
                            this[ConfigStore.KEY_BLUR] = blur.toString(); this[ConfigStore.KEY_LIFT] = lift.toString()
                            this[ConfigStore.KEY_TINT] = tint.toString(); this[ConfigStore.KEY_RIM] = rim.toString()
                            this[ConfigStore.KEY_ANGLE] = angle.toString(); this[ConfigStore.KEY_DEPTH] = depth.toString()
                            this[ConfigStore.KEY_ADAPTIVE] = adaptive.toString(); this[ConfigStore.KEY_PRESERVE] = preserveContent.toString()
                            this[ConfigStore.KEY_DISPERSION] = dispersion.toString()
                        }
                        val ok = ConfigStore.savePreset(presetName, values)
                        Toast.makeText(context, if (ok) "预设已保存" else "保存失败，请检查 Root 权限", Toast.LENGTH_SHORT).show()
                        if (ok) presetLabel = presetName.trim()
                        savePresetDialog = false
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { savePresetDialog = false }) { Text("取消") } },
            )
        }

        Box(Modifier.fillMaxSize()) {
            LiquidBackdropProvider(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    LiquidPageTransition(
                        page = currentPage,
                    ) { page ->
                    when (page) {
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

                            SettingSection("核心", collapsible = true, initiallyExpanded = true) {
                                LiquidToggleRow(
                                    "启用 liquidFrame",
                                    enabled,
                                    { enabled = !enabled },
                                    "下一次拍照开始生效",
                                )
                            }

                            SettingSection("材质预设", collapsible = true, initiallyExpanded = true) {
                                Box {
                                    SettingRow(
                                        title = "当前材质",
                                        subtitle = "选择内置风格或载入已保存的自定义预设",
                                        trailing = { Text(presetLabel, color = LiquidColors.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                        onClick = { presetMenuExpanded = true },
                                    )
                                    DropdownMenu(expanded = presetMenuExpanded, onDismissRequest = { presetMenuExpanded = false }) {
                                        listOf("Edge Clear", "Edge Soft", "Night Clear").forEach { name ->
                                            DropdownMenuItem(text = { Text(name) }, onClick = { presetMenuExpanded = false; applyBuiltInPreset(name) })
                                        }
                                        ConfigStore.presetNames().forEach { name ->
                                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                                presetMenuExpanded = false
                                                ConfigStore.loadPreset(name)?.let { applyPresetValues(it); presetLabel = name }
                                            })
                                        }
                                    }
                                }
                                SettingDivider()
                                SettingRow(
                                    title = "保存当前预设",
                                    subtitle = "将当前玻璃参数保存为可重复使用的配置",
                                    trailing = { Text("保存", color = LiquidColors.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                    onClick = { presetName = ""; savePresetDialog = true },
                                )
                            }

                            SettingSection("显示与渲染", collapsible = true, initiallyExpanded = false) {
                                LiquidToggleRow("自适应明暗", adaptive, { adaptive = !adaptive }, "按画面亮度自动调整玻璃存在感")
                                SettingDivider()
                                LiquidToggleRow("边缘色散", dispersion, { dispersion = !dispersion }, "仅在边缘折射带产生轻微色彩分离")
                            }

                            SettingSection("胶囊显示", collapsible = true, initiallyExpanded = true) {
                                LiquidToggleRow(
                                    "重建胶囊",
                                    capsuleEnabled,
                                    { capsuleEnabled = !capsuleEnabled },
                                    "在相机水印输出阶段替换原水印",
                                )
                                SettingDivider()
                                LiquidToggleRow(
                                    "日期时间",
                                    capsuleShowDatetime,
                                    { capsuleShowDatetime = !capsuleShowDatetime },
                                    "使用相机本次拍摄记录的时间，不使用当前系统时间代替",
                                )
                                SettingDivider()
                                LiquidToggleRow(
                                    "相机参数",
                                    capsuleShowExif,
                                    { capsuleShowExif = !capsuleShowExif },
                                    "仅显示本次拍摄记录的 35mm 等效焦距、光圈、快门和 ISO；缺失项不猜测",
                                )
                                SettingDivider()
                                LiquidToggleRow(
                                    "位置信息",
                                    capsuleShowGps,
                                    { capsuleShowGps = !capsuleShowGps },
                                    "读取本次拍摄记录中的 GPS Location",
                                )
                            }

                            SettingSection("品牌与布局", collapsible = true, initiallyExpanded = false) {
                                LiquidToggleRow("Logo", capsuleShowLogo, { capsuleShowLogo = !capsuleShowLogo }, "选择胶囊中的品牌标识")
                                if (capsuleShowLogo) {
                                    SettingDivider()
                                    SettingDropdownRow(
                                        title = "品牌标识",
                                        subtitle = "选择胶囊中显示的品牌图形",
                                        selected = LogoPreset.fromName(capsuleLogoPreset).displayName,
                                        options = LogoPreset.entries.filter { it == LogoPreset.Apple || it == LogoPreset.Leica || it == LogoPreset.LeicaWhite || it == LogoPreset.None }.map { it.name to it.displayName },
                                        onSelected = { capsuleLogoPreset = it },
                                    )
                                    SettingDivider()
                                    SettingDropdownRow(
                                        title = "标识位置",
                                        subtitle = "设置 Logo 在胶囊中的相对位置",
                                        selected = LogoPosition.fromName(capsuleLogoPosition).displayName,
                                        options = LogoPosition.entries.map { it.name to it.displayName },
                                        onSelected = { capsuleLogoPosition = it },
                                    )
                                }
                                SettingDivider()
                                LiquidToggleRow(
                                    "自定义设备名称",
                                    capsuleModelEnabled,
                                    { capsuleModelEnabled = !capsuleModelEnabled },
                                    "使用自定义文字替代系统机型名",
                                )
                                AnimatedVisibility(
                                    visible = capsuleModelEnabled,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut(),
                                ) {
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
                                LiquidSliderRow(
                                    "胶囊大小",
                                    capsuleScale,
                                    0.6f..2.0f,
                                    "",
                                    valueLabel = String.format(java.util.Locale.ROOT, "%.2f×", capsuleScale),
                                ) { capsuleScale = it }
                                SettingDivider()
                                SettingDropdownRow(
                                    title = "胶囊位置",
                                    subtitle = "沿照片底边选择对齐方式",
                                    selected = when (capsulePos) { "center" -> "居中"; "right" -> "靠右"; else -> "靠左" },
                                    options = listOf("left" to "靠左", "center" to "居中", "right" to "靠右"),
                                    onSelected = { capsulePos = it },
                                )
                            }

                            SettingSection("维护", collapsible = true, initiallyExpanded = false) {
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
                                    title = "清除持久化配置",
                                    subtitle = "删除 /data/local/tmp 中的配置与自定义预设",
                                    trailing = { Text("清除", color = LiquidColors.error, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                    onClick = { clearConfigDialog = true },
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
                                            appendLine("capsuleLogoPreset=$capsuleLogoPreset")
                                            appendLine("capsuleLogoPosition=$capsuleLogoPosition")
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

                        // ---- Glass params page: preview at top, all params flat below ----
                        LiquidPage.Glass -> {
                            Column(
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

                                // Preview
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

                                SettingSection("折射", collapsible = true, initiallyExpanded = true) {
                                    LiquidSliderRow("折射带", band, 0f..0.45f, "") { band = it }
                                    SettingDivider()
                                    LiquidSliderRow(
                                        "折射强度", amount, 0f..4f, "",
                                        valueLabel = String.format(java.util.Locale.ROOT, "%.1f×", amount),
                                    ) { amount = it }
                                }

                                SettingSection("表面", collapsible = true, initiallyExpanded = false) {
                                    LiquidSliderRow("背景模糊", blur, 0f..1.5f, "") { blur = it }
                                    SettingDivider()
                                    LiquidSliderRow("内部提亮", lift, 0f..48f, "") { lift = it }
                                    SettingDivider()
                                    LiquidSliderRow("着色", tint, 0f..0.6f, "") { tint = it }
                                }

                                SettingSection("边缘光效", collapsible = true, initiallyExpanded = false) {
                                    LiquidSliderRow("边缘高光", rim, 0f..1.2f, "") { rim = it }
                                }

                                // Detail params (flat)
                                SettingSection("细节", collapsible = true, initiallyExpanded = false) {
                                    LiquidSliderRow("高光方向", angle, 0f..360f, "°", valueLabel = "${angle.roundToInt()}°")
                                        { angle = it }
                                    SettingDivider()
                                    LiquidSliderRow("立体感", depth, 0f..1f, "")
                                        { depth = it }
                                }
                            }
                        }

                        LiquidPage.Log -> {
                            val logScroll = rememberScrollState()
                            var logText by remember { mutableStateOf<List<String>>(emptyList()) }
                            LaunchedEffect(Unit) {
                                logText = LFLogger.tail(200)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(logScroll)
                                    .padding(PaddingValues(start = 18.dp, end = 18.dp, top = 54.dp, bottom = 132.dp)),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "运行日志",
                                        fontSize = 30.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textPrimary(),
                                    )
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            logText = LFLogger.tail(200)
                                        }
                                    ) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("刷新", modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                                GlassCard {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        if (logText.isEmpty()) {
                                            Text("暂无日志", fontSize = 13.sp, color = textSecondary())
                                        } else {
                                            logText.forEach { line ->
                                                Text(
                                                    line,
                                                    fontSize = 11.sp,
                                                    color = when {
                                                        line.contains("ERROR", ignoreCase = true) -> LiquidColors.error
                                                        line.contains("WARN", ignoreCase = true) -> LiquidColors.warning
                                                        line.contains("INFO", ignoreCase = true) -> LiquidColors.blue
                                                        else -> textSecondary()
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                        }
                                    }
                                }
                                GlassCard {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column {
                                            Text("日志文件", fontSize = 14.sp, color = textPrimary(), fontWeight = FontWeight.Medium)
                                            Text(
                                                LFLogger.filePath(),
                                                fontSize = 11.sp,
                                                color = textSecondary(),
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            androidx.compose.material3.TextButton(onClick = {
                                                val content = LFLogger.tail(500).joinToString("\n")
                                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(
                                                    ClipData.newPlainText("LiquidFrame Log", content),
                                                )
                                                Toast.makeText(this@ConfigActivity, "日志已复制", Toast.LENGTH_SHORT).show()
                                            }) {
                                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Text("复制")
                                            }
                                            androidx.compose.material3.TextButton(onClick = {
                                                LFLogger.clear()
                                                logText = emptyList()
                                                Toast.makeText(this@ConfigActivity, "日志已清空", Toast.LENGTH_SHORT).show()
                                            }) {
                                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Text("清空")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        LiquidPage.About -> AboutPage(
                             versionName = BuildConfig.VERSION_NAME,
                             versionCode = BuildConfig.VERSION_CODE,
                             modifier = Modifier.fillMaxSize(),
                         )
                     }
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
        val width = bitmap.width
        val height = bitmap.height
        val shortSide = minOf(width, height)
        if (width < 160 || height < 160) return "预览图尺寸不足"
        val previewMeta = CapsuleRenderer.CaptureMeta(
            captureMillis = 1_792_657_488_000L,
            location = Location("preview").apply { latitude = 27.925; longitude = 118.549 },
            exif = CapsuleRenderer.ExifBits(100.0, 2.8, 1.0 / 461.0, 50),
        )
        val showLogo = ConfigStore.capsuleShowLogo()
        val logo = if (showLogo) loadPreviewLogo(ConfigStore.capsuleLogoPreset()) else null
        val options = CapsuleRenderer.Options(
            modelOverride = ConfigStore.capsuleModel(),
            position = ConfigStore.capsulePos(),
            showDateTime = ConfigStore.capsuleShowDatetime(),
            showExif = ConfigStore.capsuleShowExif(),
            showGps = ConfigStore.capsuleShowGps(),
            scale = ConfigStore.capsuleScale(),
            showLogo = showLogo,
            logo = logo,
            logoPreset = ConfigStore.capsuleLogoPreset(),
            logoPosition = ConfigStore.capsuleLogoPosition(),
        )
        val lines = CapsuleRenderer.buildLines(options, previewMeta, shortSide)
        val metrics = CapsuleRenderer.measureCapsule(width, shortSide, options.scale, lines, options)
            ?: return "预览内容不足"
        val leftInset = (width * CapsuleRenderer.HORIZONTAL_EDGE_INSET_FRACTION).coerceAtLeast(1f)
        val left = when (options.position) {
            "right" -> width - leftInset - metrics.capsuleWidth
            "center" -> (width - metrics.capsuleWidth) * 0.5f
            else -> leftInset
        }.coerceIn(0f, (width - metrics.capsuleWidth).coerceAtLeast(0f))
        val bottomMargin = (shortSide * 0.016f).coerceIn(4f, shortSide * 0.06f)
        val top = height - bottomMargin - metrics.capsuleHeight
        val panel = PanelRect(left, top, metrics.capsuleWidth, metrics.capsuleHeight, metrics.capsuleHeight * 0.5f)
        return try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val tuned = GlassSceneTuner.resolveParams(pixels, width, height, panel, params).copy(preserveContent = false)
            val glassResult = LiquidGlassOptics.renderPanel(pixels, width, height, panel, tuned, panelIsOpaquePlate = false)
            if (glassResult.startsWith("skip")) return "玻璃渲染跳过：$glassResult"
            val textLayer = CapsuleRenderer.drawTextLayer(width, height, panel, metrics, CapsuleRenderer.TEXT_COLOR_WHITE)
                ?: return "文字层生成失败"
            CapsuleRenderer.blendTextOver(pixels, textLayer)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            "共用成片渲染器 · 参数为示例值 · ${metrics.capsuleWidth.toInt()}×${metrics.capsuleHeight.toInt()} · $glassResult"
        } catch (t: Throwable) {
            "预览失败：${t.javaClass.simpleName}"
        } finally {
            logo?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun loadPreviewLogo(name: String): Bitmap? {
        return try {
            when (LogoPreset.fromName(name)) {
                LogoPreset.None -> null
                LogoPreset.Apple -> {
                    val drawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.logo_apple) ?: return null
                    val w = drawable.intrinsicWidth.coerceAtLeast(64)
                    val h = drawable.intrinsicHeight.coerceAtLeast(64)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(canvas)
                    }
                }
                LogoPreset.Leica -> assets.open("leica_logo.png").use { BitmapFactory.decodeStream(it) }
                LogoPreset.LeicaWhite -> assets.open("leica_wordmark_white.png").use { BitmapFactory.decodeStream(it) }
                else -> null
            }
        } catch (_: Throwable) { null }
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