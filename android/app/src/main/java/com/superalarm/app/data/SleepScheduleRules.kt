package com.superalarm.app.data

fun sleepWakeTimeMinutes(nowMinutes: Int, durationMinutes: Int): Int {
  val dayMinutes = 24 * 60
  val normalizedNow = ((nowMinutes % dayMinutes) + dayMinutes) % dayMinutes
  val normalizedDuration = durationMinutes.coerceAtLeast(0)
  return (normalizedNow + normalizedDuration) % dayMinutes
}

fun alarmWaitMinutes(nowDay: Int, nowMinutes: Int, alarmMinutes: Int, repeatDays: List<Int>): Int {
  val dayMinutes = 24 * 60
  val normalizedNowDay = ((nowDay % 7) + 7) % 7
  val normalizedNowMinutes = ((nowMinutes % dayMinutes) + dayMinutes) % dayMinutes
  val normalizedAlarmMinutes = ((alarmMinutes % dayMinutes) + dayMinutes) % dayMinutes
  val activeDays = repeatDays.distinct().filter { it in 0..6 }

  if (activeDays.isEmpty()) {
    return if (normalizedAlarmMinutes > normalizedNowMinutes) {
      normalizedAlarmMinutes - normalizedNowMinutes
    } else {
      dayMinutes - normalizedNowMinutes + normalizedAlarmMinutes
    }
  }

  for (dayOffset in 0..7) {
    val candidateDay = (normalizedNowDay + dayOffset) % 7
    if (activeDays.contains(candidateDay) && (dayOffset > 0 || normalizedAlarmMinutes > normalizedNowMinutes)) {
      return dayOffset * dayMinutes + normalizedAlarmMinutes - normalizedNowMinutes
    }
  }

  return dayMinutes - normalizedNowMinutes + normalizedAlarmMinutes
}
