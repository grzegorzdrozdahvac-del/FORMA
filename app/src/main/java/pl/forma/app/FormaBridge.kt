package pl.forma.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * window.FormaNative. Every method returns a JSON string. Events go to JS through
 * window.__formaNativeEvent(<json>), always evaluated on the main thread.
 */
class FormaBridge(private val activity: MainActivity, private val webView: WebView) {

    private val ctx = activity.applicationContext

    init {
        // Health Connect results (any thread) -> JS events (emit posts to the main thread itself)
        HealthConnectManager.emitter = { o -> emit(o) }
    }

    private fun ok(): String = "{\"ok\":true}"

    private fun err(msg: String): String {
        val o = JSONObject()
        o.put("ok", false)
        o.put("error", msg)
        return o.toString()
    }

    /** Haptics for the web UI: window.FormaNative.vibrate(ms). Fire-and-forget, never throws. */
    @JavascriptInterface
    fun vibrate(ms: Int) {
        try {
            val duration: Long = ms.coerceIn(1, 200).toLong()
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            // ignore: haptics are optional
        }
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
            if (state == "reconnecting") {
                o.put("attempt", BleManager.reconnectAttempt)
                o.put("reason", BleManager.lastReason ?: JSONObject.NULL)
                o.put("code", BleManager.lastStatus ?: JSONObject.NULL)
            }
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

        override fun onBatteryEntry(level: Int, via: String, t: Long) {
            val o = ev("battery")
            o.put("level", level)
            o.put("via", via)
            o.put("t", t)
            emit(o)
        }

        override fun onCharging(charging: Boolean) {
            val o = ev("charging")
            o.put("charging", charging)
            emit(o)
        }

        override fun onGattInfo(json: String) {
            val o = ev("gattInfo")
            try {
                o.put("info", JSONObject(json))
            } catch (e: Exception) {
                return
            }
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

    /** Separate from the Bluetooth 'permissions' event: a refused step permission must not show the Bluetooth error. */
    fun emitStepsPermission() {
        val o = ev("stepsPermission")
        o.put("granted", StepCounter.permitted(ctx))
        emit(o)
    }

    /** Result of the location dialog: granted = precise location; coarse = only approximate location was given (not enough for a route). */
    fun emitGpsPermission() {
        val o = ev("gpsPermission")
        val fine = GpsService.hasFine(ctx)
        o.put("granted", fine)
        o.put("coarse", !fine && GpsService.hasCoarse(ctx))
        emit(o)
    }

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

    // ---------- Etap 4: notifications, reminders, steps ----------
    /** Alarm notification on the high-importance channel; the route (e.g. "#/alarm") opens on tap. */
    @JavascriptInterface
    fun notify(title: String, text: String, route: String): String {
        if (!notificationsGranted()) return err("Brak zgody na powiadomienia.")
        val r: String = if (Regex("^#/[a-z/]+$").matches(route)) route else "#/dzis"
        Notifications.post(ctx, Notifications.ALERT_ID, title, text, r)
        return ok()
    }

    /** One-shot reminder at the next hh:mm (AlarmManager.setAndAllowWhileIdle, may be a few minutes late). */
    @JavascriptInterface
    fun scheduleReminder(hhmm: String, title: String, text: String, route: String): String {
        if (!Regex("^\\d{2}:\\d{2}$").matches(hhmm)) return err("Nieprawidłowa godzina.")
        val r: String = if (Regex("^#/[a-z/]+$").matches(route)) route else "#/dzis"
        return try {
            val tomorrow = ReminderReceiver.schedule(ctx, hhmm, title, text, r)
            val o = JSONObject()
            o.put("ok", true)
            o.put("tomorrow", tomorrow)
            o.toString()
        } catch (e: Exception) {
            err("Nie udało się ustawić przypomnienia.")
        }
    }

    @JavascriptInterface
    fun cancelReminder(): String {
        ReminderReceiver.cancel(ctx)
        return ok()
    }

    @JavascriptInterface
    fun requestStepsPermission(): String {
        if (Build.VERSION.SDK_INT >= 29 && !StepCounter.permitted(ctx)) {
            activity.runOnUiThread { activity.askStepsPermission() }
        } else {
            emitStepsPermission()
        }
        return ok()
    }

    /** { available, permission, tracking, hourly:[{t,steps}], daily:[{date,steps}] } from the local day fromIso. */
    @JavascriptInterface
    fun getSteps(fromIso: String): String = StepCounter.toJson(ctx, fromIso).toString()

    /** json: { track, nudge, from, to, days:[1..7] }. Starts / stops the listener and the hourly alarm. */
    @JavascriptInterface
    fun setNudgeSettings(json: String): String {
        return try {
            val j = JSONObject(json)
            val from = j.optInt("from", 9).coerceIn(0, 22)
            val to = j.optInt("to", 20).coerceIn(1, 23)
            val arr: JSONArray? = j.optJSONArray("days")
            val days = ArrayList<String>()
            if (arr != null) {
                for (k in 0 until arr.length()) {
                    val d = arr.optInt(k, 0)
                    if (d in 1..7) days.add(d.toString())
                }
            }
            Prefs.setNudgeSettings(ctx, j.optBoolean("track", false), j.optBoolean("nudge", false), from, if (to > from) to else from + 1, days.joinToString(","))
            StepAlarmReceiver.applySettings(ctx)
            val o = JSONObject()
            o.put("ok", true)
            o.put("permission", StepCounter.permitted(ctx))
            o.put("available", StepCounter.available(ctx))
            o.toString()
        } catch (e: Exception) {
            err("Nieprawidłowe ustawienia.")
        }
    }

    /** The web app tells whether a workout is running (no nudges during it). */
    @JavascriptInterface
    fun setWorkoutRunning(running: Boolean): String {
        Prefs.setWorkoutRunning(ctx, running)
        return ok()
    }

    // ---------- Etap 5B: route of a Spacer / Bieg workout (GpsService stores the fixes; the web code fetches and analyses them) ----------
    /** {ok, supported, permission (precise location granted), coarse, running, count, gpsOn (GPS switched on in the phone)}. */
    @JavascriptInterface
    fun gpsStatus(): String {
        val o = JSONObject()
        o.put("ok", true)
        o.put("supported", true)
        o.put("permission", GpsService.hasFine(ctx))
        o.put("coarse", GpsService.hasCoarse(ctx))
        o.put("running", GpsService.running)
        o.put("count", GpsTrack.count())
        val lmgr = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        o.put("gpsOn", try { lmgr != null && lmgr.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) } catch (e: Exception) { false })
        return o.toString()
    }

    /** Only called when the user turns the GPS switch on (after the Polish explanation). Result = event gpsPermission. */
    @JavascriptInterface
    fun gpsRequestPermission(): String {
        if (GpsService.hasFine(ctx)) {
            emitGpsPermission()
        } else {
            activity.runOnUiThread { activity.askGpsPermission() }
        }
        return ok()
    }

    /** "Start" of a Spacer / Bieg workout with the switch on. Same sessionId again = continue the same track. */
    @JavascriptInterface
    fun gpsStart(sessionId: String): String {
        if (sessionId.isEmpty() || sessionId.length > 40) return errCode("arg", "Nieprawidłowy identyfikator treningu.")
        if (!GpsService.hasFine(ctx)) return errCode("permission", "Brak zgody na dokładną lokalizację.")
        return if (GpsService.start(ctx, sessionId)) ok() else errCode("service", "Nie udało się uruchomić GPS.")
    }

    /** Fixes from index [from] on: {ok, next, state, running, pts:[[t, lat, lon, acc|null]]}. Works with the screen off (the points are stored natively). */
    @JavascriptInterface
    fun gpsPoints(from: Int): String = GpsTrack.since(from).toString()

    /** "Zakończ" / discard: stops the service and removes the stored track (the web code has already fetched everything). */
    @JavascriptInterface
    fun gpsStop(): String {
        GpsService.stop()
        GpsTrack.clear(ctx)
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

    /** Auto-connect to the remembered band (no picker). {ok:false,error} when no band is remembered. Never connects to any other device. */
    @JavascriptInterface
    fun connectPreferred(): String {
        return if (BleManager.connectPreferred()) ok() else err("Brak zapamiętanej opaski.")
    }

    /** Urządzenia: the default / remembered band follows the user's choice. */
    @JavascriptInterface
    fun setPreferredBand(id: String, name: String): String {
        if (id.isEmpty()) return err("Brak identyfikatora urządzenia.")
        Prefs.setPreferred(ctx, id, name)
        return ok()
    }

    /** Urządzenia -> "Zapomnij urządzenie". */
    @JavascriptInterface
    fun forgetBand(): String {
        Prefs.clearPreferred(ctx)
        return ok()
    }

    /** Raport tygodnia -> "Eksportuj PDF": the system print dialog of the WebView (it offers "Zapisz jako PDF"; the web print stylesheet hides the rest). */
    @JavascriptInterface
    fun printPage(): String {
        activity.runOnUiThread {
            try {
                val pm = activity.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
                val name = "Forma - raport tygodnia"
                pm.print(name, webView.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build())
            } catch (e: Exception) {
                BleManager.notifyError("Nie można otworzyć drukowania. Zrób zrzut ekranu raportu.")
            }
        }
        return ok()
    }

    /** Connection log ring buffer: [{t, kind, code, reason, attempt}], oldest first. */
    @JavascriptInterface
    fun getConnectionLog(): String = BleManager.connectionLogJson().toString()

    /**
     * mode: "session" (morning measurement / workout: foreground service + HIGH connection priority),
     * "diag" (Diagnostyka: HIGH priority only), anything else = idle (BALANCED, service stopped).
     */
    @JavascriptInterface
    fun setLiveUse(mode: String): String {
        val high = mode == "session" || mode == "diag"
        BleManager.setLiveUse(high)
        if (mode == "session" && BleManager.hasConnectPermission()) LiveService.start(ctx) else LiveService.stop()
        return ok()
    }

    @JavascriptInterface
    fun getState(): String {
        val o = JSONObject()
        o.put("state", BleManager.state)
        o.put("name", BleManager.deviceName ?: JSONObject.NULL)
        o.put("battery", BleManager.batteryLevel ?: JSONObject.NULL)
        o.put("charging", BleManager.chargingFlag ?: JSONObject.NULL)
        o.put("lastDeviceName", Prefs.lastDeviceName(ctx) ?: JSONObject.NULL)
        o.put("lastDeviceId", Prefs.lastDeviceId(ctx) ?: JSONObject.NULL)
        o.put("preferredId", Prefs.preferredId(ctx) ?: JSONObject.NULL)
        o.put("preferredName", Prefs.preferredName(ctx) ?: JSONObject.NULL)
        o.put("attempt", BleManager.reconnectAttempt)
        o.put("reason", BleManager.lastReason ?: JSONObject.NULL)
        o.put("code", BleManager.lastStatus ?: JSONObject.NULL)
        return o.toString()
    }

    /** Persistent native battery log [{t, level, via, device, connected:true}], includes readings taken by the night service. */
    @JavascriptInterface
    fun getBatteryLog(): String = BatteryLog.toJson(ctx).toString()

    @JavascriptInterface
    fun clearBatteryLog(): String {
        BatteryLog.clear(ctx)
        return ok()
    }

    /** Last GATT diagnostics (all services / characteristics with properties, battery details) or the string "null". */
    @JavascriptInterface
    fun getGattInfo(): String = BleManager.gattInfoJson ?: "null"

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

    // ---------- Health Connect (HealthConnectManager does the work on Dispatchers.IO; results arrive as events) ----------
    /** {ok, status: "available"|"update"|"unavailable", sdk}. The permission lists follow as the event hcStatus. */
    @JavascriptInterface
    fun hcStatus(): String {
        HealthConnectManager.emitStatus(ctx)
        return HealthConnectManager.statusJson(ctx).toString()
    }

    /** System permission dialog for all read + write permissions; result = event hcPermissions. Missing / old Health Connect -> Play Store. */
    @JavascriptInterface
    fun hcRequestPermissions(): String {
        if (!HealthConnectManager.isAvailable(ctx)) {
            activity.runOnUiThread { HealthConnectManager.openPlayStore(activity) }
            HealthConnectManager.emitStatus(ctx)
            return errCode("unavailable", "Zainstaluj lub zaktualizuj Health Connect.")
        }
        activity.runOnUiThread { activity.askHealthPermissions(HealthConnectManager.ALL_PERMISSIONS) }
        return ok()
    }

    /** Result = event hcSyncResult {ok, data{stepsDaily, stepsHourly, weight, height, exercise, sleep, rhr, hrv, calDaily}, failed[]} or {ok:false, code, message}. */
    @JavascriptInterface
    fun hcSync(sinceIso: String): String {
        if (!HealthConnectManager.isAvailable(ctx)) return errCode("unavailable", "Health Connect nie jest dostępny.")
        HealthConnectManager.sync(ctx, sinceIso)
        return ok()
    }

    /** json {"records":[{kind: hrv|rhr|exercise|hr|sleep, cid, ver, ...}]}; result = event hcWriteResult. */
    @JavascriptInterface
    fun hcWrite(json: String): String {
        if (!HealthConnectManager.isAvailable(ctx)) return errCode("unavailable", "Health Connect nie jest dostępny.")
        HealthConnectManager.write(ctx, json)
        return ok()
    }

    /** Health Connect settings (permissions); the Play Store page when Health Connect is missing or must be updated. */
    @JavascriptInterface
    fun hcOpenSettings(): String {
        activity.runOnUiThread { HealthConnectManager.openSettings(activity) }
        return ok()
    }

    // ---------- WHOOP Developer API (all network work happens in WhoopClient, on background threads) ----------
    private val whoopSyncing = AtomicBoolean(false)
    private var whoopStatusServed = false

    private fun errCode(code: String, msg: String): String {
        val o = JSONObject()
        o.put("ok", false)
        o.put("code", code)
        o.put("error", msg)
        return o.toString()
    }

    /** Stores Client ID / secret in SharedPreferences. Two empty strings remove everything (credentials and tokens). */
    @JavascriptInterface
    fun whoopSetCredentials(id: String, secret: String): String {
        return try {
            WhoopClient.setCredentials(ctx, id, secret)
            ok()
        } catch (e: Exception) {
            err(e.message ?: "Nie można zapisać danych logowania.")
        }
    }

    /** Opens the WHOOP authorization page in the browser; the redirect comes back as pl.forma.app://whoop-callback. */
    @JavascriptInterface
    fun whoopAuthorize(): String {
        val url: String = try {
            WhoopClient.beginAuthorize(ctx)
        } catch (e: WhoopException) {
            return errCode(e.code, e.message ?: "")
        } catch (e: Exception) {
            return errCode("config", e.message ?: "")
        }
        activity.runOnUiThread {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                activity.startActivity(i)
            } catch (e: Exception) {
                val o = ev("whoopAuth")
                o.put("ok", false)
                o.put("code", "app")
                o.put("message", "Nie znaleziono przeglądarki do logowania WHOOP.")
                emit(o)
            }
        }
        return ok()
    }

    /** {configured, connected, clientId, authResult?}. Never contains the secret or tokens. */
    @JavascriptInterface
    fun whoopStatus(): String {
        val withAuth = !whoopStatusServed
        whoopStatusServed = true
        return WhoopClient.status(ctx, withAuth).toString()
    }

    /** Result arrives as the event {type:"whoopSync", ok, data:{recovery,sleep,workout,cycle}} or {ok:false, code, message}. */
    @JavascriptInterface
    fun whoopSync(sinceIso: String): String {
        if (!WhoopClient.isConnected(ctx)) return errCode("auth", "Konto WHOOP nie jest połączone.")
        if (!whoopSyncing.compareAndSet(false, true)) return ok()
        val since = if (sinceIso.isBlank()) java.time.Instant.now().minusSeconds(30L * 86400L).toString() else sinceIso
        Thread {
            val o = ev("whoopSync")
            try {
                val data = WhoopClient.sync(ctx, since)
                o.put("ok", true)
                o.put("data", data)
            } catch (e: WhoopException) {
                o.put("ok", false)
                o.put("code", e.code)
                o.put("status", e.status)
                o.put("message", e.message ?: "")
            } catch (e: Exception) {
                o.put("ok", false)
                o.put("code", "http")
                o.put("message", e.message ?: "")
            }
            whoopSyncing.set(false)
            emit(o)
        }.start()
        return ok()
    }

    @JavascriptInterface
    fun whoopDisconnect(): String {
        WhoopClient.disconnect(ctx)
        return ok()
    }

    /** Called by MainActivity for pl.forma.app://whoop-callback. */
    fun handleWhoopCallback(uri: Uri) {
        WhoopClient.handleCallback(ctx, uri) { o -> emit(o) }
    }
}
