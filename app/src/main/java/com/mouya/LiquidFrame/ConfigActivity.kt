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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mouya.LiquidFrame.glass.GlassParams
import com.mouya.LiquidFrame.glass.LiquidGlassOptics
import com.mouya.LiquidFrame.glass.PanelRect
import java.util.Locale

// ---------------------------------------------------------------------------------------
// iOS-style design tokens (adapted from MusicHapticsX HapticDashboardActivity)
// ---------------------------------------------------------------------------------------

private object IOSColors {
    val blue = androidx.compose.ui.graphics.Color(0xFF007AFF)
    val green = androidx.compose.ui.graphics.Color(0xFF34C759)
    val lightBg = androidx.compose.ui.graphics.Color(0xFFF2F2F7)
    val darkBg = androidx.compose.ui.graphics.Color(0xFF000000)
    val lightCard = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    val darkCard = androidx.compose.ui.graphics.Color(0xFF1C1C1E)
    val glassLight = androidx.compose.ui.graphics.Color(0xFFFFFFFF).copy(alpha = 0.72f)
    val glassDark = androidx.compose.ui.graphics.Color(0xFF1C1C1E).copy(alpha = 0.72f)
    val lightTextPrimary = androidx.compose.ui.graphics.Color(0xFF000000)
    val darkTextPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    val lightTextSecondary = androidx.compose.ui.graphics.Color(0xFF3C3C43).copy(alpha = 0.6f)
    val darkTextSecondary = androidx.compose.ui.graphics.Color(0xFFEBEBF5).copy(alpha = 0.6f)
    val separatorLight = androidx.compose.ui.graphics.Color(0xFFC6C6C8)
    val separatorDark = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f)
    val toggleOffLight = androidx.compose.ui.graphics.Color(0xFFE9E9EA)
    val toggleOffDark = androidx.compose.ui.graphics.Color(0xFF39393B)
}

@Composable private fun isDark() = isSystemInDarkTheme()
@Composable private fun bgPrimary() = if (isDark()) IOSColors.darkBg else IOSColors.lightBg
@Composable private fun cardColor() = if (isDark()) IOSColors.darkCard else IOSColors.lightCard
@Composable private fun glassColor() = if (isDark()) IOSColors.glassDark else IOSColors.glassLight
@Composable private fun textPrimary() = if (isDark()) IOSColors.darkTextPrimary else IOSColors.lightTextPrimary
@Composable private fun textSecondary() = if (isDark()) IOSColors.darkTextSecondary else IOSColors.lightTextSecondary
@Composable private fun separatorColor() = if (isDark()) IOSColors.separatorDark else IOSColors.separatorLight

// ---------------------------------------------------------------------------------------
// iOS Toggle
// ---------------------------------------------------------------------------------------

@Composable
fun IOSToggle(checked: Boolean, onToggle: () -> Unit) {
    val animatedBg by animateColorAsState(
        targetValue = if (checked) IOSColors.green else if (isDark()) IOSColors.toggleOffDark else IOSColors.toggleOffLight,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "ToggleBg"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "ThumbOffset"
    )
    Box(
        modifier = Modifier.width(52.dp).height(32.dp)
            .clip(RoundedCornerShape(16.dp)).background(animatedBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggle() }
    ) {
        Box(
            modifier = Modifier.offset(x = thumbOffset, y = 2.dp).size(28.dp)
                .clip(CircleShape).background(androidx.compose.ui.graphics.Color.White)
        )
    }
}

// ---------------------------------------------------------------------------------------
// iOS Slider
// ---------------------------------------------------------------------------------------

@Composable
fun IOSSettingSliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    unit: String, onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 15.sp, color = textPrimary())
            Text(
                "${String.format(Locale.ROOT, "%.2f", value)} $unit",
                fontSize = 15.sp, color = IOSColors.blue, fontWeight = FontWeight.Medium
            )
        }
        val progress = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val trackWidth = maxWidth
            val thumbSize = 24.dp
            val thumbOffset = trackWidth * progress - thumbSize / 2

            Box(
                Modifier.fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (isDark()) IOSColors.toggleOffDark else IOSColors.toggleOffLight)
            )
            Box(
                Modifier.fillMaxWidth(progress).height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(IOSColors.blue)
            )
            Box(
                Modifier.offset(x = thumbOffset).size(thumbSize)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape).background(androidx.compose.ui.graphics.Color.White)
                    .border(0.5.dp, IOSColors.blue.copy(alpha = 0.2f), CircleShape)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Glass card container
// ---------------------------------------------------------------------------------------

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(glassColor())
            .border(0.5.dp, if (isDark()) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.06f), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

// ---------------------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------------------

class ConfigActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private var renderPending = false
    private var basePhoto: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigStore.load()
        basePhoto = buildSamplePhoto(PREVIEW_W, PREVIEW_H)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isSystemInDarkTheme()
            isAppearanceLightNavigationBars = !isSystemInDarkTheme()
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
    // Compose UI
    // ---------------------------------------------------------------------------------------

    @Composable
    private fun LiquidFrameDashboard() {
        val context = LocalContext.current
        val scrollState = rememberScrollState()

        var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var previewResult by remember { mutableStateOf("") }
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

        fun saveAndRefresh() {
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
                .background(bgPrimary())
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title
                Text(
                    "LiquidFrame",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary()
                )
                Text(
                    "液态玻璃水印 · iOS-style Liquid Glass",
                    fontSize = 14.sp,
                    color = textSecondary()
                )

                // Preview
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = androidx.compose.ui.graphics.asImageBitmap(bmp),
                        contentDescription = "Preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Color(0xFF1C1C20))
                    )
                }
                if (previewResult.isNotEmpty()) {
                    Text(
                        previewResult,
                        fontSize = 11.sp,
                        color = textSecondary(),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // Main switches
                GlassCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用模块", fontSize = 16.sp, color = textPrimary(), fontWeight = FontWeight.Medium)
                        IOSToggle(checked = enabled, onToggle = { enabled = !enabled; saveAndRefresh() })
                    }
                    HorizontalDivider(color = separatorColor())
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("自适应（按画面明暗调整）", fontSize = 16.sp, color = textPrimary())
                        IOSToggle(checked = adaptive, onToggle = { adaptive = !adaptive; saveAndRefresh() })
                    }
                    HorizontalDivider(color = separatorColor())
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("保留相机原有文字", fontSize = 16.sp, color = textPrimary())
                        IOSToggle(checked = preserveContent, onToggle = { preserveContent = !preserveContent; saveAndRefresh() })
                    }
                    HorizontalDivider(color = separatorColor())
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("色散（边缘彩边）", fontSize = 16.sp, color = textPrimary())
                        IOSToggle(checked = dispersion, onToggle = { dispersion = !dispersion; saveAndRefresh() })
                    }
                }

                // Sliders
                GlassCard {
                    IOSSettingSliderRow("折射带高度", band, 0f..0.45f, "", onValueChange = { band = it; saveAndRefresh() })
                    IOSSettingSliderRow("折射强度", amount, 0f..5f, "", onValueChange = { amount = it; saveAndRefresh() })
                    IOSSettingSliderRow("背景模糊", blur, 0f..1.5f, "", onValueChange = { blur = it; saveAndRefresh() })
                    IOSSettingSliderRow("内部提亮", lift, 0f..48f, "", onValueChange = { lift = it; saveAndRefresh() })
                    IOSSettingSliderRow("着色", tint, 0f..0.6f, "", onValueChange = { tint = it; saveAndRefresh() })
                    IOSSettingSliderRow("边缘高光", rim, 0f..1.2f, "", onValueChange = { rim = it; saveAndRefresh() })
                    IOSSettingSliderRow("内阴影", shadow, 0f..0.6f, "", onValueChange = { shadow = it; saveAndRefresh() })
                    IOSSettingSliderRow("高光方向", angle, 0f..360f, "°", onValueChange = { angle = it; saveAndRefresh() })
                    IOSSettingSliderRow("立体感", depth, 0f..1f, "", onValueChange = { depth = it; saveAndRefresh() })
                }

                // Buttons
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            val d = GlassParams.Default
                            enabled = true; adaptive = d.adaptive; preserveContent = d.preserveContent
                            dispersion = d.chromaticAberration; band = d.refractionHeightFraction
                            amount = d.refractionAmountFraction; blur = d.blurFraction; lift = d.interiorLift
                            tint = d.tintAlpha; rim = d.rimAlpha; shadow = d.innerShadowAlpha
                            angle = d.highlightAngleDeg; depth = d.depthEffect
                            saveAndRefresh()
                        },
                        modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = IOSColors.blue.copy(alpha = 0.15f))
                    ) {
                        Text("恢复默认", color = IOSColors.blue, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("LiquidFrame Log", LogHelper.readLog()))
                            Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = IOSColors.blue.copy(alpha = 0.15f))
                    ) {
                        Text("复制日志", color = IOSColors.blue, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Log
                GlassCard {
                    Text("运行日志", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textPrimary())
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (isDark()) androidx.compose.ui.graphics.Color(0xFF0F0F10) else androidx.compose.ui.graphics.Color(0xFFF4F4F6))
                            .padding(8.dp)
                    ) {
                        Text(
                            logText.ifEmpty { "暂无日志\n拍照后此处会显示处理信息" },
                            fontSize = 11.sp,
                            color = if (isDark()) androidx.compose.ui.graphics.Color(0xFFCCCCCC) else androidx.compose.ui.graphics.Color(0xFF1C1C1E),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))
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
    // Synthetic test photo (same as before)
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

    private companion object {
        const val PREVIEW_W = 900
        const val PREVIEW_H = 420
        const val PREVIEW_MARGIN = 34
        const val PREVIEW_PANEL_H = 62
    }
}