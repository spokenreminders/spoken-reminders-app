package com.example.ttsreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.analytics.FirebaseAnalytics

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_REMINDER = "com.example.ttsreminder.ACTION_TRIGGER_REMINDER"
        const val ACTION_TRIGGER_TIMER = "com.example.ttsreminder.ACTION_TRIGGER_TIMER"
        const val EXTRA_ID = "extra_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_SPOKEN_TEXT = "extra_spoken_text"
        const val EXTRA_RECURRING = "extra_recurring"

        private const val FALLBACK_CHANNEL_ID = "tts_reminder_alert_channel"
        private const val FALLBACK_NOTIFICATION_ID = 1002

        @Volatile
        private var receiverWakeLock: PowerManager.WakeLock? = null

        private fun acquireHandoverWakeLock(context: Context) {
            try {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = receiverWakeLock ?: pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SpokenReminders::ReceiverHandoverWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    receiverWakeLock = this
                }
                wl.acquire(30_000L)
            } catch (t: Throwable) {
                ReminderRepository.recordAlarmEvent(
                    context,
                    "receiver wakeLock FAILED: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }

        fun postFallbackFullScreenNotification(
            context: Context,
            title: String,
            contentText: String,
            reasonNote: String
        ) {
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        FALLBACK_CHANNEL_ID,
                        "Spoken Reminder Alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Speaks reminders and timers aloud using the system Text-to-Speech engine."
                        setSound(null, null)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(channel)
                }

                val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val fullScreenPi = PendingIntent.getActivity(
                    context,
                    FALLBACK_NOTIFICATION_ID,
                    fullScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, FALLBACK_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(title)
                    .setContentText(contentText)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setFullScreenIntent(fullScreenPi, true)
                    .setContentIntent(fullScreenPi)
                    .setAutoCancel(true)
                    .build()

                nm.notify(FALLBACK_NOTIFICATION_ID, notification)
                ReminderRepository.recordAlarmEvent(
                    context,
                    "$reasonNote -> fallback full-screen notification posted"
                )
            } catch (t: Throwable) {
                ReminderRepository.recordAlarmEvent(
                    context,
                    "$reasonNote -> fallback notif FAILED: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        acquireHandoverWakeLock(context)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    val now = System.currentTimeMillis()
                    val reminders = ReminderRepository.getReminders(context)
                    val updatedReminders = mutableListOf<Reminder>()
                    val missedAnnouncements = mutableListOf<String>()

                    reminders.forEach { reminder ->
                        if (reminder.triggerTimeMillis <= now) {
                            if (reminder.recurring) {
                                missedAnnouncements.add("Missed reminder: ${reminder.category}, ${reminder.title}.")
                                var nextTime = reminder.triggerTimeMillis
                                while (nextTime <= now) {
                                    nextTime = ReminderScheduler.computeNextOccurrenceAfter(nextTime)
                                }
                                val updated = reminder.copy(triggerTimeMillis = nextTime)
                                updatedReminders.add(updated)
                                ReminderScheduler.schedule(context, updated)
                            } else {
                                missedAnnouncements.add("Missed reminder: ${reminder.category}, ${reminder.title}.")
                            }
                        } else {
                            updatedReminders.add(reminder)
                            ReminderScheduler.schedule(context, reminder)
                        }
                    }
                    ReminderRepository.saveAll(context, updatedReminders)

                    val timers = TimerRepository.getTimers(context)
                    val activeTimers = mutableListOf<TimerItem>()
                    val missedTimers = mutableListOf<String>()
                    timers.forEach { timer ->
                        if (timer.endTimeMillis <= now) {
                            val labelText = if (timer.label.isNotEmpty()) timer.label else "timer"
                            missedTimers.add("Your timer finished while the phone was off: $labelText.")
                        } else {
                            activeTimers.add(timer)
                            TimerScheduler.schedule(context, timer)
                        }
                    }
                    TimerRepository.saveAll(context, activeTimers)

                    if (missedAnnouncements.isNotEmpty() || missedTimers.isNotEmpty()) {
                        val combinedText = (missedAnnouncements + missedTimers).joinToString(" ")
                        val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                            putExtra(EXTRA_TITLE, "Missed Reminders & Timers")
                            putExtra(EXTRA_CATEGORY, "General")
                            putExtra(EXTRA_SPOKEN_TEXT, combinedText)
                        }
                        startAlertServiceWithDiagnostics(
                            context = context,
                            serviceIntent = serviceIntent,
                            alertTitle = "Missed Reminders & Timers",
                            alertBody = combinedText
                        )
                    }
                } catch (t: Throwable) {
                    ReminderRepository.recordAlarmEvent(
                        context,
                        "boot catch-up FAILED: ${t.javaClass.simpleName}: ${t.message}"
                    )
                }
            }

            ACTION_TRIGGER_REMINDER -> {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "You have a scheduled reminder."
                val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "General"
                val recurring = intent.getBooleanExtra(EXTRA_RECURRING, false)

                try {
                    FirebaseAnalytics.getInstance(context.applicationContext).logEvent("reminder_fired", null)
                } catch (ignored: Throwable) {
                }

                ReminderRepository.recordAlarmEvent(context, "alarm received ($title)")

                val spokenText = "$category reminder: $title. Again, $category reminder: $title."
                val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_CATEGORY, category)
                    putExtra(EXTRA_SPOKEN_TEXT, spokenText)
                    if (!recurring && id != -1) {
                        putExtra(EXTRA_ID, id)
                    }
                }

                startAlertServiceWithDiagnostics(
                    context = context,
                    serviceIntent = serviceIntent,
                    alertTitle = "Reminder: $category",
                    alertBody = title
                )

                if (id != -1) {
                    try {
                        if (recurring) {
                            val reminders = ReminderRepository.getReminders(context)
                            val target = reminders.find { it.id == id }
                            if (target != null) {
                                val now = System.currentTimeMillis()
                                var nextTime = ReminderScheduler.computeNextOccurrenceAfter(target.triggerTimeMillis)
                                while (nextTime <= now) {
                                    nextTime = ReminderScheduler.computeNextOccurrenceAfter(nextTime)
                                }
                                val updated = target.copy(triggerTimeMillis = nextTime)
                                ReminderRepository.updateReminder(context, updated)
                            }
                        } else {
                            ReminderRepository.removeReminderRecordOnly(context, id)
                        }
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }
            }

            ACTION_TRIGGER_TIMER -> {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                val label = intent.getStringExtra(EXTRA_LABEL)?.trim().orEmpty()

                ReminderRepository.recordAlarmEvent(
                    context,
                    "alarm received (Timer ${label.ifEmpty { "default" }})"
                )

                val spokenText = if (label.isNotEmpty()) {
                    "Timer finished: $label. Again, timer finished: $label."
                } else {
                    "Your timer is finished. Again, your timer is finished."
                }
                val notifTitle = if (label.isNotEmpty()) label else "Timer Finished"

                val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
                    putExtra(EXTRA_TITLE, notifTitle)
                    putExtra(EXTRA_CATEGORY, "Timer")
                    putExtra(EXTRA_SPOKEN_TEXT, spokenText)
                }

                startAlertServiceWithDiagnostics(
                    context = context,
                    serviceIntent = serviceIntent,
                    alertTitle = "Timer Finished",
                    alertBody = notifTitle
                )

                if (id != -1) {
                    try {
                        TimerRepository.removeTimerRecordOnly(context, id)
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }
            }
        }
    }

    private fun startAlertServiceWithDiagnostics(
        context: Context,
        serviceIntent: Intent,
        alertTitle: String,
        alertBody: String
    ) {
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            ReminderRepository.recordAlarmEvent(context, "alarm received -> startForegroundService requested")
        } catch (t: Throwable) {
            val failNote = "start service FAILED: ${t.javaClass.simpleName}: ${t.message}"
            ReminderRepository.recordAlarmEvent(context, failNote)
            postFallbackFullScreenNotification(
                context = context,
                title = alertTitle,
                contentText = alertBody,
                reasonNote = failNote
            )
            // Also try plain startService while holding the receiver wake lock
            try {
                context.startService(serviceIntent)
            } catch (ignored: Throwable) {
            }
        }
    }
}
