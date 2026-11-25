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
    const val RESULT_ID = 4002

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val night = NotificationChannel(CH_NIGHT, "Pomiar nocy", NotificationManager.IMPORTANCE_LOW)
        night.setShowBadge(false)
        nm.createNotificationChannel(night)
        val morning = NotificationChannel(CH_MORNING, "Poranek", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(morning)
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
}
