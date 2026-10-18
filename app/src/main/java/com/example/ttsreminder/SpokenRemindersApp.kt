package com.example.ttsreminder

import android.app.Application

class SpokenRemindersApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installUncaughtExceptionRecorder()
        BroadcastChecker.schedulePeriodicChecks(this)
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

