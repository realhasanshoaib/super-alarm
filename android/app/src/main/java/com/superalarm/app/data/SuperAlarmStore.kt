package com.superalarm.app.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
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
import com.superalarm.app.telemetry.DismissalTargetType
import com.superalarm.app.telemetry.ProductEvent
import com.superalarm.app.telemetry.SuperAlarmTelemetry
import com.superalarm.app.telemetry.Telemetry
import com.superalarm.app.telemetry.TelemetryEventName
import com.superalarm.app.telemetry.TelemetryFailureOperation
import com.superalarm.app.telemetry.TelemetryOutcome
import com.superalarm.app.telemetry.TelemetrySource
import com.superalarm.app.widget.SuperAlarmWidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

class SuperAlarmStore(
  private val context: Context,
  private val recoverInterruptedRuntime: Boolean = true,
  private val telemetry: Telemetry = SuperAlarmTelemetry,
) {
  private val prefs = context.getSharedPreferences("super_alarm_native_state", Context.MODE_PRIVATE)
  private val _state = MutableStateFlow(loadState())
  val state: StateFlow<AppState> = _state.asStateFlow()
  val analyticsEnabled: StateFlow<Boolean> = telemetry.analyticsEnabled

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

  fun completeOnboarding() {
    val completedStep = _state.value.onboardingStep
    update {
      it.copy(
        alarm = it.alarm.copy(enabled = true).normalized(),
        onboardingComplete = true,
        onboardingStep = OnboardingSteps.Ready,
      )
    }
    telemetry.capture(ProductEvent(TelemetryEventName.OnboardingStepCompleted, onboardingStep = completedStep, source = TelemetrySource.Onboarding))
    telemetry.capture(ProductEvent(TelemetryEventName.OnboardingCompleted, source = TelemetrySource.Onboarding))
  }

  fun skipOnboarding() {
    val skippedAtStep = _state.value.onboardingStep
    update { it.copy(onboardingComplete = true) }
    telemetry.capture(ProductEvent(TelemetryEventName.OnboardingSkipped, onboardingStep = skippedAtStep, source = TelemetrySource.Onboarding))
  }

  fun restartOnboarding() {
    update { it.copy(onboardingComplete = false, onboardingStep = OnboardingSteps.Welcome) }
    telemetry.capture(ProductEvent(TelemetryEventName.OnboardingRestarted, source = TelemetrySource.App))
  }

  fun setAnalyticsEnabled(enabled: Boolean) = telemetry.setAnalyticsEnabled(enabled)

  fun setOnboardingStep(step: Int) {
    val previousStep = _state.value.onboardingStep
    val nextStep = step.coerceIn(OnboardingSteps.Welcome, OnboardingSteps.Last)
    update { it.copy(onboardingStep = nextStep) }
    if (nextStep > previousStep) {
      telemetry.capture(ProductEvent(
        TelemetryEventName.OnboardingStepCompleted,
        onboardingStep = previousStep,
        source = TelemetrySource.Onboarding,
      ))
    }
  }

  fun setTime(minutes: Int) {
    mutateAlarm { it.copy(timeMinutesLocal = minutes.coerceIn(0, 1439)) }
    telemetry.capture(ProductEvent(TelemetryEventName.AlarmTimeChanged, source = TelemetrySource.App))
  }

  fun toggleDay(day: Int) = mutateAlarm { alarm ->
    val next = if (alarm.repeatDays.contains(day)) alarm.repeatDays - day else (alarm.repeatDays + day).sorted()
    alarm.copy(repeatDays = next)
  }.also {
    telemetry.capture(ProductEvent(TelemetryEventName.AlarmRepeatChanged, count = _state.value.alarm.repeatDays.size, source = TelemetrySource.App))
  }

  fun setVibration(enabled: Boolean) = mutateAlarm { it.copy(vibrationEnabled = enabled) }

  fun setEnabled(enabled: Boolean): Boolean {
    if (enabled && !_state.value.alarm.hasDismissalTarget()) {
      addEvent("enable_blocked", "Add an allowed QR spot or object before enabling the alarm.")
      return false
    }
    mutateAlarm { it.copy(enabled = enabled) }
    telemetry.capture(ProductEvent(if (enabled) TelemetryEventName.AlarmEnabled else TelemetryEventName.AlarmDisabled, source = TelemetrySource.App))
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
    telemetry.capture(ProductEvent(TelemetryEventName.AlarmScheduled, source = TelemetrySource.App))
    return true
  }

  fun addLocation(label: String): QrLocation {
    val previousStep = _state.value.onboardingStep
    val source = if (_state.value.onboardingComplete) TelemetrySource.App else TelemetrySource.Onboarding
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
    if (source == TelemetrySource.Onboarding && _state.value.onboardingStep > previousStep) {
      telemetry.capture(ProductEvent(TelemetryEventName.OnboardingStepCompleted, onboardingStep = previousStep, source = source))
    }
    telemetry.capture(ProductEvent(TelemetryEventName.DismissalEnrollmentCompleted, targetType = DismissalTargetType.Qr, source = source))
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
    telemetry.capture(ProductEvent(TelemetryEventName.DismissalTargetDeleted, targetType = DismissalTargetType.Qr, source = TelemetrySource.App))
  }

  fun toggleAllowed(locationId: String) {
    update { state ->
      val current = state.alarm.allowedLocationIds
      val next = if (current.contains(locationId)) current - locationId else (current + locationId).distinct()
      val alarm = state.alarm.copy(allowedLocationIds = next)
      state.copy(alarm = alarm.copy(enabled = alarm.enabled && alarm.hasDismissalTarget()))
    }
    telemetry.capture(ProductEvent(
      TelemetryEventName.DismissalTargetAvailabilityChanged,
      targetType = DismissalTargetType.Qr,
      source = TelemetrySource.App,
      enabled = _state.value.alarm.allowedLocationIds.contains(locationId),
    ))
  }

  fun addObjectTarget(
    label: String,
    embeddings: List<String>,
    thumbnailJpeg: ByteArray?,
  ): ObjectTarget {
    require(embeddings.isNotEmpty())
    val previousStep = _state.value.onboardingStep
    val source = if (_state.value.onboardingComplete) TelemetrySource.App else TelemetrySource.Onboarding
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
    if (source == TelemetrySource.Onboarding && _state.value.onboardingStep > previousStep) {
      telemetry.capture(ProductEvent(TelemetryEventName.OnboardingStepCompleted, onboardingStep = previousStep, source = source))
    }
    telemetry.capture(ProductEvent(TelemetryEventName.DismissalEnrollmentCompleted, targetType = DismissalTargetType.Object, count = embeddings.size, source = source))
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
    telemetry.capture(ProductEvent(TelemetryEventName.DismissalEnrollmentCompleted, targetType = DismissalTargetType.Object, count = embeddings.size, source = TelemetrySource.App))
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
    telemetry.capture(ProductEvent(TelemetryEventName.DismissalTargetDeleted, targetType = DismissalTargetType.Object, source = TelemetrySource.App))
  }

  fun toggleObjectAllowed(targetId: String) {
    update { state ->
      val current = state.alarm.allowedObjectTargetIds
      val next = if (current.contains(targetId)) current - targetId else (current + targetId).distinct()
      val alarm = state.alarm.copy(allowedObjectTargetIds = next)
      state.copy(alarm = alarm.copy(enabled = alarm.enabled && alarm.hasDismissalTarget()))
    }
    telemetry.capture(ProductEvent(
      TelemetryEventName.DismissalTargetAvailabilityChanged,
      targetType = DismissalTargetType.Object,
      source = TelemetrySource.App,
      enabled = _state.value.alarm.allowedObjectTargetIds.contains(targetId),
    ))
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
    telemetry.capture(ProductEvent(TelemetryEventName.WakeUpTestStarted, source = TelemetrySource.App))
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
    if (!current.alarm.hasDismissalTarget()) {
      telemetry.capture(ProductEvent(TelemetryEventName.WakeUpTestFailed, source = TelemetrySource.Onboarding, outcome = TelemetryOutcome.Blocked, failureCode = "missing_target"))
      return false
    }

    val caps = capabilities()
    if (!caps.notificationsGranted || !caps.exactAlarmAllowed || !caps.fullscreenIntentAllowed) {
      addEvent("onboarding_test_blocked", "Prepare notification, exact alarm, and lock-screen access first.")
      telemetry.capture(ProductEvent(TelemetryEventName.WakeUpTestFailed, source = TelemetrySource.Onboarding, outcome = TelemetryOutcome.Blocked, failureCode = "permission_blocked"))
      return false
    }

    val alarm = current.alarm.copy(enabled = false).normalized()
    val triggerAtMillis = AlarmScheduler.scheduleTest(
      context = context,
      config = alarm.toStoredConfig(),
      delayMillis = delayMillis,
      previousTriggerAtMillis = current.onboardingTestScheduledAtMillis,
    ) ?: run {
      telemetry.capture(ProductEvent(TelemetryEventName.WakeUpTestFailed, source = TelemetrySource.Onboarding, outcome = TelemetryOutcome.Failed, failureCode = "schedule_failed"))
      return false
    }
    update {
      it.copy(
        alarm = alarm,
        onboardingStep = OnboardingSteps.WakeUpTest,
        onboardingTestScheduledAtMillis = triggerAtMillis,
      )
    }
    addEvent("onboarding_test_scheduled", "Wake-up test scheduled through the Android alarm system.")
    telemetry.capture(ProductEvent(TelemetryEventName.OnboardingStepCompleted, onboardingStep = current.onboardingStep, source = TelemetrySource.Onboarding))
    telemetry.capture(ProductEvent(TelemetryEventName.WakeUpTestScheduled, source = TelemetrySource.Onboarding))
    return true
  }

  fun dismissWithPayload(payload: String): Boolean {
    val state = _state.value
    val runtime = state.runtime ?: return false
    val validation = validateDismissalPayload(payload, state)
    val location = validation.location
    if (!validation.accepted || location == null) {
      telemetry.capture(ProductEvent(TelemetryEventName.DismissalAttempted, targetType = DismissalTargetType.Qr, outcome = TelemetryOutcome.Failed, source = TelemetrySource.App))
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
      telemetry.capture(ProductEvent(TelemetryEventName.DismissalAttempted, targetType = DismissalTargetType.Object, outcome = TelemetryOutcome.Failed, source = TelemetrySource.App))
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
      notificationsGranted = NotificationManagerCompat.from(context).areNotificationsEnabled() && notificationChannelReady(notificationManager),
      exactAlarmAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true,
      fullscreenIntentAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) notificationManager.canUseFullScreenIntent() else true,
      batteryOptimizationRelaxed = powerManager.isIgnoringBatteryOptimizations(context.packageName),
    )
  }

  fun notificationPermissionGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  fun cameraGranted(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

  fun objectThumbnailFile(target: ObjectTarget): File? =
    target.thumbnailFileName
      ?.let(::objectThumbnailFile)
      ?.takeIf(File::isFile)

  fun openExactAlarmSettings() {
    captureSettingsOpened("exact_alarm")
    openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
  }

  fun openNotificationSettings() {
    captureSettingsOpened("notifications")
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, AlarmConstants.NOTIFICATION_CHANNEL_ID)
      }
    } else {
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      }
    }
    openSettings(intent)
  }

  fun openDeviceSupportSettings() {
    captureSettingsOpened("device_support")
    val support = deviceSupportForManufacturer(Build.MANUFACTURER)
    support?.settingsComponents?.forEach { component ->
      if (tryOpenSettings(Intent().setComponent(ComponentName(component.packageName, component.className)))) {
        return
      }
    }
    openAppDetailsSettings()
  }

  fun openFullscreenSettings() {
    captureSettingsOpened("full_screen_alarm")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply { data = Uri.parse("package:${context.packageName}") })
    }
  }

  fun openBatterySettings() {
    captureSettingsOpened("battery_optimization")
    openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
  }

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
      put("manufacturer", Build.MANUFACTURER)
      put("model", Build.MODEL)
      put("sdkInt", Build.VERSION.SDK_INT)
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
    telemetry.capture(ProductEvent(
      name = TelemetryEventName.DismissalStarted,
      source = if (launch.kind == "test") TelemetrySource.Onboarding else TelemetrySource.System,
      count = launch.backupStage,
    ))
  }

  private fun completeDismissal(
    runtime: AlarmRuntimeState,
    targetId: String,
    targetLabel: String,
    method: String,
  ) {
    val dismissedAtInstant = Instant.now()
    val dismissedAt = dismissedAtInstant.toString()
    val durationSeconds = runCatching {
      Duration.between(Instant.parse(runtime.triggeredAt), dismissedAtInstant).seconds.toInt()
    }.getOrNull()
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
    telemetry.capture(ProductEvent(
      name = if (runtime.kind == "test") TelemetryEventName.WakeUpTestPassed else TelemetryEventName.AlarmDismissed,
      targetType = if (method == "object") DismissalTargetType.Object else DismissalTargetType.Qr,
      outcome = TelemetryOutcome.Success,
      source = TelemetrySource.App,
      durationSeconds = durationSeconds,
    ))
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
    telemetry.capture(ProductEvent(TelemetryEventName.RingingStoppedUnexpectedly, source = TelemetrySource.System))
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
    return runCatching { appStateFromJson(JSONObject(raw)) }.getOrElse { error ->
      telemetry.captureFailure(TelemetryFailureOperation.StateLoading, error)
      AppState()
    }
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
    }.getOrElse { error ->
      telemetry.captureFailure(TelemetryFailureOperation.ObjectThumbnailSaving, error)
      null
    }
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

  private fun captureSettingsOpened(permissionKind: String) {
    telemetry.capture(ProductEvent(
      TelemetryEventName.DeviceSettingsOpened,
      source = TelemetrySource.App,
      permissionKind = permissionKind,
    ))
  }

  private fun tryOpenSettings(intent: Intent): Boolean = try {
    openSettings(intent)
    true
  } catch (_: ActivityNotFoundException) {
    false
  } catch (_: SecurityException) {
    false
  }

  private fun openAppDetailsSettings() {
    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:${context.packageName}")
    })
  }

  private fun notificationChannelReady(notificationManager: NotificationManager): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
      (notificationManager.getNotificationChannel(AlarmConstants.NOTIFICATION_CHANNEL_ID)?.importance ?: NotificationManager.IMPORTANCE_NONE) >=
      NotificationManager.IMPORTANCE_HIGH

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
