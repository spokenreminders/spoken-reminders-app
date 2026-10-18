package com.example.ttsreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

class TtsAlertService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val CHANNEL_ID = "tts_reminder_alert_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_TTS_ATTEMPTS = 4
        private const val MAX_SPEAK_ERROR_RETRIES = 2
        private val RETRY_DELAYS_MS = longArrayOf(1500L, 3000L, 6000L)
    }

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var isDestroyed = false
    private val pendingMessages = ConcurrentLinkedDeque<String>()
    private val activeUtterances = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reminderToRemoveId: Int = -1

    private var currentAttempt = 0
    private var speakErrorRetries = 0
    private var currentEngineTried: String? = null
    private var defaultEnginePkg: String? = null
    private val candidateEngines = mutableListOf<String>()
    private var lastAlertTitle: String = "Speaking Reminder"
    private var lastAlertBody: String = "You have a scheduled reminder."

    override fun onCreate() {
        super.onCreate()

        try {
            createNotificationChannel()
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onCreate channel FAILED: ${t.javaClass.simpleName}:${t.message}"
            )
        }

        try {
            acquireWakeLock()
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onCreate wakeLock FAILED: ${t.javaClass.simpleName}:${t.message}"
            )
        }

        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onCreate audioManager FAILED: ${t.javaClass.simpleName}:${t.message}"
            )
        }

        val initialNotification = try {
            buildForegroundNotification("Spoken Reminder Alert")
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onCreate buildNotif FAILED: ${t.javaClass.simpleName}:${t.message}"
            )
            null
        }

        if (initialNotification != null) {
            ReminderRepository.recordAlarmEvent(applicationContext, "calling startForeground")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        initialNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, initialNotification)
                }
                ReminderRepository.recordAlarmEvent(applicationContext, "service started")
            } catch (t: Throwable) {
                val errNote = "startForeground FAILED: ${t.javaClass.simpleName}:${t.message}"
                ReminderRepository.recordAlarmEvent(applicationContext, errNote)
                ReminderReceiver.postFallbackFullScreenNotification(
                    context = applicationContext,
                    title = "Speaking Reminder",
                    contentText = "Spoken Reminder Alert",
                    reasonNote = errNote
                )
            }
        }

        discoverInstalledTtsEngines()
        startTtsAttempt(attemptNumber = 1, explicitEngine = null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        extendWakeLock()

        val title = intent?.getStringExtra(ReminderReceiver.EXTRA_TITLE) ?: "Reminder"
        val category = intent?.getStringExtra(ReminderReceiver.EXTRA_CATEGORY) ?: "General"
        val customSpoken = intent?.getStringExtra(ReminderReceiver.EXTRA_SPOKEN_TEXT)
        val spokenText = customSpoken ?: "$category reminder:$title. Again, $category reminder:$title."

        lastAlertTitle = "Reminder: $category"
        lastAlertBody = "$category:$title"

        val extraId = intent?.getIntExtra(ReminderReceiver.EXTRA_ID, -1) ?: -1
        if (extraId != -1) {
            reminderToRemoveId = extraId
        }

        try {
            val notification = buildForegroundNotification("$category:$title")
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "onStartCommand notify FAILED: ${t.javaClass.simpleName}:${t.message}"
            )
        }

        if (isTtsInitialized) {
            speakOutLoud(spokenText)
        } else {
            pendingMessages.add(spokenText)
        }

        return START_NOT_STICKY
    }

    private fun discoverInstalledTtsEngines() {
        try {
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val resolveInfos = packageManager.queryIntentServices(intent, 0)
            resolveInfos.forEach { info ->
                val pkg = info.serviceInfo?.packageName
                if (!pkg.isNullOrBlank() && !candidateEngines.contains(pkg)) {
                    candidateEngines.add(pkg)
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun harvestEnginesFromTtsInstance(instance: TextToSpeech?) {
        if (instance == null) return
        try {
            val def = instance.defaultEngine
            if (!def.isNullOrBlank()) {
                defaultEnginePkg = def
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        try {
            instance.engines?.forEach { engineInfo ->
                val pkg = engineInfo.name
                if (!pkg.isNullOrBlank() && !candidateEngines.contains(pkg)) {
                    candidateEngines.add(pkg)
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun pickEngineForRetry(attemptNumber: Int): String? {
        // Build an ordered list: non-default engines first (e.g., if the default failed while screen-reader engine is active),
        // followed by the default engine again once the device has been awake for a few seconds.
        val nonDefault = candidateEngines.filter { it != defaultEnginePkg }
        val ordered = mutableListOf<String?>()
        nonDefault.forEach { ordered.add(it) }
        if (!defaultEnginePkg.isNullOrBlank()) {
            ordered.add(defaultEnginePkg)
        }
        ordered.add(null) // null = system default constructor

        val index = (attemptNumber - 2).coerceAtLeast(0)
        return ordered.getOrElse(index % ordered.size) { null }
    }

    private fun startTtsAttempt(attemptNumber: Int, explicitEngine: String?) {
        if (isDestroyed) return
        currentAttempt = attemptNumber
        currentEngineTried = explicitEngine
        extendWakeLock()

        try {
            // Use the Service context (`this`) rather than bare applicationContext so service binding
            // inherits the active Foreground Service's process state and privileges on Android 14.
            tts = if (explicitEngine.isNullOrBlank()) {
                TextToSpeech(this, this)
            } else {
                TextToSpeech(this, this, explicitEngine)
            }
            val defLabel = try {
                tts?.defaultEngine ?: "unknown"
            } catch (t: Throwable) {
                "unknown"
            }
            if (!defLabel.isBlank() && defLabel != "unknown") {
                defaultEnginePkg = defLabel
            }
            val triedLabel = explicitEngine ?: "default($defLabel)"
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "TTS init attempt $attemptNumber started (TTS engine:$triedLabel)"
            )
        } catch (t: Throwable) {
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "TTS ctor attempt $attemptNumber FAILED: ${t.javaClass.simpleName}:${t.message}"
            )
            scheduleNextTtsRetryOrGiveUp(status = TextToSpeech.ERROR)
        }
    }

    override fun onInit(status: Int) {
        mainHandler.post {
            if (isDestroyed) return@post

            val engine = tts
            harvestEnginesFromTtsInstance(engine)

            val defEngine = defaultEnginePkg ?: "unknown"
            val activeEnginePkg = currentEngineTried ?: defEngine

            if (status == TextToSpeech.SUCCESS && engine != null) {
                try {
                    val langResult = engine.setLanguage(Locale.getDefault())
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setLanguage(Locale.US)
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                try {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    engine.setAudioAttributes(audioAttributes)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { onUtteranceFinished() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { onUtteranceFinished() }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post { onUtteranceFinished() }
                    }
                })

                isTtsInitialized = true
                ReminderRepository.recordAlarmEvent(
                    applicationContext,
                    "TTS ready (engine $activeEnginePkg)"
                )

                while (pendingMessages.isNotEmpty() && isTtsInitialized) {
                    val msg = pendingMessages.poll()
                    if (msg != null) {
                        speakOutLoud(msg)
                    }
                }
            } else {
                ReminderRepository.recordAlarmEvent(
                    applicationContext,
                    "TTS init attempt $currentAttempt failed (status=$status, TTS engine: $activeEnginePkg, default:$defEngine)"
                )
                scheduleNextTtsRetryOrGiveUp(status)
            }
        }
    }

    private fun scheduleNextTtsRetryOrGiveUp(status: Int) {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        tts = null
        isTtsInitialized = false

        if (currentAttempt < MAX_TTS_ATTEMPTS) {
            val nextAttempt = currentAttempt + 1
            val delayMs = RETRY_DELAYS_MS.getOrElse(currentAttempt - 1) { 3000L }
            val nextEngine = pickEngineForRetry(nextAttempt)
            extendWakeLock()

            mainHandler.postDelayed({
                startTtsAttempt(attemptNumber = nextAttempt, explicitEngine = nextEngine)
            }, delayMs)
        } else {
            // All 4 attempts failed: post persistent high-priority full-screen alarm notification
            val fallbackMsg = pendingMessages.peek() ?: lastAlertBody
            ReminderReceiver.postFallbackFullScreenNotification(
                context = applicationContext,
                title = lastAlertTitle,
                contentText = fallbackMsg,
                reasonNote = "TTS init failed (status=$status)"
            )
            ReminderRepository.recordAlarmEvent(
                applicationContext,
                "TTS gave up after $MAX_TTS_ATTEMPTS attempts, notification shown"
            )
            if (reminderToRemoveId != -1) {
                ReminderRepository.removeReminderRecordOnly(applicationContext, reminderToRemoveId)
                reminderToRemoveId = -1
            }
            stopSelfKeepingNotification()
        }
    }

    private fun speakOutLoud(text: String) {
        extendWakeLock()
        requestAudioFocus()
        val utteranceId = "REMINDER_UTTERANCE_${System.currentTimeMillis()}_${activeUtterances.incrementAndGet()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        val speakResult = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        if (speakResult == TextToSpeech.ERROR) {
            activeUtterances.decrementAndGet()
            pendingMessages.addFirst(text)

            if (speakErrorRetries < MAX_SPEAK_ERROR_RETRIES) {
                speakErrorRetries++
                ReminderRepository.recordAlarmEvent(
                    applicationContext,
                    "TTS speak() returned ERROR, rebuilding engine (retry $speakErrorRetries)"
                )
                try {
                    tts?.stop()
                    tts?.shutdown()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
                tts = null
                isTtsInitialized = false
                startTtsAttempt(attemptNumber = 1, explicitEngine = null)
            } else {
                try {
                    tts?.stop()
                    tts?.shutdown()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
                tts = null
                isTtsInitialized = false

                val fallbackMsg = pendingMessages.peek() ?: lastAlertBody
                ReminderReceiver.postFallbackFullScreenNotification(
                    context = applicationContext,
                    title = lastAlertTitle,
                    contentText = fallbackMsg,
                    reasonNote = "TTS speak() kept failing"
                )
                ReminderRepository.recordAlarmEvent(
                    applicationContext,
                    "TTS speak retries exhausted, notification shown"
                )
                if (reminderToRemoveId != -1) {
                    ReminderRepository.removeReminderRecordOnly(applicationContext, reminderToRemoveId)
                    reminderToRemoveId = -1
                }
                abandonAudioFocus()
                stopSelfKeepingNotification()
            }
        }
    }

    private fun onUtteranceFinished() {
        if (activeUtterances.decrementAndGet() <= 0 && pendingMessages.isEmpty()) {
            ReminderRepository.recordAlarmEvent(applicationContext, "speech finished")
            if (reminderToRemoveId != -1) {
                ReminderRepository.removeReminderRecordOnly(applicationContext, reminderToRemoveId)
                reminderToRemoveId = -1
            }
            mainHandler.postDelayed({
                abandonAudioFocus()
                stopSelfSafely()
            }, 500L)
        }
    }

    private fun requestAudioFocus() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = req
            audioManager?.requestAudioFocus(req)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun abandonAudioFocus() {
        try {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SpokenReminders::TtsWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(90_000L)
        }
    }

    private fun extendWakeLock() {
        try {
            val wl = wakeLock
            if (wl != null) {
                wl.acquire(90_000L)
            } else {
                acquireWakeLock()
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        stopSelf()
    }

    private fun stopSelfKeepingNotification() {
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        stopSelf()
    }

    override fun onDestroy() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        if (reminderToRemoveId != -1) {
            ReminderRepository.removeReminderRecordOnly(applicationContext, reminderToRemoveId)
            reminderToRemoveId = -1
        }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Spoken Reminder Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Speaks reminders and timers aloud using the system Text-to-Speech engine."
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPi = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Speaking Reminder")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .build()
    }
}

