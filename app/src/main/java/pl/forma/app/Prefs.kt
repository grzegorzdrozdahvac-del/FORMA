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
}
