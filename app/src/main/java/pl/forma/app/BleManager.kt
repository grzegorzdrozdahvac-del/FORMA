package pl.forma.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The only place that talks BLE. Shared by the live mode (JS bridge) and NightService.
 * Standard Heart Rate service only (0x180D / 0x2A37) plus Battery (0x180F / 0x2A19).
 * All GATT state is touched on the main thread; GATT callbacks hop to it first.
 */
@SuppressLint("MissingPermission")
object BleManager {
    interface Listener {
        fun onScanResult(id: String, name: String, rssi: Int) {}
        fun onScanFinished() {}
        fun onState(state: String, name: String?) {}
        fun onHr(sample: HrSample) {}
        fun onBattery(level: Int) {}
        /** Every battery read / notification, with its own timestamp (also fed to BatteryLog). via = "read" | "notify". */
        fun onBatteryEntry(level: Int, via: String, t: Long) {}
        /** 0x2A1A / 0x2BED charging flag, only when the band exposes those characteristics. */
        fun onCharging(charging: Boolean) {}
        /** GATT diagnostics JSON (services, characteristics, properties, battery details); sent again when the 0x2904 descriptor arrives. */
        fun onGattInfo(json: String) {}
        fun onError(message: String) {}
        fun onNight(state: String, id: String?) {}
        fun onWarning(code: String, level: Int) {}
    }

    val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HR_CHAR: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val BATT_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATT_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val CPF: UUID = UUID.fromString("00002904-0000-1000-8000-00805f9b34fb")
    val BATT_POWER_STATE: UUID = UUID.fromString("00002a1a-0000-1000-8000-00805f9b34fb")
    val BATT_LEVEL_STATUS: UUID = UUID.fromString("00002bed-0000-1000-8000-00805f9b34fb")

    private const val CONNECT_TIMEOUT_MS = 25_000L
    private const val BATT_POLL_MS = 60_000L

    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile var state: String = "disconnected"
        private set
    @Volatile var deviceName: String? = null
        private set
    @Volatile var deviceAddress: String? = null
        private set
    @Volatile var batteryLevel: Int? = null
        private set
    /** null = the band does not report a charging flag (0x2A1A / 0x2BED missing or unreadable). */
    @Volatile var chargingFlag: Boolean? = null
        private set
    /** Last GATT diagnostics as a JSON string (kept after disconnect, replaced on the next connection). */
    @Volatile var gattInfoJson: String? = null
        private set

    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null
    private var scanning = false
    private var hrChar: BluetoothGattCharacteristic? = null
    private var battChar: BluetoothGattCharacteristic? = null
    private var battNotify = false
    private var powerStateChar: BluetoothGattCharacteristic? = null
    private var levelStatusChar: BluetoothGattCharacteristic? = null
    private var cpfPresent = false
    private var cpfHex: String? = null

    private val ops = java.util.ArrayDeque<() -> Boolean>()
    private var opBusy = false

    fun init(ctx: Context) {
        if (!::appCtx.isInitialized) appCtx = ctx.applicationContext
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    fun isConnected(): Boolean = state == "connected"

    // ---------- permissions ----------
    private fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(appCtx, p) == PackageManager.PERMISSION_GRANTED

    fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_CONNECT) else true

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_SCAN)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun adapter(): BluetoothAdapter? =
        (appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ---------- listener fan-out (also used by NightService) ----------
    fun notifyNight(s: String, id: String?) { listeners.forEach { it.onNight(s, id) } }
    fun notifyWarning(code: String, level: Int) { listeners.forEach { it.onWarning(code, level) } }
    fun notifyError(msg: String) { listeners.forEach { it.onError(msg) } }

    private fun setState(s: String) {
        state = s
        listeners.forEach { it.onState(s, deviceName) }
    }

    // ---------- scan ----------
    /**
     * Unfiltered scan; the callback keeps a device when it advertises the Heart Rate service (0x180D)
     * OR its name starts with WHOOP / Polar. With all = true every named device is listed.
     */
    fun startScan(timeoutMs: Long, all: Boolean) {
        main.post {
            stopScanInternal()
            val ad = adapter()
            if (ad == null || !ad.isEnabled) {
                notifyError("Bluetooth jest wyłączony. Włącz Bluetooth i spróbuj ponownie.")
                listeners.forEach { it.onScanFinished() }
                return@post
            }
            if (!hasScanPermission()) {
                notifyError("Brak uprawnienia do skanowania Bluetooth.")
                listeners.forEach { it.onScanFinished() }
                return@post
            }
            val sc = ad.bluetoothLeScanner
            if (sc == null) {
                notifyError("Skaner Bluetooth jest niedostępny.")
                listeners.forEach { it.onScanFinished() }
                return@post
            }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) { handleScan(result, all) }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    for (r in results) handleScan(r, all)
                }
                override fun onScanFailed(errorCode: Int) {
                    main.post {
                        notifyError("Skanowanie nie powiodło się (kod $errorCode).")
                        stopScanInternal()
                    }
                }
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                sc.startScan(null, settings, cb)
                scanner = sc
                scanCb = cb
                scanning = true
                main.postDelayed(scanTimeout, timeoutMs)
            } catch (e: Exception) {
                notifyError("Nie udało się rozpocząć skanowania: " + (e.message ?: e.javaClass.simpleName))
                listeners.forEach { it.onScanFinished() }
            }
        }
    }

    private val scanTimeout = Runnable { stopScanInternal() }

    private fun handleScan(r: ScanResult, all: Boolean) {
        val dev = r.device ?: return
        val name = r.scanRecord?.deviceName ?: dev.name ?: ""
        val hasHrService = r.scanRecord?.serviceUuids?.contains(ParcelUuid(HR_SERVICE)) == true
        val nameOk = name.startsWith("WHOOP", true) || name.startsWith("Polar", true)
        val keep = if (all) name.isNotEmpty() else (hasHrService || nameOk)
        if (!keep) return
        val shown = if (name.isEmpty()) "Urządzenie z tętnem" else name
        listeners.forEach { it.onScanResult(dev.address, shown, r.rssi) }
    }

    fun stopScan() { main.post { stopScanInternal() } }

    private fun stopScanInternal() {
        main.removeCallbacks(scanTimeout)
        val wasScanning = scanning
        try {
            val cb = scanCb
            if (cb != null) scanner?.stopScan(cb)
        } catch (e: Exception) { /* adapter turned off etc. */ }
        scanner = null
        scanCb = null
        scanning = false
        if (wasScanning) listeners.forEach { it.onScanFinished() }
    }

    // ---------- connect / disconnect ----------
    fun connect(address: String) {
        main.post {
            stopScanInternal()
            if (!hasConnectPermission()) { notifyError("Brak uprawnienia Bluetooth (urządzenia w pobliżu)."); return@post }
            if (state == "connected" && deviceAddress == address) { setState("connected"); return@post }
            if (state == "connecting" && deviceAddress == address) return@post
            closeGatt()
            val ad = adapter()
            if (ad == null || !ad.isEnabled) { notifyError("Bluetooth jest wyłączony."); setState("disconnected"); return@post }
            val dev: BluetoothDevice = try {
                ad.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                notifyError("Nieprawidłowy adres urządzenia."); return@post
            }
            deviceAddress = address
            deviceName = dev.name ?: (if (Prefs.lastDeviceId(appCtx) == address) Prefs.lastDeviceName(appCtx) else null)
            batteryLevel = null
            setState("connecting")
            try {
                gatt = dev.connectGatt(appCtx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                notifyError("Nie udało się połączyć: " + (e.message ?: e.javaClass.simpleName))
                setState("disconnected")
            }
        }
    }

    private val connectTimeout = Runnable {
        if (state != "connected") {
            notifyError("Przekroczono czas łączenia z opaską.")
            closeGatt()
            setState("disconnected")
        }
    }

    fun disconnect() {
        main.post {
            val g = gatt
            if (g == null) { if (state != "disconnected") setState("disconnected"); return@post }
            try { g.disconnect() } catch (e: Exception) { /* ignore */ }
            main.postDelayed({ if (gatt === g) { closeGatt(); setState("disconnected") } }, 1500)
        }
    }

    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(battPoll)
        ops.clear()
        opBusy = false
        hrChar = null
        battChar = null
        battNotify = false
        powerStateChar = null
        levelStatusChar = null
        try { gatt?.close() } catch (e: Exception) { /* ignore */ }
        gatt = null
        batteryLevel = null
        chargingFlag = null
    }

    // ---------- GATT operation queue (main thread) ----------
    private fun enqueue(op: () -> Boolean) { ops.add(op); pump() }

    private fun pump() {
        if (opBusy) return
        val op = ops.poll() ?: return
        opBusy = true
        val started = try { op() } catch (e: Exception) { false }
        if (!started) { opBusy = false; pump() }
    }

    private fun opDone() { opBusy = false; pump() }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        val cccd = ch.getDescriptor(CCCD) ?: return false
        g.setCharacteristicNotification(ch, true)
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return g.writeDescriptor(cccd)
    }

    private val battPoll = object : Runnable {
        override fun run() {
            val g = gatt
            val bc = battChar
            if (g != null && bc != null && state == "connected") {
                // active read every 60 s even when notifications are on: the read is the ground truth we log
                enqueue { g.readCharacteristic(bc) }
                val ps = powerStateChar
                if (ps != null) enqueue { g.readCharacteristic(ps) }
                val ls = levelStatusChar
                if (ls != null) enqueue { g.readCharacteristic(ls) }
                main.postDelayed(this, BATT_POLL_MS)
            }
        }
    }

    private fun handleBattery(level: Int, via: String) {
        if (level < 0 || level > 100) return
        val t = System.currentTimeMillis()
        batteryLevel = level
        BatteryLog.add(appCtx, t, level, via, deviceName)
        listeners.forEach { it.onBattery(level) }
        listeners.forEach { it.onBatteryEntry(level, via, t) }
    }

    /** 0x2A1A Battery Power State: bits 4-5 = charge state (3 = charging). Layout from memory, unverified on a real band. */
    private fun handlePowerState(v: ByteArray) {
        if (v.isEmpty()) return
        val cs = ((v[0].toInt() and 0xFF) shr 4) and 3
        if (cs == 0) return
        setCharging(cs == 3)
    }

    /** 0x2BED Battery Level Status: flags(u8), power state(u16 LE); bits 5-6 of power state = charge state (1 = charging). Unverified. */
    private fun handleLevelStatus(v: ByteArray) {
        if (v.size < 3) return
        val ps = (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        val cs = (ps shr 5) and 3
        if (cs == 0) return
        setCharging(cs == 1)
    }

    private fun setCharging(on: Boolean) {
        val was = chargingFlag
        chargingFlag = on
        if (was != on) listeners.forEach { it.onCharging(on) }
    }

    private fun propNames(p: Int): JSONArray {
        val a = JSONArray()
        if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) a.put("read")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) a.put("write")
        if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) a.put("write_no_response")
        if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) a.put("notify")
        if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) a.put("indicate")
        return a
    }

    private fun hexOf(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format("%02x", x.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /** Full service / characteristic list with properties, plus what matters for the battery question. */
    private fun buildGattInfo(g: BluetoothGatt): String {
        val root = JSONObject()
        root.put("device", deviceName ?: JSONObject.NULL)
        val svcs = JSONArray()
        val list: List<BluetoothGattService> = g.services ?: emptyList()
        for (svc in list) {
            val so = JSONObject()
            so.put("uuid", svc.uuid.toString())
            val chs = JSONArray()
            for (ch in svc.characteristics) {
                val co = JSONObject()
                co.put("uuid", ch.uuid.toString())
                co.put("props", propNames(ch.properties))
                val ds = JSONArray()
                for (d in ch.descriptors) ds.put(d.uuid.toString())
                co.put("descriptors", ds)
                chs.put(co)
            }
            so.put("characteristics", chs)
            svcs.put(so)
        }
        root.put("services", svcs)
        val bc = battChar
        val b = JSONObject()
        b.put("present", bc != null)
        b.put("props", if (bc != null) propNames(bc.properties) else JSONArray())
        b.put("cpfPresent", cpfPresent)
        b.put("cpfHex", cpfHex ?: JSONObject.NULL)
        b.put("has2BED", levelStatusChar != null)
        b.put("has2A1A", powerStateChar != null)
        root.put("battery", b)
        return root.toString()
    }

    private fun publishGattInfo(g: BluetoothGatt) {
        val json = try { buildGattInfo(g) } catch (e: Exception) { return }
        gattInfoJson = json
        listeners.forEach { it.onGattInfo(json) }
    }

    private fun handleDisconnected(g: BluetoothGatt) {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(battPoll)
        try { g.close() } catch (e: Exception) { /* ignore */ }
        if (gatt === g) gatt = null
        ops.clear()
        opBusy = false
        hrChar = null
        battChar = null
        powerStateChar = null
        levelStatusChar = null
        batteryLevel = null
        chargingFlag = null
        if (state != "disconnected") setState("disconnected")
    }

    private fun setupServices(g: BluetoothGatt) {
        val hrSvc = g.getService(HR_SERVICE)
        val hr = hrSvc?.getCharacteristic(HR_CHAR)
        if (hr == null) {
            notifyError("To urządzenie nie udostępnia tętna (usługa 0x180D). WHOOP: włącz „HR Broadcast” w aplikacji WHOOP.")
            disconnect()
            return
        }
        hrChar = hr
        enqueue { enableNotify(g, hr) } // success -> onDescriptorWrite marks the link as connected
        val battSvc = g.getService(BATT_SERVICE)
        val bc = battSvc?.getCharacteristic(BATT_CHAR)
        cpfPresent = false
        cpfHex = null
        powerStateChar = battSvc?.getCharacteristic(BATT_POWER_STATE)
        levelStatusChar = battSvc?.getCharacteristic(BATT_LEVEL_STATUS)
        if (bc != null) {
            battChar = bc
            enqueue { g.readCharacteristic(bc) }
            battNotify = (bc.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            if (battNotify) enqueue { enableNotify(g, bc) }
            val cpf = bc.getDescriptor(CPF)
            if (cpf != null) {
                cpfPresent = true
                enqueue { g.readDescriptor(cpf) }
            }
            val ps = powerStateChar
            if (ps != null) enqueue { g.readCharacteristic(ps) }
            val ls = levelStatusChar
            if (ls != null) enqueue { g.readCharacteristic(ls) }
            main.removeCallbacks(battPoll)
            main.postDelayed(battPoll, BATT_POLL_MS) // always poll, notifications or not
        }
        publishGattInfo(g)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) { try { g.close() } catch (e: Exception) { /* ignore */ }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!g.discoverServices()) { notifyError("Nie udało się wykryć usług opaski."); disconnect() }
                } else {
                    handleDisconnected(g)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { notifyError("Wykrywanie usług nie powiodło się (kod $status)."); disconnect(); return@post }
                setupServices(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val uuid = d.characteristic?.uuid
            main.post {
                if (g !== gatt) return@post
                if (uuid == HR_CHAR) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        main.removeCallbacks(connectTimeout)
                        val addr = deviceAddress
                        if (addr != null) Prefs.setLastDevice(appCtx, addr, deviceName)
                        setState("connected")
                    } else {
                        notifyError("Nie udało się włączyć powiadomień tętna (kod $status).")
                        disconnect()
                    }
                }
                opDone()
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val uuid = ch.uuid
            val v = ch.value?.copyOf()
            main.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && v != null && v.isNotEmpty()) {
                    if (uuid == BATT_CHAR) handleBattery(v[0].toInt() and 0xFF, "read")
                    else if (uuid == BATT_POWER_STATE) handlePowerState(v)
                    else if (uuid == BATT_LEVEL_STATUS) handleLevelStatus(v)
                }
                opDone()
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val uuid = d.uuid
            val v = d.value?.copyOf()
            main.post {
                if (g !== gatt) return@post
                if (uuid == CPF) {
                    cpfHex = if (status == BluetoothGatt.GATT_SUCCESS && v != null) hexOf(v) else "błąd odczytu (status $status)"
                    publishGattInfo(g)
                }
                opDone()
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val t = System.currentTimeMillis()
            val uuid = ch.uuid
            val v = ch.value?.copyOf()
            main.post {
                if (g !== gatt) return@post
                if (uuid == HR_CHAR) {
                    val s = HrParser.parse(v, t)
                    if (s != null) listeners.forEach { it.onHr(s) }
                } else if (uuid == BATT_CHAR && v != null && v.isNotEmpty()) {
                    handleBattery(v[0].toInt() and 0xFF, "notify")
                } else if (uuid == BATT_POWER_STATE && v != null) {
                    handlePowerState(v)
                } else if (uuid == BATT_LEVEL_STATUS && v != null) {
                    handleLevelStatus(v)
                }
            }
        }
    }
}
