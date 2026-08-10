package com.superalarm.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.superalarm.app.data.AppState
import com.superalarm.app.data.DeviceSupport
import com.superalarm.app.data.OnboardingSteps
import com.superalarm.app.data.QrLocation
import com.superalarm.app.data.ReliabilityChecklistState
import com.superalarm.app.data.SuperAlarmStore
import com.superalarm.app.data.deviceSupportForManufacturer
import com.superalarm.app.data.onboardingTestPassed
import com.superalarm.app.features.qr.qrBitmap
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
  state: AppState,
  store: SuperAlarmStore,
  cameraDenied: Boolean,
  onScanObject: (String) -> Unit,
) {
  val step = state.onboardingStep
  var refreshKey by remember { mutableStateOf(0) }
  var notificationDenied by remember { mutableStateOf(false) }
  var onboardingQr by remember { mutableStateOf<QrLocation?>(null) }
  val capabilities = remember(refreshKey, step) { store.capabilities() }
  val deviceSupport = remember { deviceSupportForManufacturer(Build.MANUFACTURER) }
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    notificationDenied = !granted
    refreshKey++
  }
  val lifecycleOwner = LocalLifecycleOwner.current

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) refreshKey++
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  BackHandler(enabled = step > OnboardingSteps.Welcome) {
    store.setOnboardingStep(previousOnboardingStep(step))
  }

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("Super Alarm") },
        navigationIcon = {
          if (step > OnboardingSteps.Welcome) {
            IconButton(onClick = { store.setOnboardingStep(previousOnboardingStep(step)) }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
          }
        },
        actions = {
          Text("${step + 1} of ${OnboardingSteps.Last + 1}", style = MaterialTheme.typography.labelLarge)
          Spacer(Modifier.padding(end = 8.dp))
        },
      )
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      LinearProgressIndicator(
        progress = { (step + 1f) / (OnboardingSteps.Last + 1f) },
        modifier = Modifier.fillMaxWidth(),
      )
      when (step) {
        OnboardingSteps.Welcome -> WelcomeStep(
          onContinue = { store.setOnboardingStep(OnboardingSteps.DismissalMethod) },
        )
        OnboardingSteps.DismissalMethod -> DismissalMethodStep(
          cameraDenied = cameraDenied,
          onCreateQrSpot = {
            onboardingQr = store.addLocation(it)
          },
          onScanObject = onScanObject,
        )
        OnboardingSteps.Alarm -> AlarmStep(
          state = state,
          store = store,
          onContinue = { store.setOnboardingStep(OnboardingSteps.PhoneSetup) },
        )
        OnboardingSteps.PhoneSetup -> PhoneSetupStep(
          capabilities = capabilities,
          deviceSupport = deviceSupport,
          notificationPermissionGranted = store.notificationPermissionGranted(),
          notificationDenied = notificationDenied,
          onNotifications = {
            if (store.notificationPermissionGranted() || notificationDenied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
              store.openNotificationSettings()
            } else {
              notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
          },
          onExactAlarm = store::openExactAlarmSettings,
          onFullscreen = store::openFullscreenSettings,
          onBattery = store::openBatterySettings,
          onDeviceSupport = store::openDeviceSupportSettings,
          onRunTest = {
            if (store.scheduleOnboardingTest()) {
              refreshKey++
            }
          },
        )
        OnboardingSteps.WakeUpTest -> WakeUpTestStep(
          state = state,
          onSchedule = store::scheduleOnboardingTest,
          onContinue = { store.setOnboardingStep(OnboardingSteps.Ready) },
        )
        OnboardingSteps.Ready -> ReadyStep(
          state = state,
          capabilities = capabilities,
          onFinish = store::completeOnboarding,
        )
      }
    }
  }

  onboardingQr?.let { location ->
    OnboardingQrDialog(
      location = location,
      onDismiss = { onboardingQr = null },
    )
  }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
  OnboardingPage {
    Spacer(Modifier.weight(1f))
    Icon(
      Icons.Default.Alarm,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
      "Wake up knowing it works",
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier
        .align(Alignment.CenterHorizontally)
        .semantics { heading() },
    )
    Text(
      "Set a wake-up task, prepare this phone, and prove the alarm works before tonight.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    FeatureRow(Icons.Default.Security, "Private by design", "Object scans stay on this phone.")
    FeatureRow(Icons.Default.CheckCircle, "Tested before bedtime", "You’ll complete a real lock-screen test.")
    Spacer(Modifier.weight(1f))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
      Text("Set up my alarm")
    }
  }
}

@Composable
private fun DismissalMethodStep(
  cameraDenied: Boolean,
  onCreateQrSpot: (String) -> Unit,
  onScanObject: (String) -> Unit,
) {
  var label by remember { mutableStateOf("Fridge") }
  OnboardingPage {
    Text("Choose how to dismiss", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(
      "Pick something you must get out of bed to reach.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = label,
      onValueChange = { label = it },
      label = { Text("Object or spot name") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Button(
      onClick = { onScanObject(label.ifBlank { "Wake object" }) },
      modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
      Icon(Icons.Default.CameraAlt, contentDescription = null)
      Spacer(Modifier.padding(4.dp))
      Column {
        Text("Scan an object")
        Text("No printer needed", style = MaterialTheme.typography.labelMedium)
      }
    }
    OutlinedButton(
      onClick = { onCreateQrSpot(label.ifBlank { "Wake spot" }) },
      modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
      Icon(Icons.Default.QrCode, contentDescription = null)
      Spacer(Modifier.padding(4.dp))
      Column {
        Text("Create a QR spot")
        Text("Print or display it away from bed", style = MaterialTheme.typography.labelMedium)
      }
    }
    if (cameraDenied) {
      Text(
        "Camera access is required to scan an object. Choose Scan an object again to retry.",
        color = MaterialTheme.colorScheme.error,
      )
    }
  }
}

@Composable
private fun OnboardingQrDialog(
  location: QrLocation,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val bitmap = remember(location.payload) { qrBitmap(location.payload).asImageBitmap() }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Save your QR spot") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Screenshot or show this code on another device before continuing. You’ll scan it during the wake-up test.")
        Image(
          bitmap = bitmap,
          contentDescription = "QR code for ${location.label}",
          modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          context.startActivity(
            Intent.createChooser(
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, location.payload)
              },
              "Share QR payload",
            ),
          )
        },
      ) {
        Text("Share")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Done") }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmStep(
  state: AppState,
  store: SuperAlarmStore,
  onContinue: () -> Unit,
) {
  var showTimePicker by remember { mutableStateOf(false) }
  OnboardingPage {
    Text("Set your first alarm", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(
      "Choose a wake-up time and repeat days. You can change these later.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
      ListItem(
        headlineContent = { Text(formatOnboardingTime(state.alarm.timeMinutesLocal)) },
        supportingContent = { Text("Wake-up time") },
        leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
        trailingContent = { TextButton(onClick = { showTimePicker = true }) { Text("Edit") } },
      )
    }
    Text("Repeat", style = MaterialTheme.typography.titleMedium)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { day, name ->
        FilterChip(
          selected = day in state.alarm.repeatDays,
          onClick = { store.toggleDay(day) },
          label = { Text(name) },
          shape = CircleShape,
        )
      }
    }
    Spacer(Modifier.weight(1f))
    Button(
      onClick = onContinue,
      enabled = state.alarm.repeatDays.isNotEmpty(),
      modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
      Text("Prepare this phone")
    }
  }

  if (showTimePicker) {
    val picker = androidx.compose.material3.rememberTimePickerState(
      initialHour = state.alarm.timeMinutesLocal / 60,
      initialMinute = state.alarm.timeMinutesLocal % 60,
      is24Hour = false,
    )
    AlertDialog(
      onDismissRequest = { showTimePicker = false },
      confirmButton = {
        TextButton(onClick = {
          store.setTime(picker.hour * 60 + picker.minute)
          showTimePicker = false
        }) { Text("Save") }
      },
      dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
      text = { TimePicker(state = picker) },
    )
  }
}

@Composable
private fun PhoneSetupStep(
  capabilities: ReliabilityChecklistState,
  deviceSupport: DeviceSupport?,
  notificationPermissionGranted: Boolean,
  notificationDenied: Boolean,
  onNotifications: () -> Unit,
  onExactAlarm: () -> Unit,
  onFullscreen: () -> Unit,
  onBattery: () -> Unit,
  onDeviceSupport: () -> Unit,
  onRunTest: () -> Unit,
) {
  val requiredReady = capabilities.notificationsGranted &&
    capabilities.exactAlarmAllowed &&
    capabilities.fullscreenIntentAllowed
  OnboardingPage {
    Text("Make the alarm reliable", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(
      "Open one phone setting at a time. Super Alarm checks it when you return.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionRow(
      icon = Icons.Default.Notifications,
      title = "Notifications",
      detail = "Shows the alarm and keeps it running.",
      ready = capabilities.notificationsGranted,
      actionLabel = if (notificationPermissionGranted || notificationDenied) "Settings" else "Allow",
      onAction = onNotifications,
    )
    PermissionRow(
      icon = Icons.Default.Schedule,
      title = "Exact alarms",
      detail = "Fires at the wake-up time you chose.",
      ready = capabilities.exactAlarmAllowed,
      onAction = onExactAlarm,
    )
    PermissionRow(
      icon = Icons.Default.Lock,
      title = "Lock-screen alarm",
      detail = "Opens the ringing screen while locked.",
      ready = capabilities.fullscreenIntentAllowed,
      onAction = onFullscreen,
    )
    PermissionRow(
      icon = Icons.Default.Power,
      title = "Battery access · recommended",
      detail = "Helps on Xiaomi, Vivo, and similar phones.",
      ready = capabilities.batteryOptimizationRelaxed,
      actionLabel = "Open",
      onAction = onBattery,
    )
    DeviceSupportRow(deviceSupport = deviceSupport, onAction = onDeviceSupport)
    Spacer(Modifier.weight(1f))
    Button(
      onClick = onRunTest,
      enabled = requiredReady,
      modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
      Text(if (requiredReady) "Run wake-up test" else "Prepare required settings")
    }
  }
}

@Composable
private fun DeviceSupportRow(
  deviceSupport: DeviceSupport?,
  onAction: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    ListItem(
      headlineContent = { Text("${deviceSupport?.label ?: "Device"} background access · recommended") },
      supportingContent = {
        Text(
          "${deviceSupport?.instruction ?: "Check Battery or Background activity settings and allow Super Alarm to run unrestricted."} Android cannot report this setting back to Super Alarm.",
        )
      },
      leadingContent = { Icon(Icons.Default.Power, contentDescription = null) },
      trailingContent = { TextButton(onClick = onAction) { Text("Open") } },
    )
  }
}

@Composable
private fun WakeUpTestStep(
  state: AppState,
  onSchedule: () -> Boolean,
  onContinue: () -> Unit,
) {
  var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
  val passed = state.onboardingTestPassed()
  val scheduledAt = state.onboardingTestScheduledAtMillis
  val waiting = scheduledAt != null && nowMillis < scheduledAt + 25_000L && !passed

  LaunchedEffect(scheduledAt, passed) {
    while (!passed && scheduledAt != null && nowMillis < scheduledAt + 25_000L) {
      delay(1_000L)
      nowMillis = System.currentTimeMillis()
    }
  }

  OnboardingPage {
    Icon(
      if (passed) Icons.Default.CheckCircle else Icons.Default.Alarm,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
      if (passed) "Your wake-up test passed" else "Lock your phone",
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier
        .align(Alignment.CenterHorizontally)
        .semantics { heading() },
    )
    Text(
      when {
        passed -> "Background delivery, sound, lock-screen behavior, and dismissal all worked."
        waiting -> "Super Alarm will ring through the real Android alarm path. Dismiss it with your enrolled target."
        else -> "The test did not finish. Schedule it again, lock the phone, and dismiss it with your target."
      },
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (passed) {
      TestResultRow("Background delivery")
      TestResultRow("Sound and vibration")
      TestResultRow("Target recognition")
    } else if (waiting) {
      val triggerAtMillis = scheduledAt ?: nowMillis
      val seconds = ((triggerAtMillis - nowMillis).coerceAtLeast(0L) + 999L) / 1_000L
      Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
          headlineContent = { Text(if (seconds > 0) "Ringing in ${seconds}s" else "Alarm is due now") },
          supportingContent = { Text("Testing the real background path") },
          leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
        )
      }
    }
    Spacer(Modifier.weight(1f))
    if (passed) {
      Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("Finish setup")
      }
    } else if (!waiting) {
      Button(onClick = { onSchedule() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("Schedule 10-second test")
      }
    }
  }
}

@Composable
private fun ReadyStep(
  state: AppState,
  capabilities: ReliabilityChecklistState,
  onFinish: () -> Unit,
) {
  val target = state.objectTargets.firstOrNull { it.id in state.alarm.allowedObjectTargetIds }?.label
    ?: state.qrLocations.firstOrNull { it.id in state.alarm.allowedLocationIds }?.label
    ?: "wake-up target"
  OnboardingPage {
    Icon(
      Icons.Default.CheckCircle,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
      "Ready for tonight",
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier
        .align(Alignment.CenterHorizontally)
        .semantics { heading() },
    )
    Text(
      "${formatOnboardingTime(state.alarm.timeMinutesLocal)} · dismissed with $target",
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
      ListItem(
        headlineContent = { Text("Alarm protection") },
        supportingContent = {
          Text(if (capabilities.batteryOptimizationRelaxed) "Phone prepared and wake-up test passed" else "Wake-up test passed · battery access remains recommended")
        },
        leadingContent = { Icon(Icons.Default.Security, contentDescription = null) },
        trailingContent = { Text("Ready", color = MaterialTheme.colorScheme.primary) },
      )
    }
    Spacer(Modifier.weight(1f))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(52.dp)) {
      Text("Go to alarm")
    }
  }
}

@Composable
private fun OnboardingPage(content: @Composable ColumnScope.() -> Unit) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      Column(
        modifier = Modifier.fillParentMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
      )
    }
  }
}

@Composable
private fun FeatureRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  detail: String,
) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(detail) },
    leadingContent = { Icon(icon, contentDescription = null) },
  )
}

@Composable
private fun PermissionRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  detail: String,
  ready: Boolean,
  actionLabel: String = "Allow",
  onAction: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    ListItem(
      headlineContent = { Text(title) },
      supportingContent = { Text(detail) },
      leadingContent = { Icon(icon, contentDescription = null) },
      trailingContent = {
        if (ready) {
          Text("Ready", color = MaterialTheme.colorScheme.primary)
        } else {
          TextButton(onClick = onAction) { Text(actionLabel) }
        }
      },
    )
  }
}

@Composable
private fun TestResultRow(label: String) {
  ListItem(
    headlineContent = { Text(label) },
    trailingContent = { Text("Passed", color = MaterialTheme.colorScheme.primary) },
    leadingContent = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
  )
}

private fun previousOnboardingStep(step: Int): Int = when (step) {
  OnboardingSteps.DismissalMethod -> OnboardingSteps.Welcome
  OnboardingSteps.Alarm -> OnboardingSteps.DismissalMethod
  OnboardingSteps.PhoneSetup -> OnboardingSteps.Alarm
  OnboardingSteps.WakeUpTest -> OnboardingSteps.PhoneSetup
  OnboardingSteps.Ready -> OnboardingSteps.WakeUpTest
  else -> OnboardingSteps.Welcome
}

private fun formatOnboardingTime(minutes: Int): String {
  val time = LocalTime.of(minutes / 60, minutes % 60)
  return DateTimeFormatter.ofPattern("h:mm a").format(time)
}
