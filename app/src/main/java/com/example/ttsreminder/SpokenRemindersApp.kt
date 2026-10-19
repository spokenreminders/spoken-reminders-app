package com.example.ttsreminder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SpokenRemindersApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installUncaughtExceptionRecorder()
        createPushNotificationChannel()
        BroadcastChecker.schedulePeriodicChecks(this)
    }

    private fun createPushNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java) ?: return
                val channel = NotificationChannel(
                    PushMessagingService.CHANNEL_ID,
                    PushMessagingService.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun installUncaughtExceptionRecorder() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val root = throwable.cause ?: throwable
                val firstLine = root.stackTrace.firstOrNull()?.let {
                    "${it.fileName}:${it.lineNumber}"
                } ?: "no-stack"
                val summary = "UNCAUGHT: ${root.javaClass.simpleName}: ${root.message} ($firstLine)"
                ReminderRepository.recordAlarmEvent(applicationContext, summary)
            } catch (ignored: Throwable) {
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
