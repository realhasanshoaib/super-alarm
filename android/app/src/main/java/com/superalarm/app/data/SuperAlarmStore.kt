package com.superalarm.app.data

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.superalarm.app.alarm.AlarmConstants
import com.superalarm.app.alarm.AlarmRingingService
import com.superalarm.app.alarm.AlarmScheduler
import com.superalarm.app.alarm.AlarmStorage
import com.superalarm.app.alarm.PendingAlarmLaunch
import com.superalarm.app.alarm.StoredAlarmConfig
import com.superalarm.app.admin.SuperAlarmDeviceAdminReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Instant

class SuperAlarmStore(private val context: Context) {
  private val prefs = context.getSharedPreferences("super_alarm_native_state", Context.MODE_PRIVATE)
  private val _state = MutableStateFlow(loadState())
  val state: StateFlow<AppState> = _state.asStateFlow()

  init {
    recoverColdRuntime()
    syncScheduler(_state.value.alarm)
  }

  fun handleLaunchIntent(intent: Intent?) {
    val pending = AlarmStorage.readPendingLaunch(context)
    if (intent?.getBooleanExtra(AlarmConstants.EXTRA_LAUNCH_ALARM, false) == true || pending != null) {
      val launch = pending ?: PendingAlarmLaunch(
        alarmId = intent?.getStringExtra(AlarmConstants.EXTRA_ALARM_ID) ?: _state.value.alarm.id,
        attemptId = intent?.getStringExtra(AlarmConstants.EXTRA_ATTEMPT_ID) ?: "attempt-${System.currentTimeMillis()}",
        kind = intent?.getStringExtra(AlarmConstants.EXTRA_KIND) ?: "live",
        triggeredAt = intent?.getStringExtra(AlarmConstants.EXTRA_TRIGGERED_AT) ?: Instant.now().toString(),
        backupStage = intent?.getIntExtra(AlarmConstants.EXTRA_BACKUP_STAGE, 0) ?: 0,
      )
      startRuntime(launch)
    }
  }

  fun completeOnboarding() = update { it.copy(onboardingComplete = true) }

  fun setTime(minutes: Int) = mutateAlarm { it.copy(timeMinutesLocal = minutes.coerceIn(0, 1439)) }

  fun toggleDay(day: Int) = mutateAlarm { alarm ->
    val next = if (alarm.repeatDays.contains(day)) alarm.repeatDays - day else (alarm.repeatDays + day).sorted()
    alarm.copy(repeatDays = next)
  }

  fun setVibration(enabled: Boolean) = mutateAlarm { it.copy(vibrationEnabled = enabled) }

  fun setEnabled(enabled: Boolean): Boolean {
    if (enabled && _state.value.alarm.allowedLocationIds.isEmpty()) {
      addEvent("enable_blocked", "Add an allowed QR spot before enabling the alarm.")
      return false
    }
    mutateAlarm { it.copy(enabled = enabled) }
    return true
  }

  fun addLocation(label: String): QrLocation {
    val location = QrLocation(label = label.ifBlank { "Wake spot" })
    update { state ->
      state.copy(
        qrLocations = state.qrLocations + location,
        alarm = state.alarm.copy(allowedLocationIds = (state.alarm.allowedLocationIds + location.id).distinct()),
        onboardingComplete = true,
      )
    }
    return location
  }

  fun deleteLocation(locationId: String) {
    update { state ->
      val remainingAllowed = state.alarm.allowedLocationIds - locationId
      state.copy(
        qrLocations = state.qrLocations.filterNot { it.id == locationId },
        alarm = state.alarm.copy(
          allowedLocationIds = remainingAllowed,
          enabled = state.alarm.enabled && remainingAllowed.isNotEmpty(),
        ),
      )
    }
  }

  fun toggleAllowed(locationId: String) {
    update { state ->
      val current = state.alarm.allowedLocationIds
      val next = if (current.contains(locationId)) current - locationId else (current + locationId).distinct()
      state.copy(alarm = state.alarm.copy(allowedLocationIds = next, enabled = state.alarm.enabled && next.isNotEmpty()))
    }
  }

  fun startRoutineTest() {
    val alarm = _state.value.alarm
    val launch = PendingAlarmLaunch(
      alarmId = alarm.id,
      attemptId = "test-${System.currentTimeMillis()}",
      kind = "test",
      triggeredAt = Instant.now().toString(),
      backupStage = 0,
    )
    AlarmStorage.savePendingLaunch(context, launch)
    startRuntime(launch)
    ContextCompat.startForegroundService(
      context,
      Intent(context, AlarmRingingService::class.java).apply {
        putExtra(AlarmConstants.EXTRA_ALARM_ID, alarm.id)
        putExtra(AlarmConstants.EXTRA_ATTEMPT_ID, launch.attemptId)
        putExtra(AlarmConstants.EXTRA_KIND, "test")
        putExtra(AlarmConstants.EXTRA_TRIGGERED_AT, launch.triggeredAt)
        putExtra(AlarmConstants.EXTRA_BACKUP_STAGE, 0)
        putExtra(AlarmConstants.EXTRA_SOUND_ID, alarm.soundId)
        putExtra(AlarmConstants.EXTRA_VIBRATION_ENABLED, alarm.vibrationEnabled)
      },
    )
  }

  fun dismissWithPayload(payload: String): Boolean {
    val state = _state.value
    val runtime = state.runtime ?: return false
    val validation = validateDismissalPayload(payload, state)
    val location = validation.location
    if (!validation.accepted || location == null) {
      if (validation.reason == "disallowed" && location != null) {
        addEvent("disallowed_qr", "${location.label} is enrolled but not allowed for dismissal.")
      } else {
        addEvent("invalid_qr", "Scanned QR is not enrolled.")
      }
      return false
    }

    AlarmScheduler.cancelActiveBackups(context, AlarmStorage.readAlarmConfig(context))
    AlarmStorage.clearPendingLaunch(context)
    context.stopService(Intent(context, AlarmRingingService::class.java))
    update { current ->
      current.copy(
        runtime = null,
        attempts = current.attempts.map {
          if (it.id == runtime.attemptId) it.copy(
            dismissedAt = Instant.now().toString(),
            result = "dismissed",
            dismissalLocationId = location.id,
          ) else it
        },
      )
    }
    return true
  }

  fun dismissWithoutScanForTestOnly() {
    if (_state.value.runtime?.kind != "test") return
    context.stopService(Intent(context, AlarmRingingService::class.java))
    AlarmStorage.clearPendingLaunch(context)
    update { it.copy(runtime = null) }
  }

  fun capabilities(): ReliabilityChecklistState {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return ReliabilityChecklistState(
      notificationsGranted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
      exactAlarmAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true,
      fullscreenIntentAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) notificationManager.canUseFullScreenIntent() else true,
      batteryOptimizationRelaxed = powerManager.isIgnoringBatteryOptimizations(context.packageName),
      uninstallProtectionActive = isUninstallProtectionActive(),
    )
  }

  fun cameraGranted(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

  fun openExactAlarmSettings() = openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))

  fun openFullscreenSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply { data = Uri.parse("package:${context.packageName}") })
    }
  }

  fun openBatterySettings() = openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

  fun isUninstallProtectionActive(): Boolean {
    val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return devicePolicyManager.isAdminActive(deviceAdminComponent())
  }

  fun deviceAdminActivationIntent(): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent())
    putExtra(
      DevicePolicyManager.EXTRA_ADD_EXPLANATION,
      "Super Alarm can add one extra step before uninstalling, so sleepy-you has to deliberately disable protection first.",
    )
  }

  private fun deviceAdminComponent(): ComponentName = ComponentName(context, SuperAlarmDeviceAdminReceiver::class.java)

  fun diagnosticsJson(): String = JSONObject().apply {
    put("appState", _state.value.toJson())
    put("capabilities", JSONObject().apply {
      val caps = capabilities()
      put("notificationsGranted", caps.notificationsGranted)
      put("exactAlarmAllowed", caps.exactAlarmAllowed)
      put("fullscreenIntentAllowed", caps.fullscreenIntentAllowed)
      put("batteryOptimizationRelaxed", caps.batteryOptimizationRelaxed)
      put("uninstallProtectionActive", caps.uninstallProtectionActive)
      put("cameraGranted", cameraGranted())
      put("platform", "android")
    })
  }.toString(2)

  private fun startRuntime(launch: PendingAlarmLaunch) {
    update { state ->
      val exists = state.attempts.any { it.id == launch.attemptId }
      state.copy(
        runtime = AlarmRuntimeState(launch.attemptId, launch.alarmId, launch.kind, launch.triggeredAt, launch.backupStage),
        attempts = if (exists) state.attempts else listOf(
          AlarmAttempt(
            id = launch.attemptId,
            alarmId = launch.alarmId,
            kind = launch.kind,
            triggeredAt = launch.triggeredAt,
            backupStage = launch.backupStage,
          ),
        ) + state.attempts,
      )
    }
  }

  private fun mutateAlarm(transform: (AlarmConfig) -> AlarmConfig) {
    update { state -> state.copy(alarm = transform(state.alarm).normalized()) }
  }

  private fun update(transform: (AppState) -> AppState) {
    val next = transform(_state.value).let { it.copy(alarm = it.alarm.normalized()) }
    _state.value = next
    saveState(next)
    syncScheduler(next.alarm)
  }

  private fun addEvent(type: String, message: String) {
    update { it.copy(diagnosticEvents = listOf(AlarmDiagnosticEvent(type = type, message = message)) + it.diagnosticEvents.take(49)) }
  }

  private fun recoverColdRuntime() {
    val runtime = _state.value.runtime ?: return
    update { state ->
      state.copy(
        runtime = null,
        attempts = state.attempts.map { if (it.id == runtime.attemptId) it.copy(result = "interrupted") else it },
        diagnosticEvents = listOf(AlarmDiagnosticEvent(type = "runtime_recovered", message = "Previous ringing state was interrupted on launch.")) + state.diagnosticEvents,
      )
    }
  }

  private fun syncScheduler(alarm: AlarmConfig) {
    val stored = alarm.toStoredConfig()
    AlarmStorage.saveAlarmConfig(context, stored)
    if (stored.enabled) AlarmScheduler.scheduleNext(context, stored) else {
      AlarmScheduler.cancelUpcoming(context, stored)
      AlarmStorage.setScheduledTriggerAt(context, 0L)
    }
  }

  private fun loadState(): AppState {
    val raw = prefs.getString(KEY_STATE, null) ?: return AppState()
    return runCatching { appStateFromJson(JSONObject(raw)) }.getOrElse { AppState() }
  }

  private fun saveState(state: AppState) {
    prefs.edit().putString(KEY_STATE, state.toJson().toString()).apply()
  }

  private fun openSettings(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
  }

  companion object {
    private const val KEY_STATE = "state"
  }
}

fun AlarmConfig.normalized(): AlarmConfig = copy(
  enabled = enabled && allowedLocationIds.isNotEmpty(),
  repeatDays = repeatDays.distinct().filter { it in 0..6 }.sorted(),
  backupOffsetsSeconds = backupOffsetsSeconds.distinct().filter { it > 0 }.sorted(),
)

fun AlarmConfig.toStoredConfig(): StoredAlarmConfig = StoredAlarmConfig(
  alarmId = id,
  label = label,
  enabled = enabled && allowedLocationIds.isNotEmpty(),
  timeMinutesLocal = timeMinutesLocal,
  repeatDays = repeatDays,
  soundId = soundId,
  vibrationEnabled = vibrationEnabled,
  backupOffsetsSeconds = backupOffsetsSeconds,
)
