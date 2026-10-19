package com.example.ttsreminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "broadcast_push"
        const val CHANNEL_NAME = "Messages from the developer"
        private const val NOTIFICATION_ID = 2002
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        try {
            val titleRaw = remoteMessage.notification?.title
                ?.takeIf { it.isNotBlank() }
                ?: remoteMessage.data["title"]?.takeIf { it.isNotBlank() }
                ?: "Spoken Reminders"
            val title = titleRaw.trim()

            val bodyRaw = remoteMessage.notification?.body
                ?.takeIf { it.isNotBlank() }
                ?: remoteMessage.data["message"]?.takeIf { it.isNotBlank() }
                ?: remoteMessage.data["body"]?.takeIf { it.isNotBlank() }
                ?: return
            val body = bodyRaw.trim()
            if (body.isEmpty()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }

            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }

            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPi = PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (ignored: SecurityException) {
        } catch (ignored: Throwable) {
        }
    }

    override fun onNewToken(token: String) {
        // Do nothing
    }
}
