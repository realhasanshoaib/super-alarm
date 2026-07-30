package com.superalarm.app.widget

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil

fun formatWidgetAlarmTime(minutesLocal: Int, use24HourTime: Boolean): String {
  val time = LocalTime.of(minutesLocal.coerceIn(0, 1_439) / 60, minutesLocal.coerceIn(0, 1_439) % 60)
  return time.format(DateTimeFormatter.ofPattern(if (use24HourTime) "HH:mm" else "h:mm a"))
}

fun formatWidgetAlarmStatus(
  enabled: Boolean,
  triggerAtMillis: Long,
  nowMillis: Long,
  zoneId: ZoneId,
): String {
  if (!enabled) return "Off · tap to set"
  if (triggerAtMillis <= nowMillis) return "Enabled · check permissions"

  val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
  val triggerDate = Instant.ofEpochMilli(triggerAtMillis).atZone(zoneId).toLocalDate()
  val dayLabel = when (triggerDate) {
    nowDate -> "Today"
    nowDate.plusDays(1) -> "Tomorrow"
    else -> triggerDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
  }
  val hoursUntil = ceil((triggerAtMillis - nowMillis) / 3_600_000.0).toLong().coerceAtLeast(1L)
  val waitLabel = if (hoursUntil < 48) "in ${hoursUntil}h" else "in ${ceil(hoursUntil / 24.0).toLong()}d"
  return "$dayLabel · $waitLabel"
}

fun formatWidgetRepeatDays(days: List<Int>): String {
  val normalized = days.distinct().filter { it in 0..6 }.sorted()
  return when (normalized) {
    listOf(1, 2, 3, 4, 5) -> "Weekdays"
    (0..6).toList() -> "Every day"
    else -> normalized.joinToString(" ") { DAY_LABELS[it] }.ifEmpty { "No repeat days" }
  }
}

private val DAY_LABELS = listOf("S", "M", "T", "W", "T", "F", "S")
