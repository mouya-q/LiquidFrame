package com.mouya.LiquidFrame

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The module's log, written where both the camera and the settings screen can reach it.
 *
 * `/data/local/tmp` is `shell:shell` mode 771, so a plain application cannot create a file
 * there. The first version of this file swallowed that `IOException`, which is why the camera
 * process looked completely silent no matter what it did. Logging now escalates once through
 * `su` and keeps a bounded in-memory mirror, so the settings screen can always show *something*
 * even when the write is refused.
 */
object LogHelper {
    private const val LOG_FILE = "/data/local/tmp/lf_log.txt"
    private const val MAX_LOG_SIZE = 100 * 1024 // 100KB max
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** In-memory mirror, so the UI has content even if the file cannot be written. */
    private val mirror = StringBuilder()

    private const val MAX_MIRROR = 16 * 1024

    /** True once a write has succeeded, so the escalation is attempted only when needed. */
    private var directWriteWorks = false

    private var escalationAttempted = false

    fun log(tag: String, message: String) {
        try {
            val line = "[${dateFormat.format(Date())}] $tag: $message\n"
            synchronized(mirror) {
                mirror.append(line)
                if (mirror.length > MAX_MIRROR) mirror.delete(0, mirror.length - MAX_MIRROR)
            }
            appendToFile(line)
        } catch (_: Throwable) {
            // Logging must never take down the caller.
        }
    }

    private fun appendToFile(line: String) {
        val file = File(LOG_FILE)
        try {
            if (directWriteWorks) {
                if (file.exists() && file.length() > MAX_LOG_SIZE) file.delete()
                file.appendText(line)
                return
            }
        } catch (_: Throwable) {
            directWriteWorks = false
        }

        // First failure (or a refused directory): escalate once, then leave the file to root.
        if (escalationAttempted) return
        escalationAttempted = true
        try {
            val script = buildString {
                append("mkdir -p /data/local/tmp && chmod 777 /data/local/tmp\n")
                append("touch ").append(quote(LOG_FILE)).append(" && chmod 666 ").append(quote(LOG_FILE))
            }
            if (runSu(script) == 0) {
                directWriteWorks = try {
                    file.appendText(line)
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Runs a shell script as root, returning its exit status. */
    private fun runSu(script: String): Int {
        for (binary in listOf("su", "/system/bin/su", "/system/xbin/su")) {
            try {
                val process = ProcessBuilder(binary, "-c", script).redirectErrorStream(true).start()
                val output = process.inputStream.readBytes()
                process.outputStream.close()
                val code = process.waitFor()
                if (code == 0) return 0
            } catch (_: Throwable) {
                // Try the next binary.
            }
        }
        return -1
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun readLog(): String {
        // Prefer the file: the camera process writes there, and its lines are the interesting
        // ones. Fall back to the mirror when the file is unreadable.
        val fromFile = try {
            val file = File(LOG_FILE)
            if (file.exists() && file.length() > 0) file.readText() else ""
        } catch (_: Throwable) {
            ""
        }
        if (fromFile.isNotEmpty()) return fromFile
        return synchronized(mirror) { mirror.toString() }
    }

    fun clearLog() {
        try {
            File(LOG_FILE).delete()
        } catch (_: Throwable) {
        }
        synchronized(mirror) { mirror.setLength(0) }
    }
}