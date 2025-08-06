package com.tak.lite.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CryptoQRCodeGenerator @Inject constructor() {
    private val TAG = "CryptoQRCodeGenerator"

    fun generateBitcoinQRCode(address: String, amount: String? = null): Bitmap? {
        val qrContent = if (amount != null) {
            "bitcoin:$address?amount=$amount"
        } else {
            "bitcoin:$address"
        }
        return generateQRCode(qrContent, 512, 512)
    }



    fun generateEthereumQRCode(address: String, amount: String? = null): Bitmap? {
        val qrContent = if (amount != null) {
            "ethereum:$address?amount=$amount"
        } else {
            "ethereum:$address"
        }
        return generateQRCode(qrContent, 512, 512)
    }

    private fun generateQRCode(content: String, width: Int, height: Int): Bitmap? {
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1

            val qrCodeWriter = QRCodeWriter()
            val bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height, hints)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error generating QR code", e)
            null
        }
    }

    fun generateGenericQRCode(content: String, width: Int = 512, height: Int = 512): Bitmap? {
        return generateQRCode(content, width, height)
    }
} 