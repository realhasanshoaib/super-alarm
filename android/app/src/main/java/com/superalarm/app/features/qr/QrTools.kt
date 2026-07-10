package com.superalarm.app.features.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

fun qrBitmap(payload: String, size: Int = 640): Bitmap {
  val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
  val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  for (x in 0 until size) {
    for (y in 0 until size) {
      bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
    }
  }
  return bitmap
}
