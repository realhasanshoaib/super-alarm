package com.superalarm.app.data

import android.Manifest
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
import com.superalarm.app.widget.SuperAlarmWidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

class SuperAlarmStore(
  private val context: Context,
  private val recoverInterruptedRuntime: Boolean = true,
) {
  private val prefs = context.getSharedPreferences("super_alarm_native_state", Context.MODE_PRIVATE)
  private val _state = MutableStateFlow(loadState())
  val state: StateFlow<AppState> = _state.asStateFlow()

  init {
    if (recoverInterruptedRuntime) recoverColdRuntime()
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

  fun completeOnboarding() = update {
    it.copy(
      onboardingComplete = true,
      onboardingStep = OnboardingSteps.Ready,
    )
  }

  fun setOnboardingStep(step: Int) = update {
    it.copy(onboardingStep = step.coerceIn(OnboardingSteps.Welcome, OnboardingSteps.Last))
  }

  fun setTime(minutes: Int) = mutateAlarm { it.copy(timeMinutesLocal = minutes.coerceIn(0, 1439)) }

  fun toggleDay(day: Int) = mutateAlarm { alarm ->
    val next = if (alarm.repeatDays.contains(day)) alarm.repeatDays - day else (alarm.repeatDays + day).sorted()
    alarm.copy(repeatDays = next)
  }

  fun setVibration(enabled: Boolean) = mutateAlarm { it.copy(vibrationEnabled = enabled) }

  fun setEnabled(enabled: Boolean): Boolean {
    if (enabled && !_state.value.alarm.hasDismissalTarget()) {
      addEvent("enable_blocked", "Add an allowed QR spot or object before enabling the alarm.")
      return false
    }
    mutateAlarm { it.copy(enabled = enabled) }
    return true
  }

  fun setSleepDuration(durationMinutes: Int, now: ZonedDateTime = ZonedDateTime.now()): Boolean {
    if (!_state.value.alarm.hasDismissalTarget()) {
      addEvent("enable_blocked", "Add an allowed QR spot or object before enabling the alarm.")
      return false
    }

    val trigger = now.plusMinutes(durationMinutes.coerceAtLeast(1).toLong())
    val next = _state.value.copy(
      alarm = _state.value.alarm.copy(
        enabled = true,
        timeMinutesLocal = trigger.hour * 60 + trigger.minute,
      ).normalized(),
    )
    _state.value = next
    saveState(next)
    val stored = next.alarm.toStoredConfig()
    AlarmStorage.saveAlarmConfig(context, stored)
    AlarmScheduler.scheduleOneShot(context, stored, trigger.toInstant().toEpochMilli())
    SuperAlarmWidgetUpdater.requestUpdate(context)
    return true
  }

  fun addLocation(label: String): QrLocation {
    val location = QrLocation(label = label.ifBlank { "Wake spot" })
    update { state ->
      state.copy(
        qrLocations = state.qrLocations + location,
        alarm = state.alarm.copy(allowedLocationIds = (state.alarm.allowedLocationIds + location.id).distinct()),
        onboardingStep = if (state.onboardingComplete) {
          state.onboardingStep
        } else {
          maxOf(state.onboardingStep, OnboardingSteps.Alarm)
        },
      )
    }
    return location
  }

  fun deleteLocation(locationId: String) {
    update { state ->
      val remainingAllowed = state.alarm.allowedLocationIds - locationId
      val alarm = state.alarm.copy(allowedLocationIds = remainingAllowed)
      state.copy(
        qrLocations = state.qrLocations.filterNot { it.id == locationId },
        alarm = alarm.copy(enabled = alarm.enabled && alarm.hasDismissalTarget()),
      )
    }
  }

  fun toggleAllowed(locationId: String) {
    update { state ->
      val current = state.alarm.allowedLocationIds
      val next = if (current.contains(locationId)) current - locationId else (current + locationId).distinct()
      val alarm = state.alarm.copy(allowedLocationIds = next)
      state.copy(alarm = alarm.copy(enabled = alarm.enabled && alarm.hasDismissalTarget()))
    }
  }

  fun addObjectTarget(
    label: String,
    embeddings: List<String>,
    thumbnailJpeg: ByteArray?,
  ): ObjectTarget {
    require(embeddings.isNotEmpty())
    val id = UUID.randomUUID().toString()
    val target = ObjectTarget(
      id = id,
      label = label.ifBlank { "Wake object" },
      embeddings = embeddings,
      thumbnailFileName = saveObjectThumbnail(thumbnailJpeg),
    )
    update { state ->
      state.copy(
        objectTargets = state.objectTargets + target,
        alarm = state.alarm.copy(
          allowedObjectTargetIds = (state.alarm.allowedObjectTargetIds + target.id).distinct(),
        ),
        onboardingStep = if (state.onboardingComplete) {
          state.onboardingStep
        } else {
          maxOf(state.onboardingStep, OnboardingSteps.Alarm)
        },
      )
    }
    addEvent("object_enrolled", "Enrolled object: ${target.label}")
    return target
  }

  fun updateObjectTarget(
    targetId: String,
    embeddings: List<String>,
    thumbnailJpeg: ByteArray?,
  ) {
    require(embeddings.isNotEmpty())
    val current = _state.value.objectTargets.firstOrNull { it.id == targetId } ?: return
    val nextThumbnail = saveObjectThumbnail(thumbnailJpeg) ?: current.thumbnailFileName
    update { state ->
      state.copy(
        objectTargets = state.objectTargets.map { target ->
          if (target.id == targetId) {
            target.copy(embeddings = embeddings, thumbnailFileName = nextThumbnail)
          } else {
            target
          }
        },
      )
    }
    if (nextThumbnail != current.thumbnailFileName) {
      current.thumbnailFileName?.let(::deleteObjectThumbnail)
    }
    addEvent("object_rescanned", "Updated object scan: ${current.label}")
  }

  fun deleteObjectTarget(targetId: String) {
    val thumbnailFileName = _state.value.objectTargets
      .firstOrNull { it.id == targetId }
      ?.thumbnailFileName
    update { state ->
      val remainingAllowed = state.alarm.allowedObjectTargetIds - targetId
      val alarm = state.alarm.copy(allowedObjectTargetIds = remainingAllowed)
      state.copy(
        objectTargets = state.objectTargets.filterNot { it.id == targetId },
        alarm = alarm.copy(enabled = alarm.enabled && alarm.hasDismissalTarget()),
      )
    }
    thumbnailFileName?.let(::deleteObjectThumbnail)
  }

  fun toggleObjectAllowed(targetId: String) {
    update { state ->
      val current = state.alarm.allowedObjectTargetIds
      val next = if (current.contains(targetId)) current - targetId else (current + targetId).distinct()
      val alarm = state.alarm.copy(allowedObjectTargetIds = next)
      state.copy(alarm = alarm.copy(enabled = alarm.enabled && alarm.hasDismissalTarget()))
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

  fun scheduleOnboardingTest(delayMillis: Long = 10_000L): Boolean {
    val current = _state.value
    if (!current.alarm.hasDismissalTarget()) return false

    val caps = capabilities()
    if (!caps.notificationsGranted || !caps.exactAlarmAllowed || !caps.fullscreenIntentAllowed) {
      addEvent("onboarding_test_blocked", "Prepare notification, exact alarm, and lock-screen access first.")
      return false
    }

    val alarm = current.alarm.copy(enabled = true).normalized()
    val triggerAtMillis = AlarmScheduler.scheduleTest(context, alarm.toStoredConfig(), delayMillis) ?: return false
    update {
      it.copy(
        alarm = alarm,
        onboardingStep = OnboardingSteps.WakeUpTest,
        onboardingTestScheduledAtMillis = triggerAtMillis,
      )
    }
    addEvent("onboarding_test_scheduled", "Wake-up test scheduled through the Android alarm system.")
    return true
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

    completeDismissal(runtime, location.id, location.label, "qr")
    return true
  }

  fun dismissWithObject(targetId: String): Boolean {
    val state = _state.value
    val runtime = state.runtime ?: return false
    val target = state.objectTargets.firstOrNull { it.id == targetId }
    if (target == null || !state.alarm.allowedObjectTargetIds.contains(targetId)) {
      addEvent("invalid_object", "Object is not enrolled or allowed for dismissal.")
      return false
    }
    completeDismissal(runtime, target.id, target.label, "object")
    addEvent("object_dismissal", "Alarm dismissed with ${target.label}.")
    return true
  }

  fun dismissWithoutScanForTestOnly() {
    val runtime = _state.value.runtime?.takeIf { it.kind == "test" } ?: return
    val stoppedAt = Instant.now().toString()
    context.stopService(Intent(context, AlarmRingingService::class.java))
    AlarmStorage.clearPendingLaunch(context)
    update { state ->
      state.copy(
        runtime = null,
        attempts = state.attempts.map { attempt ->
          if (attempt.id == runtime.attemptId) {
            attempt.copy(result = "interrupted", resultAt = stoppedAt)
          } else {
            attempt
          }
        },
      )
    }
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
    )
  }

  fun cameraGranted(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

  fun objectThumbnailFile(target: ObjectTarget): File? =
    target.thumbnailFileName
      ?.let(::objectThumbnailFile)
      ?.takeIf(File::isFile)

  fun openExactAlarmSettings() = openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))

  fun openNotificationSettings() {
    openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
      putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    })
  }

  fun openFullscreenSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply { data = Uri.parse("package:${context.packageName}") })
    }
  }

  fun openBatterySettings() = openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

  fun diagnosticsJson(): String = JSONObject().apply {
    put("appState", _state.value.toJson())
    put("capabilities", JSONObject().apply {
      val caps = capabilities()
      put("notificationsGranted", caps.notificationsGranted)
      put("exactAlarmAllowed", caps.exactAlarmAllowed)
      put("fullscreenIntentAllowed", caps.fullscreenIntentAllowed)
      put("batteryOptimizationRelaxed", caps.batteryOptimizationRelaxed)
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

  private fun completeDismissal(
    runtime: AlarmRuntimeState,
    targetId: String,
    targetLabel: String,
    method: String,
  ) {
    val dismissedAt = Instant.now().toString()
    AlarmScheduler.cancelActiveBackups(context, AlarmStorage.readAlarmConfig(context))
    AlarmStorage.clearPendingLaunch(context)
    context.stopService(Intent(context, AlarmRingingService::class.java))
    update { current ->
      current.copy(
        runtime = null,
        attempts = current.attempts.map {
          if (it.id == runtime.attemptId) {
            it.copy(
              dismissedAt = dismissedAt,
              resultAt = dismissedAt,
              result = "dismissed",
              dismissalLocationId = targetId,
              dismissalMethod = method,
              dismissalTargetLabel = targetLabel,
            )
          } else {
            it
          }
        },
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
    val interruptedAt = Instant.now().toString()
    update { state ->
      state.copy(
        runtime = null,
        attempts = state.attempts.map {
          if (it.id == runtime.attemptId) {
            it.copy(result = "interrupted", resultAt = interruptedAt)
          } else {
            it
          }
        },
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
    SuperAlarmWidgetUpdater.requestUpdate(context)
  }

  private fun loadState(): AppState {
    val raw = prefs.getString(KEY_STATE, null) ?: return AppState()
    return runCatching { appStateFromJson(JSONObject(raw)) }.getOrElse { AppState() }
  }

  private fun saveState(state: AppState) {
    prefs.edit().putString(KEY_STATE, state.toJson().toString()).apply()
  }

  private fun saveObjectThumbnail(jpeg: ByteArray?): String? {
    val bytes = jpeg?.takeIf { it.isNotEmpty() } ?: return null
    val directory = File(context.filesDir, OBJECT_THUMBNAIL_DIRECTORY)
    if (!directory.exists() && !directory.mkdirs()) return null
    val fileName = "${UUID.randomUUID()}.jpg"
    return runCatching {
      File(directory, fileName).writeBytes(bytes)
      fileName
    }.getOrNull()
  }

  private fun objectThumbnailFile(fileName: String): File? {
    if (!OBJECT_THUMBNAIL_FILE.matches(fileName)) return null
    return File(File(context.filesDir, OBJECT_THUMBNAIL_DIRECTORY), fileName)
  }

  private fun deleteObjectThumbnail(fileName: String) {
    objectThumbnailFile(fileName)?.delete()
  }

  private fun openSettings(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
  }

  companion object {
    private const val KEY_STATE = "state"
    private const val OBJECT_THUMBNAIL_DIRECTORY = "object-thumbnails"
    private val OBJECT_THUMBNAIL_FILE = Regex("[0-9a-f-]{36}\\.jpg")
  }
}

fun AlarmConfig.normalized(): AlarmConfig = copy(
  enabled = enabled && hasDismissalTarget(),
  repeatDays = repeatDays.distinct().filter { it in 0..6 }.sorted(),
  backupOffsetsSeconds = backupOffsetsSeconds.distinct().filter { it > 0 }.sorted(),
)

fun AlarmConfig.hasDismissalTarget(): Boolean =
  allowedLocationIds.isNotEmpty() || allowedObjectTargetIds.isNotEmpty()

fun AlarmConfig.toStoredConfig(): StoredAlarmConfig = StoredAlarmConfig(
  alarmId = id,
  label = label,
  enabled = enabled && hasDismissalTarget(),
  timeMinutesLocal = timeMinutesLocal,
  repeatDays = repeatDays,
  soundId = soundId,
  vibrationEnabled = vibrationEnabled,
  backupOffsetsSeconds = backupOffsetsSeconds,
)
