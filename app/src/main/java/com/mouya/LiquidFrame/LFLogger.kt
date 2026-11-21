package com.mouya.LiquidFrame

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Cross-process logger for the settings app and the camera process. */
object LFLogger {

    private const val TAG = "LiquidFrame"
    private const val LOG_FILE = "/data/local/tmp/lf_log.txt"
    private const val MAX_FILE_SIZE = 512 * 1024
    private const val RING_CAPACITY = 2_000
    private const val MAX_READ_BYTES = 512 * 1024

    enum class Level(val tag: String) {
        VERBOSE("V"), DEBUG("D"), INFO("I"), WARN("W"), ERROR("E")
    }

    private val ring = ConcurrentLinkedQueue<String>()
    private val ringSize = AtomicInteger(0)
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var lastRootAttemptMs = 0L

    // Keep disk / su work off the camera render thread and bound queued diagnostics.
    private val fileWriter = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(512),
        ThreadFactory { task -> Thread(task, "LiquidFrame-LogWriter").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    fun v(msg: String) = log(Level.VERBOSE, msg)
    fun d(msg: String) = log(Level.DEBUG, msg)
    fun i(msg: String) = log(Level.INFO, msg)
    fun w(msg: String) = log(Level.WARN, msg)
    fun e(msg: String, t: Throwable? = null) {
        val full = if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            "$msg\n$sw"
        } else msg
        log(Level.ERROR, full)
    }

    @Synchronized
    fun log(level: Level, msg: String) {
        val line = "[${timeFmt.format(Date())}] ${level.tag}/$TAG [${Thread.currentThread().name}] $msg"
        when (level) {
            Level.VERBOSE -> Log.v(TAG, msg)
            Level.DEBUG -> Log.d(TAG, msg)
            Level.INFO -> Log.i(TAG, msg)
            Level.WARN -> Log.w(TAG, msg)
            Level.ERROR -> Log.e(TAG, msg)
        }

        ring.add(line)
        while (ringSize.incrementAndGet() > RING_CAPACITY) {
            ring.poll()
            ringSize.decrementAndGet()
        }
        runCatching { fileWriter.execute { appendShared(line) } }
    }

    private fun appendShared(line: String) {
        val file = File(LOG_FILE)
        try {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (!file.exists()) file.createNewFile()
            if (file.length() > MAX_FILE_SIZE) {
                val keep = file.readText().takeLast(MAX_FILE_SIZE / 2)
                file.writeText(keep)
            }
            file.appendText(line + "\n")
            return
        } catch (t: Throwable) {
            Log.w(TAG, "shared log write needs elevated access: ${t.javaClass.simpleName}")
        }

        val now = System.currentTimeMillis()
        if (now - lastRootAttemptMs < 30_000L) return
        lastRootAttemptMs = now
        val setup = "mkdir -p /data/local/tmp && chmod 777 /data/local/tmp && touch '$LOG_FILE' && chmod 666 '$LOG_FILE'"
        if (runSu(setup) == 0) {
            try {
                if (file.length() > MAX_FILE_SIZE) {
                    val keep = file.readText().takeLast(MAX_FILE_SIZE / 2)
                    file.writeText(keep)
                }
                file.appendText(line + "\n")
            } catch (t: Throwable) {
                Log.w(TAG, "shared log append failed after root setup: ${t.message}")
            }
        }
    }

    /** Local-process tail retained for callers that explicitly need only this process. */
    fun tail(n: Int = 500): List<String> {
        val all = ring.toList()
        return all.takeLast(n.coerceIn(1, RING_CAPACITY))
    }

    /** Read the shared camera/app log. Call from Dispatchers.IO, never the main thread. */
    fun readTail(n: Int = 200): List<String> {
        // Wait for events already accepted by the bounded writer before reading the
        // shared file, otherwise the first refresh can race the logger thread.
        runCatching { fileWriter.submit {}.get(2, TimeUnit.SECONDS) }
        val count = n.coerceIn(1, 2_000)
        val direct = runCatching {
            val file = File(LOG_FILE)
            if (file.exists() && file.canRead()) file.readText().takeLast(MAX_READ_BYTES) else ""
        }.getOrDefault("")
        val content = if (direct.isNotBlank()) direct else {
            runSuCapture("tail -n $count '$LOG_FILE'")
                .ifBlank { LogHelper.readLog() }
        }
        if (content.isNotBlank()) return content.lineSequence().filter { it.isNotBlank() }.toList().takeLast(count)
        return tail(count)
    }

    /** Clears the shared file and local ring. Call from an IO dispatcher. */
    fun clear() {
        runCatching { fileWriter.submit {}.get(2, TimeUnit.SECONDS) }
        ring.clear()
        ringSize.set(0)
        LogHelper.clearLog()
        try {
            File(LOG_FILE).writeText("")
        } catch (_: Throwable) {
            runSu("mkdir -p /data/local/tmp && : > '$LOG_FILE' && chmod 666 '$LOG_FILE'")
        }
    }

    fun filePath(): String = LOG_FILE

    private fun runSu(script: String): Int = executeSu(script, capture = false).first

    private fun runSuCapture(script: String): String {
        val (code, output) = executeSu(script, capture = true)
        return if (code == 0) output else ""
    }

    private fun executeSu(script: String, capture: Boolean): Pair<Int, String> {
        for (binary in listOf("su", "/system/bin/su", "/system/xbin/su")) {
            var process: Process? = null
            try {
                val activeProcess = ProcessBuilder(binary, "-c", script).redirectErrorStream(true).start()
                process = activeProcess
                val output = ByteArrayOutputStream()
                val reader = Thread({
                    runCatching {
                        activeProcess.inputStream.use { input ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (capture && output.size() < MAX_READ_BYTES) {
                                    val remaining = MAX_READ_BYTES - output.size()
                                    output.write(buffer, 0, minOf(read, remaining))
                                }
                            }
                        }
                    }
                }, "LiquidFrame-SuReader").apply { isDaemon = true }
                reader.start()
                runCatching { activeProcess.outputStream.close() }
                // Bound the wait independently from stream drainage. A root-consent
                // prompt must never stall the logger's only writer thread indefinitely.
                if (!activeProcess.waitFor(4, TimeUnit.SECONDS)) {
                    activeProcess.destroyForcibly()
                    reader.join(250)
                    return -1 to ""
                }
                reader.join(300)
                val code = activeProcess.exitValue()
                if (code == 0) return code to output.toString(Charsets.UTF_8.name())
            } catch (t: Throwable) {
                Log.w(TAG, "su command failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { process?.destroy() }
            }
        }
        return -1 to ""
    }
}
