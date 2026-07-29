package com.superalarm.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class AlarmConfig(
  val id: String = "morning-alarm",
  val label: String = "Morning Alarm",
  val enabled: Boolean = false,
  val timeMinutesLocal: Int = 7 * 60 + 30,
  val repeatDays: List<Int> = listOf(1, 2, 3, 4, 5),
  val soundId: String = "alarm",
  val vibrationEnabled: Boolean = true,
  val backupOffsetsSeconds: List<Int> = listOf(45, 120),
  val allowedLocationIds: List<String> = emptyList(),
  val allowedObjectTargetIds: List<String> = emptyList(),
)

data class QrLocation(
  val id: String = UUID.randomUUID().toString(),
  val label: String,
  val payload: String = "superalarm://alarm-qr/${UUID.randomUUID()}",
)

data class ObjectTarget(
  val id: String = UUID.randomUUID().toString(),
  val label: String,
  val embeddings: List<String>,
)

data class AlarmAttempt(
  val id: String,
  val alarmId: String,
  val kind: String,
  val triggeredAt: String,
  val dismissedAt: String? = null,
  val result: String = "ringing",
  val backupStage: Int = 0,
  val dismissalLocationId: String? = null,
  val dismissalMethod: String? = null,
)

data class AlarmRuntimeState(
  val attemptId: String,
  val alarmId: String,
  val kind: String,
  val triggeredAt: String,
  val backupStage: Int,
)

data class AlarmDiagnosticEvent(
  val id: String = UUID.randomUUID().toString(),
  val type: String,
  val message: String,
  val createdAt: String = Instant.now().toString(),
)

data class ReliabilityChecklistState(
  val notificationsGranted: Boolean = false,
  val exactAlarmAllowed: Boolean = true,
  val fullscreenIntentAllowed: Boolean = true,
  val batteryOptimizationRelaxed: Boolean = false,
)

data class AppState(
  val alarm: AlarmConfig = AlarmConfig(),
  val qrLocations: List<QrLocation> = emptyList(),
  val objectTargets: List<ObjectTarget> = emptyList(),
  val attempts: List<AlarmAttempt> = emptyList(),
  val diagnosticEvents: List<AlarmDiagnosticEvent> = emptyList(),
  val runtime: AlarmRuntimeState? = null,
  val onboardingComplete: Boolean = false,
)

fun AppState.toJson(): JSONObject = JSONObject().apply {
  put("alarm", alarm.toJson())
  put("qrLocations", JSONArray(qrLocations.map { it.toJson() }))
  put("objectTargets", JSONArray(objectTargets.map { it.toJson() }))
  put("attempts", JSONArray(attempts.map { it.toJson() }))
  put("diagnosticEvents", JSONArray(diagnosticEvents.map { it.toJson() }))
  put("runtime", runtime?.toJson() ?: JSONObject.NULL)
  put("onboardingComplete", onboardingComplete)
}

fun appStateFromJson(json: JSONObject): AppState = AppState(
  alarm = alarmFromJson(json.optJSONObject("alarm") ?: JSONObject()),
  qrLocations = json.optJSONArray("qrLocations").toObjectList(::qrLocationFromJson),
  objectTargets = json.optJSONArray("objectTargets").toObjectList(::objectTargetFromJson),
  attempts = json.optJSONArray("attempts").toObjectList(::attemptFromJson),
  diagnosticEvents = json.optJSONArray("diagnosticEvents").toObjectList(::eventFromJson),
  runtime = json.optJSONObject("runtime")?.let(::runtimeFromJson),
  onboardingComplete = json.optBoolean("onboardingComplete", false),
)

fun AlarmConfig.toJson(): JSONObject = JSONObject().apply {
  put("id", id)
  put("label", label)
  put("enabled", enabled)
  put("timeMinutesLocal", timeMinutesLocal)
  put("repeatDays", JSONArray(repeatDays))
  put("soundId", soundId)
  put("vibrationEnabled", vibrationEnabled)
  put("backupOffsetsSeconds", JSONArray(backupOffsetsSeconds))
  put("allowedLocationIds", JSONArray(allowedLocationIds))
  put("allowedObjectTargetIds", JSONArray(allowedObjectTargetIds))
}

fun alarmFromJson(json: JSONObject): AlarmConfig = AlarmConfig(
  id = json.optString("id", "morning-alarm"),
  label = json.optString("label", "Morning Alarm"),
  enabled = json.optBoolean("enabled", false),
  timeMinutesLocal = json.optInt("timeMinutesLocal", 7 * 60 + 30).coerceIn(0, 1439),
  repeatDays = json.optJSONArray("repeatDays").toIntList(default = listOf(1, 2, 3, 4, 5)),
  soundId = json.optString("soundId", "alarm"),
  vibrationEnabled = json.optBoolean("vibrationEnabled", true),
  backupOffsetsSeconds = json.optJSONArray("backupOffsetsSeconds").toIntList(default = listOf(45, 120)),
  allowedLocationIds = json.optJSONArray("allowedLocationIds").toStringList(),
  allowedObjectTargetIds = json.optJSONArray("allowedObjectTargetIds").toStringList(),
)

fun QrLocation.toJson(): JSONObject = JSONObject().apply {
  put("id", id)
  put("label", label)
  put("payload", payload)
}

fun qrLocationFromJson(json: JSONObject): QrLocation = QrLocation(
  id = json.optString("id", UUID.randomUUID().toString()),
  label = json.optString("label", "Wake spot"),
  payload = json.optString("payload", "superalarm://alarm-qr/${UUID.randomUUID()}"),
)

fun ObjectTarget.toJson(): JSONObject = JSONObject().apply {
  put("id", id)
  put("label", label)
  put("embeddings", JSONArray(embeddings))
}

fun objectTargetFromJson(json: JSONObject): ObjectTarget = ObjectTarget(
  id = json.optString("id", UUID.randomUUID().toString()),
  label = json.optString("label", "Wake object"),
  embeddings = json.optJSONArray("embeddings").toStringList(),
)

fun AlarmAttempt.toJson(): JSONObject = JSONObject().apply {
  put("id", id)
  put("alarmId", alarmId)
  put("kind", kind)
  put("triggeredAt", triggeredAt)
  put("dismissedAt", dismissedAt ?: JSONObject.NULL)
  put("result", result)
  put("backupStage", backupStage)
  put("dismissalLocationId", dismissalLocationId ?: JSONObject.NULL)
  put("dismissalMethod", dismissalMethod ?: JSONObject.NULL)
}

fun attemptFromJson(json: JSONObject): AlarmAttempt = AlarmAttempt(
  id = json.optString("id", "attempt-${System.currentTimeMillis()}"),
  alarmId = json.optString("alarmId", "morning-alarm"),
  kind = json.optString("kind", "live"),
  triggeredAt = json.optString("triggeredAt", Instant.now().toString()),
  dismissedAt = json.optNullableString("dismissedAt"),
  result = json.optString("result", "ringing"),
  backupStage = json.optInt("backupStage", 0),
  dismissalLocationId = json.optNullableString("dismissalLocationId"),
  dismissalMethod = json.optNullableString("dismissalMethod"),
)

fun AlarmRuntimeState.toJson(): JSONObject = JSONObject().apply {
  put("attemptId", attemptId)
  put("alarmId", alarmId)
  put("kind", kind)
  put("triggeredAt", triggeredAt)
  put("backupStage", backupStage)
}

fun runtimeFromJson(json: JSONObject): AlarmRuntimeState = AlarmRuntimeState(
  attemptId = json.optString("attemptId"),
  alarmId = json.optString("alarmId", "morning-alarm"),
  kind = json.optString("kind", "live"),
  triggeredAt = json.optString("triggeredAt", Instant.now().toString()),
  backupStage = json.optInt("backupStage", 0),
)

fun AlarmDiagnosticEvent.toJson(): JSONObject = JSONObject().apply {
  put("id", id)
  put("type", type)
  put("message", message)
  put("createdAt", createdAt)
}

fun eventFromJson(json: JSONObject): AlarmDiagnosticEvent = AlarmDiagnosticEvent(
  id = json.optString("id", UUID.randomUUID().toString()),
  type = json.optString("type", "event"),
  message = json.optString("message", "Alarm event"),
  createdAt = json.optString("createdAt", Instant.now().toString()),
)

private fun JSONArray?.toIntList(default: List<Int> = emptyList()): List<Int> {
  if (this == null) return default
  return (0 until length()).mapNotNull { opt(it) as? Number }.map { it.toInt() }
}

private fun JSONArray?.toStringList(): List<String> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}

private fun <T> JSONArray?.toObjectList(transform: (JSONObject) -> T): List<T> {
  if (this == null) return emptyList()
  return (0 until length()).mapNotNull { optJSONObject(it)?.let(transform) }
}

private fun JSONObject.optNullableString(key: String): String? =
  if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
