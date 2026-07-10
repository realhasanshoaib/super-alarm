package com.superalarm.app.features.qr

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrScannerView(
  modifier: Modifier = Modifier,
  onPayload: (String) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scanner = remember {
    BarcodeScanning.getClient(
      BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build(),
    )
  }

  AndroidView(
    modifier = modifier,
    factory = { viewContext ->
      PreviewView(viewContext).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        bindCamera(viewContext, this, lifecycleOwner, scanner, onPayload)
      }
    },
  )

  DisposableEffect(Unit) {
    onDispose { scanner.close() }
  }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun bindCamera(
  context: Context,
  previewView: PreviewView,
  lifecycleOwner: androidx.lifecycle.LifecycleOwner,
  scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
  onPayload: (String) -> Unit,
) {
  val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
  cameraProviderFuture.addListener({
    val cameraProvider = cameraProviderFuture.get()
    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
    val analysis = ImageAnalysis.Builder()
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build()
      .also { analyzer ->
        analyzer.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
          processImage(imageProxy, scanner, onPayload)
        }
      }

    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
  }, ContextCompat.getMainExecutor(context))
}

@ExperimentalGetImage
private fun processImage(
  imageProxy: ImageProxy,
  scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
  onPayload: (String) -> Unit,
) {
  val mediaImage = imageProxy.image ?: run {
    imageProxy.close()
    return
  }
  val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
  scanner.process(image)
    .addOnSuccessListener { barcodes ->
      barcodes.firstOrNull()?.rawValue?.let(onPayload)
    }
    .addOnCompleteListener { imageProxy.close() }
}
