package com.superalarm.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superalarm.app.BuildConfig
import com.superalarm.app.data.AlarmConfig
import com.superalarm.app.data.AppState
import com.superalarm.app.data.QrLocation
import com.superalarm.app.data.alarmWaitMinutes
import com.superalarm.app.data.sleepWakeTimeMinutes
import com.superalarm.app.data.SuperAlarmStore
import com.superalarm.app.features.qr.QrScannerView
import com.superalarm.app.features.qr.qrBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

private enum class Tab { Alarm, Spots, Health }

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
  val state by store.state.collectAsStateWithLifecycle()
  var tab by remember { mutableStateOf(Tab.Alarm) }
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()

  if (!state.onboardingComplete && state.qrLocations.isEmpty()) {
    OnboardingScreen(onCreateSpot = { label -> store.addLocation(label) })
    return
  }

  state.runtime?.let {
    RingingScreen(store = store, state = state)
    return
  }

  Scaffold(
    topBar = { CenterAlignedTopAppBar(title = { Text("Super Alarm") }) },
    snackbarHost = { SnackbarHost(snackbar) },
    bottomBar = {
      NavigationBar {
        NavigationBarItem(selected = tab == Tab.Alarm, onClick = { tab = Tab.Alarm }, icon = { Icon(Icons.Default.Alarm, null) }, label = { Text("Alarm") })
        NavigationBarItem(selected = tab == Tab.Spots, onClick = { tab = Tab.Spots }, icon = { Icon(Icons.Default.QrCodeScanner, null) }, label = { Text("QR spots") })
        NavigationBarItem(selected = tab == Tab.Health, onClick = { tab = Tab.Health }, icon = { Icon(Icons.Default.HealthAndSafety, null) }, label = { Text("Health") })
      }
    },
  ) { padding ->
    when (tab) {
      Tab.Alarm -> AlarmScreen(
        modifier = Modifier.padding(padding),
        state = state,
        onToggleEnabled = { enabled ->
          if (!store.setEnabled(enabled)) scope.launch { snackbar.showSnackbar("Add an allowed QR spot first") }
        },
        onTime = store::setTime,
        onDay = store::toggleDay,
        onVibration = store::setVibration,
      )
      Tab.Spots -> SpotsScreen(Modifier.padding(padding), state, store)
      Tab.Health -> HealthScreen(Modifier.padding(padding), state, store)
    }
  }
}

@Composable
private fun OnboardingScreen(onCreateSpot: (String) -> Unit) {
  var label by remember { mutableStateOf("Bathroom mirror") }
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Add your first QR spot", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(12.dp))
    Text("Your alarm can only be dismissed from an allowed QR spot. Start with the place you actually get out of bed to reach.")
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Spot label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    Button(onClick = { onCreateSpot(label) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
      Icon(Icons.Default.Add, null)
      Spacer(Modifier.size(8.dp))
      Text("Create QR spot")
    }
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

  LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item { AlarmDial(state.alarm, waitLabel, onEditTime = { showTimeEditor = true }) }
    item {
      ListItem(
        headlineContent = { Text("Alarm enabled") },
        supportingContent = { Text(if (state.alarm.allowedLocationIds.isEmpty()) "Requires at least one allowed QR spot" else "Next ring uses allowed QR spots only") },
        trailingContent = { Switch(checked = state.alarm.enabled, onCheckedChange = onToggleEnabled) },
      )
    }
    item { TimeEditor(state.alarm.timeMinutesLocal, waitLabel, onEditTime = { showTimeEditor = true }) }
    item { SleepDurationSetter(onTime = onTime) }
    item { DayPicker(state.alarm.repeatDays, onDay) }
    item {
      ListItem(
        headlineContent = { Text("Vibration") },
        supportingContent = { Text("Use alarm vibration while ringing") },
        trailingContent = { Switch(checked = state.alarm.vibrationEnabled, onCheckedChange = onVibration) },
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-20).dp)) {
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
    headlineContent = { Text("Time") },
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
    Text("Sleep for", style = MaterialTheme.typography.titleMedium)
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
    Text("Repeat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { day, label ->
        FilterChip(selected = selected.contains(day), onClick = { onDay(day) }, label = { Text(label) })
      }
    }
  }
}

@Composable
private fun SpotsScreen(modifier: Modifier, state: AppState, store: SuperAlarmStore) {
  var showAdd by remember { mutableStateOf(false) }
  var selected by remember { mutableStateOf<QrLocation?>(null) }
  LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.size(8.dp)); Text("Add QR spot") } }
    if (state.qrLocations.isEmpty()) {
      item { Text("No QR spots yet. Add one before enabling the alarm.", modifier = Modifier.padding(16.dp)) }
    }
    items(state.qrLocations, key = { it.id }) { location ->
      val allowed = state.alarm.allowedLocationIds.contains(location.id)
      ListItem(
        headlineContent = { Text(location.label) },
        supportingContent = { Text(if (allowed) "Allowed to dismiss" else "Not allowed for this alarm") },
        leadingContent = { Checkbox(checked = allowed, onCheckedChange = { store.toggleAllowed(location.id) }) },
        trailingContent = {
          Row {
            IconButton(onClick = { selected = location }) { Icon(Icons.Default.Share, "Show QR") }
            IconButton(onClick = { store.deleteLocation(location.id) }) { Icon(Icons.Default.Delete, "Delete") }
          }
        },
      )
    }
  }
  if (showAdd) AddSpotDialog(onDismiss = { showAdd = false }, onAdd = { store.addLocation(it); showAdd = false })
  selected?.let { QrDialog(location = it, onDismiss = { selected = null }) }
}

@Composable
private fun AddSpotDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
  var label by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("New QR spot") },
    text = { OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Spot label") }, singleLine = true) },
    confirmButton = { TextButton(onClick = { onAdd(label) }) { Text("Create") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun QrDialog(location: QrLocation, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val bitmap = remember(location.payload) { qrBitmap(location.payload).asImageBitmap() }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(location.label) },
    text = {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(bitmap = bitmap, contentDescription = "QR code for ${location.label}", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        Text(location.payload, style = MaterialTheme.typography.bodySmall)
      }
    },
    confirmButton = {
      TextButton(onClick = {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, location.payload) }, "Share QR payload"))
      }) { Text("Share") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

@Composable
private fun HealthScreen(modifier: Modifier, state: AppState, store: SuperAlarmStore) {
  var refreshKey by remember { mutableStateOf(0) }
  val caps = remember(refreshKey) { store.capabilities() }
  val context = LocalContext.current
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshKey++ }
  LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
    item { HealthRow("Notifications", caps.notificationsGranted, "Required for foreground alarm alerts") {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    } }
    item { HealthRow("Exact alarms", caps.exactAlarmAllowed, "Required for reliable wake-up time", store::openExactAlarmSettings) }
    item { HealthRow("Full-screen intent", caps.fullscreenIntentAllowed, "Opens the ringing screen while locked", store::openFullscreenSettings) }
    item { HealthRow("Battery optimization", caps.batteryOptimizationRelaxed, "Relax this if alarms are delayed", store::openBatterySettings) }
    item { Button(onClick = store::startRoutineTest, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp)) { Text("Run routine test") } }
    item { OutlinedButton(onClick = { shareText(context, store.diagnosticsJson()) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) { Text("Export diagnostics") } }
    item {
      ListItem(
        headlineContent = { Text("App version") },
        supportingContent = { Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
      )
    }
    item { Text("Recent attempts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) }
    if (state.attempts.isEmpty()) item { Text("No attempts recorded yet.") }
    items(state.attempts.take(8)) { Text("${it.kind}: ${it.result} at ${it.triggeredAt}", modifier = Modifier.padding(vertical = 8.dp)) }
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
private fun RingingScreen(store: SuperAlarmStore, state: AppState) {
  val allowed = state.qrLocations.filter { state.alarm.allowedLocationIds.contains(it.id) }
  var scanner by remember { mutableStateOf(false) }
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scanner = it }
  Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(if (state.runtime?.kind == "test") "Test alarm" else "Alarm", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("Scan an allowed QR spot to dismiss.")
    Spacer(Modifier.height(16.dp))
    allowed.forEach { Text(it.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp)) }
    Spacer(Modifier.height(24.dp))
    Button(onClick = { if (store.cameraGranted()) scanner = true else permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
      Icon(Icons.Default.QrCodeScanner, null)
      Spacer(Modifier.size(8.dp))
      Text("Scan QR")
    }
    if (state.runtime?.kind == "test") TextButton(onClick = store::dismissWithoutScanForTestOnly, modifier = Modifier.padding(top = 8.dp)) { Text("Stop test") }
  }
  if (scanner) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      QrScannerView(modifier = Modifier.fillMaxSize(), onPayload = { if (store.dismissWithPayload(it)) scanner = false })
      TextButton(onClick = { scanner = false }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("Close", color = Color.White) }
    }
  }
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
