package com.superalarm.app.features.objectscan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.superalarm.app.data.ObjectTarget

private val enrollmentSteps = listOf(
  "Center the object inside the ring",
  "Move slowly to the left",
  "Sweep slowly to the right",
  "Raise the phone a little",
  "Lower the phone a little",
  "Move closer to a distinctive detail",
  "Ease back to show the whole object",
  "Hold steady to finish",
)

@Composable
fun ObjectEnrollmentScreen(
  label: String,
  onCancel: () -> Unit,
  onComplete: (List<String>) -> Unit,
) {
  var currentFrame by remember { mutableStateOf<ObjectFrameSample?>(null) }
  var capturedEmbeddings by remember { mutableStateOf(emptyList<String>()) }
  var lastCaptureAt by remember { mutableLongStateOf(0L) }
  var error by remember { mutableStateOf<String?>(null) }
  val haptics = LocalHapticFeedback.current
  val step = capturedEmbeddings.size.coerceAtMost(enrollmentSteps.lastIndex)
  val frameEmbedding = currentFrame?.embedding
  val viewSimilarity = frameEmbedding?.let { embedding ->
    capturedEmbeddings.lastOrNull()?.let { ObjectEmbeddings.cosineSimilarity(it, embedding) }
  }
  val distinctView = viewSimilarity == null || viewSimilarity < MAX_ENROLLMENT_VIEW_SIMILARITY

  LaunchedEffect(currentFrame?.capturedAt) {
    val sample = currentFrame ?: return@LaunchedEffect
    val embedding = sample.embedding ?: return@LaunchedEffect
    val captureDelay = sample.capturedAt - lastCaptureAt
    if (
      sample.issue != null ||
      captureDelay < AUTO_CAPTURE_INTERVAL_MILLIS ||
      (!distinctView && captureDelay < AUTO_CAPTURE_FALLBACK_MILLIS)
    ) {
      return@LaunchedEffect
    }

    val next = capturedEmbeddings + embedding
    lastCaptureAt = sample.capturedAt
    currentFrame = null
    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    if (next.size == enrollmentSteps.size) {
      onComplete(ObjectEmbeddings.selectDiverse(next, SAVED_ENROLLMENT_VIEWS))
    } else {
      capturedEmbeddings = next
    }
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    ObjectCameraView(
      modifier = Modifier.fillMaxSize(),
      onFrame = { currentFrame = it },
      onError = { error = it },
    )
    ScanGuide(
      color = if (frameEmbedding != null && currentFrame?.issue == null) Color(0xFF6EE7B7) else Color.White,
      progress = capturedEmbeddings.size / enrollmentSteps.size.toFloat(),
    )
    ScanTopBar(title = "Scan $label", onClose = onCancel)
    ScanPanel(
      modifier = Modifier.align(Alignment.BottomCenter),
      title = if (capturedEmbeddings.isEmpty()) "Look at $label" else "Keep moving slowly",
      instruction = error
        ?: currentFrame?.issue?.message
        ?: if (frameEmbedding != null && !distinctView) "Move a little farther in that direction" else enrollmentSteps[step],
      progress = capturedEmbeddings.size / enrollmentSteps.size.toFloat(),
    )
  }
}

@Composable
fun ObjectDismissalScreen(
  targets: List<ObjectTarget>,
  onCancel: () -> Unit,
  onMatch: (String) -> Unit,
) {
  val tracker = remember(targets) { ObjectMatchTracker(targets) }
  var progress by remember {
    mutableStateOf(ObjectMatchProgress(null, null, 0.0, 0, ObjectMatchTracker.REQUIRED_MATCHES))
  }
  var issue by remember { mutableStateOf<ObjectFrameIssue?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var completed by remember { mutableStateOf(false) }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    ObjectCameraView(
      modifier = Modifier.fillMaxSize(),
      onFrame = { sample ->
        issue = sample.issue
        val embedding = sample.embedding
        if (embedding != null && !completed) {
          progress = tracker.accept(embedding, sample.capturedAt)
          if (progress.accepted) {
            completed = true
            progress.targetId?.let(onMatch)
          }
        } else if (sample.issue != null) {
          tracker.reset()
          progress = progress.copy(matchingFrames = 0)
        }
      },
      onError = { error = it },
    )
    ScanGuide(
      color = if (progress.matchingFrames > 0) Color(0xFF6EE7B7) else Color.White,
      progress = progress.matchingFrames / progress.requiredFrames.toFloat(),
    )
    ScanTopBar(title = "Find your object", onClose = onCancel)
    ScanPanel(
      modifier = Modifier.align(Alignment.BottomCenter),
      title = progress.targetLabel?.let { "Recognizing $it" } ?: "Move close to your object",
      instruction = error
        ?: issue?.message
        ?: if (progress.matchingFrames > 0) {
          "${(progress.similarity * 100).toInt()}% match · hold steady"
        } else {
          "Stand about an arm's length away, fill the square, then move slowly"
        },
      progress = progress.matchingFrames / progress.requiredFrames.toFloat(),
    )
  }
}

private const val MAX_ENROLLMENT_VIEW_SIMILARITY = 0.998
private const val AUTO_CAPTURE_INTERVAL_MILLIS = 325L
private const val AUTO_CAPTURE_FALLBACK_MILLIS = 900L
private const val SAVED_ENROLLMENT_VIEWS = 5

@Composable
private fun ScanGuide(color: Color, progress: Float) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(
      progress = { progress.coerceIn(0f, 1f) },
      modifier = Modifier.fillMaxWidth(0.79f).aspectRatio(1f),
      color = Color(0xFF6EE7B7),
      trackColor = Color.White.copy(alpha = 0.28f),
      strokeWidth = 7.dp,
    )
    Box(
      Modifier
        .fillMaxWidth(0.74f)
        .aspectRatio(1f)
        .border(BorderStroke(2.dp, color), RoundedCornerShape(96.dp)),
    )
  }
}

@Composable
private fun ScanTopBar(title: String, onClose: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = Color.Black.copy(alpha = 0.66f),
    contentColor = Color.White,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close scanner") }
      Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun ScanPanel(
  modifier: Modifier,
  title: String,
  instruction: String,
  progress: Float,
  content: @Composable () -> Unit = {},
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = Color.Black.copy(alpha = 0.76f),
    contentColor = Color.White,
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
      Text(instruction, style = MaterialTheme.typography.bodyLarge)
      LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
      )
      content()
    }
  }
}
