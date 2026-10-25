package com.hiweb.hiwebremotedesk.rdp

/**
 * Динамический канал «Microsoft::Windows::RDS::DisplayControl» (MS-RDPEDISP): смена размера рабочего стола
 * без переподключения (поворот планшета). Сервер открывает канал и присылает возможности; после этого
 * клиент шлёт раскладку монитора, а сервер перестраивает сеанс (повторный Demand Active или ResetGraphics).
 */
class DisplayControlChannel(private val log: (String) -> Unit = {}) : DvcHandler {
    companion object {
        const val CHANNEL_NAME = "Microsoft::Windows::RDS::DisplayControl"
        private const val PDU_MONITOR_LAYOUT = 0x02
        private const val PDU_CAPS = 0x05
        private const val MONITOR_LAYOUT_SIZE = 40
        private const val MONITOR_PRIMARY = 0x01

        /** Допустимые стороны рабочего стола по MS-RDPEDISP; ширина должна быть чётной. */
        const val MIN_SIDE = 200
        const val MAX_SIDE = 8192

        /** PDU раскладки из одного основного монитора. */
        fun monitorLayout(width: Int, height: Int, physicalWidthMm: Int, physicalHeightMm: Int, scalePercent: Int): ByteArray {
            val w = ByteWriter(8 + 8 + MONITOR_LAYOUT_SIZE)
            w.u32le(PDU_MONITOR_LAYOUT).u32le(8 + 8 + MONITOR_LAYOUT_SIZE)
            w.u32le(MONITOR_LAYOUT_SIZE).u32le(1)
            w.u32le(MONITOR_PRIMARY).u32le(0).u32le(0)        // флаги, left, top
            w.u32le(width).u32le(height)
            w.u32le(physicalWidthMm).u32le(physicalHeightMm)
            // Стороны уже даны в повёрнутом виде, поэтому ориентация «альбомная» (0).
            w.u32le(0)
            w.u32le(scalePercent.coerceIn(100, 500)).u32le(100)
            return w.toByteArray()
        }
    }

    @Volatile private var write: ((ByteArray) -> Unit)? = null
    @Volatile private var ready = false
    @Volatile private var pending: ByteArray? = null

    override fun onOpen(write: (ByteArray) -> Unit) {
        this.write = write
    }

    override fun onMessage(data: ByteArray) {
        if (data.size < 8) return
        val r = ByteReader(data)
        if (r.u32le().toInt() != PDU_CAPS) return
        r.u32le()
        if (r.remaining >= 12) log("DISP caps: monitors=${r.u32le()} areaA=${r.u32le()} areaB=${r.u32le()}")
        ready = true
        // Поворот мог случиться до того, как сервер открыл канал.
        pending?.let { pending = null; write?.invoke(it) }
    }

    override fun onClose() {
        ready = false
        write = null
    }

    /**
     * Просит сервер сменить размер рабочего стола. Пока канал не готов, запрос запоминается (последний
     * побеждает). Возвращает false, если сторона вне допустимых пределов.
     */
    fun requestSize(width: Int, height: Int, physicalWidthMm: Int, physicalHeightMm: Int, scalePercent: Int): Boolean {
        if (width !in MIN_SIDE..MAX_SIDE || height !in MIN_SIDE..MAX_SIDE || width % 2 != 0) return false
        val pdu = monitorLayout(width, height, physicalWidthMm, physicalHeightMm, scalePercent)
        val w = write
        if (ready && w != null) w(pdu) else pending = pdu
        return true
    }
}
