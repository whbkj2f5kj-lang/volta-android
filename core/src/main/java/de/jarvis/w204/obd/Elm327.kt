package de.jarvis.w204.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class ElmTimeoutException(cmd: String) : IOException("Timeout bei $cmd")

/** Serielle Verbindung zu einem ELM327 über Bluetooth Classic (SPP). */
class Elm327 private constructor(private val socket: BluetoothSocket) : Closeable {
    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream
    private val buf = ByteArray(512)

    /** Sendet einen Befehl und liest bis zum Prompt '>'. */
    @Synchronized
    fun send(cmd: String, timeoutMs: Long = 1500): String {
        // Reste einer alten Antwort verwerfen
        while (input.available() > 0) { if (input.read(buf, 0, minOf(input.available(), buf.size)) <= 0) break }
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val reply = ElmReplyBuffer(cmd)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val n = input.available()
            if (n > 0) {
                // blockweise lesen: deutlich weniger Systemaufrufe als Byte für Byte
                val r = input.read(buf, 0, minOf(n, buf.size))
                if (r < 0) throw IOException("Verbindung getrennt")
                for (i in 0 until r) {
                    val c = buf[i].toInt() and 0xFF
                    reply.accept(c.toChar())?.let { return it }
                }
            } else {
                Thread.sleep(2)
            }
        }
        reply.pendingAtDeadline()?.let { return it }
        throw ElmTimeoutException(cmd)
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @SuppressLint("MissingPermission")
        fun connect(adapter: BluetoothAdapter, address: String): Elm327 {
            runCatching { adapter.cancelDiscovery() }
            val device = adapter.getRemoteDevice(address)
            // China-Klone brauchen oft die unsichere oder die Reflection-Variante
            val attempts: List<() -> BluetoothSocket> = listOf(
                { device.createRfcommSocketToServiceRecord(SPP) },
                { device.createInsecureRfcommSocketToServiceRecord(SPP) },
                {
                    device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                },
            )
            var last: Throwable? = null
            for (make in attempts) {
                var s: BluetoothSocket? = null
                try {
                    s = make()
                    s.connect()
                    return Elm327(s)
                } catch (t: Throwable) {
                    last = t
                    runCatching { s?.close() }
                }
            }
            throw IOException("Adapter nicht erreichbar (${last?.message ?: "unbekannt"})", last)
        }
    }
}

/** Gekoppelte Geräte, OBD-Adapter zuerst. */
@SuppressLint("MissingPermission")
fun bondedDevices(context: Context): List<Pair<String, String>> = try {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    adapter?.bondedDevices.orEmpty()
        .map { (it.name ?: "Unbekannt") to it.address }
        .sortedByDescending { (name, _) ->
            listOf("OBD", "ELM", "LINK", "V-").any { k -> name.contains(k, ignoreCase = true) }
        }
} catch (e: SecurityException) {
    emptyList()
}
