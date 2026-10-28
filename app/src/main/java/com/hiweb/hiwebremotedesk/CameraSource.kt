package com.hiweb.hiwebremotedesk

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.hiweb.hiwebremotedesk.rdp.EcamCamera
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Камеры планшета для канала rdpecam: Camera2 отдаёт YUV_420_888; кадр упаковывается в NV12 и,
 * если Windows выбрала MJPG, сжимается в JPEG.
 *
 * Каждый объектив — отдельное устройство в Windows (тыльная основная, ультраширокая, телеобъектив,
 * фронтальная…), так что переключение делается в самой программе на ПК. Датчики глубины и ИК
 * не предлагаются (не умеют обычную YUV-сессию и подвешивают канал), одинаковые по фокусному
 * расстоянию дубли одной стороны отбрасываются. Если ультраширокого объектива нет отдельным
 * идентификатором, но логическая камера умеет зум меньше 1× (Pixel и подобные), он предлагается
 * как зум до минимума.
 *
 * Кадр всегда поворачивается так, чтобы картинка была прямой при планшете, лежащем в альбомной ориентации
 * (по `SENSOR_ORIENTATION`), плюс дополнительный поворот [rotations] из настроек ПК; камеры из [off]
 * Windows не предлагаются.
 */
class CameraSource(
    private val context: Context,
    /** Идентификаторы камер ([catalog]), которые не показываются Windows. */
    off: Set<String> = emptySet(),
    /** Дополнительный поворот по часовой стрелке (0/90/180/270°) по идентификатору камеры. */
    rotations: Map<String, Int> = emptyMap(),
) : EcamCamera {
    @Volatile private var off = off
    @Volatile private var rotations = rotations

    /** Камеры вообще показываются Windows (кнопка «Камера» в сеансе). */
    @Volatile private var sharing = true

    /** Тип объектива по эквивалентному фокусному расстоянию. */
    private enum class Kind { MAIN, ULTRA, TELE }

    /**
     * Предлагаемая Windows камера: [id] — наш идентификатор (физический id, для зума ещё «:z<зум>»),
     * [baseRotation] — поворот по часовой стрелке, после которого кадр прямой при альбомном положении планшета.
     */
    private class Lens(
        val id: String,
        val cameraId: String,
        val zoom: Float?,
        val name: String,
        val baseRotation: Int,
    )

    private class Raw(
        val id: String,
        val facing: Int,
        val orientation: Int,
        /** Эквивалент на полный кадр, мм; null — характеристики не сообщены. */
        val equiv: Float?,
        val zoomMin: Float,
    ) {
        val kind: Kind
            get() = when {
                equiv == null -> Kind.MAIN
                equiv <= ULTRA_MAX_MM -> Kind.ULTRA
                equiv > TELE_MIN_MM -> Kind.TELE
                else -> Kind.MAIN
            }
    }

    private val manager by lazy { context.getSystemService(CameraManager::class.java) }
    private val lenses: List<Lens> by lazy { runCatching { discover() }.getOrDefault(emptyList()) }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: android.hardware.camera2.CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var request: CaptureRequest? = null

    /** Наш идентификатор камеры, которая сейчас снимает. */
    @Volatile private var activeId: String? = null
    @Volatile private var running = false
    private val starting = AtomicBoolean(false)

    /** Все камеры планшета (для настроек), включая отключённые. */
    override fun catalog(): List<Pair<String, String>> = lenses.map { it.id to it.name }

    override fun devices(): List<Pair<String, String>> =
        if (sharing) lenses.filter { it.id !in off }.map { it.id to it.name } else emptyList()

    /**
     * Меняет набор показываемых камер и повороты на лету. Поворот вступает в силу, когда программа на ПК
     * снова откроет камеру; снимающая камера, ставшая скрытой, останавливается.
     */
    fun configure(sharing: Boolean, off: Set<String>, rotations: Map<String, Int>) {
        this.sharing = sharing
        this.off = off
        this.rotations = rotations
        activeId?.let { if (!sharing || it in off) stopAll() }
    }

    override fun rotation(cameraId: String): Int {
        val lens = lenses.firstOrNull { it.id == cameraId } ?: return 0
        return totalRotation(lens)
    }

    private fun totalRotation(lens: Lens) = (lens.baseRotation + (rotations[lens.id] ?: 0) % 360 + 360) % 360

    private fun discover(): List<Lens> {
        val all = ArrayList<Raw>()
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            // Датчики глубины и ИК не умеют BACKWARD_COMPATIBLE-сессию: их не предлагаем.
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (caps.none { it == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE }) continue
            val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_EXTERNAL
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val equiv = if (focal != null && size != null && size.width > 0f) {
                focal * FULL_FRAME_DIAGONAL_MM / hypot(size.width, size.height)
            } else {
                null
            }
            val zoomMin = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
            val raw = Raw(id, facing, ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, equiv, zoomMin)
            Log.i(TAG, "камера $id: сторона=$facing, датчик=${raw.orientation}°, эквивалент=${equiv?.let { "%.1f".format(it) }} мм, зум≥$zoomMin, тип=${raw.kind}")
            all += raw
        }
        // Один и тот же объектив мог попасть в список дважды (физический и логический id): оставляем первый.
        val unique = ArrayList<Raw>()
        for (raw in all) {
            val dup = unique.any {
                it.facing == raw.facing && it.equiv != null && raw.equiv != null &&
                    abs(it.equiv - raw.equiv) <= it.equiv * DUPLICATE_TOLERANCE
            }
            if (!dup) unique += raw
        }

        data class Entry(val raw: Raw, val zoom: Float?, val kind: Kind)
        val entries = ArrayList<Entry>()
        unique.forEach { entries += Entry(it, null, it.kind) }
        // Ультраширокий как зум до минимума — только если отдельного ультраширокого на этой стороне нет.
        for (facing in unique.map { it.facing }.distinct()) {
            if (entries.any { it.raw.facing == facing && it.kind == Kind.ULTRA }) continue
            val main = entries.firstOrNull { it.raw.facing == facing && it.kind == Kind.MAIN } ?: continue
            if (main.raw.zoomMin <= ZOOM_ULTRA_MAX) entries += Entry(main.raw, main.raw.zoomMin, Kind.ULTRA)
        }

        val ordered = entries.sortedWith(
            compareBy<Entry>({ facingOrder(it.raw.facing) }, { it.kind.ordinal }, { it.raw.id.toIntOrNull() ?: Int.MAX_VALUE }, { it.raw.id }),
        ).take(MAX_CAMERAS)
        val seenNames = HashMap<String, Int>()
        return ordered.map { e ->
            val title = "Планшет: " + when (e.raw.facing) {
                CameraMetadata.LENS_FACING_BACK -> "тыльная"
                CameraMetadata.LENS_FACING_FRONT -> "фронтальная"
                else -> "внешняя"
            } + when (e.kind) {
                Kind.MAIN -> ""
                Kind.ULTRA -> " (ультраширокая)"
                Kind.TELE -> " (телеобъектив)"
            }
            val n = (seenNames[title] ?: 0) + 1
            seenNames[title] = n
            val base = uprightRotation(e.raw.facing, e.raw.orientation)
            val id = if (e.zoom != null) "${e.raw.id}:z${e.zoom}" else e.raw.id
            Lens(id, e.raw.id, e.zoom, if (n == 1) title else "$title $n", base)
        }.also { list -> list.forEach { Log.i(TAG, "предлагается ${it.id}: ${it.name}, поворот ${it.baseRotation}°") } }
    }

    /**
     * Поворот кадра по часовой стрелке, после которого он прямой при повороте экрана на [DISPLAY_ROTATION]°
     * (формула из документации Camera2: тыльная — SENSOR − экран, фронтальная — SENSOR + экран).
     */
    private fun uprightRotation(facing: Int, sensorOrientation: Int): Int = when (facing) {
        CameraMetadata.LENS_FACING_BACK -> (sensorOrientation - DISPLAY_ROTATION + 360) % 360
        CameraMetadata.LENS_FACING_FRONT -> (sensorOrientation + DISPLAY_ROTATION) % 360
        else -> 0
    }

    private fun facingOrder(facing: Int) = when (facing) {
        CameraMetadata.LENS_FACING_BACK -> 0
        CameraMetadata.LENS_FACING_FRONT -> 1
        else -> 2
    }

    @SuppressLint("MissingPermission") // разрешение CAMERA спрашивает RemoteActivity до включения
    override fun start(cameraId: String, width: Int, height: Int, jpeg: Boolean, onFrame: (ByteArray) -> Unit): Boolean {
        if (!sharing || cameraId in off) return false
        stopAll()
        if (!starting.compareAndSet(false, true)) return false
        try {
            val lens = lenses.firstOrNull { it.id == cameraId } ?: return false
            val t = HandlerThread("rdp-camera").apply { start() }
            thread = t
            val h = Handler(t.looper)
            handler = h
            val turn = totalRotation(lens)
            // Датчик отдаёт альбомный кадр; при повороте на 90/270° Windows просит книжный размер — снимаем в альбомном и поворачиваем.
            val r = ImageReader.newInstance(maxOf(width, height), minOf(width, height), android.graphics.ImageFormat.YUV_420_888, 2)
            reader = r
            running = true
            activeId = cameraId
            r.setOnImageAvailableListener({ ir ->
                val image = try {
                    ir.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    // Кадр нужен только в ответ на запрос сервера, поэтому очередь не копим.
                    if (running) runCatching {
                        val nv12 = Nv12.rotate(toNv12(image), image.width, image.height, turn)
                        val sideways = turn == 90 || turn == 270
                        val w = if (sideways) image.height else image.width
                        val h = if (sideways) image.width else image.height
                        onFrame(if (jpeg) toJpeg(nv12, w, h) else nv12)
                    }
                } finally {
                    image.close()
                }
            }, h)

            val opened = java.util.concurrent.CountDownLatch(1)
            manager.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    opened.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    running = false
                    opened.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    running = false
                    opened.countDown()
                }
            }, h)
            if (!opened.await(OPEN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                stopAll()
                return false
            }
            val cam = device ?: run { stopAll(); return false }
            val surface = r.surface
            session = null
            val ready = java.util.concurrent.CountDownLatch(1)
            cam.createCaptureSession(
                listOf(surface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                        session = s
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        req.addTarget(surface)
                        // Без автокоррекции кадр уходил бы повёрнутым и сжатым по контрасту.
                        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        lens.zoom?.let { req.set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }
                        request = req.build()
                        runCatching { s.setRepeatingRequest(request!!, null, h) }
                        ready.countDown()
                    }

                    override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                        ready.countDown()
                    }
                },
                h,
            )
            if (!ready.await(OPEN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) || session == null) {
                stopAll()
                return false
            }
            return true
        } catch (_: Exception) {
            stopAll()
            return false
        } finally {
            starting.set(false)
        }
    }

    /**
     * Останавливает камеру, только если снимает именно она: при переключении Windows может запустить новую
     * камеру раньше, чем остановит старую, и поздняя остановка не должна гасить новый поток.
     */
    override fun stop(cameraId: String) {
        if (activeId == cameraId) stopAll()
    }

    override fun stopAll() {
        running = false
        activeId = null
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        request = null
        thread?.let { runCatching { it.quitSafely() } }
        thread = null
        handler = null
    }

    /**
     * YUV_420_888 → NV12 (Y целиком, затем чередующиеся U и V). Раскладки бывают полуплоскостные
     * (NV12/NV21) и плоские (I420) — обрабатываются обе.
     */
    private fun toNv12(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]

        val yBuf = y.buffer
        val yRow = y.rowStride
        if (yRow == width) {
            yBuf.get(out, 0, ySize)
        } else {
            for (row in 0 until height) {
                val src = row * yRow
                if (src + width > yBuf.limit()) break
                yBuf.position(src)
                yBuf.get(out, row * width, width)
            }
        }

        val uBuf = u.buffer
        val vBuf = v.buffer
        val uvHeight = height / 2
        val uvWidth = width / 2
        val uRow = u.rowStride
        val vRow = v.rowStride
        val uStep = u.pixelStride
        val vStep = v.pixelStride
        var dst = ySize
        // Android нумерует плоскости фиксированно: 1 — U, 2 — V, независимо от того, лежат ли они
        // в памяти как NV12 или NV21. Поэтому читаем их по отдельности и сами чередуем в NV12.
        for (row in 0 until uvHeight) {
            val uBase = row * uRow
            val vBase = row * vRow
            for (col in 0 until uvWidth) {
                if (dst + 1 >= out.size) return out
                out[dst++] = readByte(uBuf, uBase + col * uStep)
                out[dst++] = readByte(vBuf, vBase + col * vStep)
            }
        }
        return out
    }

    /** NV12 → JPEG: YuvImage знает только NV21, поэтому пары U и V меняются местами в копии. */
    private fun toJpeg(nv12: ByteArray, width: Int, height: Int): ByteArray {
        val nv21 = nv12.copyOf()
        var i = width * height
        while (i + 1 < nv21.size) {
            nv21[i] = nv12[i + 1]
            nv21[i + 1] = nv12[i]
            i += 2
        }
        val out = java.io.ByteArrayOutputStream(nv21.size / 8)
        android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            .compressToJpeg(android.graphics.Rect(0, 0, width, height), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun readByte(buffer: java.nio.ByteBuffer, index: Int): Byte =
        if (index < buffer.limit()) buffer.get(index) else 0

    private companion object {
        const val TAG = "RDP"
        const val OPEN_TIMEOUT_MS = 3000L
        const val JPEG_QUALITY = 75

        /** Диагональ полного кадра 36×24 мм — для перевода фокусного расстояния в эквивалентное. */
        const val FULL_FRAME_DIAGONAL_MM = 43.27f

        /** Эквивалент до 20 мм — ультраширокий, свыше 45 мм — телеобъектив, между ними — основной. */
        const val ULTRA_MAX_MM = 20f
        const val TELE_MIN_MM = 45f

        /** Объективы одной стороны с фокусным, различающимся меньше чем на 5%, считаются одним. */
        const val DUPLICATE_TOLERANCE = 0.05f

        /** Логическая камера с зумом от 0.7× и меньше умеет ультраширокий угол. */
        const val ZOOM_ULTRA_MAX = 0.7f
        const val MAX_CAMERAS = 6

        /** Положение планшета, под которое кадр делается прямым: экран повёрнут на 270° от естественного (альбомная ориентация). */
        const val DISPLAY_ROTATION = 270
    }
}
