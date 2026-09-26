package io.github.tuthan.steamoscompanion.android.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Reads only the Y plane. The payload stays in memory and is handed to the pairing parser. */
class QrFrameDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    fun decode(
        plane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): String? {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION)
        require(width.toLong() * height <= MAX_PIXELS)
        require(rowStride >= width && pixelStride in 1..4)
        val lastIndex = (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride
        require(lastIndex < plane.size) { "Camera frame is incomplete" }
        val luminance = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) luminance[y * width + x] = plane[row + x * pixelStride]
        }
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
            luminance.fill(0)
        }
    }

    companion object {
        private const val MAX_DIMENSION = 1_920
        private const val MAX_PIXELS = 1_920L * 1_080L
    }
}
