package com.superalarm.app.alarm

object AlarmConstants {
  const val PREFS_NAME = "super_alarm_prefs"
  const val NOTIFICATION_CHANNEL_ID = "super_alarm_channel"
  const val NOTIFICATION_ID = 4107

  const val EXTRA_ALARM_ID = "extra_alarm_id"
  const val EXTRA_ATTEMPT_ID = "extra_attempt_id"
  const val EXTRA_KIND = "extra_kind"
  const val EXTRA_TRIGGERED_AT = "extra_triggered_at"
  const val EXTRA_TRIGGER_AT_MILLIS = "extra_trigger_at_millis"
  const val EXTRA_BACKUP_STAGE = "extra_backup_stage"
  const val EXTRA_SOUND_ID = "extra_sound_id"
  const val EXTRA_VIBRATION_ENABLED = "extra_vibration_enabled"
  const val EXTRA_LAUNCH_ALARM = "extra_launch_alarm"

  const val KEY_ALARM_ID = "alarm_id"
  const val KEY_LABEL = "label"
  const val KEY_ENABLED = "enabled"
  const val KEY_TIME_MINUTES = "time_minutes"
  const val KEY_REPEAT_DAYS = "repeat_days"
  const val KEY_SOUND_ID = "sound_id"
  const val KEY_VIBRATION_ENABLED = "vibration_enabled"
  const val KEY_BACKUP_OFFSETS = "backup_offsets"
  const val KEY_SCHEDULED_TRIGGER_AT = "scheduled_trigger_at"
  const val KEY_ACTIVE_TRIGGER_AT = "active_trigger_at"

  const val KEY_PENDING_ALARM_ID = "pending_alarm_id"
  const val KEY_PENDING_ATTEMPT_ID = "pending_attempt_id"
  const val KEY_PENDING_KIND = "pending_kind"
  const val KEY_PENDING_TRIGGERED_AT = "pending_triggered_at"
  const val KEY_PENDING_BACKUP_STAGE = "pending_backup_stage"
}
