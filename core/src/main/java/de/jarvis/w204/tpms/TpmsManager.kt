package de.jarvis.w204.tpms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** Verbindung zum USB-Reifendruck-Empfänger (CH340 1A86:7523 oder FTDI 0403:6001/6015). */
class TpmsManager(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val usb = app.getSystemService(UsbManager::class.java)

    private val _tires = MutableStateFlow<Map<Wheel, TireState>>(emptyMap())
    val tires: StateFlow<Map<Wheel, TireState>> = _tires
    private val _status = MutableStateFlow(TpmsStatus())
    val status: StateFlow<TpmsStatus> = _status

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var loop: Job? = null
    private var rest = ByteArray(0)
    private var requestedDevice: Int? = null
    private var seed = (System.currentTimeMillis() and 0xFF).toInt()
    @Volatile private var enabled = true

    private val ids = setOf(0x1A86 to 0x7523, 0x0403 to 0x6001, 0x0403 to 0x6015)
    private val action = "de.jarvis.volta.TPMS_USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == action) scope.launch(Dispatchers.IO) { tryOpen() }
        }
    }

    init {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(permissionReceiver, filter)
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) start() else stop()
    }

    /** Sucht alle 5 s nach dem Empfänger und hält die Verbindung (Herzschlag 1×/s). */
    fun start() {
        if (loop?.isActive == true || !enabled) return
        loop = scope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                if (port == null) {
                    if (tick % 5 == 0) tryOpen()
                } else {
                    write(TpmsProtocol.HEARTBEAT)
                    if (_status.value.genuine != true) write(TpmsProtocol.seed(seed))
                    // Reifen ohne neue Daten seit 10 min als veraltet markieren
                    val now = SystemClock.elapsedRealtime()
                    _tires.update { m -> m.filterValues { now - it.at < 10 * 60_000L } }
                }
                tick++
                delay(1000)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        close()
        requestedDevice = null
        _tires.value = emptyMap()
        _status.value = TpmsStatus(message = "TPMS ausgeschaltet")
    }

    @Synchronized
    private fun tryOpen() {
        if (!enabled || port != null || usb == null) return
        try {
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
                .firstOrNull { (it.device.vendorId to it.device.productId) in ids }
            if (driver == null) {
                _status.value = TpmsStatus(message = "Kein TPMS-Empfänger gefunden")
                return
            }
            val dev = driver.device
            val name = "%04X:%04X".format(dev.vendorId, dev.productId)
            if (!usb.hasPermission(dev)) {
                if (requestedDevice == dev.deviceId) return
                requestedDevice = dev.deviceId
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
                val pi = PendingIntent.getBroadcast(app, 0, Intent(action).setPackage(app.packageName), flags)
                usb.requestPermission(dev, pi)
                _status.value = TpmsStatus(TpmsLink.NO_PERMISSION, "USB-Zugriff für den TPMS-Empfänger erlauben", name)
                return
            }
            val conn = usb.openDevice(dev) ?: run {
                _status.value = TpmsStatus(TpmsLink.ERROR, "Empfänger belegt – läuft noch die alte TPMS-App?", name)
                return
            }
            val p = driver.ports.first()
            p.open(conn)
            p.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = p
            rest = ByteArray(0)
            val manager = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) = onData(data)
                override fun onRunError(e: Exception) {
                    Log.w(TAG, "Verbindung verloren", e)
                    close()
                    _status.value = TpmsStatus(TpmsLink.ERROR, "Verbindung zum Empfänger verloren – suche neu …", name)
                }
            })
            io = manager
            executor.submit(manager)
            write(TpmsProtocol.STOP_PAIRING)
            _status.value = TpmsStatus(TpmsLink.CONNECTED, "Verbunden – warte auf Sensoren (kann 1–2 min dauern, Rad muss sich drehen)", name)
        } catch (t: Throwable) {
            Log.w(TAG, "Öffnen fehlgeschlagen", t)
            close()
            _status.value = TpmsStatus(TpmsLink.ERROR, "Empfänger konnte nicht geöffnet werden: ${t.message}")
        }
    }

    private fun onData(data: ByteArray) {
        val msgs = mutableListOf<TpmsProtocol.Msg>()
        synchronized(this) {
            rest = TpmsProtocol.parse(rest + data, SystemClock.elapsedRealtime(), msgs)
            if (rest.size > 256) rest = ByteArray(0)
        }
        for (m in msgs) when (m) {
            is TpmsProtocol.Msg.Tire -> {
                _tires.update { it + (m.state.wheel to m.state) }
                if (_status.value.message.startsWith("Verbunden – warte")) {
                    _status.update { it.copy(message = "Verbunden") }
                }
            }
            TpmsProtocol.Msg.Heartbeat -> write(TpmsProtocol.HEARTBEAT_ACK)
            is TpmsProtocol.Msg.SeedAnswer -> _status.update { it.copy(genuine = m.value == TpmsProtocol.seedAnswer(seed)) }
            is TpmsProtocol.Msg.Other -> {}
        }
    }

    @Synchronized
    private fun write(bytes: ByteArray) {
        try {
            port?.write(bytes, 500)
        } catch (t: Throwable) {
            Log.w(TAG, "Schreiben fehlgeschlagen", t)
        }
    }

    @Synchronized
    private fun close() {
        _tires.value = emptyMap()
        runCatching { io?.stop() }
        io = null
        runCatching { port?.close() }
        port = null
    }

    fun release() {
        stop()
        runCatching { app.unregisterReceiver(permissionReceiver) }
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "Tpms"
    }
}
