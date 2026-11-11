package de.jarvis.w204.volta

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Eigenes OBD-Protokoll neben volta_log.txt: Verbindungen, Live-Werte (alle 30 s) und
 * jeder Mercedes-Test komplett. Ordner: Android/data/de.jarvis.volta/files/volta_obd_log.txt
 * Ab 2 MB wird die alte Datei zu volta_obd_log_alt.txt.
 */
object ObdLog {
    @Volatile private var file: File? = null
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)

    fun init(ctx: Context) {
        if (file != null) return
        val dir = ctx.applicationContext.getExternalFilesDir(null) ?: ctx.applicationContext.filesDir
        file = File(dir, "volta_obd_log.txt")
        line("---- Volta gestartet ----")
    }

    @Synchronized
    fun line(text: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > 2L * 1024 * 1024) {
                val alt = File(f.parentFile, "volta_obd_log_alt.txt")
                alt.delete(); f.renameTo(alt)
            }
            f.appendText(time.format(Date()) + "  " + text + "\n")
        }
    }

    fun block(title: String, body: String) = line("==== $title ====\n$body\n==== Ende $title ====")
}
