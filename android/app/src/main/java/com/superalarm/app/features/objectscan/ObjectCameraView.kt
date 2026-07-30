package com.superalarm.app.features.objectscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class ObjectFrameIssue(val message: String) {
  TooDark("More light needed"),
  TooBright("Reduce glare or bright light"),
  Blurry("Hold still for a sharper view"),
}

data class ObjectFrameSample(
  val embedding: String?,
  val issue: ObjectFrameIssue?,
  val capturedAt: Long,
  val thumbnailJpeg: ByteArray? = null,
  val thumbnailQualityScore: Double = 0.0,
)

@Composable
fun ObjectCameraView(
  modifier: Modifier = Modifier,
  captureThumbnail: Boolean = false,
  torchEnabled: Boolean = false,
  onFrame: (ObjectFrameSample) -> Unit,
  onError: (String) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val currentOnFrame = rememberUpdatedState(onFrame)
  val currentOnError = rememberUpdatedState(onError)
  val controller = remember(context, lifecycleOwner, captureThumbnail) {
    ObjectCameraController(
      context = context,
      captureThumbnail = captureThumbnail,
      onFrame = { sample ->
        ContextCompat.getMainExecutor(context).execute { currentOnFrame.value(sample) }
      },
      onError = { error ->
        ContextCompat.getMainExecutor(context).execute { currentOnError.value(error) }
      },
    )
  }

  LaunchedEffect(controller, torchEnabled) {
    controller.setTorchEnabled(torchEnabled)
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
  captureThumbnail: Boolean,
  onFrame: (ObjectFrameSample) -> Unit,
  private val onError: (String) -> Unit,
) : Closeable {
  private val executor = Executors.newSingleThreadExecutor()
  private val analyzer = ObjectFrameAnalyzer(context, captureThumbnail, onFrame, onError)
  @Volatile private var closed = false
  private var provider: ProcessCameraProvider? = null
  private var camera: Camera? = null
  private var preview: Preview? = null
  private var analysis: ImageAnalysis? = null
  @Volatile private var torchEnabled = false

  fun start(previewView: PreviewView, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
      if (closed) return@addListener
      runCatching {
        val cameraProvider = providerFuture.get()
        val previewResolutionSelector = ResolutionSelector.Builder()
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
          .build()
        val analysisResolutionSelector = ResolutionSelector.Builder()
          .setResolutionStrategy(
            ResolutionStrategy(
              ANALYSIS_RESOLUTION,
              ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
            ),
          )
          .build()
        val nextPreview = Preview.Builder()
          .setResolutionSelector(previewResolutionSelector)
          .build()
          .also {
          it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val nextAnalysis = ImageAnalysis.Builder()
          .setResolutionSelector(analysisResolutionSelector)
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { it.setAnalyzer(executor, analyzer) }

        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
          lifecycleOwner,
          CameraSelector.DEFAULT_BACK_CAMERA,
          nextPreview,
          nextAnalysis,
        )
        applyTorch()
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

  fun setTorchEnabled(enabled: Boolean) {
    torchEnabled = enabled
    ContextCompat.getMainExecutor(context).execute(::applyTorch)
  }

  private fun applyTorch() {
    val boundCamera = camera ?: return
    if (boundCamera.cameraInfo.hasFlashUnit()) {
      boundCamera.cameraControl.enableTorch(torchEnabled)
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    analysis?.clearAnalyzer()
    val bound = listOfNotNull(preview, analysis)
    if (bound.isNotEmpty()) provider?.unbind(*bound.toTypedArray())
    camera = null
    executor.execute(analyzer::close)
    executor.shutdown()
  }

  companion object {
    private val ANALYSIS_RESOLUTION = Size(640, 480)
  }
}

private class ObjectFrameAnalyzer(
  private val context: Context,
  private val captureThumbnail: Boolean,
  private val onFrame: (ObjectFrameSample) -> Unit,
  private val onError: (String) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {
  private var engine: ObjectImageEmbedder? = null
  private var lastAnalyzedAt = 0L
  private var failed = false
  private val frameInFlight = AtomicBoolean(false)
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
    if (
      failed ||
      now - lastAnalyzedAt < FRAME_INTERVAL_MILLIS ||
      !frameInFlight.compareAndSet(false, true)
    ) {
      return
    }
    lastAnalyzedAt = now

    runCatching {
      val guideRegion = centerGuideRegion(
        imageWidth = image.width,
        imageHeight = image.height,
        rotationDegrees = image.imageInfo.rotationDegrees,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
      )
      val quality = image.frameQuality(guideRegion)
      if (quality.issue != null) {
        frameInFlight.set(false)
        ObjectFrameSample(embedding = null, issue = quality.issue, capturedAt = now)
      } else {
        imageEmbedder().embedAsync(
          bitmap = image.toBitmap(),
          guideRegion = guideRegion,
          rotationDegrees = image.imageInfo.rotationDegrees,
          capturedAt = now,
          qualityScore = quality.score,
        )
        null
      }
    }.onSuccess { sample -> sample?.let(onFrame) }.onFailure {
      frameInFlight.set(false)
      failed = true
      onError(it.message ?: "Object recognition could not start")
    }
  }

  override fun close() {
    engine?.close()
  }

  private fun imageEmbedder(): ObjectImageEmbedder =
    engine ?: ObjectImageEmbedder(
      context = context,
      captureThumbnail = captureThumbnail,
      onFrame = onFrame,
      onComplete = { frameInFlight.set(false) },
      onError = onError,
    ).also { engine = it }

  companion object {
    private const val FRAME_INTERVAL_MILLIS = 100L
  }
}

private class ObjectImageEmbedder(
  context: Context,
  private val captureThumbnail: Boolean,
  private val onFrame: (ObjectFrameSample) -> Unit,
  private val onComplete: () -> Unit,
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
        val pendingFrame = synchronized(pendingFrames) { pendingFrames[capturedAt] }
        releaseFramesThrough(capturedAt)
        onComplete()
        onFrame(
          ObjectFrameSample(
            embedding = ObjectEmbeddings.encode(embedding),
            issue = null,
            capturedAt = capturedAt,
            thumbnailJpeg = pendingFrame?.thumbnailJpeg,
            thumbnailQualityScore = pendingFrame?.qualityScore ?: 0.0,
          ),
        )
      }
      .setErrorListener { error ->
        releaseAllFrames()
        onComplete()
        onError(error.message ?: "Object recognition could not start")
      }
      .build(),
  )
  private var lastThumbnailAt = 0L

  fun embedAsync(
    bitmap: Bitmap,
    guideRegion: RectF,
    rotationDegrees: Int,
    capturedAt: Long,
    qualityScore: Double,
  ) {
    val image = BitmapImageBuilder(bitmap).build()
    val thumbnailJpeg = if (
      captureThumbnail &&
      capturedAt - lastThumbnailAt >= THUMBNAIL_CANDIDATE_INTERVAL_MILLIS
    ) {
      lastThumbnailAt = capturedAt
      bitmap.objectThumbnail(guideRegion, rotationDegrees)
    } else {
      null
    }
    synchronized(pendingFrames) {
      pendingFrames[capturedAt] = PendingFrame(image, bitmap, thumbnailJpeg, qualityScore)
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

  private data class PendingFrame(
    val image: MPImage,
    val bitmap: Bitmap,
    val thumbnailJpeg: ByteArray?,
    val qualityScore: Double,
  ) {
    fun close() {
      image.close()
      bitmap.recycle()
    }
  }

  companion object {
    private const val MODEL_ASSET = "mobilenet_v3_large.tflite"
    private const val THUMBNAIL_CANDIDATE_INTERVAL_MILLIS = 250L
  }
}

private fun Bitmap.objectThumbnail(guideRegion: RectF, rotationDegrees: Int): ByteArray? =
  runCatching {
    val left = (guideRegion.left * width).toInt().coerceIn(0, width - 1)
    val top = (guideRegion.top * height).toInt().coerceIn(0, height - 1)
    val right = (guideRegion.right * width).toInt().coerceIn(left + 1, width)
    val bottom = (guideRegion.bottom * height).toInt().coerceIn(top + 1, height)
    val cropped = Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    val rotated = if (rotationDegrees == 0) {
      cropped
    } else {
      Bitmap.createBitmap(
        cropped,
        0,
        0,
        cropped.width,
        cropped.height,
        Matrix().apply { postRotate(rotationDegrees.toFloat()) },
        true,
      )
    }
    if (rotated !== cropped && cropped !== this) cropped.recycle()
    val scaled = Bitmap.createScaledBitmap(
      rotated,
      OBJECT_THUMBNAIL_SIZE,
      OBJECT_THUMBNAIL_SIZE,
      true,
    )
    if (scaled !== rotated && rotated !== this) rotated.recycle()
    val jpeg = ByteArrayOutputStream().use { output ->
      scaled.compress(Bitmap.CompressFormat.JPEG, OBJECT_THUMBNAIL_QUALITY, output)
      output.toByteArray()
    }
    if (scaled !== this) scaled.recycle()
    jpeg
  }.getOrNull()

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

private data class ObjectFrameQuality(
  val issue: ObjectFrameIssue?,
  val score: Double,
)

private fun ImageProxy.frameQuality(guideRegion: RectF): ObjectFrameQuality {
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
  val issue = when {
    averageLuminance < 38.0 -> ObjectFrameIssue.TooDark
    averageLuminance > 220.0 -> ObjectFrameIssue.TooBright
    averageEdge < 2.0 -> ObjectFrameIssue.Blurry
    else -> null
  }
  val exposureScore = (1.0 - kotlin.math.abs(averageLuminance - 128.0) / 128.0)
    .coerceIn(0.0, 1.0)
  return ObjectFrameQuality(
    issue = issue,
    score = averageEdge * (0.65 + 0.35 * exposureScore),
  )
}

private const val GUIDE_VIEW_WIDTH_FRACTION = 0.74f
private const val FALLBACK_GUIDE_FRACTION = 0.76f
private const val QUALITY_GRID_SIZE = 64
private const val OBJECT_THUMBNAIL_SIZE = 320
private const val OBJECT_THUMBNAIL_QUALITY = 84
