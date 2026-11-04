package sk.libreta.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reminds the driver on their phone to end a shift that has been running for [Shift.OVERDUE_MINUTES].
 * The driver screen schedules it whenever it sees an open shift and cancels it when there is none.
 */
class ShiftReminder : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pripomienky smien", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, StartActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_truck)
            .setContentTitle("Nezabudol si ukončiť smenu?")
            .setContentText("Smena beží už ${Shift.OVERDUE_MINUTES / 60} hodín. Ukonči ju v Librete.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "shift_reminders"
        private const val NOTIFICATION_ID = 1

        private fun pending(context: Context) = PendingIntent.getBroadcast(
            context, 0, Intent(context, ShiftReminder::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** Sets (or moves) the single reminder to [Shift.OVERDUE_MINUTES] after [start]. */
        fun schedule(context: Context, start: LocalDateTime) {
            val at = start.plusMinutes(Shift.OVERDUE_MINUTES).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (at <= System.currentTimeMillis()) return
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(pending(context))
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}
