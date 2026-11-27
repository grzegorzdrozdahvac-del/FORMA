package pl.forma.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms everything AlarmManager forgets: after a reboot (BOOT_COMPLETED) and after an app update (MY_PACKAGE_REPLACED).
 *  - hourly step tick + nudge schedule (StepAlarmReceiver.applySettings reads the saved settings),
 *  - a pending tip reminder (ReminderReceiver.rearm),
 *  - the daily bedtime reminder (BedtimeReceiver.rearm),
 *  - a night that was running: after a reboot it is closed as interrupted ("Pomiar nocy przerwany przez restart telefonu");
 *    after an app update the wake alarm is armed again (or the night is closed normally when its wake time has passed).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action: String = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val ctx = context.applicationContext
        val pending: BroadcastReceiver.PendingResult? = goAsync()
        Thread {
            try {
                BleManager.init(ctx)
                try { StepAlarmReceiver.applySettings(ctx) } catch (e: Exception) { /* keep going */ }
                try { ReminderReceiver.rearm(ctx) } catch (e: Exception) { /* keep going */ }
                try { BedtimeReceiver.rearm(ctx) } catch (e: Exception) { /* keep going */ }
                try { HabitReceiver.armNext(ctx) } catch (e: Exception) { /* keep going */ }
                try { handleNight(ctx, action == Intent.ACTION_BOOT_COMPLETED) } catch (e: Exception) { /* keep going */ }
            } finally {
                pending?.finish()
            }
        }.start()
    }

    private fun handleNight(ctx: Context, reboot: Boolean) {
        if (!Prefs.nightRunning(ctx)) return
        if (reboot) {
            NightStore.finalizeOrphan(ctx, true)
            return
        }
        val wakeAt = Prefs.nightWakeAt(ctx)
        if (wakeAt > System.currentTimeMillis()) WakeAlarm.schedule(ctx, wakeAt) else NightStore.finalizeOrphan(ctx, false)
    }
}
