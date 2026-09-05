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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.superalarm.app.data.OnboardingSteps
import com.superalarm.app.data.QrLocation
import com.superalarm.app.data.ReliabilityChecklistState
import com.superalarm.app.data.SuperAlarmStore
import com.superalarm.app.data.resumeOnboardingStep
import com.superalarm.app.features.qr.qrBitmap
import com.superalarm.app.telemetry.ProductEvent
import com.superalarm.app.telemetry.SuperAlarmTelemetry
import com.superalarm.app.telemetry.TelemetryEventName
import com.superalarm.app.telemetry.TelemetryOutcome
import com.superalarm.app.telemetry.TelemetryPermissionKind
import com.superalarm.app.telemetry.TelemetrySource
import com.superalarm.app.telemetry.TelemetrySurface
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
  var refreshKey by remember { mutableStateOf(0) }
  var notificationDenied by remember { mutableStateOf(false) }
  var onboardingQr by remember { mutableStateOf<QrLocation?>(null) }
  var showSkipConfirmation by remember { mutableStateOf(false) }
  var activationFailed by remember { mutableStateOf(false) }
  val capabilities = remember(refreshKey, state.onboardingStep) { store.capabilities() }
  val step = state.resumeOnboardingStep(capabilities)
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    notificationDenied = !granted
    SuperAlarmTelemetry.capture(ProductEvent(
      TelemetryEventName.PermissionResultObserved,
      source = TelemetrySource.Onboarding,
      outcome = if (granted) TelemetryOutcome.Allowed else TelemetryOutcome.Denied,
      permissionKind = TelemetryPermissionKind.Notifications,
    ))
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

  LaunchedEffect(step) {
    SuperAlarmTelemetry.capture(ProductEvent(
      name = TelemetryEventName.OnboardingStepViewed,
      surface = onboardingSurface(step),
      onboardingStep = step,
    ))
  }

  LaunchedEffect(step, capabilities) {
    if (step == OnboardingSteps.PhoneSetup) {
      store.capturePhoneSetupReadiness(TelemetrySource.Onboarding)
    }
  }

  LaunchedEffect(Unit) {
    SuperAlarmTelemetry.capture(ProductEvent(TelemetryEventName.OnboardingStarted, source = TelemetrySource.Onboarding))
  }

  BackHandler(enabled = step != OnboardingSteps.DismissalMethod) {
    store.setOnboardingStep(previousOnboardingStep(step))
  }

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("Super Alarm") },
        navigationIcon = {
          if (step != OnboardingSteps.DismissalMethod) {
            IconButton(onClick = { store.setOnboardingStep(previousOnboardingStep(step)) }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
          }
        },
        actions = {
          TextButton(onClick = {
            SuperAlarmTelemetry.capture(ProductEvent(TelemetryEventName.OnboardingSkipOpened, onboardingStep = step))
            showSkipConfirmation = true
          }) {
            Text("Finish later")
          }
          Text("${activationStepNumber(step)} of 3", style = MaterialTheme.typography.labelLarge)
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
        progress = { activationStepNumber(step) / 3f },
        modifier = Modifier.fillMaxWidth(),
      )
      when (step) {
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
          notificationPermissionGranted = store.notificationPermissionGranted(),
          notificationDenied = notificationDenied,
          onNotifications = {
            SuperAlarmTelemetry.capture(ProductEvent(
              TelemetryEventName.PermissionActionSelected,
              source = TelemetrySource.Onboarding,
              permissionKind = TelemetryPermissionKind.Notifications,
            ))
            if (store.notificationPermissionGranted() || notificationDenied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
              store.openNotificationSettings()
            } else {
              notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
          },
          onExactAlarm = store::openExactAlarmSettings,
          onFullscreen = store::openFullscreenSettings,
          activationFailed = activationFailed,
          onFinish = {
            activationFailed = !store.completeOnboarding()
          },
        )
        else -> Unit
      }
    }
  }

  onboardingQr?.let { location ->
    OnboardingQrDialog(
      location = location,
      onDismiss = { onboardingQr = null },
    )
  }

  if (showSkipConfirmation) {
    AlertDialog(
      onDismissRequest = {
        SuperAlarmTelemetry.capture(ProductEvent(TelemetryEventName.OnboardingSkipCancelled, onboardingStep = step))
        showSkipConfirmation = false
      },
      title = { Text("Finish setup later?") },
      text = { Text("Your progress is saved. The home screen will show what remains before your alarm is ready.") },
      confirmButton = {
        TextButton(onClick = store::finishOnboardingLater) { Text("Finish later") }
      },
      dismissButton = {
        TextButton(onClick = {
          SuperAlarmTelemetry.capture(ProductEvent(TelemetryEventName.OnboardingSkipCancelled, onboardingStep = step))
          showSkipConfirmation = false
        }) { Text("Continue setup") }
      },
    )
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
        Text("Screenshot or show this code on another device. You’ll scan it to dismiss your alarm.")
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
    Spacer(Modifier.height(16.dp))
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
  notificationPermissionGranted: Boolean,
  notificationDenied: Boolean,
  onNotifications: () -> Unit,
  onExactAlarm: () -> Unit,
  onFullscreen: () -> Unit,
  activationFailed: Boolean,
  onFinish: () -> Unit,
) {
  val requiredReady = capabilities.notificationsGranted &&
    capabilities.exactAlarmAllowed &&
    capabilities.fullscreenIntentAllowed
  OnboardingPage {
    Text("Allow your alarm to ring", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(
      "These three permissions are required. Super Alarm checks each one when you return.",
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
    if (activationFailed) {
      Text(
        "The alarm could not be scheduled. Check the required settings and try again.",
        color = MaterialTheme.colorScheme.error,
      )
    }
    Spacer(Modifier.height(16.dp))
    Button(
      onClick = onFinish,
      enabled = requiredReady,
      modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
      Text(if (requiredReady) "Enable my alarm" else "Allow required permissions")
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
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
      )
    }
  }
}

private fun onboardingSurface(step: Int): TelemetrySurface = when (step) {
  OnboardingSteps.Welcome -> TelemetrySurface.OnboardingWelcome
  OnboardingSteps.DismissalMethod -> TelemetrySurface.OnboardingDismissalMethod
  OnboardingSteps.Alarm -> TelemetrySurface.OnboardingAlarm
  OnboardingSteps.PhoneSetup -> TelemetrySurface.OnboardingPhoneSetup
  OnboardingSteps.WakeUpTest -> TelemetrySurface.OnboardingWakeUpTest
  else -> TelemetrySurface.OnboardingReady
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

private fun previousOnboardingStep(step: Int): Int = when (step) {
  OnboardingSteps.Alarm -> OnboardingSteps.DismissalMethod
  OnboardingSteps.PhoneSetup -> OnboardingSteps.Alarm
  else -> OnboardingSteps.DismissalMethod
}

private fun activationStepNumber(step: Int): Int = when (step) {
  OnboardingSteps.DismissalMethod -> 1
  OnboardingSteps.Alarm -> 2
  else -> 3
}

private fun formatOnboardingTime(minutes: Int): String {
  val time = LocalTime.of(minutes / 60, minutes % 60)
  return DateTimeFormatter.ofPattern("h:mm a").format(time)
}
