package app.sona.notch

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "End" button of the test call notification: just removes the notification. */
class CallTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSystemService(NotificationManager::class.java).cancel(MainActivity.TEST_CALL_ID)
    }
}
