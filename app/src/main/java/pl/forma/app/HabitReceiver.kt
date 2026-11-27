package pl.forma.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

/**
 * Habit prompts ("Zwykle o tej porze siłownia – byłeś?"). The web app sends a short plan [{act,date,at,text}] (setHabitPlan);
 * this receiver arms the next not-yet-fired item, posts the notification and queues the answers in Prefs
 * (read by the web app through getHabitAnswers when it resumes). All decisions (quiet hours, modes, limits) are made in the web app.
 */
class HabitReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "pl.forma.app.HABIT_FIRE"
        const val ACTION_YES = "pl.forma.app.HABIT_YES"
        const val ACTION_NO = "pl.forma.app.HABIT_NO"
        const val X_ACT = "act"
        const val X_DATE = "date"
        private const val RC = 61

        private fun firePi(ctx: Context): PendingIntent {
            val i = Intent(ctx, HabitReceiver::class.java).setAction(ACTION_FIRE)
            return PendingIntent.getBroadcast(ctx, RC, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Arms the alarm for the earliest planned item that has not fired yet (also after a reboot). */
        fun armNext(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = System.currentTimeMillis()
            val fired = Prefs.habitFired(ctx)
            var best = Long.MAX_VALUE
            try {
                val arr = JSONArray(Prefs.habitPlan(ctx))
                for (k in 0 until arr.length()) {
                    val o = arr.getJSONObject(k)
                    val at = o.optLong("at", 0L)
                    val key = o.optString("act") + "|" + o.optString("date")
                    if (at > now - 30L * 60_000L && !fired.contains(key) && at < best) best = at
                }
            } catch (e: Exception) {
                // bad plan: nothing to arm
            }
            if (best == Long.MAX_VALUE) { am.cancel(firePi(ctx)); return }
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(best, now + 1_000L), firePi(ctx))
        }

        /** Posts every plan item that is due (at most one per call), marks it fired and arms the next one. */
        fun fireDue(ctx: Context) {
            val now = System.currentTimeMillis()
            var fired = Prefs.habitFired(ctx)
            try {
                val arr = JSONArray(Prefs.habitPlan(ctx))
                for (k in 0 until arr.length()) {
                    val o = arr.getJSONObject(k)
                    val at = o.optLong("at", 0L)
                    val key = o.optString("act") + "|" + o.optString("date")
                    if (at <= now && at > now - 30L * 60_000L && !fired.contains(key)) {
                        fired = (fired + ";" + key).takeLast(400)
                        Prefs.setHabitFired(ctx, fired)
                        Notifications.habit(ctx, o.optString("act"), o.optString("date"), o.optString("text"))
                        break
                    }
                }
            } catch (e: Exception) {
                // ignore a malformed plan
            }
            armNext(ctx)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val i = intent ?: return
        val ctx = context.applicationContext
        when (i.action) {
            ACTION_FIRE -> fireDue(ctx)
            ACTION_YES, ACTION_NO -> {
                val act = i.getStringExtra(X_ACT) ?: return
                val date = i.getStringExtra(X_DATE) ?: return
                Prefs.addHabitAnswer(ctx, act, date, if (i.action == ACTION_YES) "yes" else "no")
                Notifications.cancelHabit(ctx)
            }
        }
    }
}
