package pl.forma.app

import android.content.Context
import java.util.Calendar

/** Mirrors StepsCore.nudgeDue from the web app (steps-core.js, covered by node tests). */
object Nudge {
    const val MIN_STEPS = 250L
    const val MAX_PER_DAY = 4

    fun isoDow(c: Calendar): Int = ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // Monday = 1 .. Sunday = 7

    fun dayKey(c: Calendar): Int = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)

    fun due(ctx: Context, nowMs: Long, lastHourSteps: Long): Boolean {
        if (!Prefs.nudgeOn(ctx)) return false
        val c = Calendar.getInstance()
        c.timeInMillis = nowMs
        if (!Prefs.nudgeDays(ctx).contains(isoDow(c))) return false
        val h = c.get(Calendar.HOUR_OF_DAY)
        // the tick at hour h judges the hour that ended: window [from, to] = ticks from+1 .. to
        if (h < Prefs.nudgeFrom(ctx) + 1 || h > Prefs.nudgeTo(ctx)) return false
        if (Prefs.workoutRunning(ctx) || Prefs.nightRunning(ctx)) return false
        if (Prefs.nudgeCount(ctx, dayKey(c)) >= MAX_PER_DAY) return false
        return lastHourSteps < MIN_STEPS
    }

    fun fire(ctx: Context, nowMs: Long) {
        val c = Calendar.getInstance()
        c.timeInMillis = nowMs
        Prefs.incNudge(ctx, dayKey(c))
        Notifications.nudge(ctx)
    }
}
