package com.superalarm.app.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.superalarm.app.data.AppState
import com.superalarm.app.data.ObjectTarget
import com.superalarm.app.data.QrLocation
import com.superalarm.app.data.SuperAlarmStore
import com.superalarm.app.features.objectscan.ObjectDismissalScreen
import com.superalarm.app.features.qr.QrScannerView
import com.superalarm.app.features.qr.qrBitmap

private enum class ScannerMode { Qr, Object }

@Composable
fun DismissalTargetsScreen(
  modifier: Modifier,
  state: AppState,
  store: SuperAlarmStore,
  onAddObject: (String) -> Unit,
) {
  var showAddQr by remember { mutableStateOf(false) }
  var showAddObject by remember { mutableStateOf(false) }
  var selectedQr by remember { mutableStateOf<QrLocation?>(null) }
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      Button(onClick = { showAddObject = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Default.PhotoCamera, null)
        Spacer(Modifier.size(8.dp))
        Text("Scan an object")
      }
    }
    item {
      OutlinedButton(onClick = { showAddQr = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Default.Add, null)
        Spacer(Modifier.size(8.dp))
        Text("Add QR spot")
      }
    }
    if (state.qrLocations.isEmpty() && state.objectTargets.isEmpty()) {
      item { Text("No dismissal targets yet. Scan an object or add a QR spot.", modifier = Modifier.padding(16.dp)) }
    }
    if (state.objectTargets.isNotEmpty()) {
      item { Text("Objects", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, start = 16.dp)) }
    }
    items(state.objectTargets, key = { it.id }) { target ->
      ObjectTargetRow(target, state.alarm.allowedObjectTargetIds.contains(target.id), store)
    }
    if (state.qrLocations.isNotEmpty()) {
      item { Text("QR spots", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, start = 16.dp)) }
    }
    items(state.qrLocations, key = { it.id }) { location ->
      val allowed = state.alarm.allowedLocationIds.contains(location.id)
      ListItem(
        headlineContent = { Text(location.label) },
        supportingContent = { Text(if (allowed) "Allowed to dismiss" else "Not allowed for this alarm") },
        leadingContent = { Checkbox(checked = allowed, onCheckedChange = { store.toggleAllowed(location.id) }) },
        trailingContent = {
          Row {
            IconButton(onClick = { selectedQr = location }) { Icon(Icons.Default.Share, "Show QR") }
            IconButton(onClick = { store.deleteLocation(location.id) }) { Icon(Icons.Default.Delete, "Delete") }
          }
        },
      )
    }
  }
  if (showAddQr) {
    AddSpotDialog(
      onDismiss = { showAddQr = false },
      onAdd = {
        store.addLocation(it)
        showAddQr = false
      },
    )
  }
  if (showAddObject) {
    AddObjectDialog(
      onDismiss = { showAddObject = false },
      onAdd = {
        showAddObject = false
        onAddObject(it)
      },
    )
  }
  selectedQr?.let { QrDialog(location = it, onDismiss = { selectedQr = null }) }
}

@Composable
fun RingingScreen(store: SuperAlarmStore, state: AppState) {
  val allowedQr = state.qrLocations.filter { state.alarm.allowedLocationIds.contains(it.id) }
  val allowedObjects = state.objectTargets.filter { state.alarm.allowedObjectTargetIds.contains(it.id) }
  var scanner by remember { mutableStateOf<ScannerMode?>(null) }
  var pendingScanner by remember { mutableStateOf<ScannerMode?>(null) }
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) scanner = pendingScanner
    pendingScanner = null
  }
  val openScanner: (ScannerMode) -> Unit = { mode ->
    if (store.cameraGranted()) {
      scanner = mode
    } else {
      pendingScanner = mode
      permission.launch(Manifest.permission.CAMERA)
    }
  }

  if (scanner == ScannerMode.Object) {
    ObjectDismissalScreen(
      targets = allowedObjects,
      onCancel = { scanner = null },
      onMatch = {
        if (store.dismissWithObject(it)) scanner = null
      },
    )
    return
  }

  Column(
    Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(if (state.runtime?.kind == "test") "Test alarm" else "Alarm", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
      when {
        allowedQr.isNotEmpty() && allowedObjects.isNotEmpty() -> "Scan an allowed object or QR spot to dismiss."
        allowedObjects.isNotEmpty() -> "Find and scan an allowed object to dismiss."
        else -> "Scan an allowed QR spot to dismiss."
      },
    )
    Spacer(Modifier.height(16.dp))
    allowedObjects.forEach { Text("${it.label} · object", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp)) }
    allowedQr.forEach { Text("${it.label} · QR", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp)) }
    Spacer(Modifier.height(24.dp))
    if (allowedObjects.isNotEmpty()) {
      Button(onClick = { openScanner(ScannerMode.Object) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Icon(Icons.Default.PhotoCamera, null)
        Spacer(Modifier.size(8.dp))
        Text("Scan object")
      }
    }
    if (allowedQr.isNotEmpty()) {
      OutlinedButton(
        onClick = { openScanner(ScannerMode.Qr) },
        modifier = Modifier.fillMaxWidth().padding(top = if (allowedObjects.isEmpty()) 0.dp else 8.dp).height(56.dp),
      ) {
        Icon(Icons.Default.QrCodeScanner, null)
        Spacer(Modifier.size(8.dp))
        Text("Scan QR")
      }
    }
    if (state.runtime?.kind == "test") {
      TextButton(onClick = store::dismissWithoutScanForTestOnly, modifier = Modifier.padding(top = 8.dp)) {
        Text("Stop test")
      }
    }
  }
  if (scanner == ScannerMode.Qr) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      QrScannerView(modifier = Modifier.fillMaxSize(), onPayload = { if (store.dismissWithPayload(it)) scanner = null })
      TextButton(onClick = { scanner = null }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
        Text("Close", color = Color.White)
      }
    }
  }
}

@Composable
private fun ObjectTargetRow(target: ObjectTarget, allowed: Boolean, store: SuperAlarmStore) {
  ListItem(
    headlineContent = { Text(target.label) },
    supportingContent = {
      Text(if (allowed) "${target.embeddings.size} views · allowed to dismiss" else "Not allowed for this alarm")
    },
    leadingContent = {
      Checkbox(checked = allowed, onCheckedChange = { store.toggleObjectAllowed(target.id) })
    },
    trailingContent = {
      IconButton(onClick = { store.deleteObjectTarget(target.id) }) {
        Icon(Icons.Default.Delete, "Delete ${target.label}")
      }
    },
  )
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
private fun AddObjectDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
  var label by remember { mutableStateOf("Fridge") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("New wake object") },
    text = {
      Column {
        Text("Choose a fixed object away from your bed with distinctive details.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          value = label,
          onValueChange = { label = it },
          label = { Text("Object label") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = { TextButton(onClick = { onAdd(label) }) { Text("Start scan") } },
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
        context.startActivity(
          Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
              type = "text/plain"
              putExtra(Intent.EXTRA_TEXT, location.payload)
            },
            "Share QR payload",
          ),
        )
      }) { Text("Share") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}
