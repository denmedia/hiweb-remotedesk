package com.hiweb.hiwebremotedesk.rdp

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.util.concurrent.ConcurrentHashMap

/** Декодер H.264 одной поверхности: кадры уходят целиком, готовая картинка приходит в обратный вызов с тем же `tag`. */
interface AvcDecoder {
    /** Передаёт декодеру один кадр (Annex B); false — кадр не принят и картинка не изменится. */
    fun decode(bitstream: ByteArray, tag: Any): Boolean

    fun release()
}

/** Создаёт декодер под поверхность [width]×[height]; null — декодера нет. [onFrame] зовётся с потока декодера, плоскости действуют только внутри вызова. */
fun interface AvcDecoderFactory {
    fun create(width: Int, height: Int, onFrame: (YuvPlanes, Any) -> Unit): AvcDecoder?
}

/**
 * Аппаратный декодер H.264 на `MediaCodec` (вывод в буфер, плоскости читаются через `getOutputImage`). Берётся только
 * кодек, помеченный как аппаратный: программный декодер для сжатия экрана ничего не выигрывает у RemoteFX.
 * Входные кадры кладутся в очередь с номером в `presentationTimeUs`, по нему же результат находит свой `tag`,
 * поэтому порядок вывода и задержка буфера кодека на сопоставление прямоугольников не влияют.
 */
class HardwareAvc private constructor(
    private val codec: MediaCodec,
    private val codecName: String,
    private val onFrame: (YuvPlanes, Any) -> Unit,
    private val log: (String) -> Unit,
) : AvcDecoder {
    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val INPUT_TIMEOUT_US = 100_000L
        private const val OUTPUT_TIMEOUT_US = 20_000L
        private const val MAX_PENDING = 64
        private const val STATS_EVERY_NS = 5_000_000_000L
        private val LOW_LATENCY_KEYS = listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.rtc-ext-dec-low-latency.enable")

        /** Имя аппаратного декодера H.264, умеющего [width]×[height]; null — такого нет. */
        fun hardwareCodecName(width: Int, height: Int): String? = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && info.isHardwareAccelerated && !info.isSoftwareOnly &&
                    MIME in info.supportedTypes.map { it.lowercase() } &&
                    info.getCapabilitiesForType(MIME).videoCapabilities?.isSizeSupported(width, height) == true
            }?.name
        }.getOrNull()

        /** Фабрика аппаратных декодеров; null, если на устройстве нет аппаратного H.264. */
        fun factory(log: (String) -> Unit): AvcDecoderFactory? {
            // Размер для проверки — с запасом: поверхность рабочего стола редко больше 4K.
            if (hardwareCodecName(1920, 1088) == null) return null
            return AvcDecoderFactory { w, h, onFrame ->
                val name = hardwareCodecName(w, h)
                if (name == null) {
                    log("нет аппаратного декодера H.264 на ${w}x$h")
                    null
                } else runCatching { open(name, w, h, onFrame, log) }.onFailure { log("декодер $name не запустился: $it") }.getOrNull()
            }
        }

        private fun open(name: String, w: Int, h: Int, onFrame: (YuvPlanes, Any) -> Unit, log: (String) -> Unit): HardwareAvc {
            val codec = MediaCodec.createByCodecName(name)
            try {
                val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                    // Кадры экрана идут по одному: декодер не должен копить их в ожидании следующих.
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    // Свои ключи производителей: без них Qualcomm и Exynos держат несколько кадров в очереди вывода.
                    for (key in LOW_LATENCY_KEYS) runCatching { setInteger(key, 1) }
                }
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                runCatching { codec.release() }
                throw e
            }
            log("декодер H.264 $name, ${w}x$h")
            return HardwareAvc(codec, name, onFrame, log).also { it.thread.start() }
        }
    }

    private val tags = ConcurrentHashMap<Long, Any>()
    private val queuedAt = ConcurrentHashMap<Long, Long>()
    private var statCount = 0
    private var statSumNs = 0L
    private var statMaxNs = 0L
    private var statSince = System.nanoTime()
    private var sequence = 0L
    @Volatile private var running = true
    private val thread = Thread({ drain() }, "avc-drain")
    private var framesOut = 0L

    override fun decode(bitstream: ByteArray, tag: Any): Boolean {
        if (!running) return false
        return try {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) {
                log("входная очередь декодера занята, кадр пропущен")
                return false
            }
            val buffer = codec.getInputBuffer(index) ?: return false
            if (bitstream.size > buffer.capacity()) {
                codec.queueInputBuffer(index, 0, 0, 0, 0)
                log("кадр ${bitstream.size} Б не помещается в буфер декодера ${buffer.capacity()} Б")
                return false
            }
            buffer.clear()
            buffer.put(bitstream)
            val pts = ++sequence
            tags[pts] = tag
            queuedAt[pts] = System.nanoTime()
            // Кадры, до которых декодер не дошёл или которые отбросил, не должны копиться вечно.
            if (tags.size > MAX_PENDING) {
                tags.keys.removeAll { it < pts - MAX_PENDING }
                queuedAt.keys.removeAll { it < pts - MAX_PENDING }
            }
            codec.queueInputBuffer(index, 0, bitstream.size, pts, 0)
            true
        } catch (e: Exception) {
            log("ошибка подачи кадра: $e")
            running = false
            false
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                when {
                    index >= 0 -> {
                        try {
                            if (info.size > 0) deliver(index, info.presentationTimeUs)
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> log("формат вывода: ${codec.outputFormat}")
                }
            }
        } catch (e: Exception) {
            if (running) log("декодер остановился: $e")
            running = false
        }
    }

    private fun deliver(index: Int, pts: Long) {
        val tag = tags.remove(pts) ?: return
        queuedAt.remove(pts)?.let { recordLatency(System.nanoTime() - it) }
        val image = codec.getOutputImage(index) ?: return
        image.use { img ->
            val crop = img.cropRect
            if (framesOut++ == 0L && (crop.left != 0 || crop.top != 0)) log("у кадра смещённая обрезка $crop: картинка может сдвинуться")
            onFrame(planes(img), tag)
        }
    }

    /** Раз в 5 с пишет в журнал, сколько кадр проводит в декодере (от подачи до готовой картинки). */
    private fun recordLatency(ns: Long) {
        statCount++
        statSumNs += ns
        if (ns > statMaxNs) statMaxNs = ns
        val now = System.nanoTime()
        if (now - statSince >= STATS_EVERY_NS) {
            log("декодер: за ${(now - statSince) / 1_000_000_000} с кадров $statCount, в декодере в среднем ${statSumNs / statCount / 1000} мкс, не больше ${statMaxNs / 1000} мкс")
            statCount = 0; statSumNs = 0; statMaxNs = 0; statSince = now
        }
    }

    private fun planes(img: Image): YuvPlanes {
        val p = img.planes
        return YuvPlanes(
            img.width, img.height,
            p[0].buffer, p[0].rowStride,
            p[1].buffer, p[1].rowStride, p[1].pixelStride,
            p[2].buffer, p[2].rowStride, p[2].pixelStride,
        )
    }

    override fun release() {
        running = false
        // Поток вывода просыпается по таймауту и выходит сам; кодек освобождается после него.
        Thread {
            runCatching { thread.join(1000) }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            log("декодер $codecName закрыт, кадров выведено $framesOut")
        }.start()
    }
}
