package com.mouya.LiquidFrame

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object LogHelper {
    private const val LOG_FILE = "/data/local/tmp/lf_log.txt"
    private const val MAX_LOG_SIZE = 100 * 1024

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.getDefault())

    private val mirror = StringBuilder()

    private const val MAX_MIRROR = 16 * 1024

    private var directWriteWorks = false

    private var escalationAttempted = false

    fun log(tag: String, message: String) {
        try {
            val line = "[${LocalTime.now().format(timeFormat)}] $tag: $message\n"
            synchronized(mirror) {
                mirror.append(line)
                if (mirror.length > MAX_MIRROR) mirror.delete(0, mirror.length - MAX_MIRROR)
            }
            appendToFile(line)
        } catch (_: Throwable) {

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

    private fun runSu(script: String): Int {
        for (binary in listOf("su", "/system/bin/su", "/system/xbin/su")) {
            try {
                val process = ProcessBuilder(binary, "-c", script).redirectErrorStream(true).start()

                val output = process.inputStream.use { stream ->
                    val buf = ByteArray(4096)
                    val n = stream.read(buf)
                    if (n > 0) buf.copyOf(n) else ByteArray(0)
                }
                process.outputStream.close()

                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    log("LogHelper", "su timed out after 5s")
                    return -1
                }
                val code = process.exitValue()
                if (code == 0) return 0
            } catch (t: Throwable) {
                log("LogHelper", "su failed: ${t.javaClass.simpleName}: ${t.message}")

            }
        }
        return -1
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun readLog(): String {

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
