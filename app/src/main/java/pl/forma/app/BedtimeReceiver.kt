package pl.forma.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Daily "pora spać" reminder, 30 min before the target bedtime. The web app sends the time (hh:mm) whenever it changes
 * (new planned wake time, new sleep need); after it fires the next one is armed for the same time tomorrow, so it keeps
 * working when the app is not opened. BootReceiver arms it again after a reboot / app update.
 * Same pattern as ReminderReceiver (AlarmManager.setAndAllowWhileIdle, may be a few minutes late), but with its own slot and Prefs keys.
 */
class BedtimeReceiver : BroadcastReceiver() {
    companion object {
        private const val RC = 42

        private fun pi(ctx: Context): PendingIntent {
            val i = Intent(ctx, BedtimeReceiver::class.java)
            return PendingIntent.getBroadcast(ctx, RC, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Remembers the reminder and arms the next occurrence of hh:mm (today if still ahead, otherwise tomorrow). */
        fun schedule(ctx: Context, hhmm: String, title: String, text: String, route: String) {
            Prefs.setBed(ctx, hhmm, title, text, route)
            arm(ctx)
        }

        fun arm(ctx: Context) {
            val hhmm = Prefs.bedAt(ctx)
            if (hhmm.isEmpty()) return
            val at = WakeAlarm.nextWakeMillis(hhmm, System.currentTimeMillis())
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        }

        fun cancel(ctx: Context) {
            Prefs.clearBed(ctx)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(ctx))
        }

        /** After a reboot / app update. */
        fun rearm(ctx: Context) {
            arm(ctx)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val ctx = context.applicationContext
        if (Prefs.bedAt(ctx).isEmpty()) return
        Notifications.post(ctx, Notifications.BEDTIME_ID, Prefs.bedTitle(ctx), Prefs.bedText(ctx), Prefs.bedRoute(ctx))
        arm(ctx)
    }
}
