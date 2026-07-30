package com.superalarm.app.features.objectscan

import android.os.SystemClock
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import kotlinx.coroutines.delay

private val enrollmentSteps = listOf(
  "Center the object inside the ring",
  "Move slowly to the left",
  "Sweep slowly to the right",
  "Raise the phone a little",
  "Hold steady while the light adjusts",
  "Move closer to a distinctive detail",
  "Ease back to show the whole object",
  "Hold steady to finish",
)

@Composable
fun ObjectEnrollmentScreen(
  label: String,
  onCancel: () -> Unit,
  onComplete: (List<String>, ByteArray?) -> Unit,
) {
  BackHandler(onBack = onCancel)
  var currentFrame by remember { mutableStateOf<ObjectFrameSample?>(null) }
  var capturedEmbeddings by remember { mutableStateOf(emptyList<String>()) }
  var bestThumbnailJpeg by remember { mutableStateOf<ByteArray?>(null) }
  var bestThumbnailScore by remember { mutableStateOf(Double.NEGATIVE_INFINITY) }
  var latestThumbnailJpeg by remember { mutableStateOf<ByteArray?>(null) }
  var lastCaptureAt by remember { mutableLongStateOf(0L) }
  var error by remember { mutableStateOf<String?>(null) }
  val haptics = LocalHapticFeedback.current
  val step = capturedEmbeddings.size.coerceAtMost(enrollmentSteps.lastIndex)
  val torchEnabled = capturedEmbeddings.size >= TORCH_ENROLLMENT_START_VIEW
  val frameEmbedding = currentFrame?.embedding
  val viewSimilarity = frameEmbedding?.let { embedding ->
    capturedEmbeddings.lastOrNull()?.let { ObjectEmbeddings.cosineSimilarity(it, embedding) }
  }
  val distinctView = viewSimilarity == null || viewSimilarity < MAX_ENROLLMENT_VIEW_SIMILARITY

  LaunchedEffect(torchEnabled) {
    currentFrame = null
    lastCaptureAt = SystemClock.elapsedRealtime() + TORCH_SETTLE_MILLIS
  }

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
      onComplete(
        ObjectEmbeddings.selectDiverse(next, SAVED_ENROLLMENT_VIEWS),
        bestThumbnailJpeg ?: latestThumbnailJpeg,
      )
    } else {
      capturedEmbeddings = next
    }
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    ObjectCameraView(
      modifier = Modifier.fillMaxSize(),
      captureThumbnail = true,
      torchEnabled = torchEnabled,
      onFrame = { sample ->
        sample.thumbnailJpeg?.let { thumbnail ->
          latestThumbnailJpeg = thumbnail
          if (sample.thumbnailQualityScore >= bestThumbnailScore) {
            bestThumbnailScore = sample.thumbnailQualityScore
            bestThumbnailJpeg = thumbnail
          }
        }
        currentFrame = sample
      },
      onError = { error = it },
    )
    ScanGuide(
      color = if (frameEmbedding != null && currentFrame?.issue == null) Color(0xFF6EE7B7) else Color.White,
      progress = capturedEmbeddings.size / enrollmentSteps.size.toFloat(),
    )
    ScanTopBar(title = "Scan $label", onClose = onCancel)
    ScanPanel(
      modifier = Modifier.align(Alignment.BottomCenter),
      title = when {
        capturedEmbeddings.isEmpty() -> "Look at $label"
        torchEnabled -> "Learning it in a second light"
        else -> "Keep moving slowly"
      },
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
  BackHandler(onBack = onCancel)
  val tracker = remember(targets) { ObjectMatchTracker(targets) }
  var progress by remember {
    mutableStateOf(ObjectMatchProgress(null, null, 0.0, 0, ObjectMatchTracker.REQUIRED_MATCHES))
  }
  var issue by remember { mutableStateOf<ObjectFrameIssue?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var completed by remember { mutableStateOf(false) }
  var torchEnabled by remember { mutableStateOf(false) }
  var torchBlockedByGlare by remember { mutableStateOf(false) }

  LaunchedEffect(completed) {
    if (!completed) {
      delay(AUTO_TORCH_DELAY_MILLIS)
      if (!torchBlockedByGlare) torchEnabled = true
    }
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    ObjectCameraView(
      modifier = Modifier.fillMaxSize(),
      torchEnabled = torchEnabled,
      onFrame = { sample ->
        issue = sample.issue
        if (sample.issue == ObjectFrameIssue.TooDark && !torchBlockedByGlare) {
          torchEnabled = true
        } else if (sample.issue == ObjectFrameIssue.TooBright && torchEnabled) {
          torchEnabled = false
          torchBlockedByGlare = true
        }
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
        } else if (torchEnabled) {
          "Adding light · fill the square and move slowly"
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
private const val SAVED_ENROLLMENT_VIEWS = 6
private const val TORCH_ENROLLMENT_START_VIEW = 4
private const val TORCH_SETTLE_MILLIS = 600L
private const val AUTO_TORCH_DELAY_MILLIS = 1_200L

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
    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
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
      modifier = Modifier.navigationBarsPadding().padding(20.dp),
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
