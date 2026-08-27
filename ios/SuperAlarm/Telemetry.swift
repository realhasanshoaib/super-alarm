import Foundation
import PostHog
import Sentry

enum TelemetryEventName: String {
    case appOpened = "app_opened"
    case surfaceViewed = "surface_viewed"
    case onboardingStarted = "onboarding_started"
    case onboardingStepViewed = "onboarding_step_viewed"
    case onboardingStepCompleted = "onboarding_step_completed"
    case onboardingSkipOpened = "onboarding_skip_opened"
    case onboardingSkipCancelled = "onboarding_skip_cancelled"
    case onboardingSkipped = "onboarding_skipped"
    case onboardingCompleted = "onboarding_completed"
    case onboardingRestarted = "onboarding_restarted"
    case permissionActionSelected = "permission_action_selected"
    case permissionResultObserved = "permission_result_observed"
    case deviceSettingsOpened = "device_settings_opened"
    case dismissalEnrollmentStarted = "dismissal_enrollment_started"
    case dismissalEnrollmentCompleted = "dismissal_enrollment_completed"
    case dismissalEnrollmentCancelled = "dismissal_enrollment_cancelled"
    case dismissalEnrollmentFailed = "dismissal_enrollment_failed"
    case dismissalTargetDeleted = "dismissal_target_deleted"
    case dismissalTargetAvailabilityChanged = "dismissal_target_availability_changed"
    case alarmTimeChanged = "alarm_time_changed"
    case alarmRepeatChanged = "alarm_repeat_changed"
    case alarmEnabled = "alarm_enabled"
    case alarmDisabled = "alarm_disabled"
    case alarmScheduled = "alarm_scheduled"
    case alarmScheduleFailed = "alarm_schedule_failed"
    case alarmFired = "alarm_fired"
    case ringingStarted = "ringing_started"
    case dismissalStarted = "dismissal_started"
    case dismissalAttempted = "dismissal_attempted"
    case alarmDismissed = "alarm_dismissed"
    case ringingStoppedUnexpectedly = "ringing_stopped_unexpectedly"
    case wakeUpTestScheduled = "wake_up_test_scheduled"
    case wakeUpTestStarted = "wake_up_test_started"
    case wakeUpTestPassed = "wake_up_test_passed"
    case wakeUpTestFailed = "wake_up_test_failed"
    case healthCheckViewed = "health_check_viewed"
    case diagnosticsExported = "diagnostics_exported"
    case analyticsPreferenceChanged = "analytics_preference_changed"
}

enum TelemetrySurface: String {
    case onboardingWelcome = "onboarding_welcome"
    case onboardingDismissalMethod = "onboarding_dismissal_method"
    case onboardingAlarm = "onboarding_alarm"
    case onboardingPhoneSetup = "onboarding_phone_setup"
    case onboardingWakeUpTest = "onboarding_wake_up_test"
    case onboardingReady = "onboarding_ready"
    case alarm = "alarm"
    case settings = "settings"
    case objectEnrollment = "object_enrollment"
    case objectDismissal = "object_dismissal"
    case qrDismissal = "qr_dismissal"
    case ringing = "ringing"
}

enum DismissalTargetType: String { case object, qr }
enum TelemetrySource: String { case app, onboarding, widget, notification, system }
enum TelemetryOutcome: String { case success, failed, blocked, cancelled, allowed, denied }

enum TelemetryFailureOperation: String {
    case alarmScheduling = "alarm_scheduling"
    case stateLoading = "state_loading"
    case stateSaving = "state_saving"
    case notificationAuthorization = "notification_authorization"
    case objectEnrollment = "object_enrollment"
    case diagnosticsExport = "diagnostics_export"
}

struct ProductEvent {
    let name: TelemetryEventName
    var surface: TelemetrySurface?
    var onboardingStep: Int?
    var targetType: DismissalTargetType?
    var source: TelemetrySource?
    var outcome: TelemetryOutcome?
    var permissionKind: String?
    var failureCode: String?
    var count: Int?
    var durationSeconds: Int?
    var enabled: Bool?

    init(
        _ name: TelemetryEventName,
        surface: TelemetrySurface? = nil,
        onboardingStep: Int? = nil,
        targetType: DismissalTargetType? = nil,
        source: TelemetrySource? = nil,
        outcome: TelemetryOutcome? = nil,
        permissionKind: String? = nil,
        failureCode: String? = nil,
        count: Int? = nil,
        durationSeconds: Int? = nil,
        enabled: Bool? = nil
    ) {
        self.name = name
        self.surface = surface
        self.onboardingStep = onboardingStep
        self.targetType = targetType
        self.source = source
        self.outcome = outcome
        self.permissionKind = permissionKind
        self.failureCode = failureCode
        self.count = count
        self.durationSeconds = durationSeconds
        self.enabled = enabled
    }
}

protocol TelemetryClient {
    var analyticsEnabled: Bool { get }
    func initialize()
    func capture(_ event: ProductEvent)
    func captureFailure(_ operation: TelemetryFailureOperation, error: Error)
    func setAnalyticsEnabled(_ enabled: Bool)
}

final class LiveTelemetryClient: TelemetryClient {
    static let shared = LiveTelemetryClient()

    private let postHogProjectToken = "phc_leuu8NB2QFACooXz55aQ1BU110r9vEp4Z8cExvMxAs0"
    private let postHogHost = "https://us.i.posthog.com"
    private let sentryDsn = "https://90670ef6c0238ef2a314ed9e77b6207d@o4509774141063168.ingest.de.sentry.io/4511982995701840"
    private let analyticsPreferenceKey = "analytics_enabled"
    private var initialized = false

    var analyticsEnabled: Bool {
        guard UserDefaults.standard.object(forKey: analyticsPreferenceKey) != nil else { return true }
        return UserDefaults.standard.bool(forKey: analyticsPreferenceKey)
    }

    func initialize() {
        guard !initialized else { return }
        SentrySDK.start { [self] options in
            options.dsn = sentryDsn
            options.environment = Self.environment
            options.releaseName = Self.releaseName
            options.sendDefaultPii = false
            options.attachScreenshot = false
            options.attachViewHierarchy = false
            options.reportAccessibilityIdentifier = false
            options.tracesSampleRate = Self.environment == "development" ? 1.0 : 0.2
            options.beforeSend = { event in
                event.request?.cookies = nil
                event.request?.headers = nil
                event.request?.queryString = nil
                event.request?.fragment = nil
                return event
            }
        }

        let config = PostHogConfig(projectToken: postHogProjectToken, host: postHogHost)
        config.captureApplicationLifecycleEvents = true
        config.captureScreenViews = false
        config.captureElementInteractions = false
        config.personProfiles = .never
        config.sessionReplay = false
        config.optOut = !analyticsEnabled
        PostHogSDK.shared.setup(config)
        initialized = true
    }

    func capture(_ event: ProductEvent) {
        guard initialized, analyticsEnabled else { return }
        var properties: [String: Any] = [
            "platform": "ios",
            "app_version": Self.appVersion,
            "build_number": Self.buildNumber,
            "environment": Self.environment,
        ]
        if let surface = event.surface { properties["surface"] = surface.rawValue }
        if let onboardingStep = event.onboardingStep { properties["onboarding_step"] = min(max(onboardingStep, 0), 5) }
        if let targetType = event.targetType { properties["target_type"] = targetType.rawValue }
        if let source = event.source { properties["source"] = source.rawValue }
        if let outcome = event.outcome { properties["outcome"] = outcome.rawValue }
        if let permissionKind = event.permissionKind { properties["permission_kind"] = permissionKind }
        if let failureCode = event.failureCode { properties["failure_code"] = failureCode }
        if let count = event.count { properties["count"] = min(max(count, 0), 100) }
        if let durationSeconds = event.durationSeconds { properties["duration_seconds"] = min(max(durationSeconds, 0), 86_400) }
        if let enabled = event.enabled { properties["enabled"] = enabled }
        PostHogSDK.shared.capture(event.name.rawValue, properties: properties)
        let breadcrumb = Breadcrumb(level: .info, category: "product")
        breadcrumb.message = event.name.rawValue
        breadcrumb.setData(value: event.surface?.rawValue ?? "none", key: "surface")
        SentrySDK.addBreadcrumb(breadcrumb)
    }

    func captureFailure(_ operation: TelemetryFailureOperation, error: Error) {
        guard initialized else { return }
        SentrySDK.capture(error: error) { scope in
            scope.setTag(value: operation.rawValue, key: "operation")
        }
    }

    func setAnalyticsEnabled(_ enabled: Bool) {
        guard enabled != analyticsEnabled else { return }
        guard initialized else { return }
        if enabled {
            PostHogSDK.shared.optIn()
            UserDefaults.standard.set(true, forKey: analyticsPreferenceKey)
            capture(ProductEvent(.analyticsPreferenceChanged, enabled: true))
        } else {
            capture(ProductEvent(.analyticsPreferenceChanged, enabled: false))
            PostHogSDK.shared.optOut()
            UserDefaults.standard.set(false, forKey: analyticsPreferenceKey)
        }
    }

    private static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    }

    private static var buildNumber: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"
    }

    private static var environment: String {
        #if DEBUG
        "development"
        #else
        "production"
        #endif
    }

    private static var releaseName: String {
        "com.superalarm.app@\(appVersion)+\(buildNumber)"
    }
}
