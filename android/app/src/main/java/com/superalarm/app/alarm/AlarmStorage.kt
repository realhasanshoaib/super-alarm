package com.superalarm.app.alarm

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import org.json.JSONObject

data class StoredAlarmConfig(
  val alarmId: String,
  val label: String,
  val enabled: Boolean,
  val timeMinutesLocal: Int,
  val repeatDays: List<Int>,
  val soundId: String,
  val vibrationEnabled: Boolean,
  val backupOffsetsSeconds: List<Int>,
)

data class PendingAlarmLaunch(
  val alarmId: String,
  val attemptId: String,
  val kind: String,
  val triggeredAt: String,
  val backupStage: Int,
)

object AlarmStorage {
  fun migrateToDeviceProtectedStorage(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return
    }

    val appContext = context.applicationContext
    if (appContext.isDeviceProtectedStorage) {
      return
    }

    val userManager = appContext.getSystemService(Context.USER_SERVICE) as UserManager
    if (!userManager.isUserUnlocked) {
      return
    }

    appContext.createDeviceProtectedStorageContext()
      .moveSharedPreferencesFrom(appContext, AlarmConstants.PREFS_NAME)
  }

  private fun prefs(context: Context): SharedPreferences =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      context.createDeviceProtectedStorageContext()
        .getSharedPreferences(AlarmConstants.PREFS_NAME, Context.MODE_PRIVATE)
    } else {
      context.getSharedPreferences(AlarmConstants.PREFS_NAME, Context.MODE_PRIVATE)
    }

  fun saveAlarmConfig(context: Context, config: StoredAlarmConfig) {
    prefs(context).edit()
      .putString(AlarmConstants.KEY_ALARM_ID, config.alarmId)
      .putString(AlarmConstants.KEY_LABEL, config.label)
      .putBoolean(AlarmConstants.KEY_ENABLED, config.enabled)
      .putInt(AlarmConstants.KEY_TIME_MINUTES, config.timeMinutesLocal)
      .putString(AlarmConstants.KEY_REPEAT_DAYS, config.repeatDays.joinToString(","))
      .putString(AlarmConstants.KEY_SOUND_ID, config.soundId)
      .putBoolean(AlarmConstants.KEY_VIBRATION_ENABLED, config.vibrationEnabled)
      .putString(AlarmConstants.KEY_BACKUP_OFFSETS, config.backupOffsetsSeconds.joinToString(","))
      .apply()
  }

  fun readAlarmConfig(context: Context): StoredAlarmConfig {
    val prefs = prefs(context)
    return StoredAlarmConfig(
      alarmId = prefs.getString(AlarmConstants.KEY_ALARM_ID, "morning-alarm") ?: "morning-alarm",
      label = prefs.getString(AlarmConstants.KEY_LABEL, "Morning Alarm") ?: "Morning Alarm",
      enabled = prefs.getBoolean(AlarmConstants.KEY_ENABLED, false),
      timeMinutesLocal = prefs.getInt(AlarmConstants.KEY_TIME_MINUTES, 450),
      repeatDays = prefs.getString(AlarmConstants.KEY_REPEAT_DAYS, "1,2,3,4,5")
        ?.split(",")
        ?.mapNotNull { it.toIntOrNull() }
        ?.sorted()
        ?: listOf(1, 2, 3, 4, 5),
      soundId = prefs.getString(AlarmConstants.KEY_SOUND_ID, "alarm") ?: "alarm",
      vibrationEnabled = prefs.getBoolean(AlarmConstants.KEY_VIBRATION_ENABLED, true),
      backupOffsetsSeconds = prefs.getString(AlarmConstants.KEY_BACKUP_OFFSETS, "45,120")
        ?.split(",")
        ?.mapNotNull { it.toIntOrNull() }
        ?.sorted()
        ?: listOf(45, 120),
    )
  }

  fun setScheduledTriggerAt(context: Context, triggerAtMillis: Long) {
    prefs(context).edit().putLong(AlarmConstants.KEY_SCHEDULED_TRIGGER_AT, triggerAtMillis).apply()
  }

  fun getScheduledTriggerAt(context: Context): Long =
    prefs(context).getLong(AlarmConstants.KEY_SCHEDULED_TRIGGER_AT, 0L)

  fun setActiveTriggerAt(context: Context, triggerAtMillis: Long) {
    prefs(context).edit().putLong(AlarmConstants.KEY_ACTIVE_TRIGGER_AT, triggerAtMillis).apply()
  }

  fun getActiveTriggerAt(context: Context): Long =
    prefs(context).getLong(AlarmConstants.KEY_ACTIVE_TRIGGER_AT, 0L)

  fun clearActiveTriggerAt(context: Context) {
    prefs(context).edit().remove(AlarmConstants.KEY_ACTIVE_TRIGGER_AT).apply()
  }

  fun savePendingLaunch(context: Context, launch: PendingAlarmLaunch) {
    prefs(context).edit()
      .putString(AlarmConstants.KEY_PENDING_ALARM_ID, launch.alarmId)
      .putString(AlarmConstants.KEY_PENDING_ATTEMPT_ID, launch.attemptId)
      .putString(AlarmConstants.KEY_PENDING_KIND, launch.kind)
      .putString(AlarmConstants.KEY_PENDING_TRIGGERED_AT, launch.triggeredAt)
      .putInt(AlarmConstants.KEY_PENDING_BACKUP_STAGE, launch.backupStage)
      .apply()
  }

  fun readPendingLaunch(context: Context): PendingAlarmLaunch? {
    val prefs = prefs(context)
    val alarmId = prefs.getString(AlarmConstants.KEY_PENDING_ALARM_ID, null) ?: return null
    val attemptId = prefs.getString(AlarmConstants.KEY_PENDING_ATTEMPT_ID, null) ?: return null
    val kind = prefs.getString(AlarmConstants.KEY_PENDING_KIND, null) ?: return null
    val triggeredAt = prefs.getString(AlarmConstants.KEY_PENDING_TRIGGERED_AT, null) ?: return null

    return PendingAlarmLaunch(
      alarmId = alarmId,
      attemptId = attemptId,
      kind = kind,
      triggeredAt = triggeredAt,
      backupStage = prefs.getInt(AlarmConstants.KEY_PENDING_BACKUP_STAGE, 0),
    )
  }

  fun clearPendingLaunch(context: Context) {
    prefs(context).edit()
      .remove(AlarmConstants.KEY_PENDING_ALARM_ID)
      .remove(AlarmConstants.KEY_PENDING_ATTEMPT_ID)
      .remove(AlarmConstants.KEY_PENDING_KIND)
      .remove(AlarmConstants.KEY_PENDING_TRIGGERED_AT)
      .remove(AlarmConstants.KEY_PENDING_BACKUP_STAGE)
      .apply()
  }

  fun toLaunchMap(launch: PendingAlarmLaunch): JSONObject =
    JSONObject().apply {
      put("alarmId", launch.alarmId)
      put("attemptId", launch.attemptId)
      put("kind", launch.kind)
      put("triggeredAt", launch.triggeredAt)
      put("backupStage", launch.backupStage)
    }
}
