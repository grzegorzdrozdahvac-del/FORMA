package pl.forma.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** One-shot reminder ("Przypomnij o 17:00" for the daily tip). */
class ReminderReceiver : BroadcastReceiver() {
    companion object {
        private const val RC = 41
        private const val X_TITLE = "title"
        private const val X_TEXT = "text"
        private const val X_ROUTE = "route"

        private fun pi(ctx: Context, title: String, text: String, route: String): PendingIntent {
            val i = Intent(ctx, ReminderReceiver::class.java)
            i.putExtra(X_TITLE, title)
            i.putExtra(X_TEXT, text)
            i.putExtra(X_ROUTE, route)
            return PendingIntent.getBroadcast(ctx, RC, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Returns true when the reminder was set for tomorrow (the time had passed). */
        fun schedule(ctx: Context, hhmm: String, title: String, text: String, route: String): Boolean {
            val now = System.currentTimeMillis()
            val at = WakeAlarm.nextWakeMillis(hhmm, now)
            scheduleAt(ctx, at, title, text, route)
            val a = java.util.Calendar.getInstance()
            a.timeInMillis = at
            val n = java.util.Calendar.getInstance()
            n.timeInMillis = now
            return Nudge.dayKey(a) != Nudge.dayKey(n)
        }

        /** Arms the alarm for an absolute time and remembers it in Prefs, so BootReceiver can re-arm it after a reboot. */
        fun scheduleAt(ctx: Context, at: Long, title: String, text: String, route: String) {
            Prefs.setReminder(ctx, at, title, text, route)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, title, text, route))
        }

        fun cancel(ctx: Context) {
            Prefs.clearReminder(ctx)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(ctx, "", "", "#/dzis"))
        }

        /** After a reboot / app update: a reminder that is still in the future is armed again; one that came due while the phone was off is shown now. */
        fun rearm(ctx: Context) {
            val at = Prefs.reminderAt(ctx)
            if (at <= 0L) return
            val title = Prefs.reminderTitle(ctx)
            val text = Prefs.reminderText(ctx)
            val route = Prefs.reminderRoute(ctx)
            if (at > System.currentTimeMillis()) {
                scheduleAt(ctx, at, title, text, route)
            } else {
                Prefs.clearReminder(ctx)
                Notifications.post(ctx, Notifications.REMINDER_ID, title, text, route)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val i = intent ?: return
        val title: String = i.getStringExtra(X_TITLE) ?: "Forma"
        val text: String = i.getStringExtra(X_TEXT) ?: ""
        val route: String = i.getStringExtra(X_ROUTE) ?: "#/dzis"
        Prefs.clearReminder(context.applicationContext)
        Notifications.post(context.applicationContext, Notifications.REMINDER_ID, title, text, route)
    }
}
