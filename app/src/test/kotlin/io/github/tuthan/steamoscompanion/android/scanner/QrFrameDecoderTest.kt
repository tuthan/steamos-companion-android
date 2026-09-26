package io.github.tuthan.steamoscompanion.android.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrFrameDecoderTest {
    @Test fun decodesQrWithPaddedCameraRowStride() {
        val payload = "steamos-companion:v1:fixture-payload"
        val width = 256
        val height = 256
        val stride = width + 17
        val qr = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, width, height)
        val plane = ByteArray(stride * height) { 0xff.toByte() }
        for (y in 0 until height) for (x in 0 until width) {
            plane[y * stride + x] = if (qr[x, y]) 0 else 0xff.toByte()
        }
        assertEquals(payload, QrFrameDecoder().decode(plane, width, height, stride, 1))
    }

    @Test fun decodesQrWithInterleavedLuminancePixels() {
        val payload = "steamos-companion:v1:interleaved"
        val width = 128
        val height = 128
        val pixelStride = 2
        val rowStride = width * pixelStride + 8
        val qr = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, width, height)
        val plane = ByteArray(rowStride * height) { 0xff.toByte() }
        for (y in 0 until height) for (x in 0 until width) {
            plane[y * rowStride + x * pixelStride] = if (qr[x, y]) 0 else 0xff.toByte()
        }
        assertEquals(payload, QrFrameDecoder().decode(plane, width, height, rowStride, pixelStride))
    }

    @Test fun blankFrameDoesNotInventPayload() {
        assertNull(QrFrameDecoder().decode(ByteArray(64 * 64) { 0xff.toByte() }, 64, 64, 64, 1))
    }
}
