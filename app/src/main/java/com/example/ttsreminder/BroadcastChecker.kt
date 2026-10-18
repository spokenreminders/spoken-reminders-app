package com.example.ttsreminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object BroadcastChecker {
    private const val PREFS_NAME = ReminderRepository.PREFS_NAME
    private const val KEY_LAST_SEEN_BROADCAST_ID = "last_seen_broadcast_id"
    private const val KEY_LAST_BROADCAST_CHECK_STATUS = "last_broadcast_check_status"

    private const val BROADCAST_URL =
        "https://raw.githubusercontent.com/spokenreminders/spoken-reminders-app/main/broadcast.json"
    private const val USER_AGENT = "SpokenReminders-App"
    private const val TIMEOUT_MS = 8_000
    private const val MAX_MESSAGE_LENGTH = 500

    private const val CHANNEL_ID = "broadcast_messages_channel"
    private const val CHANNEL_NAME = "Messages from the developer"
    private const val NOTIFICATION_ID = 2001
    private const val WORK_NAME = "spoken_reminders_broadcast_check_work"

    fun schedulePeriodicChecks(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BroadcastCheckWorker>(6L, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (ignored: Throwable) {
        }
    }

    suspend fun checkNow(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        var connection: HttpURLConnection? = null
        try {
            val url = URL(BROADCAST_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                saveStatus(appContext, "HTTP $code")
                return@withContext
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            val id = json.optString("id", "").trim()
            val titleRaw = json.optString("title", "Spoken Reminders").trim()
            val title = if (titleRaw.isNotEmpty()) titleRaw else "Spoken Reminders"
            val message = json.optString("message", "").trim()

            if (id.isEmpty() || message.isEmpty() || message.length > MAX_MESSAGE_LENGTH) {
                saveStatus(appContext, "Invalid broadcast payload")
                return@withContext
            }

            val lastSeenId = getLastSeenId(appContext)
            if (id == lastSeenId) {
                saveStatus(appContext, "Already seen ($id)")
                return@withContext
            }

            setLastSeenId(appContext, id)
            saveStatus(appContext, "Delivered ($id)")

            postBroadcastNotification(appContext, title, message)
        } catch (t: Throwable) {
            saveStatus(appContext, "Check failed: ${t.javaClass.simpleName}")
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun postBroadcastNotification(context: Context, title: String, message: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }

            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPi = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (ignored: SecurityException) {
        } catch (ignored: Throwable) {
        }
    }

    private fun getLastSeenId(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_LAST_SEEN_BROADCAST_ID, null)
        } catch (t: Throwable) {
            null
        }
    }

    private fun setLastSeenId(context: Context, id: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_SEEN_BROADCAST_ID, id)
                .apply()
        } catch (ignored: Throwable) {
        }
    }

    private fun saveStatus(context: Context, status: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_BROADCAST_CHECK_STATUS, status)
                .apply()
        } catch (ignored: Throwable) {
        }
    }
}

class BroadcastCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            BroadcastChecker.checkNow(applicationContext)
            Result.success()
        } catch (ignored: Throwable) {
            Result.success()
        }
    }
}

