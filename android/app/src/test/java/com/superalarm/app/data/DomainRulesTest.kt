package com.superalarm.app.data

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
