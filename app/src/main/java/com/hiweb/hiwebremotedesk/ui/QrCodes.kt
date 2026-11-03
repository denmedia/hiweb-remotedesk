package com.hiweb.hiwebremotedesk.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Создание и распознавание QR-кодов (ZXing). */
object QrCodes {

    /**
     * QR-код [text] — чёрные модули на белом фоне с полями, сторона [sizePx].
     * Коррекция ошибок M: код читается и с экрана, и с фотографии; для длинного текста — L, чтобы он поместился.
     */
    fun encode(text: String, sizePx: Int): Bitmap {
        val level = if (text.length > 1200) ErrorCorrectionLevel.L else ErrorCorrectionLevel.M
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to level,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    /** Текст QR-кода на картинке (фотография, снимок экрана) или null. */
    fun decode(bitmap: Bitmap): String? {
        // Большие фотографии уменьшаем: распознавание быстрее, а модули кода всё равно различимы
        val scaled = scaleDown(bitmap, 1600)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return decode(RGBLuminanceSource(scaled.width, scaled.height, pixels))
    }

    /** Текст QR-кода в кадре камеры: [luminance] — яркостная плоскость (Y) кадра YUV. */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int): String? =
        decode(PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false))

    private fun decode(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
        // Светлый код на тёмном фоне (тёмная тема, снимок экрана) читается только инвертированным
        for (candidate in listOf(source, source.invert())) {
            try {
                return reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate))).text
            } catch (_: Exception) {
            } finally {
                reader.reset()
            }
        }
        return null
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val side = maxOf(bitmap.width, bitmap.height)
        if (side <= maxSide) return bitmap
        val scale = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }
}
