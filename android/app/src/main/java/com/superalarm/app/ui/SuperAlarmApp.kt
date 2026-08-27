package com.superalarm.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superalarm.app.BuildConfig
import com.superalarm.app.data.AlarmConfig
import com.superalarm.app.data.AlarmAttempt
import com.superalarm.app.data.AppState
import com.superalarm.app.data.DeviceSupport
import com.superalarm.app.data.alarmWaitMinutes
import com.superalarm.app.data.deviceSupportForManufacturer
import com.superalarm.app.data.sleepWakeTimeMinutes
import com.superalarm.app.data.SuperAlarmStore
import com.superalarm.app.features.objectscan.ObjectEnrollmentScreen
import com.superalarm.app.widget.hasSuperAlarmWidgets
import com.superalarm.app.widget.needsHomeScreenPermissionHint
import com.superalarm.app.widget.openHomeScreenPermissionSettings
import com.superalarm.app.widget.requestPinSuperAlarmWidget
import com.superalarm.app.telemetry.ProductEvent
import com.superalarm.app.telemetry.SuperAlarmTelemetry
import com.superalarm.app.telemetry.TelemetryEventName
import com.superalarm.app.telemetry.TelemetryOutcome
import com.superalarm.app.telemetry.TelemetrySource
import com.superalarm.app.telemetry.TelemetrySurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class Tab { Alarm, Dismissal, Health }

private data class EnrollmentRequest(
  val label: String,
  val targetId: String? = null,
)

@Composable
fun SuperAlarmApp(store: SuperAlarmStore) {
  val context = LocalContext.current
  val dark = androidx.compose.foundation.isSystemInDarkTheme()
  val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
  } else {
    lightColorScheme(primary = Color(0xFFB45309), secondary = Color(0xFF3D5A5A), tertiary = Color(0xFF7A5260))
  }
  MaterialTheme(colorScheme = colorScheme) {
    Surface(modifier = Modifier.fillMaxSize()) {
      SuperAlarmRoot(store)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuperAlarmRoot(store: SuperAlarmStore) {
  val context = LocalContext.current
  val state by store.state.collectAsStateWithLifecycle()
  val analyticsEnabled by store.analyticsEnabled.collectAsStateWithLifecycle()
  var tab by remember { mutableStateOf(Tab.Alarm) }
  var enrollmentRequest by remember { mutableStateOf<EnrollmentRequest?>(null) }
  var pendingEnrollmentRequest by remember { mutableStateOf<EnrollmentRequest?>(null) }
  var cameraDenied by remember { mutableStateOf(false) }
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val enrollmentPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    SuperAlarmTelemetry.capture(ProductEvent(
      TelemetryEventName.PermissionResultObserved,
      source = TelemetrySource.App,
      outcome = if (granted) TelemetryOutcome.Allowed else TelemetryOutcome.Denied,
      permissionKind = "camera",
    ))
    if (granted) {
      enrollmentRequest = pendingEnrollmentRequest
      cameraDenied = false
    } else {
      cameraDenied = true
    }
    pendingEnrollmentRequest = null
  }

  val requestObjectScan: (EnrollmentRequest) -> Unit = { request ->
    SuperAlarmTelemetry.capture(ProductEvent(TelemetryEventName.DismissalEnrollmentStarted, surface = TelemetrySurface.ObjectEnrollment))
    if (store.cameraGranted()) {
      enrollmentRequest = request
    } else {
      SuperAlarmTelemetry.capture(ProductEvent(
        TelemetryEventName.PermissionActionSelected,
        source = if (state.onboardingComplete) TelemetrySource.App else TelemetrySource.Onboarding,
        permissionKind = "camera",
      ))
      pendingEnrollmentRequest = request
      enrollmentPermission.launch(Manifest.permission.CAMERA)
    }
  }
  val requestEnrollment: (String) -> Unit = { label ->
    requestObjectScan(EnrollmentRequest(label.ifBlank { "Wake object" }))
  }

  state.runtime?.let { runtime ->
    LaunchedEffect(runtime.attemptId) {
      enrollmentRequest = null
      pendingEnrollmentRequest = null
    }
    RingingScreen(store = store, state = state)
    return
  }

  enrollmentRequest?.let { request ->
    ObjectEnrollmentScreen(
      label = request.label,
      onCancel = {
        SuperAlarmTelemetry.capture(ProductEvent(TelemetryEventName.DismissalEnrollmentCancelled, surface = TelemetrySurface.ObjectEnrollment))
        enrollmentRequest = null
      },
      onComplete = { embeddings, thumbnailJpeg ->
        if (request.targetId == null) {
          store.addObjectTarget(request.label, embeddings, thumbnailJpeg)
        } else {
          store.updateObjectTarget(request.targetId, embeddings, thumbnailJpeg)
        }
        enrollmentRequest = null
      },
    )
    return
  }

  if (!state.onboardingComplete) {
    OnboardingScreen(
      state = state,
      store = store,
      cameraDenied = cameraDenied,
      onScanObject = requestEnrollment,
    )
    return
  }

  Scaffold(
    topBar = { CenterAlignedTopAppBar(title = { Text("Super Alarm") }) },
    snackbarHost = { SnackbarHost(snackbar) },
    bottomBar = {
      NavigationBar {
        NavigationBarItem(selected = tab == Tab.Alarm, onClick = { tab = Tab.Alarm }, icon = { Icon(Icons.Default.Alarm, null) }, label = { Text("Alarm") })
        NavigationBarItem(selected = tab == Tab.Dismissal, onClick = { tab = Tab.Dismissal }, icon = { Icon(Icons.Default.QrCodeScanner, null) }, label = { Text("Dismiss") })
        NavigationBarItem(selected = tab == Tab.Health, onClick = { tab = Tab.Health }, icon = { Icon(Icons.Default.HealthAndSafety, null) }, label = { Text("Health") })
      }
    },
  ) { padding ->
    when (tab) {
      Tab.Alarm -> AlarmScreen(
        modifier = Modifier.padding(padding),
        state = state,
        onToggleEnabled = { enabled ->
          if (!store.setEnabled(enabled)) scope.launch { snackbar.showSnackbar("Add an allowed QR spot or object first") }
        },
        onTime = store::setTime,
        onDay = store::toggleDay,
        onVibration = store::setVibration,
        onAddWidget = {
          val requested = requestPinSuperAlarmWidget(context)
          if (!requested) {
            scope.launch { snackbar.showSnackbar("Add Next alarm from your launcher's widget picker") }
          } else if (needsHomeScreenPermissionHint()) {
            scope.launch {
              delay(1_500L)
              if (!hasSuperAlarmWidgets(context)) {
                val result = snackbar.showSnackbar(
                  message = "Allow Home screen shortcuts, then tap Add again",
                  actionLabel = "Settings",
                  withDismissAction = true,
                  duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                  openHomeScreenPermissionSettings(context)
                }
              }
            }
          }
        },
      )
      Tab.Dismissal -> DismissalTargetsScreen(
        modifier = Modifier.padding(padding),
        state = state,
        store = store,
        onAddObject = requestEnrollment,
        onRescanObject = { target ->
          requestObjectScan(EnrollmentRequest(target.label, target.id))
        },
      )
      Tab.Health -> HealthScreen(Modifier.padding(padding), state, analyticsEnabled, store)
    }
  }

  LaunchedEffect(tab) {
    val surface = when (tab) {
      Tab.Alarm -> TelemetrySurface.Alarm
      Tab.Dismissal -> TelemetrySurface.DismissalTargets
      Tab.Health -> TelemetrySurface.Health
    }
    SuperAlarmTelemetry.capture(ProductEvent(TelemetryEventName.SurfaceViewed, surface = surface))
  }
}

@Composable
private fun AlarmScreen(
  modifier: Modifier,
  state: AppState,
  onToggleEnabled: (Boolean) -> Unit,
  onTime: (Int) -> Unit,
  onDay: (Int) -> Unit,
  onVibration: (Boolean) -> Unit,
  onAddWidget: () -> Unit,
) {
  var showTimeEditor by remember { mutableStateOf(false) }
  var clock by remember { mutableStateOf(currentClockSnapshot()) }

  LaunchedEffect(Unit) {
    while (true) {
      clock = currentClockSnapshot()
      delay(60_000L)
    }
  }

  val waitMinutes = alarmWaitMinutes(
    nowDay = clock.day,
    nowMinutes = clock.minutes,
    alarmMinutes = state.alarm.timeMinutesLocal,
    repeatDays = state.alarm.repeatDays,
  )
  val waitLabel = formatDuration(waitMinutes)

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(
      start = 20.dp,
      top = 4.dp,
      end = 20.dp,
      bottom = 20.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item { AlarmDial(state.alarm, waitLabel, onEditTime = { showTimeEditor = true }) }
    item {
      ListItem(
        headlineContent = { Text("Alarm enabled", style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
          val allowedCount = state.alarm.allowedLocationIds.size + state.alarm.allowedObjectTargetIds.size
          Text(if (allowedCount == 0) "Requires an allowed QR spot or object" else "$allowedCount dismissal target${if (allowedCount == 1) "" else "s"} allowed")
        },
        trailingContent = { Switch(checked = state.alarm.enabled, onCheckedChange = onToggleEnabled) },
      )
    }
    item { TimeEditor(state.alarm.timeMinutesLocal, waitLabel, onEditTime = { showTimeEditor = true }) }
    item { SleepDurationSetter(onTime = onTime) }
    item { DayPicker(state.alarm.repeatDays, onDay) }
    item {
      ListItem(
        headlineContent = { Text("Vibration", style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text("Use alarm vibration while ringing") },
        trailingContent = { Switch(checked = state.alarm.vibrationEnabled, onCheckedChange = onVibration) },
      )
    }
    item {
      ListItem(
        headlineContent = { Text("Home screen widget", style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text("See the next alarm and set sleep duration from your launcher") },
        trailingContent = { TextButton(onClick = onAddWidget) { Text("Add") } },
      )
    }
  }

  if (showTimeEditor) {
    TimeEditorDialog(
      minutes = state.alarm.timeMinutesLocal,
      onDismiss = { showTimeEditor = false },
      onTime = {
        onTime(it)
        showTimeEditor = false
      },
    )
  }
}

@Composable
private fun AlarmDial(alarm: AlarmConfig, waitLabel: String, onEditTime: () -> Unit) {
  val time = formatMinutes(alarm.timeMinutesLocal)
  val colorScheme = MaterialTheme.colorScheme
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(1.35f)
      .clickable(onClick = onEditTime)
      .semantics { contentDescription = "Alarm time $time. Rings in $waitLabel. Tap to edit." },
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
      val strokeWidth = 8.dp.toPx()
      val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
      val diameter = minOf(size.width - strokeWidth, (size.height - strokeWidth) / 0.75f)
      val arcTop = (size.height - diameter * 0.75f) / 2f
      val arcLeft = (size.width - diameter) / 2f
      val arcSize = Size(diameter, diameter)
      val topLeft = Offset(arcLeft, arcTop)
      drawArc(color = colorScheme.primary, startAngle = 150f, sweepAngle = 240f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
      drawArc(color = colorScheme.tertiary, startAngle = 150f, sweepAngle = (alarm.timeMinutesLocal / 1440f) * 240f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(time, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
      Text(if (alarm.enabled) "Enabled" else "Off", style = MaterialTheme.typography.labelLarge)
      Text("Rings in $waitLabel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEditor(minutes: Int, waitLabel: String, onEditTime: () -> Unit) {
  ListItem(
    headlineContent = { Text("Time", style = MaterialTheme.typography.bodyLarge) },
    supportingContent = { Text("${formatMinutes(minutes)} · rings in $waitLabel") },
    trailingContent = { TextButton(onClick = onEditTime) { Text("Edit") } },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEditorDialog(minutes: Int, onDismiss: () -> Unit, onTime: (Int) -> Unit) {
  val state = androidx.compose.material3.rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = false)
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = { onTime(state.hour * 60 + state.minute) }) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    text = { TimePicker(state = state) },
  )
}

@Composable
private fun SleepDurationSetter(onTime: (Int) -> Unit) {
  var showCustom by remember { mutableStateOf(false) }
  val now = LocalTime.now()
  val nowMinutes = now.hour * 60 + now.minute

  Column(Modifier.padding(horizontal = 16.dp)) {
    Text("Sleep for", style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SleepPresetButton(label = "5h", nowMinutes = nowMinutes, durationMinutes = 5 * 60, onTime = onTime)
      SleepPresetButton(label = "6h", nowMinutes = nowMinutes, durationMinutes = 6 * 60, onTime = onTime)
      SleepPresetButton(label = "8h", nowMinutes = nowMinutes, durationMinutes = 8 * 60, onTime = onTime)
      AssistChip(onClick = { showCustom = true }, label = { Text("Custom") })
    }
  }

  if (showCustom) {
    SleepDurationDialog(
      onDismiss = { showCustom = false },
      onDuration = {
        onTime(sleepWakeTimeMinutes(nowMinutes, it))
        showCustom = false
      },
    )
  }
}

@Composable
private fun SleepPresetButton(label: String, nowMinutes: Int, durationMinutes: Int, onTime: (Int) -> Unit) {
  SuggestionChip(onClick = { onTime(sleepWakeTimeMinutes(nowMinutes, durationMinutes)) }, label = { Text(label) })
}

@Composable
private fun SleepDurationDialog(onDismiss: () -> Unit, onDuration: (Int) -> Unit) {
  var hours by remember { mutableStateOf("6") }
  var minutes by remember { mutableStateOf("0") }
  val duration = (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Sleep duration") },
    text = {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = hours,
          onValueChange = { hours = it.filter(Char::isDigit).take(2) },
          label = { Text("Hours") },
          singleLine = true,
          keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = minutes,
          onValueChange = { minutes = it.filter(Char::isDigit).take(2) },
          label = { Text("Minutes") },
          singleLine = true,
          keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f),
        )
      }
    },
    confirmButton = { TextButton(enabled = duration > 0, onClick = { onDuration(duration) }) { Text("Set") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun DayPicker(selected: List<Int>, onDay: (Int) -> Unit) {
  Column {
    Text("Repeat", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { day, label ->
        FilterChip(selected = selected.contains(day), onClick = { onDay(day) }, label = { Text(label) })
      }
    }
  }
}

@Composable
private fun HealthScreen(modifier: Modifier, state: AppState, analyticsEnabled: Boolean, store: SuperAlarmStore) {
  var refreshKey by remember { mutableStateOf(0) }
  var notificationDenied by remember { mutableStateOf(false) }
  val caps = remember(refreshKey) { store.capabilities() }
  val context = LocalContext.current
  val deviceSupport = remember { deviceSupportForManufacturer(Build.MANUFACTURER) }
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    notificationDenied = !granted
    SuperAlarmTelemetry.capture(ProductEvent(
      TelemetryEventName.PermissionResultObserved,
      source = TelemetrySource.App,
      outcome = if (granted) TelemetryOutcome.Allowed else TelemetryOutcome.Denied,
      permissionKind = "notifications",
    ))
    refreshKey++
  }
  LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
    item { HealthRow("Notifications", caps.notificationsGranted, "Required for foreground alarm alerts") {
      SuperAlarmTelemetry.capture(ProductEvent(TelemetryEventName.PermissionActionSelected, source = TelemetrySource.App, permissionKind = "notifications"))
      if (store.notificationPermissionGranted() || notificationDenied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        store.openNotificationSettings()
      } else {
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    } }
    item { HealthRow("Exact alarms", caps.exactAlarmAllowed, "Required for reliable wake-up time", store::openExactAlarmSettings) }
    item { HealthRow("Full-screen intent", caps.fullscreenIntentAllowed, "Opens the ringing screen while locked", store::openFullscreenSettings) }
    item { HealthRow("Battery optimization", caps.batteryOptimizationRelaxed, "Relax this if alarms are delayed", store::openBatterySettings) }
    item { DeviceSupportHealthRow(deviceSupport = deviceSupport, onAction = store::openDeviceSupportSettings) }
    item { Button(onClick = store::startRoutineTest, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp)) { Text("Run routine test") } }
    item { OutlinedButton(onClick = {
      SuperAlarmTelemetry.capture(ProductEvent(TelemetryEventName.DiagnosticsExported, surface = TelemetrySurface.Health))
      shareText(context, store.diagnosticsJson())
    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) { Text("Export diagnostics") } }
    item {
      ListItem(
        headlineContent = { Text("Share anonymous analytics") },
        supportingContent = { Text("Shares product use, device details, and coarse IP-derived location. Alarm labels and scan data stay private.") },
        trailingContent = { Switch(checked = analyticsEnabled, onCheckedChange = store::setAnalyticsEnabled) },
      )
    }
    item { OutlinedButton(onClick = store::restartOnboarding, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) { Text("Run setup again") } }
    item {
      ListItem(
        headlineContent = { Text("App version") },
        supportingContent = { Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
      )
    }
    item { Text("Recent attempts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) }
    if (state.attempts.isEmpty()) item { Text("No attempts recorded yet.") }
    items(state.attempts.take(8), key = { it.id }) { attempt ->
      AttemptRow(attempt, state)
    }
  }
}

@Composable
private fun AttemptRow(attempt: AlarmAttempt, state: AppState) {
  val alarmName = if (attempt.kind == "test") "Test alarm" else "Alarm"
  val headline = when (attempt.result) {
    "dismissed" -> "$alarmName dismissed"
    "interrupted" -> "$alarmName stopped before dismissal"
    "missed" -> "$alarmName was missed"
    else -> "$alarmName started"
  }
  val detail = buildList {
    add(formatAttemptTime(attempt.resultAt ?: attempt.dismissedAt ?: attempt.triggeredAt))
    when (attempt.dismissalMethod) {
      "object" -> {
        val label = attempt.dismissalTargetLabel
          ?: state.objectTargets.firstOrNull { it.id == attempt.dismissalLocationId }?.label
        add(label?.let { "Matched $it" } ?: "Matched an object")
      }
      "qr" -> {
        val label = attempt.dismissalTargetLabel
          ?: state.qrLocations.firstOrNull { it.id == attempt.dismissalLocationId }?.label
        add(label?.let { "Scanned $it" } ?: "Scanned a QR spot")
      }
    }
  }.joinToString(" · ")
  ListItem(
    headlineContent = { Text(headline) },
    supportingContent = { Text(detail) },
  )
}

private fun formatAttemptTime(value: String): String {
  val time = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault())
  }.getOrNull() ?: return "Time unavailable"
  val localDate = time.toLocalDate()
  val today = LocalDate.now(time.zone)
  val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(time)
  return when (localDate) {
    today -> "Today at $clock"
    today.minusDays(1) -> "Yesterday at $clock"
    else -> "${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(time)} at $clock"
  }
}

@Composable
private fun HealthRow(title: String, ok: Boolean, detail: String, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(detail) },
    leadingContent = { Box(Modifier.size(12.dp).background(if (ok) Color(0xFF2E7D32) else Color(0xFFC2410C), CircleShape)) },
    trailingContent = { if (!ok) TextButton(onClick = onClick) { Text("Open") } },
  )
}

@Composable
private fun DeviceSupportHealthRow(
  deviceSupport: DeviceSupport?,
  onAction: () -> Unit,
) {
  ListItem(
    headlineContent = { Text("${deviceSupport?.label ?: "Device"} background access · recommended") },
    supportingContent = {
      Text(
        "${deviceSupport?.instruction ?: "Check Battery or Background activity settings and allow Super Alarm to run unrestricted."} Android cannot report this setting back to Super Alarm.",
      )
    },
    leadingContent = { Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) },
    trailingContent = { TextButton(onClick = onAction) { Text("Open") } },
  )
}

private fun formatMinutes(minutes: Int): String {
  val hour24 = minutes / 60
  val minute = minutes % 60
  val suffix = if (hour24 < 12) "AM" else "PM"
  val hour12 = ((hour24 + 11) % 12) + 1
  return "%d:%02d %s".format(hour12, minute, suffix)
}

private data class ClockSnapshot(val day: Int, val minutes: Int)

private fun currentClockSnapshot(): ClockSnapshot {
  val time = LocalTime.now()
  return ClockSnapshot(
    day = LocalDate.now().dayOfWeek.value % 7,
    minutes = time.hour * 60 + time.minute,
  )
}

private fun formatDuration(minutes: Int): String {
  val hours = minutes / 60
  val remainingMinutes = minutes % 60
  return when {
    hours == 0 -> "${remainingMinutes}m"
    remainingMinutes == 0 -> "${hours}h"
    else -> "${hours}h ${remainingMinutes}m"
  }
}

private fun shareText(context: android.content.Context, text: String) {
  context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_TEXT, text) }, "Export diagnostics"))
}
