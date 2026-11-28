package pl.forma.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Etap 9: the buttons of the "Wykryto wysiłek ... to była:" notification. The answer is queued in Prefs
 * (read by the web app through getEffortAnswers when it resumes) and the notification is dismissed.
 * All decisions (what the effort is, the workout, learning) stay in the web app.
 */
class EffortReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ANSWER = "pl.forma.app.EFFORT_ANSWER"
        const val X_ID = "id"
        const val X_ACT = "act"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val i = intent ?: return
        if (i.action != ACTION_ANSWER) return
        val ctx = context.applicationContext
        val id = i.getStringExtra(X_ID) ?: return
        val act = i.getStringExtra(X_ACT) ?: return
        Prefs.addEffortAnswer(ctx, id, act)
        Notifications.cancelEffort(ctx)
    }
}
