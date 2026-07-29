package com.superalarm.app.data

import com.superalarm.app.features.objectscan.ObjectEmbeddings
import com.superalarm.app.features.objectscan.ObjectMatchTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRulesTest {
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
    assertFalse(tracker.accept(reference, frameAt = 500).accepted)
    val accepted = tracker.accept(reference, frameAt = 900)

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
