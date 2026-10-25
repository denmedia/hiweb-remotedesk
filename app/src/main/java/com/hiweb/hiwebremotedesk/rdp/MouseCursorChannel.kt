package com.hiweb.hiwebremotedesk.rdp

import android.util.Log

/**
 * Динамический канал «Microsoft::Windows::RDS::MouseCursor» (MS-RDPEMSC). Windows 11 присылает формы курсора
 * только по нему; обычные обновления указателя содержат лишь «стрелку по умолчанию».
 */
class MouseCursorChannel(private val onCursor: (RemoteCursor) -> Unit) : DvcHandler {
    companion object {
        const val CHANNEL_NAME = "Microsoft::Windows::RDS::MouseCursor"
        private const val TAG = "RDP"
        private const val PDU_CAPS_ADVERTISE = 0x01
        private const val PDU_MOUSEPTR_UPDATE = 0x03
        private const val CAPS_SIGNATURE = 0x53504143L
        private const val CACHE_SIZE = 64
    }

    private val cache = arrayOfNulls<CursorImage>(CACHE_SIZE)

    override fun onOpen(write: (ByteArray) -> Unit) {
        // Заголовок (тип, 0, резерв), затем один набор возможностей версии 1: сигнатура, версия, размер.
        val pdu = ByteWriter().u8(PDU_CAPS_ADVERTISE).u8(0).u16le(0).u32le(CAPS_SIGNATURE).u32le(1).u32le(12)
        write(pdu.toByteArray())
    }

    override fun onMessage(data: ByteArray) {
        if (data.size < 4) return
        val pduType = data[0].toInt() and 0xFF
        val updateType = data[1].toInt() and 0xFF
        if (pduType != PDU_MOUSEPTR_UPDATE) return
        val r = ByteReader(data, 4)
        try {
            when (updateType) {
                0x05 -> onCursor(RemoteCursor.Hidden)
                0x06 -> onCursor(RemoteCursor.Default)
                0x0A -> cache.getOrNull(r.u16le())?.let(onCursor)
                0x0B, 0x0C -> {
                    val parsed = PointerDecoder.parse(r, newFormat = true, large = updateType == 0x0C)
                    if (parsed.cacheIndex in cache.indices) cache[parsed.cacheIndex] = parsed.image
                    onCursor(parsed.image)
                }
            }
        } catch (e: RdpException) {
            Log.w(TAG, "mouse cursor update ignored: ${e.message}")
        }
    }
}
