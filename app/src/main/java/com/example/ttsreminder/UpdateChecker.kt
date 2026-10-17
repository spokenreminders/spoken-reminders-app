package com.example.ttsreminder

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

sealed class UpdateCheckResult {
    data class UpdateAvailable(val newVersion: String, val downloadUrl: String) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Failed(val reason: String) : UpdateCheckResult()
}

object UpdateChecker {
    private const val PREFS_NAME = ReminderRepository.PREFS_NAME
    private const val KEY_LAST_ANNOUNCED_VERSION = "last_announced_update_version"
    private const val KEY_LAST_UPDATE_CHECK_STATUS = "last_update_check_status"

    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/spokenreminders/spoken-reminders-app/releases/latest"
    const val APK_DOWNLOAD_URL =
        "https://github.com/spokenreminders/spoken-reminders-app/releases/latest/download/SpokenReminders-debug.apk"
    private const val USER_AGENT = "SpokenReminders-App"
    private const val TIMEOUT_MS = 8_000

    fun getLastUpdateCheckStatus(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_UPDATE_CHECK_STATUS, "Never checked") ?: "Never checked"
    }

    private fun saveUpdateCheckStatus(context: Context, status: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_UPDATE_CHECK_STATUS, status)
                .apply()
        } catch (ignored: Throwable) {
        }
    }

    fun getLastAnnouncedVersion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_ANNOUNCED_VERSION, null)
    }

    fun setLastAnnouncedVersion(context: Context, version: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_ANNOUNCED_VERSION, normalizeVersion(version))
                .apply()
        } catch (ignored: Throwable) {
        }
    }

    fun getInstalledVersionName(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName ?: "1.0.0"
        } catch (t: Throwable) {
            "1.0.0"
        }
    }

    fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").trim()
    }

    /**
     * Returns true if [remoteVersion] is strictly newer than [installedVersion]
     * using numeric semantic version comparison (X.Y.Z).
     */
    fun isStrictlyNewer(remoteVersion: String, installedVersion: String): Boolean {
        val remoteParts = parseVersionNumbers(remoteVersion)
        val installedParts = parseVersionNumbers(installedVersion)
        if (remoteParts.isEmpty() || installedParts.isEmpty()) return false

        val maxLen = maxOf(remoteParts.size, installedParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = installedParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun parseVersionNumbers(version: String): List<Int> {
        val clean = normalizeVersion(version).substringBefore("-")
        if (clean.isEmpty()) return emptyList()
        return clean.split(".").mapNotNull { part ->
            part.trim().toIntOrNull()
        }
    }

    suspend fun checkForUpdates(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        var connection: HttpURLConnection? = null
        try {
            val url = URL(LATEST_RELEASE_API_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val status = "Check failed: no connection"
                saveUpdateCheckStatus(appContext, status)
                return@withContext UpdateCheckResult.Failed(status)
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)
            val tagName = json.optString("tag_name", "").trim()
            val remoteClean = normalizeVersion(tagName)

            if (remoteClean.isEmpty() || parseVersionNumbers(remoteClean).isEmpty()) {
                val status = "Check failed: no connection"
                saveUpdateCheckStatus(appContext, status)
                return@withContext UpdateCheckResult.Failed(status)
            }

            val installedClean = normalizeVersion(getInstalledVersionName(appContext))
            if (isStrictlyNewer(remoteClean, installedClean)) {
                saveUpdateCheckStatus(appContext, "Update available: $remoteClean")
                UpdateCheckResult.UpdateAvailable(
                    newVersion = remoteClean,
                    downloadUrl = APK_DOWNLOAD_URL
                )
            } else {
                saveUpdateCheckStatus(appContext, "Latest version installed")
                UpdateCheckResult.UpToDate
            }
        } catch (t: Throwable) {
            val status = "Check failed: no connection"
            saveUpdateCheckStatus(appContext, status)
            UpdateCheckResult.Failed(status)
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Throwable) {
            }
        }
    }
}

/**
 * Lightweight, retry-capable TTS announcer using the app's own TextToSpeech instance
 * (independent of TalkBack) with engine discovery and bounded retries matching TtsAlertService.
 */
object UpdateVoiceAnnouncer {
    private const val MAX_ATTEMPTS = 3
    private val RETRY_DELAYS_MS = longArrayOf(1200L, 2500L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeSession: Session? = null

    fun speak(context: Context, text: String) {
        val appContext = context.applicationContext
        mainHandler.post {
            activeSession?.shutdown()
            val session = Session(appContext, text)
            activeSession = session
            session.start()
        }
    }

    private class Session(
        private val context: Context,
        private val textToSpeak: String
    ) : TextToSpeech.OnInitListener {

        private var tts: TextToSpeech? = null
        private var currentAttempt = 0
        private var isReleased = false
        private var defaultEnginePkg: String? = null
        private val candidateEngines = mutableListOf<String>()
        private var audioManager: AudioManager? = null
        private var focusRequest: AudioFocusRequest? = null

        private val safetyCleanupRunnable = Runnable {
            shutdown()
        }

        fun start() {
            audioManager = try {
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            } catch (t: Throwable) {
                null
            }
            discoverInstalledEngines()
            mainHandler.postDelayed(safetyCleanupRunnable, 30_000L)
            attemptInit(attemptNumber = 1, explicitEngine = null)
        }

        private fun discoverInstalledEngines() {
            try {
                val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                val infos = context.packageManager.queryIntentServices(intent, 0)
                infos.forEach { info ->
                    val pkg = info.serviceInfo?.packageName
                    if (!pkg.isNullOrBlank() && !candidateEngines.contains(pkg)) {
                        candidateEngines.add(pkg)
                    }
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun harvestEngines(instance: TextToSpeech?) {
            if (instance == null) return
            try {
                val def = instance.defaultEngine
                if (!def.isNullOrBlank()) {
                    defaultEnginePkg = def
                }
            } catch (ignored: Throwable) {
            }
            try {
                instance.engines?.forEach { info ->
                    val pkg = info.name
                    if (!pkg.isNullOrBlank() && !candidateEngines.contains(pkg)) {
                        candidateEngines.add(pkg)
                    }
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun pickRetryEngine(attemptNumber: Int): String? {
            val nonDefault = candidateEngines.filter { it != defaultEnginePkg }
            val ordered = mutableListOf<String?>()
            nonDefault.forEach { ordered.add(it) }
            if (!defaultEnginePkg.isNullOrBlank()) {
                ordered.add(defaultEnginePkg)
            }
            ordered.add(null)
            val idx = (attemptNumber - 2).coerceAtLeast(0)
            return ordered.getOrElse(idx % ordered.size) { null }
        }

        private fun attemptInit(attemptNumber: Int, explicitEngine: String?) {
            if (isReleased) return
            currentAttempt = attemptNumber
            try {
                tts = if (explicitEngine.isNullOrBlank()) {
                    TextToSpeech(context, this)
                } else {
                    TextToSpeech(context, this, explicitEngine)
                }
            } catch (t: Throwable) {
                retryOrStop()
            }
        }

        override fun onInit(status: Int) {
            mainHandler.post {
                if (isReleased) return@post
                val engine = tts
                harvestEngines(engine)

                if (status == TextToSpeech.SUCCESS && engine != null) {
                    try {
                        val langResult = engine.setLanguage(Locale.getDefault())
                        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                            langResult == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            engine.setLanguage(Locale.US)
                        }
                    } catch (ignored: Throwable) {
                    }

                    try {
                        val attrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        engine.setAudioAttributes(attrs)
                    } catch (ignored: Throwable) {
                    }

                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            mainHandler.postDelayed({ shutdown() }, 300L)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            mainHandler.postDelayed({ shutdown() }, 300L)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            mainHandler.postDelayed({ shutdown() }, 300L)
                        }
                    })

                    requestAudioFocus()
                    val params = Bundle().apply {
                        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                    }
                    val res = engine.speak(
                        textToSpeak,
                        TextToSpeech.QUEUE_FLUSH,
                        params,
                        "UPDATE_UTTERANCE_${System.currentTimeMillis()}"
                    )
                    if (res == TextToSpeech.ERROR) {
                        shutdown()
                    }
                } else {
                    retryOrStop()
                }
            }
        }

        private fun retryOrStop() {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (ignored: Throwable) {
            }
            tts = null

            if (currentAttempt < MAX_ATTEMPTS && !isReleased) {
                val nextAttempt = currentAttempt + 1
                val delayMs = RETRY_DELAYS_MS.getOrElse(currentAttempt - 1) { 2000L }
                val nextEngine = pickRetryEngine(nextAttempt)
                mainHandler.postDelayed({
                    attemptInit(nextAttempt, nextEngine)
                }, delayMs)
            } else {
                shutdown()
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
            } catch (ignored: Throwable) {
            }
        }

        private fun abandonAudioFocus() {
            try {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
                focusRequest = null
            } catch (ignored: Throwable) {
            }
        }

        fun shutdown() {
            if (isReleased) return
            isReleased = true
            mainHandler.removeCallbacks(safetyCleanupRunnable)
            abandonAudioFocus()
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (ignored: Throwable) {
            }
            tts = null
            if (activeSession === this) {
                activeSession = null
            }
        }
    }
}
