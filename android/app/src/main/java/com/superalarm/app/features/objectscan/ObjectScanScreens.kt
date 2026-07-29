package com.superalarm.app.features.objectscan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.superalarm.app.data.ObjectTarget

private val enrollmentSteps = listOf(
  "Stand about an arm's length away for a straight-on view",
  "Move a little to the left",
  "Move a little to the right",
  "Lower the phone slightly",
  "Move closer to a distinctive detail",
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
  val step = capturedEmbeddings.size.coerceAtMost(enrollmentSteps.lastIndex)
  val frameEmbedding = currentFrame?.embedding
  val distinctView = frameEmbedding != null && capturedEmbeddings.lastOrNull()?.let {
    ObjectEmbeddings.cosineSimilarity(it, frameEmbedding) < MAX_ENROLLMENT_VIEW_SIMILARITY
  } != false
  val readyFrame = currentFrame?.takeIf {
    it.capturedAt > lastCaptureAt && it.embedding != null && it.issue == null && distinctView
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    ObjectCameraView(
      modifier = Modifier.fillMaxSize(),
      onFrame = { currentFrame = it },
      onError = { error = it },
    )
    ScanGuide(color = if (readyFrame != null) Color(0xFF6EE7B7) else Color.White)
    ScanTopBar(title = "Scan $label", onClose = onCancel)
    ScanPanel(
      modifier = Modifier.align(Alignment.BottomCenter),
      title = "View ${step + 1} of ${enrollmentSteps.size}",
      instruction = error
        ?: currentFrame?.issue?.message
        ?: if (frameEmbedding != null && !distinctView) "Move farther toward the requested angle" else enrollmentSteps[step],
      progress = capturedEmbeddings.size / enrollmentSteps.size.toFloat(),
    ) {
      Button(
        enabled = readyFrame != null && error == null,
        onClick = {
          val sample = readyFrame ?: return@Button
          val embedding = sample.embedding ?: return@Button
          val next = capturedEmbeddings + embedding
          if (next.size == enrollmentSteps.size) {
            onComplete(next)
          } else {
            capturedEmbeddings = next
            lastCaptureAt = sample.capturedAt
            currentFrame = null
          }
        },
        modifier = Modifier.fillMaxWidth().height(54.dp),
      ) {
        Icon(Icons.Default.Camera, null)
        Spacer(Modifier.size(8.dp))
        Text(if (step == enrollmentSteps.lastIndex) "Save object" else "Capture view")
      }
    }
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
    mutableStateOf(ObjectMatchProgress(null, null, 0.0, 0, ObjectMatchTracker.REQUIRED_MATCHING_FRAMES))
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
    ScanGuide(color = if (progress.matchingFrames > 0) Color(0xFF6EE7B7) else Color.White)
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

private const val MAX_ENROLLMENT_VIEW_SIMILARITY = 0.995

@Composable
private fun ScanGuide(color: Color) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Box(
      Modifier
        .fillMaxWidth(0.76f)
        .aspectRatio(1f)
        .border(BorderStroke(3.dp, color), RoundedCornerShape(24.dp)),
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
