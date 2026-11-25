package pl.forma.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object WakeAlarm {
    fun nextWakeMillis(hhmm: String, now: Long = System.currentTimeMillis()): Long {
        val p = hhmm.split(":")
        val h = (p.getOrNull(0)?.toIntOrNull() ?: 7).coerceIn(0, 23)
        val m = (p.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now + 60_000L) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun opIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, WakeAlarmReceiver::class.java).setAction(WakeAlarmReceiver.ACTION_WAKE)
        return PendingIntent.getBroadcast(ctx, 11, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun schedule(ctx: Context, atMillis: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            ctx, 13, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), opIntent(ctx))
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(opIntent(ctx))
    }
}
