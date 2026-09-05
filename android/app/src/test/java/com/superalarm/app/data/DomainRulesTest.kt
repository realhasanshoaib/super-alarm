package com.superalarm.app.data

import com.superalarm.app.features.objectscan.ObjectEmbeddings
import com.superalarm.app.features.objectscan.ObjectMatchTracker
import com.superalarm.app.widget.formatWidgetAlarmStatus
import com.superalarm.app.widget.formatWidgetAlarmTime
import com.superalarm.app.widget.formatWidgetRepeatDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DomainRulesTest {
  @Test
  fun legacySkipBecomesDismissedButIncompleteWithoutRenumberingProgress() {
    val flags = migratedOnboardingFlags(
      storedComplete = true,
      storedDismissed = null,
      onboardingStep = OnboardingSteps.Alarm,
    )

    assertFalse(flags.complete)
    assertTrue(flags.dismissed)
    assertEquals(2, OnboardingSteps.Alarm)
  }

  @Test
  fun completedUserStaysCompleteWhenAlarmWasLaterDisabled() {
    val flags = migratedOnboardingFlags(
      storedComplete = true,
      storedDismissed = null,
      onboardingStep = OnboardingSteps.Ready,
    )

    assertTrue(flags.complete)
    assertTrue(flags.dismissed)
  }

  @Test
  fun onboardingResumesAtTheFirstUnfinishedActivationStep() {
    val permissionsReady = ReliabilityChecklistState(
      notificationsGranted = true,
      exactAlarmAllowed = true,
      fullscreenIntentAllowed = true,
    )
    val fresh = AppState(onboardingStep = OnboardingSteps.Welcome)
    val target = QrLocation(id = "mirror", label = "Mirror")
    val targetReady = AppState(
      alarm = AlarmConfig(allowedLocationIds = listOf(target.id)),
      qrLocations = listOf(target),
      onboardingStep = OnboardingSteps.Alarm,
    )
    val phoneSetup = targetReady.copy(onboardingStep = OnboardingSteps.WakeUpTest)

    assertEquals(OnboardingSteps.DismissalMethod, fresh.resumeOnboardingStep(permissionsReady))
    assertEquals(OnboardingSteps.Alarm, targetReady.resumeOnboardingStep(permissionsReady))
    assertEquals(OnboardingSteps.PhoneSetup, phoneSetup.resumeOnboardingStep(permissionsReady))
  }

  @Test
  fun backToDismissalSelectionPreservesTheChosenStepWithAnExistingTarget() {
    val target = QrLocation(id = "mirror", label = "Mirror")
    val state = AppState(
      alarm = AlarmConfig(allowedLocationIds = listOf(target.id)),
      qrLocations = listOf(target),
      onboardingStep = OnboardingSteps.DismissalMethod,
    )

    assertEquals(
      OnboardingSteps.DismissalMethod,
      state.resumeOnboardingStep(ReliabilityChecklistState()),
    )
  }

  @Test
  fun readinessRejectsDanglingTargetsAndNamesMissingPhoneSetup() {
    val state = AppState(alarm = AlarmConfig(allowedLocationIds = listOf("deleted")))
    val readiness = state.activationReadiness(ReliabilityChecklistState(notificationsGranted = false))

    assertEquals(
      listOf(ActivationStep.DismissalTarget, ActivationStep.PhonePermissions),
      readiness.missingSteps,
    )
    assertFalse(readiness.ready)
  }

  @Test
  fun onboardingTestPassRequiresScheduledTargetDismissal() {
    val scheduledAt = Instant.parse("2026-07-30T12:00:00Z").toEpochMilli()
    val dismissed = AlarmAttempt(
      id = "attempt-1",
      alarmId = "morning-alarm",
      kind = "test",
      triggeredAt = "2026-07-30T12:00:05Z",
      result = "dismissed",
      dismissalMethod = "object",
    )
    val stopped = dismissed.copy(id = "attempt-2", result = "interrupted", dismissalMethod = null)

    assertTrue(AppState(attempts = listOf(dismissed), onboardingTestScheduledAtMillis = scheduledAt).onboardingTestPassed())
    assertFalse(AppState(attempts = listOf(stopped), onboardingTestScheduledAtMillis = scheduledAt).onboardingTestPassed())
  }

  @Test
  fun widgetFormatsAlarmTimeForTwelveAndTwentyFourHourClocks() {
    assertEquals("5:05 AM", formatWidgetAlarmTime(5 * 60 + 5, use24HourTime = false))
    assertEquals("05:05", formatWidgetAlarmTime(5 * 60 + 5, use24HourTime = true))
  }

  @Test
  fun widgetFormatsTomorrowAlarmAndRepeatDaysReadably() {
    val zone = ZoneId.of("Asia/Karachi")
    val now = ZonedDateTime.of(2026, 7, 30, 20, 0, 0, 0, zone)
    val trigger = ZonedDateTime.of(2026, 7, 31, 5, 0, 0, 0, zone)

    assertEquals(
      "Tomorrow · in 9h",
      formatWidgetAlarmStatus(true, trigger.toInstant().toEpochMilli(), now.toInstant().toEpochMilli(), zone),
    )
    assertEquals(
      "No repeat days · tap to set",
      formatWidgetAlarmStatus(true, 0L, now.toInstant().toEpochMilli(), zone, hasRepeatDays = false),
    )
    assertEquals("Weekdays", formatWidgetRepeatDays(listOf(1, 2, 3, 4, 5)))
    assertEquals("Every day", formatWidgetRepeatDays((0..6).toList()))
  }

  @Test
  fun enabledAlarmRequiresAllowedQrSpot() {
    val alarm = AlarmConfig(enabled = true, allowedLocationIds = emptyList()).normalized()
    assertFalse(alarm.enabled)
  }

  @Test
  fun enabledAlarmAcceptsAllowedObject() {
    val alarm = AlarmConfig(enabled = true, allowedObjectTargetIds = listOf("fridge")).normalized()
    assertTrue(alarm.enabled)
  }

  @Test
  fun objectEmbeddingsRoundTripThroughStorageEncoding() {
    val embedding = byteArrayOf(1, 2, 3, -4)
    assertTrue(embedding.contentEquals(ObjectEmbeddings.decode(ObjectEmbeddings.encode(embedding))))
  }

  @Test
  fun objectMatchRequiresConsecutiveConfidentFrames() {
    val reference = ObjectEmbeddings.encode(byteArrayOf(10, 20, 30, 40))
    val target = ObjectTarget(id = "fridge", label = "Fridge", embeddings = listOf(reference))
    val tracker = ObjectMatchTracker(listOf(target), threshold = 0.9, requiredFrames = 3)

    assertFalse(tracker.accept(reference, frameAt = 100).accepted)
    assertFalse(tracker.accept(reference, frameAt = 300).accepted)
    val accepted = tracker.accept(reference, frameAt = 500)

    assertTrue(accepted.accepted)
    assertEquals("fridge", accepted.targetId)
    assertEquals(1.0, accepted.similarity, 0.0001)
  }

  @Test
  fun objectMatchResetsAfterLongFrameGap() {
    val reference = ObjectEmbeddings.encode(byteArrayOf(10, 20, 30, 40))
    val target = ObjectTarget(id = "fridge", label = "Fridge", embeddings = listOf(reference))
    val tracker = ObjectMatchTracker(listOf(target), threshold = 0.9, requiredFrames = 2)

    assertFalse(tracker.accept(reference, frameAt = 100).accepted)
    assertFalse(tracker.accept(reference, frameAt = 2_000).accepted)
  }

  @Test
  fun objectMatchToleratesOneMissInRollingWindow() {
    val reference = ObjectEmbeddings.encode(byteArrayOf(100, 20, 5))
    val mismatch = ObjectEmbeddings.encode(byteArrayOf(5, 100, 20))
    val target = ObjectTarget(id = "fridge", label = "Fridge", embeddings = listOf(reference))
    val tracker = ObjectMatchTracker(listOf(target), threshold = 0.9)

    assertFalse(tracker.accept(reference, frameAt = 100).accepted)
    assertFalse(tracker.accept(mismatch, frameAt = 200).accepted)
    assertTrue(tracker.accept(reference, frameAt = 300).accepted)
  }

  @Test
  fun objectMatchRequiresMoreFramesAtFallbackConfidence() {
    val reference = ObjectEmbeddings.encode(byteArrayOf(100, 0, 0))
    val lowerConfidence = ObjectEmbeddings.encode(byteArrayOf(72, 69, 0))
    val target = ObjectTarget(id = "fridge", label = "Fridge", embeddings = listOf(reference))
    val tracker = ObjectMatchTracker(
      targets = listOf(target),
      threshold = 0.9,
      requiredFrames = 2,
      fallbackThreshold = 0.7,
      fallbackRequiredFrames = 4,
    )

    assertFalse(tracker.accept(lowerConfidence, frameAt = 100).accepted)
    assertFalse(tracker.accept(lowerConfidence, frameAt = 300).accepted)
    assertFalse(tracker.accept(lowerConfidence, frameAt = 500).accepted)
    assertTrue(tracker.accept(lowerConfidence, frameAt = 700).accepted)
  }

  @Test
  fun objectMatchRejectsFramesBelowFallbackConfidence() {
    val reference = ObjectEmbeddings.encode(byteArrayOf(100, 0, 0))
    val mismatch = ObjectEmbeddings.encode(byteArrayOf(0, 100, 0))
    val target = ObjectTarget(id = "fridge", label = "Fridge", embeddings = listOf(reference))
    val tracker = ObjectMatchTracker(
      targets = listOf(target),
      fallbackThreshold = 0.6,
      fallbackRequiredFrames = 4,
    )

    repeat(5) { index ->
      assertFalse(tracker.accept(mismatch, frameAt = index * 200L).accepted)
    }
  }

  @Test
  fun diverseEnrollmentSelectionKeepsRequestedCount() {
    val embeddings = listOf(
      byteArrayOf(100, 0, 0),
      byteArrayOf(99, 1, 0),
      byteArrayOf(0, 100, 0),
      byteArrayOf(0, 99, 1),
      byteArrayOf(0, 0, 100),
      byteArrayOf(1, 0, 99),
    ).map(ObjectEmbeddings::encode)

    val selected = ObjectEmbeddings.selectDiverse(embeddings, count = 3)

    assertEquals(3, selected.size)
    assertTrue(selected.contains(embeddings.first()))
    assertTrue(selected.contains(embeddings[2]))
    assertTrue(selected.contains(embeddings[4]))
  }

  @Test
  fun removingLastAllowedSpotDisablesAlarmShape() {
    val alarm = AlarmConfig(enabled = true, allowedLocationIds = listOf("spot-1"))
    val next = alarm.copy(allowedLocationIds = emptyList(), enabled = alarm.enabled && false).normalized()
    assertFalse(next.enabled)
  }

  @Test
  fun qrDismissalRequiresEnrolledAndAllowedPayload() {
    val allowed = QrLocation(id = "allowed", label = "Mirror", payload = "superalarm://alarm-qr/allowed")
    val blocked = QrLocation(id = "blocked", label = "Desk", payload = "superalarm://alarm-qr/blocked")
    val state = AppState(
      alarm = AlarmConfig(allowedLocationIds = listOf("allowed")),
      qrLocations = listOf(allowed, blocked),
    )

    assertTrue(validateDismissalPayload(allowed.payload, state).accepted)
    assertEquals("disallowed", validateDismissalPayload(blocked.payload, state).reason)
    assertEquals("unknown", validateDismissalPayload("not-super-alarm", state).reason)
  }

  @Test
  fun sleepDurationWrapsToNextDayWakeTime() {
    assertEquals(5 * 60 + 30, sleepWakeTimeMinutes(23 * 60 + 30, 6 * 60))
  }

  @Test
  fun sleepDurationKeepsSameDayWakeTime() {
    assertEquals(14 * 60 + 15, sleepWakeTimeMinutes(8 * 60 + 15, 6 * 60))
  }

  @Test
  fun alarmWaitUsesLaterTodayWhenRepeatAllowsToday() {
    assertEquals(2 * 60, alarmWaitMinutes(nowDay = 1, nowMinutes = 7 * 60, alarmMinutes = 9 * 60, repeatDays = listOf(1, 2)))
  }

  @Test
  fun alarmWaitUsesNextAllowedDayWhenTodayAlreadyPassed() {
    assertEquals(22 * 60 + 30, alarmWaitMinutes(nowDay = 1, nowMinutes = 8 * 60 + 30, alarmMinutes = 7 * 60, repeatDays = listOf(2)))
  }
}
