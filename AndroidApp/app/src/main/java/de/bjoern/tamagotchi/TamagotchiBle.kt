package de.bjoern.tamagotchi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Verbindung zum ESP32-Tamagotchi.
 * Muss zur Firmware passen (siehe PROTOCOL.md): gleiche UUIDs, gleicher Geraetename.
 */
@SuppressLint("MissingPermission") // Berechtigungen werden in MainActivity geprueft
class TamagotchiBle(private val context: Context) {

    companion object {
        const val DEVICE_NAME = "Tamagotchi"
        val SERVICE_UUID: UUID = UUID.fromString("7a6d0001-5f3c-4a8e-9b1d-2c4e6f8a0b1c")
        val LIGHT_UUID: UUID = UUID.fromString("7a6d0002-5f3c-4a8e-9b1d-2c4e6f8a0b1c")
        val TEXT_UUID: UUID = UUID.fromString("7a6d0003-5f3c-4a8e-9b1d-2c4e6f8a0b1c")
        val VOLTAGE_UUID: UUID = UUID.fromString("7a6d0004-5f3c-4a8e-9b1d-2c4e6f8a0b1c")

        // Standard-Bluetooth: Battery Service / Battery Level / Notify-Descriptor
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 10_000L
        const val MAX_TEXT = 16
    }

    enum class Status { IDLE, SCANNING, CONNECTING, CONNECTED }

    data class State(
        val status: Status = Status.IDLE,
        val message: String = "Nicht verbunden",
        val brightness: Int = 255,
        val lcdText: String = "Hallo Welt!",
        val batteryPercent: Int? = null,
        val batteryMv: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var lightChr: BluetoothGattCharacteristic? = null
    private var textChr: BluetoothGattCharacteristic? = null

    // BLE erlaubt nur eine Operation gleichzeitig -> Warteschlange fuer Lesen, Schreiben, Notify
    private sealed interface Op {
        val chr: BluetoothGattCharacteristic
        class Write(override val chr: BluetoothGattCharacteristic, val value: ByteArray) : Op
        class Read(override val chr: BluetoothGattCharacteristic) : Op
        class EnableNotify(override val chr: BluetoothGattCharacteristic) : Op
    }

    private val queue = ArrayDeque<Op>()
    private var busy = false

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ---------- Scannen ----------

    fun scan() {
        if (_state.value.status != Status.IDLE) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            setStatus(Status.IDLE, "Bluetooth ist aus")
            return
        }
        val filter = ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        setStatus(Status.SCANNING, "Suche Tamagotchi...")
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private val scanTimeout = Runnable {
        stopScan()
        if (_state.value.status == Status.SCANNING) {
            setStatus(Status.IDLE, "Nicht gefunden. Ist der XIAO an?")
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (_state.value.status != Status.SCANNING) return
            stopScan()
            setStatus(Status.CONNECTING, "Verbinde...")
            gatt = result.device.connectGatt(
                context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            setStatus(Status.IDLE, "Scan fehlgeschlagen ($errorCode)")
        }
    }

    // ---------- Verbindung ----------

    fun disconnect() {
        stopScan()
        val g = gatt
        when {
            g == null -> setStatus(Status.IDLE, "Nicht verbunden")
            _state.value.status == Status.CONNECTED -> g.disconnect() // Callback raeumt auf
            else -> {
                cleanup(g)
                setStatus(Status.IDLE, "Abgebrochen")
            }
        }
    }

    /** Sofort alles schliessen, z. B. wenn die App beendet wird. */
    fun close() {
        stopScan()
        gatt?.let { cleanup(it) }
    }

    private fun cleanup(g: BluetoothGatt) {
        g.close()
        if (gatt == g) gatt = null
        lightChr = null
        textChr = null
        synchronized(queue) {
            queue.clear()
            busy = false
        }
        _state.update { it.copy(batteryPercent = null, batteryMv = null) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                setStatus(Status.CONNECTING, "Lese Dienste...")
                g.discoverServices()
            } else {
                val wasConnected = _state.value.status == Status.CONNECTED
                cleanup(g)
                setStatus(
                    Status.IDLE,
                    when {
                        status == BluetoothGatt.GATT_SUCCESS -> "Getrennt"
                        wasConnected -> "Verbindung verloren"
                        else -> "Verbindung fehlgeschlagen ($status)"
                    }
                )
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                setStatus(Status.CONNECTING, "Tamagotchi-Service nicht gefunden")
                g.disconnect()
                return
            }
            lightChr = service.getCharacteristic(LIGHT_UUID)
            textChr = service.getCharacteristic(TEXT_UUID)
            setStatus(Status.CONNECTED, "Verbunden")

            // Aktuelle Helligkeit lesen, damit der Regler stimmt
            lightChr?.let { enqueue(Op.Read(it)) }

            // Rohe Akkuspannung in mV lesen und abonnieren
            service.getCharacteristic(VOLTAGE_UUID)?.let {
                enqueue(Op.Read(it))
                enqueue(Op.EnableNotify(it))
            }

            // Akkustand lesen und Aenderungen abonnieren (Firmware sendet per Notify)
            g.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)?.let {
                enqueue(Op.Read(it))
                enqueue(Op.EnableNotify(it))
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleValue(characteristic.uuid, value)
            opDone()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = opDone()

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) = opDone()

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = handleValue(characteristic.uuid, value)
    }

    private fun handleValue(uuid: UUID, value: ByteArray) {
        if (value.isEmpty()) return
        val b = value[0].toInt() and 0xFF
        when (uuid) {
            LIGHT_UUID -> _state.update { it.copy(brightness = b) }
            BATTERY_LEVEL_UUID -> _state.update { it.copy(batteryPercent = b.coerceIn(0, 100)) }
            VOLTAGE_UUID -> if (value.size >= 2) {
                val mv = b or ((value[1].toInt() and 0xFF) shl 8)  // uint16 little-endian
                _state.update { it.copy(batteryMv = mv) }
            }
        }
    }

    // ---------- Befehle ----------

    fun setBrightness(value: Int) {
        val v = value.coerceIn(0, 255)
        _state.update { it.copy(brightness = v) }
        val c = lightChr ?: return
        // Beim Schieben nur den neuesten Wert senden, alte verwerfen
        enqueue(Op.Write(c, byteArrayOf(v.toByte())), replacePending = true)
    }

    fun sendText(raw: String) {
        val text = toLcdText(raw)
        _state.update { it.copy(lcdText = text) }
        val c = textChr ?: return
        enqueue(Op.Write(c, text.toByteArray(Charsets.US_ASCII)), replacePending = true)
    }

    /** Das Display kann nur ASCII: Umlaute ersetzen, Rest filtern, auf 16 Zeichen kuerzen. */
    private fun toLcdText(raw: String): String = raw
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
        .replace("ß", "ss")
        .filter { it.code in 0x20..0x7E }
        .take(MAX_TEXT)

    // ---------- Warteschlange ----------

    private fun enqueue(op: Op, replacePending: Boolean = false) {
        synchronized(queue) {
            if (replacePending) queue.removeAll { it is Op.Write && it.chr.uuid == op.chr.uuid }
            queue.addLast(op)
        }
        next()
    }

    private fun opDone() {
        synchronized(queue) { busy = false }
        next()
    }

    private fun next() {
        synchronized(queue) {
            if (busy) return
            val g = gatt ?: return
            while (true) {
                val op = queue.removeFirstOrNull() ?: return
                busy = true
                val started = when (op) {
                    is Op.Write -> g.writeCharacteristic(
                        op.chr, op.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS

                    is Op.Read -> g.readCharacteristic(op.chr)

                    is Op.EnableNotify -> {
                        val cccd = op.chr.getDescriptor(CCCD_UUID)
                        g.setCharacteristicNotification(op.chr, true) && cccd != null &&
                            g.writeDescriptor(
                                cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            ) == BluetoothStatusCodes.SUCCESS
                    }
                }
                if (started) return
                busy = false // abgelehnt -> naechste Operation versuchen
            }
        }
    }

    private fun setStatus(status: Status, message: String) {
        _state.update { it.copy(status = status, message = message) }
    }
}
