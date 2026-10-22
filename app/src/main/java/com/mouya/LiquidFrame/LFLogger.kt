package com.mouya.LiquidFrame

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * LiquidFrame full logging system.
 *
 * Records nearly all hook pipeline events for debugging:
 * install → composite hook → panel detect → erase → capsule render → text layer.
 * Removes the old rate limit; enlarges the file to 512 KB; adds an in-memory ring
 * buffer so the UI can read the tail without touching disk.
 */
object LFLogger {

    private const val TAG = "LiquidFrame"
    private const val LOG_FILE = "/data/local/tmp/lf_log.txt"
    private const val MAX_FILE_SIZE = 512 * 1024  // 512 KB
    private const val RING_CAPACITY = 2000       // in-memory ring buffer entries

    enum class Level(val tag: String) {
        VERBOSE("V"), DEBUG("D"), INFO("I"), WARN("W"), ERROR("E")
    }

    private val ring = ConcurrentLinkedQueue<String>()
    private val ringSize = AtomicInteger(0)
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var fileInitFailed = false

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun v(msg: String) = log(Level.VERBOSE, msg)
    fun d(msg: String) = log(Level.DEBUG, msg)
    fun i(msg: String) = log(Level.INFO, msg)
    fun w(msg: String) = log(Level.WARN, msg)
    fun e(msg: String, t: Throwable? = null) {
        val full = if (t != null) {
            val sw = StringWriter(); t.printStackTrace(PrintWriter(sw)); "$msg\n$sw"
        } else msg
        log(Level.ERROR, full)
    }

    /**
     * The main entry. Every call formats a line, pushes it to both the in-memory
     * ring buffer and the file, with no rate limiting.
     */
    @Synchronized
    fun log(level: Level, msg: String) {
        val ts = timeFmt.format(Date())
        val thread = Thread.currentThread().name
        val line = "[$ts] ${level.tag}/$TAG [$thread] $msg"

        // Logcat
        when (level) {
            Level.VERBOSE -> Log.v(TAG, msg)
            Level.DEBUG -> Log.d(TAG, msg)
            Level.INFO -> Log.i(TAG, msg)
            Level.WARN -> Log.w(TAG, msg)
            Level.ERROR -> Log.e(TAG, msg)
        }

        // Ring buffer (bounded)
        ring.add(line)
        while (ringSize.incrementAndGet() > RING_CAPACITY) {
            ring.poll()
            ringSize.decrementAndGet()
        }

        // File (bounded)
        if (!fileInitFailed) {
            try {
                val f = File(LOG_FILE)
                if (!f.exists()) {
                    f.parentFile?.mkdirs()
                    f.createNewFile()
                }
                if (f.length() > MAX_FILE_SIZE) {
                    // Truncate: keep last half by rewriting
                    val keep = f.readText().takeLast(MAX_FILE_SIZE / 2)
                    f.writeText(keep)
                }
                f.appendText(line + "\n")
            } catch (t: Throwable) {
                fileInitFailed = true
                Log.w(TAG, "LFLogger file write failed: ${t.message}")
            }
        }
    }

    /** Returns the tail of the log as a list of individual lines. */
    fun tail(n: Int = 500): List<String> {
        val all = ring.toList()
        val start = maxOf(0, all.size - n)
        return all.subList(start, all.size)
    }

    /** Clears both the ring buffer and the file. */
    fun clear() {
        ring.clear()
        ringSize.set(0)
        try { File(LOG_FILE).writeText("") } catch (_: Throwable) {}
    }

    /** Returns the log file path. */
    fun filePath(): String = LOG_FILE
}