package com.hiweb.hiwebremotedesk.rdp

/** Медиаформат кадра в MS-RDPECAM: сжатый MJPG (основной путь Windows) или несжатый NV12. */
data class EcamMediaType(
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    val format: Int = FORMAT_MJPG,
) {
    /** 26 байт на проводе: формат, размеры, частота, соотношение сторон, флаги. */
    fun toBytes(): ByteArray = ByteWriter(26)
        .u8(format).u32le(width).u32le(height)
        .u32le(fps.toLong()).u32le(1)
        .u32le(1).u32le(1)
        // DecodingRequired: Windows сама разжимает MJPG; NV12 уходит как есть.
        .u8(if (format == FORMAT_MJPG) FLAG_DECODING_REQUIRED else 0)
        .toByteArray()

    companion object {
        const val FORMAT_MJPG = 2
        const val FORMAT_NV12 = 4
        const val WIRE_SIZE = 26
        private const val FLAG_DECODING_REQUIRED = 1

        /** Разбирает описание медиатипа из запроса сервера (StartStreams). */
        fun parse(r: ByteReader): EcamMediaType? {
            val format = r.u8()
            val w = r.u32le().toInt()
            val h = r.u32le().toInt()
            val num = r.u32le().toInt()
            val den = r.u32le().toInt()
            r.u32le() // соотношение сторон по X
            r.u32le() // соотношение сторон по Y
            r.u8()    // флаги
            if ((format != FORMAT_NV12 && format != FORMAT_MJPG) || w <= 0 || h <= 0 || num <= 0 || den <= 0) return null
            return EcamMediaType(w, h, (num / den).coerceIn(1, 60), format)
        }
    }
}

/** Камера для канала rdpecam: кадры отдаются либо готовым JPEG (MJPG), либо несжатым NV12. */
interface EcamCamera {
    /**
     * Камеры устройства для перечисления: id (латиницей, без пробелов) и имя для пользователя Windows.
     * Каждая — отдельное устройство в Windows, поэтому переключаются они средствами самой программы на ПК.
     */
    fun devices(): List<Pair<String, String>>

    /**
     * Все камеры, в том числе сейчас не предлагаемые Windows, в неизменном порядке: по номеру в этом списке
     * называется канал устройства, поэтому включение и выключение камер имена каналов не сдвигает.
     */
    fun catalog(): List<Pair<String, String>> = devices()

    /**
     * Начинает выдачу кадров камеры [cameraId] в заданном разрешении ([jpeg] — JPEG, иначе NV12); [onFrame]
     * вызывается из потока камеры. Одновременно снимает одна камера: новая заменяет прежнюю.
     * false — камера недоступна (занята, нет разрешения).
     */
    fun start(cameraId: String, width: Int, height: Int, jpeg: Boolean, onFrame: (ByteArray) -> Unit): Boolean

    /**
     * Поворот кадра камеры [cameraId], градусы по часовой стрелке. При 90/270° кадр книжный, и Windows
     * предлагается размер 720×1280 вместо 1280×720.
     */
    fun rotation(cameraId: String): Int = 0

    /** Останавливает [cameraId], только если снимает именно она (новая камера уже могла вытеснить старую). */
    fun stop(cameraId: String)

    /** Останавливает любую камеру: сеанс закрыт. */
    fun stopAll()
}

/**
 * Канал перечисления камер `RDCamera_Device_Enumerator` (MS-RDPECAM, 2.2.2): согласует версию
 * и сообщает Windows, какие камеры есть на устройстве. На каждую заведён отдельный канал
 * устройства с придуманным нами именем («<префикс>0», «<префикс>1»…) — сервер откроет его сам.
 */
internal class EcamEnumerator(
    private val camera: EcamCamera,
    /** Префикс имён каналов устройств, анонсируемых серверу. */
    private val channelPrefix: String,
    private val onDevices: (List<Pair<String, String>>) -> Unit,
    private val log: (String) -> Unit = {},
) : DvcHandler {
    private var write: ((ByteArray) -> Unit)? = null

    /** Сервер ответил на выбор версии: камеры можно объявлять. */
    private var ready = false

    /** Идентификаторы камер, о которых Windows уже сообщено. */
    private val announced = LinkedHashSet<String>()

    @Synchronized
    override fun onOpen(write: (ByteArray) -> Unit) {
        this.write = write
        log("канал перечисления открыт")
        // Первым делом согласуем версию: до ответа сервера анонсировать камеры нельзя.
        write(byteArrayOf(VERSION.toByte(), MSG_SELECT_VERSION_REQUEST.toByte()))
    }

    @Synchronized
    override fun onMessage(data: ByteArray) {
        if (data.size < 2) return
        val version = data[0].toInt() and 0xFF
        when (data[1].toInt() and 0xFF) {
            MSG_SELECT_VERSION_RESPONSE -> {
                log("версия сервера $version")
                if (version > PROTOCOL_VERSION) {
                    log("версия канала новее нашей — камеры не предлагаются")
                    return
                }
                ready = true
                refresh()
            }
            else -> log("неожиданное сообщение 0x${"%02x".format(data[1])}")
        }
    }

    /**
     * Приводит список камер в Windows к [EcamCamera.devices]: новые объявляются, пропавшие убираются. Windows
     * показывает ровно те камеры, о которых мы сообщили; по одному уведомлению и каналу на камеру. Вызывается
     * после выбора версии и при каждом изменении набора камер (включение, выключение, настройки сеанса).
     */
    @Synchronized
    fun refresh() {
        val out = write ?: return
        if (!ready) return
        val list = runCatching { camera.devices() }.getOrDefault(emptyList())
        val catalog = runCatching { camera.catalog() }.getOrDefault(list)
        onDevices(list)
        if (list.isEmpty() && announced.isEmpty()) log("камер для Windows нет")
        val wanted = list.map { it.first }.toSet()
        for (id in announced.filter { it !in wanted }) {
            val index = catalog.indexOfFirst { it.first == id }
            announced.remove(id)
            if (index < 0) continue
            val channel = channelName(channelPrefix, index)
            val pdu = ByteWriter(24).u8(PROTOCOL_VERSION).u8(MSG_DEVICE_REMOVED_NOTIFICATION)
            pdu.bytes(channel.toByteArray(Charsets.US_ASCII)).u8(0)
            runCatching { out(pdu.toByteArray()) }
            log("камера убрана (канал $channel)")
        }
        for ((id, name) in list) {
            if (id in announced) continue
            val index = catalog.indexOfFirst { it.first == id }
            if (index < 0) continue
            val channel = channelName(channelPrefix, index)
            val pdu = ByteWriter(64)
                .u8(PROTOCOL_VERSION).u8(MSG_DEVICE_ADDED_NOTIFICATION)
            pdu.utf16(name).u16le(0)                       // имя для пользователя, с завершающим нулём
            pdu.bytes(channel.toByteArray(Charsets.US_ASCII)).u8(0)
            out(pdu.toByteArray())
            announced += id
            log("объявлена камера «$name» (канал $channel)")
        }
    }

    @Synchronized
    override fun onClose() {
        write = null
        ready = false
        announced.clear()
        log("канал перечисления закрыт")
    }

    companion object {
        const val CHANNEL_NAME = "RDCamera_Device_Enumerator"

        /** Имя канала устройства с номером [index] в списке камер. */
        fun channelName(prefix: String, index: Int) = "$prefix$index"

        /** Версия протокола: 2 — единственная, которую понимает Windows. */
        const val PROTOCOL_VERSION = 2

        private const val MSG_SELECT_VERSION_REQUEST = 0x03
        private const val MSG_SELECT_VERSION_RESPONSE = 0x04
        private const val MSG_DEVICE_ADDED_NOTIFICATION = 0x05
        private const val MSG_DEVICE_REMOVED_NOTIFICATION = 0x06

        /** Версия в заголовке каждого сообщения. */
        private const val VERSION = PROTOCOL_VERSION
    }
}

/**
 * Состояние потока камеры, общее для всех каналов `Camera0`. Windows открывает канал устройства несколько
 * раз сразу — для свойств, для запуска потока и отдельно для запросов кадров, — но поток один: `StartStreams`
 * приходит на один канал, а `SampleRequest` может прийти на другой.
 */
internal class EcamShared {
    class Pending(val index: Int, val out: (ByteArray) -> Unit)

    @Volatile var mediaType: EcamMediaType? = null
    @Volatile var streaming = false

    /** Канал, запустивший поток: его закрытие или `Deactivate` с него останавливает камеру. */
    var owner: Any? = null

    /** Запросы кадров, на которые ещё не ответили, с каналом, откуда каждый пришёл. */
    val pending = java.util.ArrayDeque<Pending>()
    var latest: ByteArray? = null
}

/**
 * Один канал камеры (MS-RDPECAM, 2.2.3). Сервер спрашивает список потоков, форматов, запускает
 * поток и по одному запрашивает кадры — отвечаем ровно на запрос, лишнего не отправляя.
 * Один поток (цвет), как и в эталонной реализации: Windows не просит больше. Каналов может быть
 * несколько: состояние потока лежит в [shared], а ответ уходит в тот канал, откуда пришёл запрос.
 */
internal class EcamDevice(
    private val camera: EcamCamera,
    /** Камера из [EcamCamera.devices], которой принадлежит этот канал устройства. */
    private val cameraId: String,
    /** Вызывается при старте и остановке потока: экран сеанса показывает, что камера пишет на ПК. */
    private val onActive: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val shared: EcamShared = EcamShared(),
) : DvcHandler {
    private var write: ((ByteArray) -> Unit)? = null

    override fun onOpen(write: (ByteArray) -> Unit) {
        this.write = write
        log("канал камеры открыт")
    }

    override fun onMessage(data: ByteArray) {
        if (data.size < 2) return
        // Кадры идут потоком, их не логируем; всё остальное — по одной строке на запрос.
        if (data[1].toInt() and 0xFF != MSG_SAMPLE_REQUEST) {
            log("← 0x${"%02x".format(data[1])} (${data.size} б) ${data.take(16).joinToString("") { "%02x".format(it) }}")
        }
        try {
            val r = ByteReader(data, 2)
            when (data[1].toInt() and 0xFF) {
                MSG_ACTIVATE_DEVICE_REQUEST -> {
                    log("активация камеры")
                    success()
                }
                MSG_DEACTIVATE_DEVICE_REQUEST -> {
                    // Каналы свойств и опроса шлют Deactivate, пока поток идёт на другом канале: его не трогаем.
                    if (synchronized(shared) { shared.owner === this }) stopStream()
                    success()
                }
                MSG_STREAM_LIST_REQUEST -> streamList()
                MSG_MEDIA_TYPE_LIST_REQUEST -> mediaTypeList()
                MSG_CURRENT_MEDIA_TYPE_REQUEST -> currentMediaType()
                MSG_PROPERTY_LIST_REQUEST -> reply(MSG_PROPERTY_LIST_RESPONSE, ByteArray(0))
                MSG_START_STREAMS_REQUEST -> startStream(r)
                MSG_STOP_STREAMS_REQUEST -> {
                    stopStream()
                    success()
                }
                MSG_SAMPLE_REQUEST -> onSampleRequest(r)
                else -> {
                    log("неизвестный запрос 0x${"%02x".format(data[1])}")
                    error(CAM_ERROR_OPERATION_NOT_SUPPORTED)
                }
            }
        } catch (e: RdpException) {
            log("повреждённый запрос проигнорирован: ${e.message}")
            error(CAM_ERROR_INVALID_MESSAGE)
        }
    }

    override fun onClose() {
        val me = write
        synchronized(shared) { shared.pending.removeAll { it.out === me } }
        if (synchronized(shared) { shared.owner === this }) stopStream()
        write = null
        log("канал камеры закрыт")
    }

    // ------------------------------------------------------------------ ответы на запросы

    /** У камеры один поток: цвет, категория «захват», выбран по умолчанию. */
    private fun streamList() {
        val body = ByteWriter(5)
            .u16le(FRAME_SOURCE_COLOR).u8(STREAM_CATEGORY_CAPTURE).u8(1).u8(0)
            .toByteArray()
        reply(MSG_STREAM_LIST_RESPONSE, body)
    }

    private fun mediaTypeList() {
        val types = supportedTypes()
        val body = ByteWriter(types.size * EcamMediaType.WIRE_SIZE)
        types.forEach { body.bytes(it.toBytes()) }
        reply(MSG_MEDIA_TYPE_LIST_RESPONSE, body.toByteArray())
        if (!shared.streaming) shared.mediaType = types.firstOrNull()
    }

    private fun currentMediaType() {
        val type = shared.mediaType ?: supportedTypes().firstOrNull()
        if (type == null) {
            error(CAM_ERROR_ITEM_NOT_FOUND)
            return
        }
        shared.mediaType = type
        reply(MSG_CURRENT_MEDIA_TYPE_RESPONSE, type.toBytes())
    }

    private fun startStream(r: ByteReader) {
        r.u8() // индекс потока: у камеры он один
        val requested = EcamMediaType.parse(r)
        if (requested == null) {
            log("сервер запросил неподдерживаемый формат")
            error(CAM_ERROR_INVALID_MEDIA_TYPE)
            return
        }
        val started = runCatching {
            camera.start(cameraId, requested.width, requested.height, requested.format == EcamMediaType.FORMAT_MJPG) { frame -> onFrame(frame) }
        }.getOrDefault(false)
        if (!started) {
            log("камера не запустилась")
            error(CAM_ERROR_UNEXPECTED)
            return
        }
        synchronized(shared) {
            shared.mediaType = requested
            shared.pending.clear()
            shared.latest = null
            shared.streaming = true
            shared.owner = this
        }
        log("поток запущен: ${requested.width}×${requested.height} @ ${requested.fps} к/с, формат ${requested.format}")
        runCatching { onActive(true) }
        success()
    }

    private fun stopStream() {
        synchronized(shared) {
            if (!shared.streaming) return
            shared.streaming = false
            shared.owner = null
            shared.pending.clear()
            shared.latest = null
        }
        runCatching { camera.stop(cameraId) }
        runCatching { onActive(false) }
        log("поток остановлен")
    }

    /**
     * Windows запрашивает кадры по одному. Кадр отдаём только в ответ на запрос, иначе сервер
     * посчитает поток рассинхронизированным; если кадра ещё нет — запрос ждёт в [EcamShared.pending]
     * вместе с каналом, на который надо ответить.
     */
    private fun onSampleRequest(r: ByteReader) {
        val index = r.u8()
        val out = write ?: return
        val streaming: Boolean
        val frame: ByteArray?
        synchronized(shared) {
            streaming = shared.streaming
            if (streaming) {
                frame = shared.latest
                if (frame != null) shared.latest = null else shared.pending.addLast(EcamShared.Pending(index, out))
            } else {
                frame = null
            }
        }
        if (!streaming) {
            sampleError(index, CAM_ERROR_NOT_INITIALIZED)
            return
        }
        if (frame != null) writeSample(out, index, frame)
    }

    private fun onFrame(frame: ByteArray) {
        val request = synchronized(shared) {
            if (!shared.streaming) return
            val p = shared.pending.pollFirst()
            // Сервер ещё не просил кадр: храним последний, он уйдёт на ближайший запрос.
            if (p == null) shared.latest = frame
            p
        } ?: return
        writeSample(request.out, request.index, frame)
    }

    private fun writeSample(out: (ByteArray) -> Unit, index: Int, frame: ByteArray) {
        runCatching {
            val pdu = ByteWriter(frame.size + 3)
                .u8(HEADER_VERSION).u8(MSG_SAMPLE_RESPONSE).u8(index).bytes(frame)
                .toByteArray()
            out(pdu)
        }.onFailure { log("кадр не отправлен: ${it.message}") }
    }

    private fun sampleError(index: Int, code: Int) {
        reply(MSG_SAMPLE_ERROR_RESPONSE, ByteWriter(5).u8(index).u32le(code).toByteArray())
    }

    private fun success() = reply(MSG_SUCCESS_RESPONSE, ByteArray(0))

    private fun error(code: Int) = reply(MSG_ERROR_RESPONSE, ByteWriter(4).u32le(code).toByteArray())

    private fun reply(messageId: Int, body: ByteArray) {
        val out = write ?: return
        log("→ 0x${"%02x".format(messageId)} (${body.size + 2} б)")
        val pdu = ByteWriter(body.size + 2).u8(HEADER_VERSION).u8(messageId).bytes(body).toByteArray()
        runCatching { out(pdu) }.onFailure { log("ответ не отправлен: ${it.message}") }
    }

    /**
     * Форматы, которые предлагаются серверу. Windows принимает сжатый MJPG; он же в разы легче несжатого
     * NV12 для канала через интернет. Разрешение ограничено 720p.
     */
    private fun supportedTypes(): List<EcamMediaType> {
        val sideways = runCatching { camera.rotation(cameraId) }.getOrDefault(0).let { it == 90 || it == 270 }
        return listOf(if (sideways) EcamMediaType(720, 1280) else EcamMediaType(1280, 720))
    }

    companion object {
        private const val HEADER_VERSION = EcamEnumerator.PROTOCOL_VERSION

        private const val MSG_SUCCESS_RESPONSE = 0x01
        private const val MSG_ERROR_RESPONSE = 0x02
        private const val MSG_ACTIVATE_DEVICE_REQUEST = 0x07
        private const val MSG_DEACTIVATE_DEVICE_REQUEST = 0x08
        private const val MSG_STREAM_LIST_REQUEST = 0x09
        private const val MSG_STREAM_LIST_RESPONSE = 0x0A
        private const val MSG_MEDIA_TYPE_LIST_REQUEST = 0x0B
        private const val MSG_MEDIA_TYPE_LIST_RESPONSE = 0x0C
        private const val MSG_CURRENT_MEDIA_TYPE_REQUEST = 0x0D
        private const val MSG_CURRENT_MEDIA_TYPE_RESPONSE = 0x0E
        private const val MSG_START_STREAMS_REQUEST = 0x0F
        private const val MSG_STOP_STREAMS_REQUEST = 0x10
        private const val MSG_SAMPLE_REQUEST = 0x11
        private const val MSG_SAMPLE_RESPONSE = 0x12
        private const val MSG_SAMPLE_ERROR_RESPONSE = 0x13
        private const val MSG_PROPERTY_LIST_REQUEST = 0x14
        private const val MSG_PROPERTY_LIST_RESPONSE = 0x15

        private const val FRAME_SOURCE_COLOR = 1
        private const val STREAM_CATEGORY_CAPTURE = 1

        private const val CAM_ERROR_UNEXPECTED = 1
        private const val CAM_ERROR_INVALID_MESSAGE = 2
        private const val CAM_ERROR_NOT_INITIALIZED = 3
        private const val CAM_ERROR_INVALID_MEDIA_TYPE = 6
        private const val CAM_ERROR_ITEM_NOT_FOUND = 8
        private const val CAM_ERROR_OPERATION_NOT_SUPPORTED = 10
    }
}
