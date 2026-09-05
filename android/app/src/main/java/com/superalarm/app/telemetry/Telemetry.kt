package com.superalarm.app.telemetry

import android.content.Context
import com.posthog.PersonProfiles
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.superalarm.app.BuildConfig
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.exception.SentryHttpClientException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TelemetryEventName(val wireName: String) {
  AppOpened("app_opened"),
  SurfaceViewed("surface_viewed"),
  OnboardingStarted("onboarding_started"),
  OnboardingStepViewed("onboarding_step_viewed"),
  OnboardingStepCompleted("onboarding_step_completed"),
  OnboardingSkipOpened("onboarding_skip_opened"),
  OnboardingSkipCancelled("onboarding_skip_cancelled"),
  OnboardingSkipped("onboarding_skipped"),
  OnboardingCompleted("onboarding_completed"),
  OnboardingRestarted("onboarding_restarted"),
  DismissalTargetReady("dismissal_target_ready"),
  AlarmPermissionsReady("alarm_permissions_ready"),
  PhoneSetupBlocked("phone_setup_blocked"),
  PermissionActionSelected("permission_action_selected"),
  PermissionResultObserved("permission_result_observed"),
  DeviceSettingsOpened("device_settings_opened"),
  DismissalEnrollmentStarted("dismissal_enrollment_started"),
  DismissalEnrollmentCompleted("dismissal_enrollment_completed"),
  DismissalEnrollmentCancelled("dismissal_enrollment_cancelled"),
  DismissalEnrollmentFailed("dismissal_enrollment_failed"),
  DismissalTargetDeleted("dismissal_target_deleted"),
  DismissalTargetAvailabilityChanged("dismissal_target_availability_changed"),
  AlarmTimeChanged("alarm_time_changed"),
  AlarmRepeatChanged("alarm_repeat_changed"),
  AlarmEnabled("alarm_enabled"),
  AlarmDisabled("alarm_disabled"),
  AlarmScheduled("alarm_scheduled"),
  AlarmScheduleFailed("alarm_schedule_failed"),
  AlarmFired("alarm_fired"),
  RingingStarted("ringing_started"),
  BackupRingFired("backup_ring_fired"),
  DismissalStarted("dismissal_started"),
  DismissalAttempted("dismissal_attempted"),
  AlarmDismissed("alarm_dismissed"),
  AlarmMissed("alarm_missed"),
  RingingStoppedUnexpectedly("ringing_stopped_unexpectedly"),
  WakeUpTestScheduled("wake_up_test_scheduled"),
  WakeUpTestStarted("wake_up_test_started"),
  WakeUpTestPassed("wake_up_test_passed"),
  WakeUpTestFailed("wake_up_test_failed"),
  HealthCheckViewed("health_check_viewed"),
  DiagnosticsExported("diagnostics_exported"),
  AnalyticsPreferenceChanged("analytics_preference_changed"),
  WidgetViewed("widget_viewed"),
  WidgetActionSelected("widget_action_selected"),
  WidgetActionCompleted("widget_action_completed"),
  WidgetActionFailed("widget_action_failed"),
}

enum class TelemetrySurface(val wireName: String) {
  OnboardingWelcome("onboarding_welcome"),
  OnboardingDismissalMethod("onboarding_dismissal_method"),
  OnboardingAlarm("onboarding_alarm"),
  OnboardingPhoneSetup("onboarding_phone_setup"),
  OnboardingWakeUpTest("onboarding_wake_up_test"),
  OnboardingReady("onboarding_ready"),
  Alarm("alarm"),
  DismissalTargets("dismissal_targets"),
  Health("health"),
  ObjectEnrollment("object_enrollment"),
  ObjectDismissal("object_dismissal"),
  QrDismissal("qr_dismissal"),
  Ringing("ringing"),
}

enum class DismissalTargetType(val wireName: String) { Object("object"), Qr("qr") }

enum class AlarmKind(val wireName: String) {
  Live("live"),
  Test("test");

  companion object {
    fun fromWireName(value: String): AlarmKind = entries.firstOrNull { it.wireName == value } ?: Live
  }
}

enum class TelemetryPermissionKind(val wireName: String) {
  Camera("camera"),
  Notifications("notifications"),
  ExactAlarm("exact_alarm"),
  FullScreenAlarm("full_screen_alarm"),
  BatteryOptimization("battery_optimization"),
  DeviceSupport("device_support"),
}

enum class TelemetryFailureCode(val wireName: String) {
  MissingTarget("missing_target"),
  MissingRepeatDays("missing_repeat_days"),
  NotificationsDenied("notifications_denied"),
  ExactAlarmDenied("exact_alarm_denied"),
  FullScreenAlarmDenied("full_screen_alarm_denied"),
  ScheduleFailed("schedule_failed"),
  MissingDismissalTarget("missing_dismissal_target"),
}

enum class TelemetrySource(val wireName: String) { App("app"), Onboarding("onboarding"), Widget("widget"), Notification("notification"), System("system") }

enum class TelemetryOutcome(val wireName: String) { Success("success"), Failed("failed"), Blocked("blocked"), Cancelled("cancelled"), Allowed("allowed"), Denied("denied") }

enum class TelemetryFailureOperation(val wireName: String) {
  AlarmScheduling("alarm_scheduling"),
  StateLoading("state_loading"),
  StateSaving("state_saving"),
  ObjectThumbnailSaving("object_thumbnail_saving"),
  SettingsOpening("settings_opening"),
  NotificationAuthorization("notification_authorization"),
  WidgetUpdate("widget_update"),
}

data class ProductEvent(
  val name: TelemetryEventName,
  val surface: TelemetrySurface? = null,
  val onboardingStep: Int? = null,
  val alarmKind: AlarmKind? = null,
  val targetType: DismissalTargetType? = null,
  val source: TelemetrySource? = null,
  val outcome: TelemetryOutcome? = null,
  val permissionKind: TelemetryPermissionKind? = null,
  val failureCode: TelemetryFailureCode? = null,
  val count: Int? = null,
  val durationSeconds: Int? = null,
  val enabled: Boolean? = null,
)

interface Telemetry {
  val analyticsEnabled: StateFlow<Boolean>

  fun initialize(context: Context)
  fun capture(event: ProductEvent)
  fun captureFailure(operation: TelemetryFailureOperation, throwable: Throwable)
  fun setAnalyticsEnabled(enabled: Boolean)
}

object SuperAlarmTelemetry : Telemetry {
  private const val POSTHOG_PROJECT_TOKEN = "phc_leuu8NB2QFACooXz55aQ1BU110r9vEp4Z8cExvMxAs0"
  private const val POSTHOG_HOST = "https://us.i.posthog.com"
  private const val SENTRY_DSN = "https://66612605d6684d75f049efeaf49dc01e@o4509774141063168.ingest.de.sentry.io/4511982994587728"
  private const val PREFERENCES_NAME = "super_alarm_telemetry"
  private const val ANALYTICS_ENABLED_KEY = "analytics_enabled"

  private val mutableAnalyticsEnabled = MutableStateFlow(true)
  override val analyticsEnabled: StateFlow<Boolean> = mutableAnalyticsEnabled.asStateFlow()
  private var preferences: android.content.SharedPreferences? = null
  private var initialized = false

  override fun initialize(context: Context) {
    if (initialized) return
    preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    mutableAnalyticsEnabled.value = preferences?.getBoolean(ANALYTICS_ENABLED_KEY, true) ?: true

    SentryAndroid.init(context) { options ->
      options.dsn = SENTRY_DSN
      options.environment = if (BuildConfig.DEBUG) "development" else "production"
      options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
      options.isSendDefaultPii = false
      options.isAttachScreenshot = false
      options.isAttachViewHierarchy = false
      options.isEnableUserInteractionBreadcrumbs = false
      options.tracesSampleRate = if (BuildConfig.DEBUG) 1.0 else 0.2
      options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
        val isTelemetryTransportFailure =
          event.throwable is SentryHttpClientException ||
            event.exceptions?.any { it.type == SentryHttpClientException::class.java.simpleName } == true
        if (isTelemetryTransportFailure) {
          null
        } else {
          event.request?.apply {
            data = null
            cookies = null
            headers = null
            queryString = null
            fragment = null
          }
          event
        }
      }
    }

    val config = PostHogAndroidConfig(POSTHOG_PROJECT_TOKEN, POSTHOG_HOST).apply {
      captureApplicationLifecycleEvents = true
      captureScreenViews = false
      personProfiles = PersonProfiles.NEVER
      sessionReplay = false
      errorTrackingConfig.autoCapture = false
      optOut = !mutableAnalyticsEnabled.value
    }
    PostHogAndroid.setup(context, config)
    initialized = true
  }

  override fun capture(event: ProductEvent) {
    if (!initialized || !mutableAnalyticsEnabled.value) return
    val properties = buildMap<String, Any> {
      put("platform", "android")
      put("app_version", BuildConfig.VERSION_NAME)
      put("build_number", BuildConfig.VERSION_CODE)
      put("environment", if (BuildConfig.DEBUG) "development" else "production")
      event.surface?.let { put("surface", it.wireName) }
      event.onboardingStep?.let { put("onboarding_step", it.coerceIn(0, 5)) }
      event.alarmKind?.let { put("alarm_kind", it.wireName) }
      event.targetType?.let { put("target_type", it.wireName) }
      event.source?.let { put("source", it.wireName) }
      event.outcome?.let { put("outcome", it.wireName) }
      event.permissionKind?.let { put("permission_kind", it.wireName) }
      event.failureCode?.let { put("failure_code", it.wireName) }
      event.count?.let { put("count", it.coerceIn(0, 100)) }
      event.durationSeconds?.let { put("duration_seconds", it.coerceIn(0, 86_400)) }
      event.enabled?.let { put("enabled", it) }
    }
    PostHog.capture(event.name.wireName, properties = properties)
    Sentry.addBreadcrumb(Breadcrumb().apply {
      category = "product"
      message = event.name.wireName
      data["surface"] = event.surface?.wireName ?: "none"
    })
  }

  override fun captureFailure(operation: TelemetryFailureOperation, throwable: Throwable) {
    if (!initialized) return
    Sentry.captureException(throwable) { scope ->
      scope.setTag("operation", operation.wireName)
    }
  }

  override fun setAnalyticsEnabled(enabled: Boolean) {
    if (enabled == mutableAnalyticsEnabled.value) return
    if (initialized) {
      if (enabled) {
        PostHog.optIn()
        mutableAnalyticsEnabled.value = true
        capture(ProductEvent(TelemetryEventName.AnalyticsPreferenceChanged, enabled = true))
      } else {
        capture(ProductEvent(TelemetryEventName.AnalyticsPreferenceChanged, enabled = false))
        PostHog.optOut()
      }
    }
    mutableAnalyticsEnabled.value = enabled
    preferences?.edit()?.putBoolean(ANALYTICS_ENABLED_KEY, enabled)?.apply()
  }
}
