package com.superalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.superalarm.app.widget.SuperAlarmWidgetUpdater
import java.time.Instant

class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val config = AlarmStorage.readAlarmConfig(context)
    val alarmId = intent.getStringExtra(AlarmConstants.EXTRA_ALARM_ID) ?: config.alarmId
    val kind = intent.getStringExtra(AlarmConstants.EXTRA_KIND) ?: "live"
    val backupStage = intent.getIntExtra(AlarmConstants.EXTRA_BACKUP_STAGE, 0)
    val triggerAtMillis = intent.getLongExtra(AlarmConstants.EXTRA_TRIGGER_AT_MILLIS, System.currentTimeMillis())
    val attemptId = "attempt-${triggerAtMillis}"
    val triggeredAt = Instant.now().toString()

    if (backupStage == 0 && kind == "live") {
      AlarmScheduler.scheduleNextAfterTrigger(context, config, triggerAtMillis)
      AlarmStorage.setActiveTriggerAt(context, triggerAtMillis)
      SuperAlarmWidgetUpdater.requestUpdate(context)
    }

    val launch = PendingAlarmLaunch(
      alarmId = alarmId,
      attemptId = attemptId,
      kind = kind,
      triggeredAt = triggeredAt,
      backupStage = backupStage,
    )
    AlarmStorage.savePendingLaunch(context, launch)

    val serviceIntent = Intent(context, AlarmRingingService::class.java).apply {
      putExtra(AlarmConstants.EXTRA_ALARM_ID, alarmId)
      putExtra(AlarmConstants.EXTRA_ATTEMPT_ID, attemptId)
      putExtra(AlarmConstants.EXTRA_KIND, kind)
      putExtra(AlarmConstants.EXTRA_BACKUP_STAGE, backupStage)
      putExtra(AlarmConstants.EXTRA_TRIGGERED_AT, triggeredAt)
      putExtra(AlarmConstants.EXTRA_TRIGGER_AT_MILLIS, triggerAtMillis)
      putExtra(AlarmConstants.EXTRA_SOUND_ID, intent.getStringExtra(AlarmConstants.EXTRA_SOUND_ID) ?: config.soundId)
      putExtra(AlarmConstants.EXTRA_VIBRATION_ENABLED, intent.getBooleanExtra(AlarmConstants.EXTRA_VIBRATION_ENABLED, config.vibrationEnabled))
    }

    context.startForegroundService(serviceIntent)
  }
}
