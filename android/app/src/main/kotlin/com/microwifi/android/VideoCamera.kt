package com.microwifi.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

// El servicio inicia/cierra y recibe avisos en su handler. La sesión y la codificación tienen su propio hilo.
internal class VideoCamera(
    context: Context,
    private val handler: Handler,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (Exception) -> Unit,
) : Closeable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private var worker: Handler? = null
    @Volatile private var closed = false
    private val latest = AtomicReference<ByteArray>()
    private val deliver = Runnable {
        val jpeg = latest.getAndSet(null)
        if (!closed && jpeg != null) onFrame(jpeg)
    }

    // Estado confinado al hilo de vídeo.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var startupTimeout: Runnable? = null
    private var lastFrameAt = 0L
    private val timeout = object : Runnable {
        override fun run() {
            val remaining = FRAME_TIMEOUT_MS - (SystemClock.uptimeMillis() - lastFrameAt)
            if (!closed && remaining > 0) {
                worker?.postDelayed(this, remaining)
            } else if (!closed) {
                failFromWorker(IllegalStateException("La cámara dejó de entregar fotogramas"))
            }
        }
    }

    @SuppressLint("MissingPermission") // El servicio comprueba CAMERA antes de construir VideoCamera.
    fun start() {
        if (closed || worker != null) return
        try {
            val choices = manager.cameraIdList.mapNotNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val profiles = profiles(characteristics)
                if (profiles.isEmpty()) null else CameraChoice(id, characteristics, profiles)
            }
            val choice = choices.firstOrNull {
                it.characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: choices.firstOrNull() ?: error("No hay cámara con salida YUV compatible")

            // La apertura responde al handler del servicio incluso si se detiene el hilo de vídeo antes de onOpened.
            val thread = HandlerThread("MicroWifi-video").apply { start() }
            val background = Handler(thread.looper)
            worker = background
            handler.postDelayed(openTimeout, FRAME_TIMEOUT_MS)
            manager.openCamera(choice.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    handler.removeCallbacks(openTimeout)
                    if (closed || !background.post {
                            if (closed) camera.close() else {
                                device = camera
                                configureNext(camera, choice, background, 0, null)
                            }
                        }) camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail(IllegalStateException("Cámara desconectada"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    fail(IllegalStateException("Error de cámara: $error"))
                }
            }, handler)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private val openTimeout = Runnable { fail(IllegalStateException("No se pudo abrir la cámara a tiempo")) }

    @Suppress("DEPRECATION") // La variante con Handler también funciona en API 26–27.
    private fun configureNext(camera: CameraDevice, choice: CameraChoice, background: Handler, index: Int, cause: Exception?) {
        if (closed) return
        closeOutput()
        val profile = choice.profiles.getOrNull(index)
        if (profile == null) {
            failFromWorker(IllegalStateException("No se pudo iniciar la captura continua", cause))
            return
        }
        val size = profile.size
        try {
            val output = ImageReader.newInstance(size.width, size.height, FORMAT, 2)
            reader = output
            val encoder = FrameEncoder()
            var streaming = false
            // Si la configuración se acepta pero no produce imágenes, se intenta el siguiente tamaño.
            val startup = Runnable {
                if (!closed && reader === output && !streaming) {
                    configureNext(camera, choice, background, index + 1, IllegalStateException("Sin imagen en $size"))
                }
            }
            startupTimeout = startup
            background.postDelayed(startup, FRAME_TIMEOUT_MS)
            var encodingScheduled = false
            val encode = Runnable {
                encodingScheduled = false
                if (!closed && reader === output) try {
                    // No se retienen imágenes entre callbacks. Si el JPEG tarda, se descartan imágenes antiguas.
                    val jpeg = output.acquireLatestImage()?.use(encoder::encode)
                    if (jpeg != null) {
                        lastFrameAt = SystemClock.uptimeMillis()
                        if (!streaming) {
                            streaming = true
                            background.removeCallbacks(startup)
                            startupTimeout = null
                            background.postDelayed(timeout, FRAME_TIMEOUT_MS)
                        }
                        if (latest.getAndSet(jpeg) == null) handler.post(deliver)
                    }
                } catch (e: Exception) {
                    if (streaming) {
                        failFromWorker(e)
                    } else {
                        configureNext(camera, choice, background, index + 1, e)
                    }
                }
            }
            output.setOnImageAvailableListener({ source ->
                // Fusiona avisos: los callbacks ya encolados se despachan antes de volver a codificar.
                if (!closed && reader === source && !encodingScheduled) {
                    encodingScheduled = background.post(encode)
                }
            }, background)
            camera.createCaptureSession(listOf(output.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (closed || reader !== output) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        try {
                            val request = previewRequest(camera, output, choice.characteristics, profile.fps, true)
                            configured.setRepeatingRequest(request, null, background)
                        } catch (e: IllegalArgumentException) {
                            // Algunos controladores rechazan ajustes anunciados; conservar los de la plantilla.
                            val request = previewRequest(camera, output, choice.characteristics, null, false)
                            configured.setRepeatingRequest(request, null, background)
                        }
                        Log.d(TAG, "Captura continua $size; rango AE preferido: ${profile.fps ?: "predeterminado"}")
                    } catch (e: Exception) {
                        configureNext(camera, choice, background, index + 1, e)
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    if (!closed && reader === output) {
                        configureNext(camera, choice, background, index + 1, IllegalStateException("Salida YUV rechazada: $size"))
                    }
                }
            }, background)
        } catch (e: Exception) {
            configureNext(camera, choice, background, index + 1, e)
        }
    }

    private fun previewRequest(
        camera: CameraDevice,
        output: ImageReader,
        characteristics: CameraCharacteristics,
        fps: Range<Int>?,
        tune: Boolean,
    ) = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(output.surface)
        if (tune) {
            val keys = characteristics.availableCaptureRequestKeys.orEmpty()
            if (CaptureRequest.CONTROL_AE_MODE in keys && characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                    ?.contains(CaptureRequest.CONTROL_AE_MODE_ON) == true) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            val focusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            if (CaptureRequest.CONTROL_AF_MODE in keys && focusModes != null) {
                val focus = listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    .firstOrNull { it in focusModes }
                if (focus != null) set(CaptureRequest.CONTROL_AF_MODE, focus)
            }
            if (CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE in keys && fps != null) {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            }
        }
    }.build()

    private fun profiles(characteristics: CameraCharacteristics): List<Profile> {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes = map.getOutputSizes(FORMAT).orEmpty().filter { it.width % 2 == 0 && it.height % 2 == 0 }
        val candidates = sizes.filter { it.width <= 1280 && it.height <= 720 }
            .ifEmpty { listOfNotNull(sizes.minByOrNull { it.width * it.height }) }
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return candidates.map { size ->
            val minDuration = map.getOutputMinFrameDuration(FORMAT, size)
            val limit = if (minDuration > 0L) (1_000_000_000.0 / minDuration).roundToInt() else null
            val preferred = preferredVideoFpsRange(ranges.map { it.lower..it.upper }, limit)
            Profile(size, preferred?.let { Range(it.first, it.last) }, limit)
        }.sortedWith(compareByDescending<Profile> { (it.maxFps ?: 30) >= 24 }
            .thenByDescending { it.size.width * it.size.height })
    }

    private fun failFromWorker(error: Exception) {
        closeOutput()
        handler.post { fail(error) }
    }

    private fun fail(error: Exception) {
        if (closed) return
        close()
        onError(error)
    }

    private fun closeOutput() {
        worker?.removeCallbacks(timeout)
        startupTimeout?.let { worker?.removeCallbacks(it) }
        startupTimeout = null
        runCatching { session?.close() }
        runCatching { reader?.close() }
        session = null
        reader = null
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(openTimeout)
        handler.removeCallbacks(deliver)
        latest.set(null)
        val background = worker ?: return
        background.post {
            runCatching { device?.close() }
            device = null
            closeOutput()
            latest.set(null)
            handler.removeCallbacks(deliver)
            background.looper.quitSafely()
        }
    }

    private data class CameraChoice(val id: String, val characteristics: CameraCharacteristics, val profiles: List<Profile>)
    private data class Profile(val size: Size, val fps: Range<Int>?, val maxFps: Int?)

    private companion object {
        const val TAG = "MicroWifi-video"
        const val FORMAT = ImageFormat.YUV_420_888
        const val FRAME_TIMEOUT_MS = 8_000L
    }
}

internal fun preferredVideoFpsRange(ranges: List<IntRange>, streamLimit: Int?): IntRange? {
    val limit = minOf(streamLimit ?: 30, 30)
    return ranges.filter { it.first <= limit && it.last <= 30 }
        .maxWithOrNull(compareBy<IntRange> { minOf(it.last, limit) >= 24 }
        // Una cadencia variable da margen a la autoexposición cuando hay poca luz.
            .thenBy { it.first < it.last }
            .thenBy { minOf(it.last, limit) }
            .thenByDescending { it.first })
}

private class FrameEncoder {
    private var nv21 = ByteArray(0)
    private val jpeg = ByteArrayOutputStream(128 * 1024)

    fun encode(image: Image): ByteArray {
        val planes = image.planes
        val crop = image.cropRect
        // NV21 requiere que el recorte esté alineado a muestras de croma de 2 × 2.
        val left = (crop.left + 1) and -2
        val top = (crop.top + 1) and -2
        val width = (crop.right - left) and -2
        val height = (crop.bottom - top) and -2
        check(width > 0 && height > 0) { "Recorte YUV vacío" }
        val bytes = width * height * 3 / 2
        if (nv21.size < bytes) nv21 = ByteArray(bytes)
        for (index in planes.indices) {
            val plane = planes[index]
            val chroma = index != 0
            copyVideoPlane(plane.buffer, plane.rowStride, plane.pixelStride,
                if (chroma) left / 2 else left, if (chroma) top / 2 else top,
                if (chroma) width / 2 else width, if (chroma) height / 2 else height,
                nv21, if (chroma) width * height + (2 - index) else 0, if (chroma) 2 else 1)
        }
        jpeg.reset()
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        check(yuv.compressToJpeg(Rect(0, 0, width, height), 80, jpeg)) { "No se pudo codificar el fotograma JPEG" }
        return jpeg.toByteArray()
    }
}

// Lee sólo muestras válidas: el último renglón del plano puede no incluir relleno.
internal fun copyVideoPlane(
    source: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int, width: Int, height: Int,
    target: ByteArray, offset: Int, step: Int,
) {
    val buffer = source.duplicate()
    val start = buffer.position() + top * rowStride + left * pixelStride
    if (step == 1 && pixelStride == 1) {
        if (rowStride == width) {
            buffer.position(start)
            buffer.get(target, offset, width * height)
        } else {
            for (row in 0 until height) {
                buffer.position(start + row * rowStride)
                buffer.get(target, offset + row * width, width)
            }
        }
    } else {
        var destination = offset
        for (row in 0 until height) {
            var sample = start + row * rowStride
            repeat(width) {
                target[destination] = buffer.get(sample)
                destination += step
                sample += pixelStride
            }
        }
    }
}
