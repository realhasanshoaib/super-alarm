package com.superalarm.app.features.objectscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable
import java.util.TreeMap
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
        doOnLayout { controller.updateViewport(it.width, it.height) }
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
        val resolutionSelector = ResolutionSelector.Builder()
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
          .build()
        val nextPreview = Preview.Builder()
          .setResolutionSelector(resolutionSelector)
          .build()
          .also {
          it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val nextAnalysis = ImageAnalysis.Builder()
          .setResolutionSelector(resolutionSelector)
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

  fun updateViewport(width: Int, height: Int) {
    analyzer.updateViewport(width, height)
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
  @Volatile private var viewportWidth = 0
  @Volatile private var viewportHeight = 0

  fun updateViewport(width: Int, height: Int) {
    viewportWidth = width
    viewportHeight = height
  }

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
      val guideRegion = centerGuideRegion(
        imageWidth = image.width,
        imageHeight = image.height,
        rotationDegrees = image.imageInfo.rotationDegrees,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
      )
      val issue = image.frameIssue(guideRegion)
      if (issue != null) {
        ObjectFrameSample(embedding = null, issue = issue, capturedAt = now)
      } else {
        imageEmbedder().embedAsync(
          bitmap = image.toBitmap(),
          guideRegion = guideRegion,
          rotationDegrees = image.imageInfo.rotationDegrees,
          capturedAt = now,
        )
        null
      }
    }.onSuccess { sample -> sample?.let(onFrame) }.onFailure {
      failed = true
      onError(it.message ?: "Object recognition could not start")
    }
  }

  override fun close() {
    engine?.close()
  }

  private fun imageEmbedder(): ObjectImageEmbedder =
    engine ?: ObjectImageEmbedder(context, onFrame, onError).also { engine = it }

  companion object {
    private const val FRAME_INTERVAL_MILLIS = 100L
  }
}

private class ObjectImageEmbedder(
  context: Context,
  private val onFrame: (ObjectFrameSample) -> Unit,
  private val onError: (String) -> Unit,
) : Closeable {
  private val pendingFrames = TreeMap<Long, PendingFrame>()
  private val embedder = ImageEmbedder.createFromOptions(
    context,
    ImageEmbedder.ImageEmbedderOptions.builder()
      .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
      .setRunningMode(RunningMode.LIVE_STREAM)
      .setQuantize(true)
      .setResultListener { result, outputImage ->
        outputImage.close()
        val capturedAt = result.timestampMs()
        val embedding = result.embeddingResult().embeddings().first().quantizedEmbedding()
        releaseFramesThrough(capturedAt)
        onFrame(
          ObjectFrameSample(
            embedding = ObjectEmbeddings.encode(embedding),
            issue = null,
            capturedAt = capturedAt,
          ),
        )
      }
      .setErrorListener { error ->
        releaseAllFrames()
        onError(error.message ?: "Object recognition could not start")
      }
      .build(),
  )

  fun embedAsync(bitmap: Bitmap, guideRegion: RectF, rotationDegrees: Int, capturedAt: Long) {
    val image = BitmapImageBuilder(bitmap).build()
    synchronized(pendingFrames) {
      pendingFrames[capturedAt] = PendingFrame(image, bitmap)
    }
    val processing = ImageProcessingOptions.builder()
      .setRegionOfInterest(guideRegion)
      .setRotationDegrees(rotationDegrees)
      .build()
    embedder.embedAsync(image, processing, capturedAt)
  }

  override fun close() {
    embedder.close()
    releaseAllFrames()
  }

  private fun releaseFramesThrough(timestamp: Long) {
    val released = synchronized(pendingFrames) {
      val frames = pendingFrames.headMap(timestamp, true).values.toList()
      pendingFrames.headMap(timestamp, true).clear()
      frames
    }
    released.forEach(PendingFrame::close)
  }

  private fun releaseAllFrames() {
    val released = synchronized(pendingFrames) {
      pendingFrames.values.toList().also { pendingFrames.clear() }
    }
    released.forEach(PendingFrame::close)
  }

  private data class PendingFrame(val image: MPImage, val bitmap: Bitmap) {
    fun close() {
      image.close()
      bitmap.recycle()
    }
  }

  companion object {
    private const val MODEL_ASSET = "mobilenet_v3_large.tflite"
  }
}

private fun centerGuideRegion(
  imageWidth: Int,
  imageHeight: Int,
  rotationDegrees: Int,
  viewportWidth: Int,
  viewportHeight: Int,
): RectF {
  val rotatedWidth = if (rotationDegrees % 180 == 0) imageWidth else imageHeight
  val rotatedHeight = if (rotationDegrees % 180 == 0) imageHeight else imageWidth
  val side = if (viewportWidth > 0 && viewportHeight > 0) {
    val scale = maxOf(
      viewportWidth.toFloat() / rotatedWidth,
      viewportHeight.toFloat() / rotatedHeight,
    )
    (viewportWidth * GUIDE_VIEW_WIDTH_FRACTION / scale).coerceAtMost(minOf(imageWidth, imageHeight).toFloat())
  } else {
    minOf(imageWidth, imageHeight) * FALLBACK_GUIDE_FRACTION
  }
  val horizontalInset = (imageWidth - side) / 2f / imageWidth
  val verticalInset = (imageHeight - side) / 2f / imageHeight
  return RectF(horizontalInset, verticalInset, 1f - horizontalInset, 1f - verticalInset)
}

private fun ImageProxy.frameIssue(guideRegion: RectF): ObjectFrameIssue? {
  val plane = planes.first()
  val buffer = plane.buffer.duplicate()
  val left = (guideRegion.left * width).toInt()
  val top = (guideRegion.top * height).toInt()
  val right = (guideRegion.right * width).toInt()
  val bottom = (guideRegion.bottom * height).toInt()
  val side = minOf(right - left, bottom - top)
  val sampleStep = (side / QUALITY_GRID_SIZE).coerceAtLeast(2)
  val columnCount = (side + sampleStep - 1) / sampleStep
  val previousRow = IntArray(columnCount) { -1 }
  var luminanceTotal = 0L
  var edgeTotal = 0L
  var edgeSamples = 0
  var samples = 0

  var y = top
  while (y < top + side) {
    var previous = -1
    var x = left
    var column = 0
    while (x < left + side) {
      val luminance = buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
      luminanceTotal += luminance
      samples++
      if (previous >= 0) {
        edgeTotal += kotlin.math.abs(luminance - previous)
        edgeSamples++
      }
      if (previousRow[column] >= 0) {
        edgeTotal += kotlin.math.abs(luminance - previousRow[column])
        edgeSamples++
      }
      previousRow[column] = luminance
      previous = luminance
      x += sampleStep
      column++
    }
    y += sampleStep
  }

  val averageLuminance = luminanceTotal.toDouble() / samples
  val averageEdge = if (edgeSamples == 0) 0.0 else edgeTotal.toDouble() / edgeSamples
  return when {
    averageLuminance < 38.0 -> ObjectFrameIssue.TooDark
    averageLuminance > 220.0 -> ObjectFrameIssue.TooBright
    averageEdge < 2.0 -> ObjectFrameIssue.Blurry
    else -> null
  }
}

private const val GUIDE_VIEW_WIDTH_FRACTION = 0.74f
private const val FALLBACK_GUIDE_FRACTION = 0.76f
private const val QUALITY_GRID_SIZE = 64
