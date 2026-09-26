package io.github.tuthan.steamoscompanion.android.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Camera2 capture adapter; the visible scanner screen owns its permission request and preview. */
class CameraQrScanner(
    context: Context,
    private val onPayload: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val cameras = appContext.getSystemService(CameraManager::class.java)
        ?: throw IllegalStateException("Camera service is unavailable")
    private val main = Handler(Looper.getMainLooper())
    private val decoder = QrFrameDecoder()
    private val delivered = AtomicBoolean()
    @Volatile private var closed = false
    private var started = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastDecodeNanos = 0L

    /** Start once after CAMERA is granted. The SurfaceView/TextureView preview owns [preview]. */
    @Synchronized fun start(preview: Surface) {
        check(!closed && !started) { "Scanner has already started or closed" }
        check(appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Camera permission is required"
        }
        require(preview.isValid) { "Preview surface is unavailable" }
        started = true
        try {
            val id = cameras.cameraIdList.firstOrNull { candidate ->
                cameras.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: throw IllegalStateException("No back camera is available")
            val sizes = cameras.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val size = sizes.filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
                .minByOrNull { kotlin.math.abs(it.width * it.height - PREFERRED_PIXELS) }
                ?: throw IllegalStateException("No supported QR camera frame size")
            val worker = HandlerThread("companion-qr-camera").also { it.start() }
            thread = worker
            val handler = Handler(worker.looper)
            val images = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader = images
            images.setOnImageAvailableListener({ source -> handleFrame(source) }, handler)
            @Suppress("MissingPermission") // The permission is checked immediately above and rechecked by the platform.
            cameras.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed) { device.close(); return }
                    camera = device
                    try {
                        device.createCaptureSession(listOf(preview, images.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                if (closed) { configured.close(); return }
                                session = configured
                                try {
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(preview)
                                        addTarget(images.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }.build()
                                    configured.setRepeatingRequest(request, null, handler)
                                } catch (_: Exception) { fail("Camera preview could not start") }
                            }

                            override fun onConfigureFailed(configured: CameraCaptureSession) {
                                configured.close()
                                fail("Camera preview is unavailable")
                            }
                        }, handler)
                    } catch (_: Exception) { fail("Camera preview is unavailable") }
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    fail("Camera disconnected")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    fail("Camera is unavailable")
                }
            }, handler)
        } catch (error: Exception) {
            fail(error.message ?: "Camera is unavailable")
        }
    }

    private fun handleFrame(source: ImageReader) {
        if (closed || delivered.get()) return
        val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        image.use {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastDecodeNanos < MIN_DECODE_INTERVAL_NANOS) return
            lastDecodeNanos = now
            if (it.format != ImageFormat.YUV_420_888) return
            val y = it.planes[0]
            val buffer = y.buffer.duplicate()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val payload = try {
                decoder.decode(bytes, it.width, it.height, y.rowStride, y.pixelStride)
            } catch (_: IllegalArgumentException) { null }
            bytes.fill(0)
            if (payload != null && delivered.compareAndSet(false, true)) {
                main.post { close(); onPayload(payload) }
            }
        }
    }

    private fun fail(message: String) {
        if (closed || delivered.getAndSet(true)) return
        close()
        main.post { onFailure(message) }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        session = null
        camera = null
        reader = null
        thread?.quitSafely()
        thread = null
    }

    companion object {
        private const val MAX_WIDTH = 1_280
        private const val MAX_HEIGHT = 720
        private const val PREFERRED_PIXELS = 640 * 480
        private const val MIN_DECODE_INTERVAL_NANOS = 150_000_000L
    }
}
