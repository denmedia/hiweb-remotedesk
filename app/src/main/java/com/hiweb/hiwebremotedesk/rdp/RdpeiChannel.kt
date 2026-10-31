package com.hiweb.hiwebremotedesk.rdp

/** Одна точка касания пальцем в кадре RDPEI. [flags] — комбинация [RdpeiFlags] (`DOWN`, `UPDATE`, `UP`, `INRANGE`, `INCONTACT`, `CANCELED`). */
class TouchPoint(val id: Int, val x: Int, val y: Int, val flags: Int)

/** Кадр касаний: все пальцы, изменившиеся к моменту [timeMs] (часы устройства, миллисекунды). */
class TouchFrame(val timeMs: Long, val points: List<TouchPoint>)

/**
 * Перо в кадре RDPEI. [penFlags] — [RdpeiFlags.PEN_BARREL] и др.; [pressure] — 0…1024 (у пера над экраном 0);
 * [tiltX], [tiltY] — наклон в градусах −90…90 (0 — перо перпендикулярно экрану; поворот пера вокруг оси S Pen не сообщает).
 */
class PenPoint(
    val x: Int,
    val y: Int,
    val flags: Int,
    val penFlags: Int,
    val pressure: Int,
    val tiltX: Int = 0,
    val tiltY: Int = 0,
)

class PenFrame(val timeMs: Long, val point: PenPoint)

/** Значения флагов MS-RDPEI (2.2.3.3.1.1 и 2.2.3.4.1.1). */
object RdpeiFlags {
    const val DOWN = 0x0001
    const val UPDATE = 0x0002
    const val UP = 0x0004
    const val INRANGE = 0x0008
    const val INCONTACT = 0x0010
    const val CANCELED = 0x0020

    const val PEN_BARREL = 0x0001
    const val PEN_INVERTED = 0x0002
    const val PEN_ERASER = 0x0004
}

/** Сжатые целые MS-RDPEI (2.2.2): старшие биты первого байта задают число дополнительных байтов (и знак). */
internal object RdpeiInts {
    private fun put(w: ByteWriter, v: Long, firstBits: Int, maxExtra: Int, countShift: Int, signMask: Int) {
        require(v >= 0) { "величина должна быть неотрицательной" }
        var extra = 0
        while (extra < maxExtra && v >= (1L shl (firstBits + 8 * extra))) extra++
        require(v < (1L shl (firstBits + 8 * extra))) { "число $v не помещается в сжатое целое" }
        w.u8((extra shl countShift) or signMask or (v shr (8 * extra)).toInt())
        for (i in extra - 1 downTo 0) w.u8(((v shr (8 * i)) and 0xFF).toInt())
    }

    /** TWO_BYTE_UNSIGNED_INTEGER, до 0x7FFF. */
    fun u2(w: ByteWriter, v: Int) = put(w, v.toLong(), 7, 1, 7, 0)

    /** TWO_BYTE_SIGNED_INTEGER, модуль до 0x3FFF. */
    fun s2(w: ByteWriter, v: Int) = put(w, kotlin.math.abs(v.toLong()), 6, 1, 7, if (v < 0) 0x40 else 0)

    /** FOUR_BYTE_UNSIGNED_INTEGER, до 0x3FFFFFFF. */
    fun u4(w: ByteWriter, v: Long) = put(w, v, 6, 3, 6, 0)

    /** FOUR_BYTE_SIGNED_INTEGER, модуль до 0x1FFFFFFF. */
    fun s4(w: ByteWriter, v: Int) = put(w, kotlin.math.abs(v.toLong()), 5, 3, 6, if (v < 0) 0x20 else 0)

    /** EIGHT_BYTE_UNSIGNED_INTEGER, до 0x1FFFFFFFFFFFFFFF. */
    fun u8(w: ByteWriter, v: Long) = put(w, v, 5, 7, 5, 0)
}

/**
 * Клиентская сторона канала ввода касаниями и пером (MS-RDPEI, DVC `Microsoft::Windows::RDS::Input`): Windows присылает
 * `SC_READY`, клиент отвечает `CS_READY`, после этого пальцы и перо уходят кадрами [sendTouch] и [sendPen]; Windows сама
 * разбирает жесты (прокрутка, щипок, сенсорная клавиатура, контекстное меню по долгому касанию).
 *
 * Канал открывает Windows, и не обязательно сразу: пока [ready] ложно, вызывающий оставляет мышь.
 * Время кадров считается по [timeMs] часов устройства: смещение между кадрами уходит в микросекундах.
 */
class RdpeiChannel(
    private val log: (String) -> Unit = {},
    /** Канал готов к касаниям (`true`, [pen] — и к перу) или закрыт (`false`). Вызывается из потока RDP. */
    private val onState: (ready: Boolean, pen: Boolean) -> Unit = { _, _ -> },
) : DvcHandler {
    companion object {
        const val CHANNEL_NAME = "Microsoft::Windows::RDS::Input"

        private const val EVENT_SC_READY = 0x0001
        private const val EVENT_CS_READY = 0x0002
        private const val EVENT_TOUCH = 0x0003
        private const val EVENT_SUSPEND = 0x0004
        private const val EVENT_RESUME = 0x0005
        private const val EVENT_PEN = 0x0008

        const val VERSION_1 = 0x00010001L
        const val VERSION_2 = 0x00020000L
        const val VERSION_3 = 0x00030000L

        /** SC_READY_MULTIPEN_INJECTION_SUPPORTED: сервер умеет перо. */
        const val SC_PEN = 0x0001L

        /** CS_READY_FLAGS_ENABLE_MULTIPEN_INJECTION. */
        const val CS_PEN = 0x0004L

        /** Сколько пальцев одновременно мы объявляем серверу. */
        const val MAX_CONTACTS = 10

        private const val HEADER = 6
    }

    @Volatile private var write: ((ByteArray) -> Unit)? = null
    private val sendLock = Any()

    /** Время последнего отправленного кадра (для смещения между кадрами); 0 — кадров ещё не было. */
    private var lastFrameMs = 0L

    /** Сервер прислал `SC_READY`, мы ответили `CS_READY`. */
    @Volatile var ready = false
        private set

    /** Сервер умеет перо (MS-RDPEI 2.0+ с multipen). */
    @Volatile var penSupported = false
        private set

    /** Сервер попросил приостановить ввод (`SUSPEND_INPUT`), например при блокировке сеанса. */
    @Volatile var suspended = false
        private set

    /** Версия протокола, которую назвал сервер. */
    @Volatile var serverVersion = 0L
        private set

    override fun onOpen(write: (ByteArray) -> Unit) {
        this.write = write
        ready = false
        suspended = false
        penSupported = false
        synchronized(sendLock) { lastFrameMs = 0L }
        log("канал открыт, жду SC_READY")
    }

    override fun onMessage(data: ByteArray) {
        if (data.size < HEADER) return
        val r = ByteReader(data)
        val event = r.u16le()
        val length = r.u32le().toInt()
        when (event) {
            EVENT_SC_READY -> {
                val version = r.u32le()
                val features = if (length >= HEADER + 8 && r.remaining >= 4) r.u32le() else 0L
                serverVersion = version
                penSupported = version >= VERSION_2 && features and SC_PEN != 0L
                log("SC_READY: версия 0x${version.toString(16)}, возможности 0x${features.toString(16)}, перо=$penSupported")
                sendCsReady(minOf(version, VERSION_3))
                ready = true
                onState(true, penSupported)
            }
            EVENT_SUSPEND -> { suspended = true; log("SUSPEND_INPUT") }
            EVENT_RESUME -> { suspended = false; log("RESUME_INPUT") }
            else -> log("событие 0x${event.toString(16)} (${data.size} байт) пропущено")
        }
    }

    override fun onClose() {
        val was = ready
        ready = false
        write = null
        if (was) onState(false, false)
    }

    private fun sendCsReady(version: Long) {
        val flags = if (penSupported) CS_PEN else 0L
        val body = ByteWriter().u32le(flags).u32le(version).u16le(MAX_CONTACTS)
        write?.invoke(pdu(EVENT_CS_READY, body.toByteArray()))
    }

    private fun pdu(event: Int, body: ByteArray): ByteArray =
        ByteWriter(HEADER + body.size).u16le(event).u32le(HEADER + body.size).bytes(body).toByteArray()

    /** Кадры касаний пальцами; false — канал не готов или приостановлен. */
    fun sendTouch(frames: List<TouchFrame>): Boolean {
        if (frames.isEmpty()) return true
        val w = write ?: return false
        if (!ready || suspended) { logDropped("касание"); return false }
        val bytes = synchronized(sendLock) { encodeTouch(frames) }
        w(bytes)
        diagnose("касание", ++touchPdus, frames.size, frames.first().points.first().let { "${it.x},${it.y} флаги 0x${it.flags.toString(16)}" })
        return true
    }

    /** Кадры пера; false — канал не готов, приостановлен или сервер не умеет перо. */
    fun sendPen(frames: List<PenFrame>): Boolean {
        if (frames.isEmpty()) return true
        val w = write ?: return false
        if (!ready || suspended || !penSupported) { logDropped("перо"); return false }
        val bytes = synchronized(sendLock) { encodePen(frames) }
        w(bytes)
        diagnose(
            "перо", ++penPdus, frames.size,
            frames.first().point.let { "${it.x},${it.y} флаги 0x${it.flags.toString(16)} нажим ${it.pressure} наклон ${it.tiltX},${it.tiltY}" },
        )
        return true
    }

    private var touchPdus = 0
    private var penPdus = 0
    private var dropped = 0

    /** Ввод не ушёл (канал приостановлен сервером): пишем первое и каждое сотое событие, чтобы журнал не рос. */
    private fun logDropped(kind: String) {
        if (++dropped == 1 || dropped % 100 == 0) log("$kind не отправлено (№$dropped): канал приостановлен или не готов")
    }

    /** В журнал: первые отправленные пакеты и далее каждый 500-й — видно, уходит ли ввод на ПК (сервер на него ничего не отвечает). */
    private fun diagnose(kind: String, n: Int, frames: Int, first: String) {
        if (n <= 5 || n % 500 == 0) log("→ $kind №$n: кадров $frames, первый $first")
    }

    /** Смещение кадра от предыдущего в микросекундах; для самого первого кадра ноль. */
    private fun offsetMicros(timeMs: Long): Long {
        val d = if (lastFrameMs == 0L) 0L else (timeMs - lastFrameMs).coerceAtLeast(0L) * 1000L
        lastFrameMs = timeMs
        return d
    }

    internal fun encodeTouch(frames: List<TouchFrame>): ByteArray {
        val b = ByteWriter()
        RdpeiInts.u4(b, 0)                       // encodeTime: кадры уходят сразу
        RdpeiInts.u2(b, frames.size)
        for (f in frames) {
            RdpeiInts.u2(b, f.points.size)
            RdpeiInts.u8(b, offsetMicros(f.timeMs))
            for (p in f.points) {
                b.u8(p.id)
                RdpeiInts.u2(b, 0)               // fieldsPresent: только координаты и флаги
                RdpeiInts.s4(b, p.x)
                RdpeiInts.s4(b, p.y)
                RdpeiInts.u4(b, p.flags.toLong())
            }
        }
        return pdu(EVENT_TOUCH, b.toByteArray())
    }

    internal fun encodePen(frames: List<PenFrame>): ByteArray {
        val b = ByteWriter()
        RdpeiInts.u4(b, 0)
        RdpeiInts.u2(b, frames.size)
        for (f in frames) {
            RdpeiInts.u2(b, 1)
            RdpeiInts.u8(b, offsetMicros(f.timeMs))
            val p = f.point
            b.u8(0)                              // penContactId
            // fieldsPresent: PENFLAGS (0x1) при нажатой кнопке/ластике, PRESSURE (0x2) всегда,
            // TILTX (0x8) и TILTY (0x10) — когда перо наклонено; отсутствующее поле Windows читает как 0.
            val tilted = p.tiltX != 0 || p.tiltY != 0
            val fields = (if (p.penFlags != 0) 0x0001 else 0) or 0x0002 or (if (tilted) 0x0008 or 0x0010 else 0)
            RdpeiInts.u2(b, fields)
            RdpeiInts.s4(b, p.x)
            RdpeiInts.s4(b, p.y)
            RdpeiInts.u4(b, p.flags.toLong())
            if (p.penFlags != 0) RdpeiInts.u4(b, p.penFlags.toLong())
            RdpeiInts.u4(b, p.pressure.coerceIn(0, 1024).toLong())
            if (tilted) {
                RdpeiInts.s2(b, p.tiltX.coerceIn(-90, 90))
                RdpeiInts.s2(b, p.tiltY.coerceIn(-90, 90))
            }
        }
        return pdu(EVENT_PEN, b.toByteArray())
    }
}
