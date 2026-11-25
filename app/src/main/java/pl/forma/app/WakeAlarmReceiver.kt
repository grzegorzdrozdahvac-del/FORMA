package pl.forma.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wake alarm and the "Zakończ" notification action both end the night. Works with a dead activity. */
class WakeAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_WAKE = "pl.forma.app.action.WAKE"
        const val ACTION_STOP = "pl.forma.app.action.STOP_NIGHT"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        BleManager.init(context)
        val svc = NightService.instance
        if (svc != null) svc.requestStop() else NightStore.finalizeOrphan(context)
    }
}
