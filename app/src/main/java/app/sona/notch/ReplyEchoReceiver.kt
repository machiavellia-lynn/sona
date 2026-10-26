package app.sona.notch

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Receives the Reply / Mark as read buttons of the test notification so the buttons can be tried end to end. */
class ReplyEchoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)
        if (reply != null) Toast.makeText(context, "Replied: $reply", Toast.LENGTH_LONG).show()
        context.getSystemService(NotificationManager::class.java).cancel(TEST_ID)
    }

    companion object {
        const val KEY_REPLY = "reply"
        const val TEST_ID = 4343
    }
}
