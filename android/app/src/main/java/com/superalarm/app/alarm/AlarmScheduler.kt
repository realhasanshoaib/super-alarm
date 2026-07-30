package com.superalarm.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.superalarm.app.MainActivity
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

object AlarmScheduler {
  fun scheduleNext(context: Context, config: StoredAlarmConfig, fromEpochMillis: Long = System.currentTimeMillis()) {
    cancelUpcoming(context, config)

    if (!config.enabled || config.repeatDays.isEmpty()) {
      AlarmStorage.setScheduledTriggerAt(context, 0L)
      return
    }

    val triggerAtMillis = computeNextTriggerMillis(config, fromEpochMillis)
    scheduleOccurrence(context, config, triggerAtMillis)
  }

  fun scheduleOneShot(context: Context, config: StoredAlarmConfig, triggerAtMillis: Long) {
    cancelUpcoming(context, config)
    if (!config.enabled || triggerAtMillis <= System.currentTimeMillis()) {
      AlarmStorage.setScheduledTriggerAt(context, 0L)
      return
    }
    scheduleOccurrence(context, config, triggerAtMillis)
  }

  private fun scheduleOccurrence(context: Context, config: StoredAlarmConfig, triggerAtMillis: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
      AlarmStorage.setScheduledTriggerAt(context, 0L)
      return
    }
    val showIntent = buildLaunchPendingIntent(context, config.alarmId, triggerAtMillis, 0, "live")
    val mainPendingIntent = buildReceiverPendingIntent(context, config.alarmId, triggerAtMillis, 0, "live", config)

    alarmManager.setAlarmClock(
      AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
      mainPendingIntent,
    )

    config.backupOffsetsSeconds.forEachIndexed { index, offsetSeconds ->
      val backupStage = index + 1
      val backupTriggerAt = triggerAtMillis + offsetSeconds * 1_000L
      val backupIntent = buildReceiverPendingIntent(context, config.alarmId, triggerAtMillis, backupStage, "live", config)

      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, backupTriggerAt, backupIntent)
    }

    AlarmStorage.setScheduledTriggerAt(context, triggerAtMillis)
  }

  fun scheduleNextAfterTrigger(context: Context, config: StoredAlarmConfig, triggerAtMillis: Long) {
    scheduleNext(context, config, triggerAtMillis + 60_000L)
  }

  fun cancelUpcoming(context: Context, config: StoredAlarmConfig) {
    val scheduledTriggerAt = AlarmStorage.getScheduledTriggerAt(context)
    if (scheduledTriggerAt == 0L) {
      return
    }

    cancelOccurrence(context, config, scheduledTriggerAt, "live")
  }

  fun cancelActiveBackups(context: Context, config: StoredAlarmConfig) {
    val activeTriggerAt = AlarmStorage.getActiveTriggerAt(context)
    if (activeTriggerAt == 0L) {
      return
    }

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    config.backupOffsetsSeconds.forEachIndexed { index, _ ->
      val backupStage = index + 1
      alarmManager.cancel(buildReceiverPendingIntent(context, config.alarmId, activeTriggerAt, backupStage, "live", config))
    }
    AlarmStorage.clearActiveTriggerAt(context)
  }

  private fun cancelOccurrence(context: Context, config: StoredAlarmConfig, triggerAtMillis: Long, kind: String) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.cancel(buildReceiverPendingIntent(context, config.alarmId, triggerAtMillis, 0, kind, config))
    config.backupOffsetsSeconds.forEachIndexed { index, _ ->
      alarmManager.cancel(buildReceiverPendingIntent(context, config.alarmId, triggerAtMillis, index + 1, kind, config))
    }
  }

  private fun buildLaunchPendingIntent(
    context: Context,
    alarmId: String,
    triggerAtMillis: Long,
    backupStage: Int,
    kind: String,
  ): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra(AlarmConstants.EXTRA_LAUNCH_ALARM, true)
      putExtra(AlarmConstants.EXTRA_ALARM_ID, alarmId)
      putExtra(AlarmConstants.EXTRA_TRIGGER_AT_MILLIS, triggerAtMillis)
      putExtra(AlarmConstants.EXTRA_BACKUP_STAGE, backupStage)
      putExtra(AlarmConstants.EXTRA_KIND, kind)
    }

    return PendingIntent.getActivity(
      context,
      requestCode(alarmId, triggerAtMillis, backupStage, kind, "launch"),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  fun buildReceiverPendingIntent(
    context: Context,
    alarmId: String,
    triggerAtMillis: Long,
    backupStage: Int,
    kind: String,
    config: StoredAlarmConfig,
  ): PendingIntent {
    val intent = Intent(context, AlarmReceiver::class.java).apply {
      putExtra(AlarmConstants.EXTRA_ALARM_ID, alarmId)
      putExtra(AlarmConstants.EXTRA_TRIGGER_AT_MILLIS, triggerAtMillis)
      putExtra(AlarmConstants.EXTRA_BACKUP_STAGE, backupStage)
      putExtra(AlarmConstants.EXTRA_KIND, kind)
      putExtra(AlarmConstants.EXTRA_SOUND_ID, config.soundId)
      putExtra(AlarmConstants.EXTRA_VIBRATION_ENABLED, config.vibrationEnabled)
    }

    return PendingIntent.getBroadcast(
      context,
      requestCode(alarmId, triggerAtMillis, backupStage, kind, "receiver"),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun requestCode(alarmId: String, triggerAtMillis: Long, backupStage: Int, kind: String, salt: String): Int =
    "${alarmId}:${triggerAtMillis}:${backupStage}:${kind}:${salt}".hashCode()

  private fun computeNextTriggerMillis(config: StoredAlarmConfig, fromEpochMillis: Long): Long {
    val zoneId = ZoneId.systemDefault()
    var candidate = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(fromEpochMillis), zoneId)
      .withHour(config.timeMinutesLocal / 60)
      .withMinute(config.timeMinutesLocal % 60)
      .withSecond(0)
      .withNano(0)

    if (candidate.toInstant().toEpochMilli() <= fromEpochMillis) {
      candidate = candidate.plusDays(1)
    }

    repeat(8) {
      if (config.repeatDays.contains(candidate.dayOfWeek.toJavaCalendarDay())) {
        return candidate.toInstant().toEpochMilli()
      }
      candidate = candidate.plusDays(1).withHour(config.timeMinutesLocal / 60).withMinute(config.timeMinutesLocal % 60)
    }

    return candidate.toInstant().toEpochMilli()
  }
}

private fun DayOfWeek.toJavaCalendarDay(): Int =
  when (this) {
    DayOfWeek.SUNDAY -> 0
    DayOfWeek.MONDAY -> 1
    DayOfWeek.TUESDAY -> 2
    DayOfWeek.WEDNESDAY -> 3
    DayOfWeek.THURSDAY -> 4
    DayOfWeek.FRIDAY -> 5
    DayOfWeek.SATURDAY -> 6
  }
