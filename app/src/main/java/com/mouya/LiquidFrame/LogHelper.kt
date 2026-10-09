package com.mouya.LiquidFrame

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogHelper {
    private const val LOG_FILE = "/data/local/tmp/lf_log.txt"
    private const val MAX_LOG_SIZE = 100 * 1024 // 100KB max
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, message: String) {
        try {
            val timestamp = dateFormat.format(Date())
            val line = "[$timestamp] $tag: $message\n"
            val file = File(LOG_FILE)
            
            // Rotate if too large
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                file.delete()
            }
            
            file.appendText(line)
        } catch (_: Throwable) {
            // Ignore log errors
        }
    }

    fun readLog(): String {
        return try {
            val file = File(LOG_FILE)
            if (file.exists()) file.readText() else ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun clearLog() {
        try {
            File(LOG_FILE).delete()
        } catch (_: Throwable) {
        }
    }
}