package com.mouya.LiquidFrame

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfigActivity : Activity() {

    private lateinit var logView: TextView
    private val logBuffer = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUI())
        loadConfig()
        updateLogView()
    }

    private fun buildUI(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "LiquidFrame 液态玻璃水印"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        })

        // Master switch
        root.addView(TextView(this).apply { text = "启用模块" })
        root.addView(SeekBar(this).apply {
            max = 1
            progress = if (GlassConfig.masterEnabled) 1 else 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    GlassConfig.masterEnabled = progress == 1
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // Glass params
        root.addView(TextView(this).apply { text = "圆角半径: ${GlassConfig.radiusDp.toInt()}dp"; setPadding(0, 16, 0, 0) })
        root.addView(SeekBar(this).apply {
            max = 50
            progress = GlassConfig.radiusDp.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    GlassConfig.radiusDp = progress.toFloat()
                    (sb?.parent as? LinearLayout)?.let { parent ->
                        (parent.getChildAt(parent.indexOfChild(sb) - 1) as? TextView)?.text = "圆角半径: ${progress}dp"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        root.addView(TextView(this).apply { text = "高光强度: ${(GlassConfig.highlightStrength * 100).toInt()}%"; setPadding(0, 16, 0, 0) })
        root.addView(SeekBar(this).apply {
            max = 200
            progress = (GlassConfig.highlightStrength * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    GlassConfig.highlightStrength = progress / 100f
                    (sb?.parent as? LinearLayout)?.let { parent ->
                        (parent.getChildAt(parent.indexOfChild(sb) - 1) as? TextView)?.text = "高光强度: ${progress}%"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        root.addView(TextView(this).apply { text = "着色透明度: ${(GlassConfig.tintAlpha * 100).toInt()}%"; setPadding(0, 16, 0, 0) })
        root.addView(SeekBar(this).apply {
            max = 50
            progress = (GlassConfig.tintAlpha * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    GlassConfig.tintAlpha = progress / 100f
                    (sb?.parent as? LinearLayout)?.let { parent ->
                        (parent.getChildAt(parent.indexOfChild(sb) - 1) as? TextView)?.text = "着色透明度: ${progress}%"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // Adaptive glass
        root.addView(TextView(this).apply { text = "自适应玻璃（根据场景调光）"; setPadding(0, 16, 0, 0) })
        root.addView(SeekBar(this).apply {
            max = 1
            progress = if (GlassConfig.adaptiveGlass) 1 else 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    GlassConfig.adaptiveGlass = progress == 1
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // Log section
        root.addView(TextView(this).apply { text = "运行日志"; setPadding(0, 24, 0, 8) })
        
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        scrollView.addView(logView)
        root.addView(scrollView)

        // Copy button
        root.addView(Button(this).apply {
            text = "复制日志"
            setOnClickListener { copyLog() }
        })

        return root
    }

    private fun loadConfig() {
        // TODO: Load from SharedPreferences
    }

    private fun updateLogView() {
        val log = LogHelper.readLog()
        logView.text = log.ifEmpty { "暂无日志\n拍照后此处会显示处理信息" }
    }

    private fun copyLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("LiquidFrame Log", LogHelper.readLog())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateLogView()
    }
}