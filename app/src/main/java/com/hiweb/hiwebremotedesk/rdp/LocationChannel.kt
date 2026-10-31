package com.hiweb.hiwebremotedesk.rdp

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Одно определение местоположения для канала [LocationChannel]. Единицы — как в MS-RDPEL: градусы, метры, метры в секунду.
 * Скорость и курс неизвестны — нули; точность неизвестна — запас (100 м), чтобы Windows не сочла координаты точными.
 */
class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Высота над уровнем моря, м (0, если неизвестна). */
    val altitude: Int = 0,
    val speed: Double = 0.0,
    /** Курс, градусы по часовой стрелке от севера. */
    val heading: Double = 0.0,
    /** Погрешность по горизонтали, м. */
    val accuracy: Double = DEFAULT_ACCURACY,
    /** Откуда координаты: [LocationChannel.SOURCE_IP]…[LocationChannel.SOURCE_GNSS]. */
    val source: Int = LocationChannel.SOURCE_WIFI,
) {
    companion object {
        const val DEFAULT_ACCURACY = 100.0
    }
}

/** Откуда канал берёт координаты; реализует Android-часть (`TabletLocation`), в тестах подставляется заглушка. */
interface LocationSource {
    /**
     * Начинает слежение: каждое новое определение (и повтор последнего, пока оно действительно) передаётся в [feed]
     * из любого потока, кроме потока интерфейса и сети. false — следить нельзя (нет разрешения или включённых датчиков).
     */
    fun start(feed: (LocationFix) -> Unit): Boolean

    /** Останавливает слежение; после возврата [feed] больше не вызывается. */
    fun stop()
}

/** Сжатые числа MS-RDPEL (2.2.1.1, 2.2.1.2); раскладка сверена с `encoded_types.c` FreeRDP. */
internal object LocationNumbers {
    /** Наибольшее основание FOUR_BYTE_FLOAT: 26 бит. */
    const val FLOAT_BASE_MAX = 0x3FFFFFFL

    /** Наибольший модуль FOUR_BYTE_SIGNED_INTEGER: 29 бит. */
    const val INT_MAX = 0x1FFFFFFF

    /**
     * FOUR_BYTE_FLOAT: старшие биты первого байта — число дополнительных байтов (2 бита), знак (1), порядок `e` (3 бита,
     * значение = основание / 10^e), остальные два бита — старшие биты основания. Берётся наибольший порядок (то есть
     * наибольшая точность), при котором основание помещается в 26 бит; лишние нули справа отбрасываются.
     */
    fun float4(w: ByteWriter, value: Double) {
        require(value.isFinite()) { "число не конечное: $value" }
        val a = abs(value)
        var e = 7
        var base = (a * 10.0.pow(e)).roundToLong()
        while (e > 0 && base > FLOAT_BASE_MAX) {
            e--
            base = (a * 10.0.pow(e)).roundToLong()
        }
        require(base <= FLOAT_BASE_MAX) { "число $value не помещается в FOUR_BYTE_FLOAT" }
        while (e > 0 && base % 10 == 0L) { base /= 10; e-- }
        val extra = when {
            base < (1L shl 2) -> 0
            base < (1L shl 10) -> 1
            base < (1L shl 18) -> 2
            else -> 3
        }
        val negative = value < 0 && base != 0L
        val top = (base shr (8 * extra)).toInt() and 0x03
        w.u8((extra shl 6) or ((if (negative) 1 else 0) shl 5) or (e shl 2) or top)
        for (i in extra - 1 downTo 0) w.u8(((base shr (8 * i)) and 0xFF).toInt())
    }

    /** FOUR_BYTE_SIGNED_INTEGER: два бита числа дополнительных байтов, знак, пять старших битов модуля. */
    fun int4(w: ByteWriter, value: Int) {
        val a = abs(value.toLong())
        require(a <= INT_MAX) { "число $value не помещается в FOUR_BYTE_SIGNED_INTEGER" }
        val extra = when {
            a < (1L shl 5) -> 0
            a < (1L shl 13) -> 1
            a < (1L shl 21) -> 2
            else -> 3
        }
        val top = (a shr (8 * extra)).toInt() and 0x1F
        w.u8((extra shl 6) or ((if (value < 0) 1 else 0) shl 5) or top)
        for (i in extra - 1 downTo 0) w.u8(((a shr (8 * i)) and 0xFF).toInt())
    }

    fun readFloat4(r: ByteReader): Double {
        val first = r.u8()
        val extra = first shr 6
        val negative = first and 0x20 != 0
        val e = (first shr 2) and 0x07
        var base = (first and 0x03).toLong()
        repeat(extra) { base = (base shl 8) or r.u8().toLong() }
        val v = base / 10.0.pow(e)
        return if (negative) -v else v
    }

    fun readInt4(r: ByteReader): Int {
        val first = r.u8()
        val extra = first shr 6
        val negative = first and 0x20 != 0
        var v = (first and 0x1F).toLong()
        repeat(extra) { v = (v shl 8) or r.u8().toLong() }
        return (if (negative) -v else v).toInt()
    }
}

/**
 * Клиентская сторона канала геолокации (MS-RDPEL, DVC `Microsoft::Windows::RDS::Location`): Windows присылает
 * `SERVER_READY`, клиент отвечает `CLIENT_READY` и затем шлёт координаты (`BASE_LOCATION3D`). Windows подставляет их как
 * показания датчика местоположения для программ на ПК. Дельта-пакеты не нужны: каждое определение уходит целиком.
 *
 * Канал открывает Windows (если разрешено перенаправление и включён доступ к местоположению), поэтому включение
 * ([setEnabled]) «взводится» и срабатывает, когда придёт `SERVER_READY`. Пока передача выключена, `CLIENT_READY` не
 * отправляется (клиент без геолокации его слать не должен), а источник не работает — датчики не тратят заряд впустую.
 * Версия протокола: 2.0, если её назвал сервер (добавляет скорость, курс, точность и тип источника), иначе 1.0.
 */
class LocationChannel(
    private val source: LocationSource,
    private val log: (String) -> Unit = {},
    /** Координаты уходят на ПК ([active] = true) или перестали уходить. Вызывается из потока, поменявшего состояние. */
    private val onState: (active: Boolean) -> Unit = {},
) : DvcHandler {
    companion object {
        const val CHANNEL_NAME = "Microsoft::Windows::RDS::Location"

        private const val EVENT_SERVER_READY = 0x0001
        private const val EVENT_CLIENT_READY = 0x0002
        private const val EVENT_BASE_LOCATION3D = 0x0003

        const val VERSION_1 = 0x00010000L
        const val VERSION_2 = 0x00020000L

        const val SOURCE_IP = 0
        const val SOURCE_WIFI = 1
        const val SOURCE_CELL = 2
        const val SOURCE_GNSS = 3

        private const val HEADER = 6

        /** Версия клиента по версии сервера, как в FreeRDP: известные совпадают, большая — 2.0, остальные — 1.0. */
        fun clientVersion(server: Long): Long = when {
            server == VERSION_2 -> VERSION_2
            server == VERSION_1 -> VERSION_1
            server > VERSION_2 -> VERSION_2
            else -> VERSION_1
        }

        /** Пакет `BASE_LOCATION3D` для версии [version]. */
        fun encodeBase(fix: LocationFix, version: Long): ByteArray {
            val body = ByteWriter()
            LocationNumbers.float4(body, fix.latitude.coerceIn(-90.0, 90.0))
            LocationNumbers.float4(body, fix.longitude.coerceIn(-180.0, 180.0))
            LocationNumbers.int4(body, fix.altitude.coerceIn(-LocationNumbers.INT_MAX, LocationNumbers.INT_MAX))
            if (version >= VERSION_2) {
                LocationNumbers.float4(body, fix.speed.coerceAtLeast(0.0))
                LocationNumbers.float4(body, ((fix.heading % 360.0) + 360.0) % 360.0)
                LocationNumbers.float4(body, fix.accuracy.coerceAtLeast(0.0))
                body.u8(fix.source.coerceIn(SOURCE_IP, SOURCE_GNSS))
            }
            return pdu(EVENT_BASE_LOCATION3D, body.toByteArray())
        }

        private fun pdu(event: Int, body: ByteArray): ByteArray =
            ByteWriter(HEADER + body.size).u16le(event).u32le(HEADER + body.size).bytes(body).toByteArray()
    }

    private val lock = Any()
    private var write: ((ByteArray) -> Unit)? = null

    /** Сервер прислал `SERVER_READY` на этом открытии канала. */
    private var serverReady = false

    /** Мы отправили `CLIENT_READY`: сервер ждёт координаты. */
    private var clientReady = false
    private var enabled = false
    private var tracking = false
    private var version = VERSION_1
    private var sent = 0

    /** Сервер открыл канал и принял наше `CLIENT_READY`; координаты идут, если [setEnabled] включено. */
    val ready: Boolean get() = synchronized(lock) { clientReady }

    /** Координаты сейчас уходят на ПК. */
    val active: Boolean get() = synchronized(lock) { clientReady && tracking }

    /** Версия протокола, согласованная с сервером (0, пока канал не открыт). */
    val negotiatedVersion: Long get() = synchronized(lock) { if (clientReady) version else 0L }

    /**
     * Включает или выключает передачу. До открытия канала Windows это лишь «взвод». false — канал открыт, но источник не
     * запустился (нет разрешения или все датчики выключены в системе).
     */
    fun setEnabled(on: Boolean): Boolean {
        val change: Boolean?
        val ok: Boolean
        synchronized(lock) {
            val before = clientReady && tracking
            enabled = on
            ok = apply()
            val after = clientReady && tracking
            change = if (before != after) after else null
        }
        change?.let(onState)
        return ok
    }

    /** Приводит работу к состоянию [enabled] и канала. Вызывать под [lock]. */
    private fun apply(): Boolean {
        if (!enabled) {
            stopTracking()
            return true
        }
        val w = write
        if (!serverReady || w == null) return true
        if (!clientReady) {
            clientReady = true
            runCatching { w(pdu(EVENT_CLIENT_READY, ByteWriter().u32le(version).u32le(0L).toByteArray())) }
            log("CLIENT_READY: версия 0x${version.toString(16)}")
        }
        if (!tracking) {
            tracking = source.start { fix -> feed(fix) }
            if (!tracking) {
                log("источник координат не запустился (нет разрешения или выключены датчики)")
                return false
            }
            log("слежение за координатами запущено")
        }
        return true
    }

    private fun stopTracking() {
        if (!tracking) return
        tracking = false
        source.stop()
        log("слежение остановлено")
    }

    private fun feed(fix: LocationFix) {
        synchronized(lock) {
            val w = write
            if (!enabled || !clientReady || !tracking || w == null) return
            val bytes = encodeBase(fix, version)
            runCatching { w(bytes) }
            sent++
            if (sent <= 3 || sent % 100 == 0) {
                log("→ координаты №$sent: %.5f, %.5f ±%.0f м, источник %d".format(java.util.Locale.ROOT, fix.latitude, fix.longitude, fix.accuracy, fix.source))
            }
        }
    }

    override fun onOpen(write: (ByteArray) -> Unit) {
        synchronized(lock) {
            this.write = write
            serverReady = false
            clientReady = false
            sent = 0
        }
        log("канал открыт, жду SERVER_READY")
    }

    override fun onMessage(data: ByteArray) {
        if (data.size < HEADER) return
        val r = ByteReader(data)
        val event = r.u16le()
        val length = r.u32le().toInt()
        if (event != EVENT_SERVER_READY) {
            log("событие 0x${event.toString(16)} (${data.size} байт) пропущено")
            return
        }
        if (length < HEADER + 4 || r.remaining < 4) return
        val serverVersion = r.u32le()
        val serverFlags = if (length >= HEADER + 8 && r.remaining >= 4) r.u32le() else 0L
        val change: Boolean?
        synchronized(lock) {
            val before = clientReady && tracking
            version = clientVersion(serverVersion)
            serverReady = true
            log("SERVER_READY: версия 0x${serverVersion.toString(16)}, флаги 0x${serverFlags.toString(16)}, передача ${if (enabled) "включена" else "выключена"}")
            apply()
            val after = clientReady && tracking
            change = if (before != after) after else null
        }
        change?.let(onState)
    }

    override fun onClose() {
        val was: Boolean
        synchronized(lock) {
            was = clientReady && tracking
            write = null
            serverReady = false
            clientReady = false
            stopTracking()
        }
        if (was) onState(false)
    }

    /** Подключение закрыто целиком: источник нужно остановить, даже если сервер канал не закрывал. */
    fun release() {
        synchronized(lock) {
            enabled = false
            stopTracking()
        }
    }
}
