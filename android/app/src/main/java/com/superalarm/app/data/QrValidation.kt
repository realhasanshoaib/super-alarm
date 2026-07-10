package com.superalarm.app.data

data class QrValidationResult(
  val accepted: Boolean,
  val location: QrLocation? = null,
  val reason: String? = null,
)

fun validateDismissalPayload(payload: String, state: AppState): QrValidationResult {
  val location = state.qrLocations.firstOrNull { it.payload == payload }
    ?: return QrValidationResult(accepted = false, reason = "unknown")
  if (!state.alarm.allowedLocationIds.contains(location.id)) {
    return QrValidationResult(accepted = false, location = location, reason = "disallowed")
  }
  return QrValidationResult(accepted = true, location = location)
}
