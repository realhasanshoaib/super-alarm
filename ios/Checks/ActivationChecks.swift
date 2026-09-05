import Foundation

@main
enum ActivationChecks {
    static func main() throws {
        try checkLegacyDecodingAndResume()
        checkCompletedUserMigration()
        checkReadiness()
        print("Activation checks passed")
    }

    private static func checkLegacyDecodingAndResume() throws {
        precondition(OnboardingStep.welcome.rawValue == 0)
        precondition(OnboardingStep.dismissalMethod.rawValue == 1)
        precondition(OnboardingStep.alarm.rawValue == 2)
        precondition(OnboardingStep.phoneSetup.rawValue == 3)
        precondition(OnboardingStep.wakeUpTest.rawValue == 4)
        precondition(OnboardingStep.ready.rawValue == 5)

        let decoded = try JSONDecoder().decode(SuperAlarmState.self, from: Data("{}".utf8))
        precondition(decoded.onboardingStep == .welcome)
        precondition(decoded.migrationVersion == 1)

        var skipped = decoded
        skipped.hasCompletedOnboarding = true
        skipped.onboardingStep = .phoneSetup
        ActivationStateMigration.migrate(&skipped)
        precondition(!skipped.hasCompletedOnboarding)
        precondition(skipped.hasDeferredOnboarding)
        precondition(skipped.onboardingStep == .dismissalMethod)
        precondition(skipped.migrationVersion == ActivationStateMigration.currentVersion)

        var staleTarget = decoded
        staleTarget.alarm.allowedObjectTargetIds = [UUID()]
        ActivationStateMigration.migrate(&staleTarget)
        precondition(staleTarget.onboardingStep == .dismissalMethod)
    }

    private static func checkCompletedUserMigration() {
        var completed = SuperAlarmState()
        completed.migrationVersion = 1
        completed.hasCompletedOnboarding = true
        completed.onboardingStep = .ready
        completed.alarm.isEnabled = false
        ActivationStateMigration.migrate(&completed)
        precondition(completed.hasCompletedOnboarding)
        precondition(!completed.hasDeferredOnboarding)
        precondition(completed.onboardingStep == .ready)
    }

    private static func checkReadiness() {
        let missingTarget = ActivationReadiness(
            hasDismissalTarget: false,
            hasRepeatDay: true,
            notificationsAuthorized: true,
            notificationAlertsEnabled: true,
            notificationSoundsEnabled: true
        )
        precondition(missingTarget.missingRequirements == [.dismissalTarget])
        precondition(missingTarget.resumeStep == .dismissalMethod)

        let missingPermissions = ActivationReadiness(
            hasDismissalTarget: true,
            hasRepeatDay: true,
            notificationsAuthorized: false,
            notificationAlertsEnabled: false,
            notificationSoundsEnabled: false
        )
        precondition(missingPermissions.missingRequirements == [.notificationAuthorization])
        precondition(missingPermissions.resumeStep == .phoneSetup)

        let ready = ActivationReadiness(
            hasDismissalTarget: true,
            hasRepeatDay: true,
            notificationsAuthorized: true,
            notificationAlertsEnabled: true,
            notificationSoundsEnabled: true
        )
        precondition(ready.prerequisitesReady)
    }
}
