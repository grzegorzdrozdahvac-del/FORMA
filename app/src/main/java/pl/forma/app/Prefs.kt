package pl.forma.app

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences: last band and the state of a running night (survives process death). */
object Prefs {
    private const val FILE = "forma_prefs"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lastDeviceId(ctx: Context): String? = sp(ctx).getString("last_device_id", null)
    fun lastDeviceName(ctx: Context): String? = sp(ctx).getString("last_device_name", null)

    /** The band the user chose (auto-connect target). Falls back to the last connected device for installs that predate it. */
    fun preferredId(ctx: Context): String? = sp(ctx).getString("preferred_id", null) ?: lastDeviceId(ctx)
    fun preferredName(ctx: Context): String? = sp(ctx).getString("preferred_name", null) ?: lastDeviceName(ctx)

    fun setPreferred(ctx: Context, id: String, name: String?) {
        sp(ctx).edit().putString("preferred_id", id).putString("preferred_name", name).apply()
    }

    fun setLastDevice(ctx: Context, id: String, name: String?) {
        sp(ctx).edit().putString("last_device_id", id).putString("last_device_name", name).apply()
    }

    fun nightRunning(ctx: Context): Boolean = sp(ctx).getBoolean("night_running", false)
    fun nightId(ctx: Context): String? = sp(ctx).getString("night_id", null)
    fun nightStart(ctx: Context): Long = sp(ctx).getLong("night_start", 0L)
    fun nightWake(ctx: Context): String = sp(ctx).getString("night_wake", "07:00") ?: "07:00"
    fun nightWakeAt(ctx: Context): Long = sp(ctx).getLong("night_wake_at", 0L)
    fun nightOwnsConnection(ctx: Context): Boolean = sp(ctx).getBoolean("night_owns_conn", false)

    fun setNight(ctx: Context, id: String, start: Long, wake: String, wakeAt: Long, ownsConn: Boolean) {
        sp(ctx).edit()
            .putBoolean("night_running", true)
            .putString("night_id", id)
            .putLong("night_start", start)
            .putString("night_wake", wake)
            .putLong("night_wake_at", wakeAt)
            .putBoolean("night_owns_conn", ownsConn)
            .apply()
    }

    fun clearNight(ctx: Context) {
        sp(ctx).edit().putBoolean("night_running", false).remove("night_id").apply()
    }

    // ---------- Etap 4: steps, nudges, reminders ----------
    fun stepsTrack(ctx: Context): Boolean = sp(ctx).getBoolean("steps_track", false)
    fun nudgeOn(ctx: Context): Boolean = sp(ctx).getBoolean("nudge_on", false)
    fun nudgeFrom(ctx: Context): Int = sp(ctx).getInt("nudge_from", 9)
    fun nudgeTo(ctx: Context): Int = sp(ctx).getInt("nudge_to", 20)

    /** ISO weekdays 1 (Monday) .. 7 (Sunday). */
    fun nudgeDays(ctx: Context): Set<Int> {
        val raw: String = sp(ctx).getString("nudge_days", "1,2,3,4,5") ?: "1,2,3,4,5"
        val out = HashSet<Int>()
        for (part in raw.split(",")) {
            val d = part.trim().toIntOrNull()
            if (d != null && d in 1..7) out.add(d)
        }
        return out
    }

    fun setNudgeSettings(ctx: Context, track: Boolean, nudge: Boolean, from: Int, to: Int, days: String) {
        sp(ctx).edit()
            .putBoolean("steps_track", track || nudge)
            .putBoolean("nudge_on", nudge)
            .putInt("nudge_from", from)
            .putInt("nudge_to", to)
            .putString("nudge_days", days)
            .apply()
    }

    /** Last step counter total that is already stored in steps.csv (-1 = none yet). */
    fun stepsLastTotal(ctx: Context): Long = sp(ctx).getLong("steps_last_total", -1L)
    fun setStepsLastTotal(ctx: Context, total: Long) { sp(ctx).edit().putLong("steps_last_total", total).apply() }

    fun nudgeCount(ctx: Context, dayKey: Int): Int {
        val p = sp(ctx)
        return if (p.getInt("nudge_day", 0) == dayKey) p.getInt("nudge_count", 0) else 0
    }

    fun incNudge(ctx: Context, dayKey: Int) {
        val n = nudgeCount(ctx, dayKey) + 1
        sp(ctx).edit().putInt("nudge_day", dayKey).putInt("nudge_count", n).apply()
    }

    fun snoozeBase(ctx: Context): Long = sp(ctx).getLong("snooze_base", -1L)
    fun setSnoozeBase(ctx: Context, total: Long) { sp(ctx).edit().putLong("snooze_base", total).apply() }

    /** The web app says a workout is running; the flag expires after 6 h in case the app died without clearing it. */
    fun workoutRunning(ctx: Context): Boolean {
        val p = sp(ctx)
        if (!p.getBoolean("workout_running", false)) return false
        return System.currentTimeMillis() - p.getLong("workout_running_at", 0L) < 6L * 3_600_000L
    }

    fun setWorkoutRunning(ctx: Context, running: Boolean) {
        sp(ctx).edit().putBoolean("workout_running", running).putLong("workout_running_at", System.currentTimeMillis()).apply()
    }

    // ---------- pending tip reminder (re-armed after a reboot / app update) ----------
    fun reminderAt(ctx: Context): Long = sp(ctx).getLong("reminder_at", 0L)
    fun reminderTitle(ctx: Context): String = sp(ctx).getString("reminder_title", "Forma") ?: "Forma"
    fun reminderText(ctx: Context): String = sp(ctx).getString("reminder_text", "") ?: ""
    fun reminderRoute(ctx: Context): String = sp(ctx).getString("reminder_route", "#/dzis") ?: "#/dzis"

    fun setReminder(ctx: Context, at: Long, title: String, text: String, route: String) {
        sp(ctx).edit().putLong("reminder_at", at).putString("reminder_title", title).putString("reminder_text", text).putString("reminder_route", route).apply()
    }

    fun clearReminder(ctx: Context) {
        sp(ctx).edit().remove("reminder_at").apply()
    }
}
