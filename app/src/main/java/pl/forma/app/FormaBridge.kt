package pl.forma.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * window.FormaNative. Every method returns a JSON string. Events go to JS through
 * window.__formaNativeEvent(<json>), always evaluated on the main thread.
 */
class FormaBridge(private val activity: MainActivity, private val webView: WebView) {

    private val ctx = activity.applicationContext

    private fun ok(): String = "{\"ok\":true}"

    private fun err(msg: String): String {
        val o = JSONObject()
        o.put("ok", false)
        o.put("error", msg)
        return o.toString()
    }

    // ---------- events to JS ----------
    fun emit(o: JSONObject) {
        val json = o.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val js = "window.__formaNativeEvent && window.__formaNativeEvent($json)"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun ev(type: String): JSONObject {
        val o = JSONObject()
        o.put("type", type)
        return o
    }

    val listener = object : BleManager.Listener {
        override fun onScanResult(id: String, name: String, rssi: Int) {
            val o = ev("scanResult")
            o.put("id", id)
            o.put("name", name)
            o.put("rssi", rssi)
            emit(o)
        }

        override fun onScanFinished() {
            val o = ev("scanState")
            o.put("scanning", false)
            emit(o)
        }

        override fun onState(state: String, name: String?) {
            val o = ev("state")
            o.put("state", state)
            o.put("name", name ?: JSONObject.NULL)
            emit(o)
        }

        override fun onHr(sample: HrSample) {
            val o = ev("hr")
            o.put("hr", sample.hr)
            val rr = JSONArray()
            for (v in sample.rr) rr.put(v)
            o.put("rr", rr)
            val c = JSONObject()
            c.put("supported", sample.contactSupported)
            c.put("detected", sample.contactDetected)
            o.put("contact", c)
            o.put("t", sample.t)
            emit(o)
        }

        override fun onBattery(level: Int) {
            val o = ev("battery")
            o.put("level", level)
            emit(o)
        }

        override fun onError(message: String) {
            val o = ev("error")
            o.put("message", message)
            emit(o)
        }

        override fun onNight(state: String, id: String?) {
            val o = ev("night")
            o.put("state", state)
            o.put("id", id ?: JSONObject.NULL)
            emit(o)
        }

        override fun onWarning(code: String, level: Int) {
            val o = ev("warning")
            o.put("code", code)
            o.put("level", level)
            emit(o)
        }
    }

    fun emitPermissions() {
        val o = ev("permissions")
        o.put("granted", btPermissionsGranted())
        o.put("notifications", notificationsGranted())
        emit(o)
    }

    fun emitAppResumed() { emit(ev("appResumed")) }

    private fun btPermissionsGranted(): Boolean = BleManager.hasScanPermission() && BleManager.hasConnectPermission()

    private fun notificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true

    // ---------- JS API ----------
    @JavascriptInterface
    fun isAvailable(): String = "true"

    @JavascriptInterface
    fun requestPermissions(): String {
        val missing = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (!BleManager.hasScanPermission()) missing.add(android.Manifest.permission.BLUETOOTH_SCAN)
            if (!BleManager.hasConnectPermission()) missing.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (!BleManager.hasScanPermission()) missing.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted()) missing.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isEmpty()) {
            emitPermissions()
        } else {
            activity.runOnUiThread { activity.askPermissions(missing.toTypedArray()) }
        }
        return ok()
    }

    @JavascriptInterface
    fun scan(timeoutMs: Int): String {
        BleManager.startScan(timeoutMs.toLong().coerceIn(2_000L, 120_000L), false)
        return ok()
    }

    @JavascriptInterface
    fun scanAll(timeoutMs: Int): String {
        BleManager.startScan(timeoutMs.toLong().coerceIn(2_000L, 120_000L), true)
        return ok()
    }

    @JavascriptInterface
    fun stopScan(): String {
        BleManager.stopScan()
        return ok()
    }

    @JavascriptInterface
    fun connect(id: String): String {
        BleManager.connect(id)
        return ok()
    }

    @JavascriptInterface
    fun disconnect(): String {
        BleManager.disconnect()
        return ok()
    }

    @JavascriptInterface
    fun getState(): String {
        val o = JSONObject()
        o.put("state", BleManager.state)
        o.put("name", BleManager.deviceName ?: JSONObject.NULL)
        o.put("battery", BleManager.batteryLevel ?: JSONObject.NULL)
        o.put("lastDeviceName", Prefs.lastDeviceName(ctx) ?: JSONObject.NULL)
        o.put("lastDeviceId", Prefs.lastDeviceId(ctx) ?: JSONObject.NULL)
        return o.toString()
    }

    @JavascriptInterface
    fun startNight(wakeHHmm: String): String {
        if (!Regex("^\\d{2}:\\d{2}$").matches(wakeHHmm)) return err("Nieprawidłowa godzina pobudki.")
        if (NightService.running) return ok()
        if (!BleManager.hasConnectPermission()) return err("Brak uprawnienia Bluetooth. Połącz opaskę i zezwól na „Urządzenia w pobliżu”.")
        if (Prefs.lastDeviceId(ctx) == null && !BleManager.isConnected()) return err("Najpierw połącz opaskę.")
        NightService.start(ctx, wakeHHmm)
        return ok()
    }

    @JavascriptInterface
    fun stopNight(): String {
        val svc = NightService.instance
        if (svc != null) svc.requestStop() else NightStore.finalizeOrphan(ctx)
        return ok()
    }

    @JavascriptInterface
    fun nightStatus(): String {
        val o = JSONObject()
        val running = NightService.running
        o.put("running", running)
        if (running) {
            o.put("startedAt", NightService.startedAt)
            o.put("beats", NightService.beats)
            o.put("lastHr", if (NightService.lastHr > 0) NightService.lastHr else JSONObject.NULL)
            o.put("battery", BleManager.batteryLevel ?: JSONObject.NULL)
            o.put("deviceName", BleManager.deviceName ?: Prefs.lastDeviceName(ctx) ?: JSONObject.NULL)
            o.put("wake", NightService.wake)
            o.put("wakeAt", NightService.wakeAt)
        } else {
            o.put("startedAt", JSONObject.NULL)
            o.put("beats", 0)
            o.put("lastHr", JSONObject.NULL)
            o.put("battery", JSONObject.NULL)
            o.put("deviceName", Prefs.lastDeviceName(ctx) ?: JSONObject.NULL)
        }
        return o.toString()
    }

    @JavascriptInterface
    fun getNights(): String {
        val runningId = if (NightService.running) NightService.currentId else null
        val arr = JSONArray()
        for (i in NightStore.list(ctx, runningId)) arr.put(NightStore.toJson(i))
        return arr.toString()
    }

    @JavascriptInterface
    fun getNightData(id: String): String = NightStore.dataJson(ctx, id)

    @JavascriptInterface
    fun deleteNight(id: String): String {
        val runningId = if (NightService.running) NightService.currentId else null
        return if (NightStore.delete(ctx, id, runningId)) ok() else err("Nie można usunąć tej nocy.")
    }

    @JavascriptInterface
    fun isIgnoringBatteryOptimizations(): String {
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        return if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "true" else "false"
    }

    @JavascriptInterface
    fun openBatterySettings(): String {
        activity.runOnUiThread {
            try {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:" + ctx.packageName)
                activity.startActivity(i)
            } catch (e: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    BleManager.notifyError("Nie można otworzyć ustawień baterii. Wyłącz optymalizację baterii dla Formy ręcznie.")
                }
            }
        }
        return ok()
    }
}
