package pl.forma.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Pressure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/**
 * Health Connect (androidx.health.connect:connect-client 1.1.0-alpha07).
 * All reads / writes run on Dispatchers.IO; results go to the web UI as JSON events through [emitter]:
 *   hcStatus {status, sdk, granted[], missing[]}, hcPermissions {granted[], missing[]}, hcSyncResult {ok, since, data{...}, failed[]},
 *   hcWriteResult {ok, written[cid], failed[{cid,error}], denied[kind]}, hcError {code, message}.
 * Times are epoch milliseconds; the web side derives local dates itself. Daily aggregates carry a local "date" string.
 * Version-dependent spots are kept in a few places: sdkStatus(), grantedPermissions(), meta() (see BUILD-NOTES, fallback plan).
 */
object HealthConnectManager {
    const val PROVIDER = "com.google.android.apps.healthdata"
    private const val P = "android.permission.health."

    val READ_PERMISSIONS: Set<String> = setOf(
        P + "READ_STEPS", P + "READ_WEIGHT", P + "READ_HEIGHT", P + "READ_EXERCISE", P + "READ_SLEEP",
        P + "READ_HEART_RATE", P + "READ_RESTING_HEART_RATE", P + "READ_HEART_RATE_VARIABILITY",
        P + "READ_ACTIVE_CALORIES_BURNED", P + "READ_TOTAL_CALORIES_BURNED"
    )
    val WRITE_PERMISSIONS: Set<String> = setOf(
        P + "WRITE_HEART_RATE_VARIABILITY", P + "WRITE_RESTING_HEART_RATE", P + "WRITE_EXERCISE", P + "WRITE_HEART_RATE", P + "WRITE_SLEEP",
        P + "WRITE_BLOOD_PRESSURE", P + "WRITE_BLOOD_GLUCOSE"
    )
    val ALL_PERMISSIONS: Set<String> = READ_PERMISSIONS + WRITE_PERMISSIONS

    /** Set by FormaBridge; called from background threads (the bridge posts to the main thread itself). */
    @Volatile
    var emitter: ((JSONObject) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncing = AtomicBoolean(false)
    private val writeMutex = Mutex()

    private fun ev(type: String): JSONObject {
        val o = JSONObject()
        o.put("type", type)
        return o
    }

    private fun emit(o: JSONObject) {
        val f = emitter
        if (f != null) f(o)
    }

    fun emitError(code: String, message: String) {
        val o = ev("hcError")
        o.put("code", code)
        o.put("message", message)
        emit(o)
    }

    // ---------------------------------------------------------------- availability
    fun sdkStatus(ctx: Context): Int {
        return try {
            HealthConnectClient.getSdkStatus(ctx, PROVIDER)
        } catch (e: Exception) {
            HealthConnectClient.SDK_UNAVAILABLE
        }
    }

    fun statusName(status: Int): String {
        return when (status) {
            HealthConnectClient.SDK_AVAILABLE -> "available"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
            else -> "unavailable"
        }
    }

    fun isAvailable(ctx: Context): Boolean = sdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    private suspend fun grantedPermissions(ctx: Context): Set<String> {
        val c = HealthConnectClient.getOrCreate(ctx)
        return c.permissionController.getGrantedPermissions()
    }

    private fun permJson(o: JSONObject, granted: Set<String>) {
        val g = JSONArray()
        for (p in ALL_PERMISSIONS) if (granted.contains(p)) g.put(p)
        val m = JSONArray()
        for (p in ALL_PERMISSIONS) if (!granted.contains(p)) m.put(p)
        o.put("granted", g)
        o.put("missing", m)
    }

    /** Immediate answer for the bridge (no permission list; that arrives with the hcStatus event). */
    fun statusJson(ctx: Context): JSONObject {
        val s = sdkStatus(ctx)
        val o = JSONObject()
        o.put("ok", true)
        o.put("status", statusName(s))
        o.put("sdk", s)
        return o
    }

    /** Emits hcStatus with the granted / missing permission lists (when Health Connect is available). */
    fun emitStatus(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            val s = sdkStatus(app)
            val o = ev("hcStatus")
            o.put("status", statusName(s))
            o.put("sdk", s)
            if (s == HealthConnectClient.SDK_AVAILABLE) {
                try {
                    permJson(o, grantedPermissions(app))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    o.put("error", e.message ?: e.javaClass.simpleName)
                }
            }
            emit(o)
        }
    }

    /** After the system permission dialog: re-read the real grants (the dialog result only lists what it asked for). */
    fun onPermissionResult(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            val o = ev("hcPermissions")
            try {
                permJson(o, grantedPermissions(app))
                emit(o)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emitError("permission", e.message ?: "Nie udało się odczytać uprawnień Health Connect.")
            }
        }
    }

    // ---------------------------------------------------------------- Play Store / settings
    fun openPlayStore(activity: Activity) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setPackage("com.android.vending")
            i.data = Uri.parse("market://details?id=" + PROVIDER + "&url=healthconnect%3A%2F%2Fonboarding")
            i.putExtra("overlay", true)
            i.putExtra("callerId", activity.packageName)
            activity.startActivity(i)
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + PROVIDER)))
            } catch (e2: Exception) {
                emitError("app", "Nie można otworzyć sklepu Google Play.")
            }
        }
    }

    /** Health Connect settings (manage permissions); when it is missing or too old, the Play Store page instead. */
    fun openSettings(activity: Activity) {
        if (!isAvailable(activity)) {
            openPlayStore(activity)
            return
        }
        val actions = listOf(
            "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS",
            "android.health.connect.action.HEALTH_HOME_SETTINGS",
            "android.settings.HEALTH_CONNECT_SETTINGS"
        )
        for (a in actions) {
            try {
                activity.startActivity(Intent(a))
                return
            } catch (e: Exception) {
                // try the next action
            }
        }
        openPlayStore(activity)
    }

    // ---------------------------------------------------------------- reading
    private class Range(val zone: ZoneId, val since: Instant, val now: Instant) {
        val startOfDay: Instant = since.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val startLdt: LocalDateTime = since.atZone(zone).toLocalDate().atStartOfDay()
        val endLdt: LocalDateTime = now.atZone(zone).toLocalDateTime()
        val instants: TimeRangeFilter = TimeRangeFilter.between(startOfDay, now)
    }

    private fun has(granted: Set<String>, name: String): Boolean = granted.contains(P + name)

    private suspend fun <T : Record> readAll(c: HealthConnectClient, type: KClass<T>, range: TimeRangeFilter): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val resp = c.readRecords(
                ReadRecordsRequest(recordType = type, timeRangeFilter = range, ascendingOrder = true, pageSize = 1000, pageToken = token)
            )
            out.addAll(resp.records)
            token = resp.pageToken
        } while (!token.isNullOrEmpty() && out.size < 20000)
        return out
    }

    private suspend fun <T : Record> newest(c: HealthConnectClient, type: KClass<T>, now: Instant): T? {
        val resp = c.readRecords(
            ReadRecordsRequest(recordType = type, timeRangeFilter = TimeRangeFilter.before(now), ascendingOrder = false, pageSize = 1)
        )
        return resp.records.firstOrNull()
    }

    private suspend fun section(name: String, failed: JSONArray, block: suspend () -> JSONArray): JSONArray {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed.put(name + ": " + (e.message ?: e.javaClass.simpleName))
            JSONArray()
        }
    }

    /** Steps per local day (AggregateGroupByPeriod: Health Connect de-duplicates overlapping data of several apps). */
    private suspend fun stepsDaily(c: HealthConnectClient, r: Range): JSONArray {
        val out = JSONArray()
        val res = c.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(r.startLdt, r.endLdt),
                timeRangeSlicer = Period.ofDays(1)
            )
        )
        for (g in res) {
            val n: Long = g.result[StepsRecord.COUNT_TOTAL] ?: 0L
            val o = JSONObject()
            o.put("date", g.startTime.toLocalDate().toString())
            o.put("steps", n)
            out.put(o)
        }
        return out
    }

    /** Steps per hour (AggregateGroupByDuration), buckets start at local midnight so they line up with local hours. */
    private suspend fun stepsHourly(c: HealthConnectClient, r: Range): JSONArray {
        val out = JSONArray()
        val res = c.aggregateGroupByDuration(
            AggregateGroupByDurationRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = r.instants,
                timeRangeSlicer = Duration.ofHours(1)
            )
        )
        for (g in res) {
            val n: Long = g.result[StepsRecord.COUNT_TOTAL] ?: 0L
            if (n > 0L) {
                val o = JSONObject()
                o.put("t", g.startTime.toEpochMilli())
                o.put("steps", n)
                out.put(o)
            }
        }
        return out
    }

    private suspend fun weights(c: HealthConnectClient, r: Range): JSONArray {
        val list = ArrayList<WeightRecord>(readAll(c, WeightRecord::class, r.instants))
        val last = newest(c, WeightRecord::class, r.now) // the latest weighing even when it is older than the sync window
        if (last != null && list.none { it.metadata.id == last.metadata.id }) list.add(last)
        val out = JSONArray()
        for (w in list) {
            val o = JSONObject()
            o.put("t", w.time.toEpochMilli())
            o.put("kg", w.weight.inKilograms)
            o.put("origin", w.metadata.dataOrigin.packageName)
            out.put(o)
        }
        return out
    }

    private suspend fun heights(c: HealthConnectClient, r: Range): JSONArray {
        val out = JSONArray()
        val h = newest(c, HeightRecord::class, r.now)
        if (h != null) {
            val o = JSONObject()
            o.put("t", h.time.toEpochMilli())
            o.put("cm", h.height.inMeters * 100.0)
            o.put("origin", h.metadata.dataOrigin.packageName)
            out.put(o)
        }
        return out
    }

    private suspend fun exercises(c: HealthConnectClient, r: Range, canHr: Boolean): JSONArray {
        val out = JSONArray()
        var hrQueries = 0
        for (e in readAll(c, ExerciseSessionRecord::class, r.instants)) {
            val o = JSONObject()
            o.put("id", e.metadata.id)
            o.put("type", e.exerciseType)
            o.put("start", e.startTime.toEpochMilli())
            o.put("end", e.endTime.toEpochMilli())
            o.put("title", e.title ?: "")
            o.put("origin", e.metadata.dataOrigin.packageName)
            if (canHr && hrQueries < 80) {
                hrQueries++
                try {
                    val a = c.aggregate(
                        AggregateRequest(
                            metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX),
                            timeRangeFilter = TimeRangeFilter.between(e.startTime, e.endTime),
                            dataOriginFilter = setOf(DataOrigin(e.metadata.dataOrigin.packageName))
                        )
                    )
                    val avg: Long? = a[HeartRateRecord.BPM_AVG]
                    val max: Long? = a[HeartRateRecord.BPM_MAX]
                    if (avg != null) o.put("hrAvg", avg)
                    if (max != null) o.put("hrMax", max)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    // heart rate of one session is optional
                }
            }
            out.put(o)
        }
        return out
    }

    private suspend fun sleeps(c: HealthConnectClient, r: Range): JSONArray {
        val out = JSONArray()
        for (s in readAll(c, SleepSessionRecord::class, r.instants)) {
            val o = JSONObject()
            o.put("id", s.metadata.id)
            o.put("start", s.startTime.toEpochMilli())
            o.put("end", s.endTime.toEpochMilli())
            o.put("title", s.title ?: "")
            o.put("origin", s.metadata.dataOrigin.packageName)
            val st = JSONArray()
            for (g in s.stages) {
                val x = JSONObject()
                x.put("s", g.startTime.toEpochMilli())
                x.put("e", g.endTime.toEpochMilli())
                x.put("st", g.stage)
                st.put(x)
            }
            o.put("stages", st)
            out.put(o)
        }
        return out
    }

    private suspend fun restingHr(c: HealthConnectClient, r: Range): JSONArray {
        val out = JSONArray()
        for (x in readAll(c, RestingHeartRateRecord::class, r.instants)) {
            val o = JSONObject()
            o.put("t", x.time.toEpochMilli())
            o.put("bpm", x.beatsPerMinute)
            o.put("origin", x.metadata.dataOrigin.packageName)
            out.put(o)
        }
        return out
    }

    /** RMSSD of other apps; the web side labels it by data origin and uses it for comparison only. */
    private suspend fun hrv(c: HealthConnectClient, r: Range): JSONArray {
        val out = JSONArray()
        for (x in readAll(c, HeartRateVariabilityRmssdRecord::class, r.instants)) {
            val o = JSONObject()
            o.put("t", x.time.toEpochMilli())
            o.put("rmssd", x.heartRateVariabilityMillis)
            o.put("origin", x.metadata.dataOrigin.packageName)
            out.put(o)
        }
        return out
    }

    /** Active and total kilocalories per local day. Two separate aggregates so one missing permission does not hide the other. */
    private suspend fun calories(c: HealthConnectClient, r: Range, active: Boolean, total: Boolean, failed: JSONArray): JSONArray {
        val byDate = java.util.TreeMap<String, JSONObject>()
        if (active) {
            try {
                val res = c.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(r.startLdt, r.endLdt),
                        timeRangeSlicer = Period.ofDays(1)
                    )
                )
                for (g in res) {
                    val kcal = g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                    if (kcal != null) {
                        val d = g.startTime.toLocalDate().toString()
                        val o = byDate[d] ?: JSONObject()
                        o.put("date", d)
                        o.put("active", kcal)
                        byDate[d] = o
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.put("kcalActive: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        if (total) {
            try {
                val res = c.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(r.startLdt, r.endLdt),
                        timeRangeSlicer = Period.ofDays(1)
                    )
                )
                for (g in res) {
                    val kcal = g.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                    if (kcal != null) {
                        val d = g.startTime.toLocalDate().toString()
                        val o = byDate[d] ?: JSONObject()
                        o.put("date", d)
                        o.put("total", kcal)
                        byDate[d] = o
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.put("kcalTotal: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        val out = JSONArray()
        for (o in byDate.values) out.put(o)
        return out
    }

    /**
     * Reads everything the user granted, from [sinceIso] (the web app sends: 30 days on the first sync, afterwards last sync - 2 days).
     * Only one sync runs at a time. Every category is read separately; a failing one is listed in "failed" and does not lose the others.
     */
    fun sync(ctx: Context, sinceIso: String) {
        val app = ctx.applicationContext
        if (!syncing.compareAndSet(false, true)) return
        scope.launch {
            val o = ev("hcSyncResult")
            try {
                val s = sdkStatus(app)
                if (s != HealthConnectClient.SDK_AVAILABLE) {
                    o.put("ok", false)
                    o.put("code", statusName(s))
                    o.put("message", "Health Connect nie jest dostępny.")
                } else {
                    val c = HealthConnectClient.getOrCreate(app)
                    val granted = grantedPermissions(app)
                    val now = Instant.now()
                    val floor = now.minus(Duration.ofDays(30))
                    var since: Instant = try {
                        Instant.parse(sinceIso)
                    } catch (e: Exception) {
                        floor
                    }
                    if (since.isBefore(floor)) since = floor
                    val r = Range(ZoneId.systemDefault(), since, now)
                    val failed = JSONArray()
                    val data = JSONObject()
                    val canSteps = has(granted, "READ_STEPS")
                    data.put("stepsDaily", if (canSteps) { section("steps", failed) { stepsDaily(c, r) } } else { JSONArray() })
                    data.put("stepsHourly", if (canSteps) { section("stepsHourly", failed) { stepsHourly(c, r) } } else { JSONArray() })
                    data.put("weight", if (has(granted, "READ_WEIGHT")) { section("weight", failed) { weights(c, r) } } else { JSONArray() })
                    data.put("height", if (has(granted, "READ_HEIGHT")) { section("height", failed) { heights(c, r) } } else { JSONArray() })
                    val canHr = has(granted, "READ_HEART_RATE")
                    data.put("exercise", if (has(granted, "READ_EXERCISE")) { section("exercise", failed) { exercises(c, r, canHr) } } else { JSONArray() })
                    data.put("sleep", if (has(granted, "READ_SLEEP")) { section("sleep", failed) { sleeps(c, r) } } else { JSONArray() })
                    data.put("rhr", if (has(granted, "READ_RESTING_HEART_RATE")) { section("rhr", failed) { restingHr(c, r) } } else { JSONArray() })
                    data.put("hrv", if (has(granted, "READ_HEART_RATE_VARIABILITY")) { section("hrv", failed) { hrv(c, r) } } else { JSONArray() })
                    val ka = has(granted, "READ_ACTIVE_CALORIES_BURNED")
                    val kt = has(granted, "READ_TOTAL_CALORIES_BURNED")
                    data.put("calDaily", if (ka || kt) { section("kcal", failed) { calories(c, r, ka, kt, failed) } } else { JSONArray() })
                    o.put("ok", true)
                    o.put("since", since.toString())
                    o.put("data", data)
                    o.put("failed", failed)
                    permJson(o, granted)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                o.put("ok", false)
                o.put("code", "permission")
                o.put("message", e.message ?: "Brak uprawnień Health Connect.")
            } catch (e: Exception) {
                o.put("ok", false)
                o.put("code", "sync")
                o.put("message", e.message ?: e.javaClass.simpleName)
            } finally {
                syncing.set(false)
            }
            emit(o)
        }
    }

    // ---------------------------------------------------------------- writing
    /**
     * Metadata of our records: the same clientRecordId (our record id) with a higher clientRecordVersion updates the record instead of
     * duplicating it. alpha07 has the public constructor; from 1.1.0-alpha10 on use Metadata.manualEntry(clientRecordId, clientRecordVersion).
     */
    private fun meta(cid: String, ver: Long): Metadata = Metadata(clientRecordId = cid, clientRecordVersion = ver)

    private fun writePermFor(kind: String): String? {
        return when (kind) {
            "hrv" -> P + "WRITE_HEART_RATE_VARIABILITY"
            "rhr" -> P + "WRITE_RESTING_HEART_RATE"
            "exercise" -> P + "WRITE_EXERCISE"
            "hr" -> P + "WRITE_HEART_RATE"
            "sleep" -> P + "WRITE_SLEEP"
            "bp" -> P + "WRITE_BLOOD_PRESSURE"
            "glucose" -> P + "WRITE_BLOOD_GLUCOSE"
            else -> null
        }
    }

    private fun exerciseType(name: String): Int {
        return when (name) {
            "STRENGTH_TRAINING" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            "RUNNING" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            "WALKING" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }
    }

    private fun buildRecord(j: JSONObject): Record? {
        val kind = j.optString("kind")
        val cid = j.optString("cid")
        val ver = j.optLong("ver", 0L)
        if (cid.isEmpty() || ver <= 0L) return null
        val md = meta(cid, ver)
        val off = ZoneOffset.ofTotalSeconds(j.optInt("off", 0))
        return when (kind) {
            "hrv" -> {
                val rmssd = j.optDouble("rmssd", Double.NaN)
                if (rmssd.isNaN() || rmssd <= 0.0) null
                else HeartRateVariabilityRmssdRecord(
                    time = Instant.ofEpochMilli(j.getLong("t")), zoneOffset = off, heartRateVariabilityMillis = rmssd, metadata = md
                )
            }
            "rhr" -> {
                val bpm = j.optLong("bpm", 0L)
                if (bpm < 20L || bpm > 220L) null
                else RestingHeartRateRecord(time = Instant.ofEpochMilli(j.getLong("t")), zoneOffset = off, beatsPerMinute = bpm, metadata = md)
            }
            "exercise" -> {
                val start = Instant.ofEpochMilli(j.getLong("start"))
                val end = Instant.ofEpochMilli(j.getLong("end"))
                if (!end.isAfter(start)) null
                else ExerciseSessionRecord(
                    startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
                    exerciseType = exerciseType(j.optString("etype")), title = j.optString("title", "Forma"), metadata = md
                )
            }
            "hr" -> {
                val arr = j.optJSONArray("samples")
                val samples = ArrayList<HeartRateRecord.Sample>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val bpm = s.optLong("bpm", 0L)
                        if (bpm in 20L..250L) samples.add(HeartRateRecord.Sample(time = Instant.ofEpochMilli(s.getLong("t")), beatsPerMinute = bpm))
                    }
                }
                val start = Instant.ofEpochMilli(j.getLong("start"))
                val end = Instant.ofEpochMilli(j.getLong("end"))
                if (samples.isEmpty() || !end.isAfter(start)) null
                else HeartRateRecord(startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off, samples = samples, metadata = md)
            }
            "sleep" -> {
                val start = Instant.ofEpochMilli(j.getLong("start"))
                val end = Instant.ofEpochMilli(j.getLong("end"))
                if (!end.isAfter(start)) null
                else SleepSessionRecord(
                    startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off, title = "Sen (Forma)", metadata = md
                )
            }
            "bp" -> {
                // manual entry typed by the user (Zdrowie serca); mmHg
                val sys = j.optDouble("sys", Double.NaN)
                val dia = j.optDouble("dia", Double.NaN)
                if (sys.isNaN() || dia.isNaN() || sys < 60.0 || sys > 260.0 || dia < 30.0 || dia > 160.0 || sys <= dia) null
                else BloodPressureRecord(
                    time = Instant.ofEpochMilli(j.getLong("t")), zoneOffset = off,
                    systolic = Pressure.millimetersOfMercury(sys), diastolic = Pressure.millimetersOfMercury(dia), metadata = md
                )
            }
            "glucose" -> {
                // manual entry typed by the user (Zdrowie serca); mg/dL, fasting or after a meal
                val mg = j.optDouble("mgdl", Double.NaN)
                if (mg.isNaN() || mg < 20.0 || mg > 600.0) null
                else BloodGlucoseRecord(
                    time = Instant.ofEpochMilli(j.getLong("t")), zoneOffset = off,
                    level = BloodGlucose.milligramsPerDeciliter(mg),
                    relationToMeal = if (j.optString("rel") == "fasting") BloodGlucoseRecord.RELATION_TO_MEAL_FASTING else BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL,
                    metadata = md
                )
            }
            else -> null
        }
    }

    /**
     * json: {"records":[{kind, cid, ver, ...}]} from the web app (own records only: HRV, resting HR, exercise, heart rate, sleep).
     * Records go in per kind; if a batch fails they are retried one by one so a single bad record does not block the others.
     */
    fun write(ctx: Context, json: String) {
        val app = ctx.applicationContext
        scope.launch {
            writeMutex.withLock {
                val o = ev("hcWriteResult")
                val written = JSONArray()
                val failed = JSONArray()
                val deniedKinds = LinkedHashSet<String>()
                try {
                    val s = sdkStatus(app)
                    if (s != HealthConnectClient.SDK_AVAILABLE) {
                        o.put("ok", false)
                        o.put("code", statusName(s))
                        o.put("message", "Health Connect nie jest dostępny.")
                    } else {
                        val c = HealthConnectClient.getOrCreate(app)
                        val granted = grantedPermissions(app)
                        val arr = JSONObject(json).optJSONArray("records") ?: JSONArray()
                        val groups = LinkedHashMap<String, ArrayList<Pair<String, Record>>>()
                        for (i in 0 until arr.length()) {
                            val j = arr.optJSONObject(i) ?: continue
                            val kind = j.optString("kind")
                            val need = writePermFor(kind)
                            if (need == null || !granted.contains(need)) {
                                deniedKinds.add(kind)
                                continue
                            }
                            val rec = try {
                                buildRecord(j)
                            } catch (e: Exception) {
                                null
                            }
                            if (rec == null) {
                                val f = JSONObject()
                                f.put("cid", j.optString("cid"))
                                f.put("error", "nieprawidłowy rekord")
                                failed.put(f)
                                continue
                            }
                            groups.getOrPut(kind) { ArrayList() }.add(Pair(j.optString("cid"), rec))
                        }
                        for ((_, items) in groups) {
                            var okBatch = false
                            try {
                                c.insertRecords(items.map { it.second })
                                okBatch = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                okBatch = false
                            }
                            if (okBatch) {
                                for (p in items) written.put(p.first)
                            } else {
                                for (p in items) {
                                    try {
                                        c.insertRecords(listOf(p.second))
                                        written.put(p.first)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        val f = JSONObject()
                                        f.put("cid", p.first)
                                        f.put("error", e.message ?: e.javaClass.simpleName)
                                        failed.put(f)
                                    }
                                }
                            }
                        }
                        o.put("ok", true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    o.put("ok", false)
                    o.put("code", if (e is SecurityException) "permission" else "write")
                    o.put("message", e.message ?: e.javaClass.simpleName)
                }
                val denied = JSONArray()
                for (k in deniedKinds) denied.put(k)
                o.put("written", written)
                o.put("failed", failed)
                o.put("denied", denied)
                emit(o)
            }
        }
    }
}
