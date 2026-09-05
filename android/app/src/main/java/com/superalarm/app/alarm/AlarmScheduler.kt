package com.superalarm.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.superalarm.app.MainActivity
import com.superalarm.app.telemetry.AlarmKind
import com.superalarm.app.telemetry.ProductEvent
import com.superalarm.app.telemetry.SuperAlarmTelemetry
import com.superalarm.app.telemetry.TelemetryEventName
import com.superalarm.app.telemetry.TelemetryFailureCode
import com.superalarm.app.telemetry.TelemetryFailureOperation
import com.superalarm.app.telemetry.TelemetrySource
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

sealed interface AlarmScheduleResult {
  data class Scheduled(val triggerAtMillis: Long) : AlarmScheduleResult
  data object NotScheduled : AlarmScheduleResult
  data class Failed(val cause: RuntimeException) : AlarmScheduleResult
}

object AlarmScheduler {
  fun scheduleNext(
    context: Context,
    config: StoredAlarmConfig,
    fromEpochMillis: Long = System.currentTimeMillis(),
  ): AlarmScheduleResult {
    cancelUpcoming(context, config)

    if (!config.enabled || config.repeatDays.isEmpty()) {
      AlarmStorage.setScheduledTriggerAt(context, 0L)
      return AlarmScheduleResult.NotScheduled
    }

    val triggerAtMillis = computeNextTriggerMillis(config, fromEpochMillis)
    return scheduleOccurrence(context, config, triggerAtMillis)
  }

  fun scheduleOneShot(context: Context, config: StoredAlarmConfig, triggerAtMillis: Long): AlarmScheduleResult {
    cancelUpcoming(context, config)
    if (!config.enabled || triggerAtMillis <= System.currentTimeMillis()) {
      AlarmStorage.setScheduledTriggerAt(context, 0L)
      return AlarmScheduleResult.NotScheduled
    }
    return scheduleOccurrence(context, config, triggerAtMillis)
  }

  fun scheduleTest(
    context: Context,
    config: StoredAlarmConfig,
    delayMillis: Long,
    previousTriggerAtMillis: Long? = null,
    nowMillis: Long = System.currentTimeMillis(),
  ): Long? {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
      captureScheduleBlocked(AlarmKind.Test)
      return null
    }

    previousTriggerAtMillis?.let { cancelOccurrence(context, config, it, "test") }
    val triggerAtMillis = nowMillis + delayMillis.coerceAtLeast(5_000L)
    val showIntent = buildShowPendingIntent(context, config.alarmId, triggerAtMillis, 0, "test")
    val receiverIntent = buildReceiverPendingIntent(context, config.alarmId, triggerAtMillis, 0, "test", config)
    return try {
      alarmManager.setAlarmClock(
        AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
        receiverIntent,
      )
      captureScheduled(AlarmKind.Test)
      triggerAtMillis
    } catch (error: RuntimeException) {
      captureScheduleFailure(AlarmKind.Test, error)
      null
    }
  }

  fun cancelTest(context: Context, config: StoredAlarmConfig, triggerAtMillis: Long) {
    cancelOccurrence(context, config, triggerAtMillis, "test")
  }

  private fun scheduleOccurrence(
    context: Context,
    config: StoredAlarmConfig,
    triggerAtMillis: Long,
  ): AlarmScheduleResult {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
      AlarmStorage.setScheduledTriggerAt(context, 0L)
      captureScheduleBlocked(AlarmKind.Live)
      return AlarmScheduleResult.NotScheduled
    }
    return try {
      val showIntent = buildShowPendingIntent(context, config.alarmId, triggerAtMillis, 0, "live")
      val mainPendingIntent = buildReceiverPendingIntent(context, config.alarmId, triggerAtMillis, 0, "live", config)
      alarmManager.setAlarmClock(
        AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
        mainPendingIntent,
      )

      config.backupOffsetsSeconds.forEachIndexed { index, offsetSeconds ->
        val backupStage = index + 1
        val backupTriggerAt = triggerAtMillis + offsetSeconds * 1_000L
        val backupIntent = buildReceiverPendingIntent(context, config.alarmId, triggerAtMillis, backupStage, "live", config)
        val backupShowIntent = buildShowPendingIntent(context, config.alarmId, triggerAtMillis, backupStage, "live")

        alarmManager.setAlarmClock(
          AlarmManager.AlarmClockInfo(backupTriggerAt, backupShowIntent),
          backupIntent,
        )
      }

      AlarmStorage.setScheduledTriggerAt(context, triggerAtMillis)
      captureScheduled(AlarmKind.Live)
      AlarmScheduleResult.Scheduled(triggerAtMillis)
    } catch (error: RuntimeException) {
      try {
        cancelOccurrence(context, config, triggerAtMillis, "live")
      } catch (cleanupError: RuntimeException) {
        error.addSuppressed(cleanupError)
      }
      AlarmStorage.setScheduledTriggerAt(context, 0L)
      captureScheduleFailure(AlarmKind.Live, error)
      AlarmScheduleResult.Failed(error)
    }
  }

  fun scheduleNextAfterTrigger(
    context: Context,
    config: StoredAlarmConfig,
    triggerAtMillis: Long,
  ): AlarmScheduleResult {
    if (!config.enabled || config.repeatDays.isEmpty()) {
      AlarmStorage.setScheduledTriggerAt(context, 0L)
      return AlarmScheduleResult.NotScheduled
    }

    val nextTriggerAtMillis = computeNextTriggerMillis(config, triggerAtMillis + 60_000L)
    return scheduleOccurrence(context, config, nextTriggerAtMillis)
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

  private fun buildShowPendingIntent(
    context: Context,
    alarmId: String,
    triggerAtMillis: Long,
    backupStage: Int,
    kind: String,
  ): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
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

  private fun captureScheduled(kind: AlarmKind) {
    SuperAlarmTelemetry.capture(ProductEvent(
      TelemetryEventName.AlarmScheduled,
      source = TelemetrySource.System,
      alarmKind = kind,
    ))
  }

  private fun captureScheduleBlocked(kind: AlarmKind) {
    SuperAlarmTelemetry.capture(ProductEvent(
      TelemetryEventName.AlarmScheduleFailed,
      source = TelemetrySource.System,
      outcome = com.superalarm.app.telemetry.TelemetryOutcome.Blocked,
      alarmKind = kind,
      permissionKind = com.superalarm.app.telemetry.TelemetryPermissionKind.ExactAlarm,
      failureCode = TelemetryFailureCode.ExactAlarmDenied,
    ))
  }

  private fun captureScheduleFailure(kind: AlarmKind, error: RuntimeException) {
    SuperAlarmTelemetry.captureFailure(TelemetryFailureOperation.AlarmScheduling, error)
    SuperAlarmTelemetry.capture(ProductEvent(
      TelemetryEventName.AlarmScheduleFailed,
      source = TelemetrySource.System,
      outcome = com.superalarm.app.telemetry.TelemetryOutcome.Failed,
      alarmKind = kind,
      failureCode = TelemetryFailureCode.ScheduleFailed,
    ))
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
