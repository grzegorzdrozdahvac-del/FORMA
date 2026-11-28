package pl.forma.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifications {
    const val CH_NIGHT = "night"
    const val CH_MORNING = "morning"
    const val CH_NUDGE = "nudge"
    const val CH_LIVE = "live"
    const val RESULT_ID = 4002
    const val NUDGE_ID = 4003
    const val ALERT_ID = 4004
    const val REMINDER_ID = 4005
    const val BEDTIME_ID = 4006
    const val HABIT_ID = 4007
    const val EFFORT_ID = 4008

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val night = NotificationChannel(CH_NIGHT, "Pomiar nocy", NotificationManager.IMPORTANCE_LOW)
        night.setShowBadge(false)
        nm.createNotificationChannel(night)
        val morning = NotificationChannel(CH_MORNING, "Poranek", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(morning)
        val nudge = NotificationChannel(CH_NUDGE, "Przypomnienia o ruchu", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(nudge)
        val live = NotificationChannel(CH_LIVE, "Połączenie z opaską (pomiar, trening)", NotificationManager.IMPORTANCE_LOW)
        live.setShowBadge(false)
        nm.createNotificationChannel(live)
    }

    private fun openApp(ctx: Context, requestCode: Int, route: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (route != null) i.putExtra(MainActivity.EXTRA_ROUTE, route)
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun night(ctx: Context, text: String): Notification {
        val stop = Intent(ctx, WakeAlarmReceiver::class.java).setAction(WakeAlarmReceiver.ACTION_STOP)
        val stopPi = PendingIntent.getBroadcast(ctx, 12, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(ctx, CH_NIGHT)
            .setSmallIcon(R.drawable.ic_stat_forma)
            .setContentTitle("Pomiar nocy")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, 1, null))
            .addAction(0, "Zakończ", stopPi)
            .build()
    }

    /** Foreground notification of LiveService (morning measurement, workout). */
    fun live(ctx: Context, connected: Boolean): Notification {
        val allDay = Prefs.allDayOn(ctx)
        val title = if (connected) "Forma – połączono z opaską" else "Forma – łączenie z opaską…"
        return NotificationCompat.Builder(ctx, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_forma)
            .setContentTitle(title)
            .setContentText(if (allDay) "Tryb całodniowy: zapisuję tętno przez cały dzień. Wyłączysz go w Profilu." else "Trwa pomiar lub trening. Powiadomienie zniknie po zakończeniu.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, 4, null))
            .build()
    }

    /** Foreground notification of GpsService (route of a Spacer / Bieg workout). */
    fun gps(ctx: Context): Notification {
        return NotificationCompat.Builder(ctx, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_forma)
            .setContentTitle("Forma – zapisuję trasę (GPS)")
            .setContentText("GPS działa tylko do końca treningu. Lokalizacja zostaje w telefonie.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, 5, null))
            .build()
    }

    fun postResult(ctx: Context) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_MORNING)
            .setSmallIcon(R.drawable.ic_stat_forma)
            .setContentTitle("Forma")
            .setContentText("Wynik nocy gotowy – otwórz Formę")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 2, "#/dzis"))
            .build()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(RESULT_ID, n)
        } catch (e: Exception) { /* notifications may be blocked */ }
    }

    /** Generic notification on the existing high-importance channel (alarm, reminders). Tap opens the app at route. */
    fun post(ctx: Context, id: Int, title: String, text: String, route: String?) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_MORNING)
            .setSmallIcon(R.drawable.ic_stat_forma)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 50 + id % 1000, route))
            .build()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
        } catch (e: Exception) { /* notifications may be blocked */ }
    }

    /** "Zwykle o tej porze siłownia – byłeś?" with the actions "Tak, zapisz" and "Nie" (answers go to Prefs through HabitReceiver). */
    fun habit(ctx: Context, act: String, date: String, text: String) {
        ensureChannels(ctx)
        val yes = Intent(ctx, HabitReceiver::class.java).setAction(HabitReceiver.ACTION_YES)
            .putExtra(HabitReceiver.X_ACT, act).putExtra(HabitReceiver.X_DATE, date)
        val no = Intent(ctx, HabitReceiver::class.java).setAction(HabitReceiver.ACTION_NO)
            .putExtra(HabitReceiver.X_ACT, act).putExtra(HabitReceiver.X_DATE, date)
        val rc = 70 + (act + date).hashCode() % 1000
        val yesPi = PendingIntent.getBroadcast(ctx, rc * 2, yes, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val noPi = PendingIntent.getBroadcast(ctx, rc * 2 + 1, no, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_NUDGE)
            .setSmallIcon(R.drawable.ic_stat_forma)
            .setContentTitle("Forma")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 6, "#/dzis"))
            .addAction(0, "Tak, zapisz", yesPi)
            .addAction(0, "Nie", noPi)
            .build()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(HABIT_ID, n)
        } catch (e: Exception) { /* notifications may be blocked */ }
    }

    /**
     * Etap 9: "Wykryto wysiłek 16:10-17:05, śr. 128/min - to była:" with up to two guesses as buttons (the web app sends them best first).
     * Tapping a button queues the answer in Prefs through EffortReceiver; the notification body opens the Dziś card (the third choice "Inne").
     * choicesJson: [{k:"gym",l:"Siłownia"}, ...]
     */
    fun effort(ctx: Context, id: String, text: String, choicesJson: String) {
        ensureChannels(ctx)
        val b = NotificationCompat.Builder(ctx, CH_NUDGE)
            .setSmallIcon(R.drawable.ic_stat_forma)
            .setContentTitle("Forma")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 8, "#/dzis"))
        try {
            val arr = org.json.JSONArray(choicesJson)
            for (k in 0 until minOf(arr.length(), 2)) {
                val o = arr.getJSONObject(k)
                val act = o.optString("k")
                val label = o.optString("l")
                if (act.isEmpty() || label.isEmpty()) continue
                val i = Intent(ctx, EffortReceiver::class.java).setAction(EffortReceiver.ACTION_ANSWER)
                    .putExtra(EffortReceiver.X_ID, id).putExtra(EffortReceiver.X_ACT, act)
                val pi = PendingIntent.getBroadcast(ctx, 9000 + (id + act).hashCode().and(0x7fff), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(0, label, pi)
            }
        } catch (e: Exception) { /* no buttons: the body still opens the card */ }
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(EFFORT_ID, b.build())
        } catch (e: Exception) { /* notifications may be blocked */ }
    }

    fun cancelEffort(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(EFFORT_ID)
        } catch (e: Exception) { /* ignore */ }
    }

    fun cancelHabit(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(HABIT_ID)
        } catch (e: Exception) { /* ignore */ }
    }

    /** "Godzina bez ruchu" with the actions "Za 30 min" and "OK". */
    fun nudge(ctx: Context) {
        ensureChannels(ctx)
        val snooze = Intent(ctx, StepAlarmReceiver::class.java).setAction(StepAlarmReceiver.ACTION_SNOOZE)
        val snoozePi = PendingIntent.getBroadcast(ctx, 31, snooze, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ok = Intent(ctx, StepAlarmReceiver::class.java).setAction(StepAlarmReceiver.ACTION_OK)
        val okPi = PendingIntent.getBroadcast(ctx, 32, ok, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "Wstań i przejdź się 5 minut."
        val n = NotificationCompat.Builder(ctx, CH_NUDGE)
            .setSmallIcon(R.drawable.ic_stat_forma)
            .setContentTitle("Godzina bez ruchu")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 3, "#/dzis"))
            .addAction(0, "Za 30 min", snoozePi)
            .addAction(0, "OK", okPi)
            .build()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NUDGE_ID, n)
        } catch (e: Exception) { /* notifications may be blocked */ }
    }
}
