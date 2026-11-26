package pl.forma.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Hourly step tick (AlarmManager.setAndAllowWhileIdle, inexact on purpose: no exact-alarm permission) plus the nudge actions.
 * After a reboot or an app update BootReceiver calls applySettings; MainActivity.onStart does the same when the app is opened.
 */
class StepAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TICK = "pl.forma.app.action.STEPS_TICK"
        const val ACTION_SNOOZE = "pl.forma.app.action.NUDGE_SNOOZE"
        const val ACTION_SNOOZE_FIRE = "pl.forma.app.action.NUDGE_SNOOZE_FIRE"
        const val ACTION_OK = "pl.forma.app.action.NUDGE_OK"
        private const val RC_TICK = 21
        private const val RC_SNOOZE = 22
        private const val SNOOZE_MS = 30L * 60_000L

        private fun pi(ctx: Context, rc: Int, action: String): PendingIntent {
            val i = Intent(ctx, StepAlarmReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(ctx, rc, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Next full hour + 20 s. */
        fun scheduleNext(ctx: Context) {
            val c = Calendar.getInstance()
            c.add(Calendar.HOUR_OF_DAY, 1)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 20)
            c.set(Calendar.MILLISECOND, 0)
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi(ctx, RC_TICK, ACTION_TICK))
        }

        fun cancelAll(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(ctx, RC_TICK, ACTION_TICK))
            am.cancel(pi(ctx, RC_SNOOZE, ACTION_SNOOZE_FIRE))
        }

        fun scheduleSnooze(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + SNOOZE_MS, pi(ctx, RC_SNOOZE, ACTION_SNOOZE_FIRE))
        }

        /** Applies the saved settings: starts or stops the listener and the hourly chain. Called by the bridge and on app start. */
        fun applySettings(ctx: Context) {
            val app = ctx.applicationContext
            if (Prefs.stepsTrack(app) && StepCounter.permitted(app)) {
                StepCounter.start(app)
                scheduleNext(app)
            } else {
                StepCounter.stop(app)
                cancelAll(app)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val ctx = context.applicationContext
        val action: String = intent?.action ?: ACTION_TICK
        if (action == ACTION_OK) {
            cancelNudge(ctx)
            return
        }
        if (action == ACTION_SNOOZE) {
            cancelNudge(ctx)
            if (Prefs.nudgeOn(ctx)) {
                Prefs.setSnoozeBase(ctx, StepCounter.currentTotal(ctx))
                scheduleSnooze(ctx)
            }
            return
        }
        if (!Prefs.stepsTrack(ctx)) return
        val pending: BroadcastReceiver.PendingResult? = goAsync()
        Thread {
            try {
                if (action == ACTION_SNOOZE_FIRE) handleSnooze(ctx) else handleTick(ctx)
            } catch (e: Exception) {
                // never crash a receiver
            } finally {
                pending?.finish()
            }
        }.start()
    }

    private fun cancelNudge(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(Notifications.NUDGE_ID)
    }

    private fun handleTick(ctx: Context) {
        val now = System.currentTimeMillis()
        try {
            // steps taken since the previous reading belong to the hour that just ended (ticks run at hh:00 + a little)
            val judged = StepCounter.hourStart(now - 10 * 60_000L)
            val got = StepCounter.sampleOnce(ctx, now - 10 * 60_000L, 4_000L)
            StepCounter.compact(ctx)
            if (got || StepCounter.currentTotal(ctx) >= 0L) {
                val last = StepCounter.stepsInHour(ctx, judged)
                if (Nudge.due(ctx, now, last)) Nudge.fire(ctx, now)
            }
        } finally {
            scheduleNext(ctx) // keep the chain alive even after an exception
        }
    }

    /** "Za 30 min": nudge again only when fewer than ~125 steps were taken since the button was pressed (half of the hourly threshold). */
    private fun handleSnooze(ctx: Context) {
        val now = System.currentTimeMillis()
        StepCounter.sampleOnce(ctx, now, 4_000L)
        val base = Prefs.snoozeBase(ctx)
        val total = StepCounter.currentTotal(ctx)
        if (base < 0L || total < 0L) return
        val delta: Long = if (total >= base) total - base else total
        if (Nudge.due(ctx, now, if (delta < 125L) 0L else Nudge.MIN_STEPS)) Nudge.fire(ctx, now)
    }
}
