package com.superalarm.app.features.objectscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable
import java.util.concurrent.Executors

enum class ObjectFrameIssue(val message: String) {
  TooDark("More light needed"),
  TooBright("Reduce glare or bright light"),
  Blurry("Hold still for a sharper view"),
}

data class ObjectFrameSample(
  val embedding: String?,
  val issue: ObjectFrameIssue?,
  val capturedAt: Long,
)

@Composable
fun ObjectCameraView(
  modifier: Modifier = Modifier,
  onFrame: (ObjectFrameSample) -> Unit,
  onError: (String) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val currentOnFrame = rememberUpdatedState(onFrame)
  val currentOnError = rememberUpdatedState(onError)
  val controller = remember(context, lifecycleOwner) {
    ObjectCameraController(
      context = context,
      onFrame = { sample ->
        ContextCompat.getMainExecutor(context).execute { currentOnFrame.value(sample) }
      },
      onError = { error ->
        ContextCompat.getMainExecutor(context).execute { currentOnError.value(error) }
      },
    )
  }

  AndroidView(
    modifier = modifier,
    factory = { viewContext ->
      PreviewView(viewContext).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        controller.start(this, lifecycleOwner)
      }
    },
  )

  DisposableEffect(controller) {
    onDispose { controller.close() }
  }
}

private class ObjectCameraController(
  private val context: Context,
  onFrame: (ObjectFrameSample) -> Unit,
  private val onError: (String) -> Unit,
) : Closeable {
  private val executor = Executors.newSingleThreadExecutor()
  private val analyzer = ObjectFrameAnalyzer(context, onFrame, onError)
  @Volatile private var closed = false
  private var provider: ProcessCameraProvider? = null
  private var preview: Preview? = null
  private var analysis: ImageAnalysis? = null

  fun start(previewView: PreviewView, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
      if (closed) return@addListener
      runCatching {
        val cameraProvider = providerFuture.get()
        val nextPreview = Preview.Builder().build().also {
          it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val nextAnalysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { it.setAnalyzer(executor, analyzer) }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
          lifecycleOwner,
          CameraSelector.DEFAULT_BACK_CAMERA,
          nextPreview,
          nextAnalysis,
        )
        provider = cameraProvider
        preview = nextPreview
        analysis = nextAnalysis
      }.onFailure {
        if (!closed) onError(it.message ?: "Camera could not start")
      }
    }, ContextCompat.getMainExecutor(context))
  }

  override fun close() {
    if (closed) return
    closed = true
    analysis?.clearAnalyzer()
    val bound = listOfNotNull(preview, analysis)
    if (bound.isNotEmpty()) provider?.unbind(*bound.toTypedArray())
    executor.execute(analyzer::close)
    executor.shutdown()
  }
}

private class ObjectFrameAnalyzer(
  private val context: Context,
  private val onFrame: (ObjectFrameSample) -> Unit,
  private val onError: (String) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {
  private var engine: ObjectImageEmbedder? = null
  private var lastAnalyzedAt = 0L
  private var failed = false

  override fun analyze(image: ImageProxy) {
    try {
      analyzeFrame(image)
    } finally {
      image.close()
    }
  }

  private fun analyzeFrame(image: ImageProxy) {
    val now = SystemClock.elapsedRealtime()
    if (failed || now - lastAnalyzedAt < FRAME_INTERVAL_MILLIS) return
    lastAnalyzedAt = now

    runCatching {
      val bitmap = image.toBitmap()
      try {
        val oriented = bitmap.oriented(image.imageInfo.rotationDegrees)
        try {
          val cropped = oriented.centerGuideCrop()
          try {
            val input = cropped.scale(INPUT_SIZE, INPUT_SIZE)
            try {
              val issue = input.frameIssue()
              val embedding = if (issue == null) imageEmbedder().embed(input) else null
              ObjectFrameSample(embedding = embedding, issue = issue, capturedAt = now)
            } finally {
              if (input !== cropped) input.recycle()
            }
          } finally {
            cropped.recycle()
          }
        } finally {
          if (oriented !== bitmap) oriented.recycle()
        }
      } finally {
        bitmap.recycle()
      }
    }.onSuccess(onFrame).onFailure {
      failed = true
      onError(it.message ?: "Object recognition could not start")
    }
  }

  override fun close() {
    engine?.close()
  }

  private fun imageEmbedder(): ObjectImageEmbedder =
    engine ?: ObjectImageEmbedder(context).also { engine = it }

  companion object {
    private const val FRAME_INTERVAL_MILLIS = 400L
    private const val INPUT_SIZE = 320
  }
}

private class ObjectImageEmbedder(context: Context) : Closeable {
  private val embedder = ImageEmbedder.createFromOptions(
    context,
    ImageEmbedder.ImageEmbedderOptions.builder()
      .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
      .setRunningMode(RunningMode.IMAGE)
      .setQuantize(true)
      .build(),
  )

  fun embed(bitmap: Bitmap): String {
    val image = BitmapImageBuilder(bitmap).build()
    return try {
      val embedding = embedder.embed(image).embeddingResult().embeddings().first()
      ObjectEmbeddings.encode(embedding.quantizedEmbedding())
    } finally {
      image.close()
    }
  }

  override fun close() = embedder.close()

  companion object {
    private const val MODEL_ASSET = "mobilenet_v3_large.tflite"
  }
}

private fun Bitmap.oriented(rotationDegrees: Int): Bitmap {
  if (rotationDegrees == 0) return this
  val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
  return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.centerGuideCrop(): Bitmap {
  val side = (minOf(width, height) * 0.76f).toInt()
  val left = (width - side) / 2
  val top = (height - side) / 2
  return Bitmap.createBitmap(this, left, top, side, side)
}

private fun Bitmap.frameIssue(): ObjectFrameIssue? {
  val pixels = IntArray(width * height)
  getPixels(pixels, 0, width, 0, 0, width, height)
  var luminanceTotal = 0L
  var edgeTotal = 0L
  var edgeSamples = 0

  pixels.indices.forEach { index ->
    val luminance = pixels[index].luminance()
    luminanceTotal += luminance
    if (index % width != 0) {
      edgeTotal += kotlin.math.abs(luminance - pixels[index - 1].luminance())
      edgeSamples++
    }
    if (index >= width) {
      edgeTotal += kotlin.math.abs(luminance - pixels[index - width].luminance())
      edgeSamples++
    }
  }

  val averageLuminance = luminanceTotal.toDouble() / pixels.size
  val averageEdge = edgeTotal.toDouble() / edgeSamples
  return when {
    averageLuminance < 38.0 -> ObjectFrameIssue.TooDark
    averageLuminance > 220.0 -> ObjectFrameIssue.TooBright
    averageEdge < 3.0 -> ObjectFrameIssue.Blurry
    else -> null
  }
}

private fun Int.luminance(): Int {
  val red = this shr 16 and 0xFF
  val green = this shr 8 and 0xFF
  val blue = this and 0xFF
  return (red * 77 + green * 150 + blue * 29) shr 8
}
