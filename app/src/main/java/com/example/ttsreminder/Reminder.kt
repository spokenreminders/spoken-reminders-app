package com.example.ttsreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class Reminder(
    val id: Int,
    val title: String,
    val category: String,
    val triggerTimeMillis: Long,
    val recurring: Boolean = false
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("hh:mm:ss a, MMM dd", Locale.getDefault())
        return sdf.format(Date(triggerTimeMillis))
    }
}

object SpeechAudioGuard {
    fun ensureAlarmAudible(context: Context, audioManager: AudioManager?): String {
        return try {
            val am = audioManager
                ?: (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?: return "alarm vol unknown (no AudioManager)"

            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
            val currentVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val isMuted = am.isStreamMute(AudioManager.STREAM_ALARM)

            if (isMuted || currentVol <= 0) {
                val targetVol = (maxVol * 0.7f).roundToInt().coerceAtLeast(1).coerceAtMost(maxVol)
                if (isMuted) {
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
                    } catch (ignored: Throwable) {
                    }
                }
                am.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
                val newVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                "alarm was muted, raised to $newVol/$maxVol"
            } else {
                "alarm vol $currentVol/$maxVol"
            }
        } catch (t: Throwable) {
            "alarm vol check error: ${t.javaClass.simpleName}"
        }
    }
}

object ReminderRepository {
    const val PREFS_NAME = "tts_reminders_prefs"
    private const val KEY_REMINDERS = "reminders_json"
    private const val KEY_SETUP_DONE = "setup_completed_v2"
    private const val KEY_LAST_ALARM_EVENT = "last_alarm_event"
    private const val MAX_EVENT_HISTORY = 8

    fun isSetupCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SETUP_DONE, false)
    }

    fun setSetupCompleted(context: Context, completed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_DONE, completed)
            .apply()
    }

    @Synchronized
    fun recordAlarmEvent(context: Context, eventNote: String) {
        try {
            val timestamp = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
            val entry = "$timestamp - $eventNote"
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingRaw = prefs.getString(KEY_LAST_ALARM_EVENT, null)
            val existingLines = if (existingRaw.isNullOrBlank() || existingRaw == "No alarm events recorded yet.") {
                emptyList()
            } else {
                existingRaw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            }
            val updatedLines = (existingLines + entry).takeLast(MAX_EVENT_HISTORY)
            prefs.edit()
                .putString(KEY_LAST_ALARM_EVENT, updatedLines.joinToString("\n"))
                .commit()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLastAlarmEvent(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_ALARM_EVENT, "No alarm events recorded yet.")
            ?: "No alarm events recorded yet."
    }

    fun getReminders(context: Context): List<Reminder> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_REMINDERS, "[]") ?: "[]"
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<Reminder>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(
                Reminder(
                    id = obj.getInt("id"),
                    title = obj.getString("title"),
                    category = obj.optString("category", "General"),
                    triggerTimeMillis = obj.getLong("triggerTimeMillis"),
                    recurring = obj.optBoolean("recurring", false)
                )
            )
        }
        return list.sortedBy { it.triggerTimeMillis }
    }

    fun addReminder(context: Context, reminder: Reminder) {
        val current = getReminders(context).toMutableList()
        current.removeAll { it.id == reminder.id }
        current.add(reminder)
        saveAll(context, current)
        ReminderScheduler.schedule(context, reminder)
    }

    fun updateReminder(context: Context, reminder: Reminder) {
        val current = getReminders(context).toMutableList()
        val index = current.indexOfFirst { it.id == reminder.id }
        if (index != -1) {
            current[index] = reminder
            saveAll(context, current)
            ReminderScheduler.schedule(context, reminder)
        }
    }

    fun deleteReminder(context: Context, reminderId: Int) {
        val current = getReminders(context).toMutableList()
        val toRemove = current.find { it.id == reminderId }
        if (toRemove != null) {
            ReminderScheduler.cancel(context, toRemove)
            current.remove(toRemove)
            saveAll(context, current)
        }
    }

    fun removeReminderRecordOnly(context: Context, reminderId: Int) {
        val current = getReminders(context).toMutableList()
        val removed = current.removeAll { it.id == reminderId }
        if (removed) {
            saveAll(context, current)
        }
    }

    internal fun saveAll(context: Context, list: List<Reminder>) {
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("category", item.category)
                put("triggerTimeMillis", item.triggerTimeMillis)
                put("recurring", item.recurring)
            }
            jsonArray.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMINDERS, jsonArray.toString())
            .apply()
    }
}

object ReminderScheduler {
    fun schedule(context: Context, reminder: Reminder) {
        if (reminder.triggerTimeMillis <= System.currentTimeMillis() && !reminder.recurring) return

        var triggerAt = reminder.triggerTimeMillis
        val now = System.currentTimeMillis()
        if (triggerAt <= now && reminder.recurring) {
            while (triggerAt <= now) {
                triggerAt = computeNextOccurrenceAfter(triggerAt)
            }
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
            putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
            putExtra(ReminderReceiver.EXTRA_CATEGORY, reminder.category)
            putExtra(ReminderReceiver.EXTRA_RECURRING, reminder.recurring)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeFormatted = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(triggerAt))

        try {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }

            if (canExact) {
                // Exact v1.0.1 AlarmClock scheduling (bypasses Doze and grants FGS start privilege)
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAt, pendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                ReminderRepository.recordAlarmEvent(
                    context,
                    "Scheduled exact AlarmClock '${reminder.title}' for $timeFormatted"
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                ReminderRepository.recordAlarmEvent(
                    context,
                    "Scheduled allowWhileIdle '${reminder.title}' for $timeFormatted (exact permission false)"
                )
            }
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            ReminderRepository.recordAlarmEvent(
                context,
                "Scheduled fallback inexact '${reminder.title}' for $timeFormatted (SecurityException)"
            )
        }
    }

    fun cancel(context: Context, reminder: Reminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun calculateNextTriggerMillis(hourOfDay: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now) || target == now) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun computeNextOccurrenceAfter(timeMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            this.timeInMillis = timeMillis
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}

