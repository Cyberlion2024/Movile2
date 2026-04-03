package com.movile2.bot

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger del bot — scrive su file E su logcat simultaneamente.
 *
 * File: Android/data/com.movile2.bot/files/bot_log.txt
 * Leggibile da qualsiasi file manager del telefono, oppure:
 *   adb pull /sdcard/Android/data/com.movile2.bot/files/bot_log.txt
 *
 * Il file viene resettato ad ogni avvio del servizio (onServiceConnected).
 * Dimensione massima: 2 MB — oltre quella soglia le righe più vecchie
 * vengono scartate automaticamente.
 */
object BotLogger {

    private const val MAX_FILE_BYTES = 2 * 1024 * 1024L   // 2 MB
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()
    private var logFile: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            logFile = File(context.getExternalFilesDir(null), "bot_log.txt")
            logFile?.writeText("=== Bot avviato ${Date()} ===\n")
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("D", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W", tag, msg)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        append("E", tag, msg)
    }

    private fun append(level: String, tag: String, msg: String) {
        val line = "[${sdf.format(Date())}] $level/$tag: $msg\n"
        synchronized(lock) {
            try {
                val f = logFile ?: return
                if (f.length() > MAX_FILE_BYTES) {
                    // Taglia le prime ~500 righe per fare spazio
                    val lines = f.readLines()
                    f.writeText(lines.drop(500).joinToString("\n") + "\n")
                }
                f.appendText(line)
            } catch (_: Exception) {}
        }
    }
}
