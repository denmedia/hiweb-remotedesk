package com.hiweb.hiwebremotedesk.rdp

import android.util.Log

/** Обработчик одного динамического канала (MS-RDPEDYC). */
interface DvcHandler {
    /** Канал открыт; [write] отправляет серверу сообщение целиком. */
    fun onOpen(write: (ByteArray) -> Unit)

    /** Получено сообщение целиком (фрагменты уже склеены). */
    fun onMessage(data: ByteArray)

    /** Сервер закрыл канал (программа на ПК закрыла его или сеанс завершён). */
    fun onClose() {}
}

/**
 * Статический канал drdynvc (MS-RDPEDYC): подтверждает возможности, открывает каналы, для которых есть
 * обработчик в [handlers], остальные отклоняет. Имена запрошенных каналов пишутся в лог.
 */
class DynamicChannel(
    private val send: (ByteArray) -> Unit,
    private val handlers: Map<String, DvcHandler>,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    companion object {
        const val CHANNEL_NAME = "drdynvc"
        private const val TAG = "RDP"
        private const val CMD_CREATE = 0x01
        private const val CMD_DATA_FIRST = 0x02
        private const val CMD_DATA = 0x03
        private const val CMD_CLOSE = 0x04
        private const val CMD_CAPS = 0x05
        private const val STATUS_OK = 0L
        private const val STATUS_NO_LISTENER = 0xC0000001L

        /** CHANNEL_CHUNK_LENGTH: наибольший PDU динамического канала. */
        const val MAX_PDU = 1600
    }

    private class Open(val handler: DvcHandler, val cbId: Int) {
        var expected = 0
        val buffer = ByteWriter()
    }

    private val reassembly = ByteWriter()
    // Фрагменты одного сообщения не должны перемежаться фрагментами другого из параллельного потока.
    private val sendLock = Any()
    private val open = HashMap<Long, Open>()

    fun onChannelData(r: ByteReader) {
        if (r.remaining < 8) return
        r.u32le() // общая длина
        val flags = r.u32le().toInt()
        val chunk = r.bytes(r.remaining)
        if (flags and ClipboardChannel.CHANNEL_FLAG_FIRST != 0) reassembly.reset()
        reassembly.bytes(chunk)
        if (flags and ClipboardChannel.CHANNEL_FLAG_LAST == 0) return
        val data = reassembly.toByteArray()
        reassembly.reset()
        if (data.isEmpty()) return

        val header = data[0].toInt() and 0xFF
        val cmd = header shr 4
        val sp = (header shr 2) and 0x03
        val cbId = header and 0x03
        val r2 = ByteReader(data, 1)
        when (cmd) {
            CMD_CAPS -> {
                r2.u8()
                log("DVC caps request version=${r2.u16le()}")
                send(byteArrayOf(0x50, 0x00, 0x01, 0x00)) // версия 1: без приоритетов
            }
            CMD_CREATE -> {
                val id = readId(r2, cbId)
                val nameBytes = r2.bytes(r2.remaining)
                val end = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
                val name = String(nameBytes, 0, end, Charsets.US_ASCII)
                val handler = handlers[name]
                log("DVC create request id=$id name=$name accepted=${handler != null}")
                val rsp = ByteWriter().u8((CMD_CREATE shl 4) or cbId)
                writeId(rsp, cbId, id)
                rsp.u32le(if (handler != null) STATUS_OK else STATUS_NO_LISTENER)
                send(rsp.toByteArray())
                if (handler != null) {
                    open[id] = Open(handler, cbId)
                    handler.onOpen { msg -> sendData(id, cbId, msg) }
                }
            }
            CMD_DATA_FIRST -> {
                val ch = open[readId(r2, cbId)] ?: return
                ch.expected = when (sp) { 0 -> r2.u8(); 1 -> r2.u16le(); else -> r2.u32le().toInt() }
                ch.buffer.reset()
                ch.buffer.bytes(r2.bytes(r2.remaining))
                deliverIfComplete(ch)
            }
            CMD_DATA -> {
                val ch = open[readId(r2, cbId)] ?: return
                val payload = r2.bytes(r2.remaining)
                if (ch.expected == 0) {
                    ch.handler.onMessage(payload)
                } else {
                    ch.buffer.bytes(payload)
                    deliverIfComplete(ch)
                }
            }
            CMD_CLOSE -> {
                val id = readId(r2, cbId)
                val ch = open.remove(id) ?: return
                // Ответ клиента на закрытие — такой же PDU с тем же идентификатором.
                val rsp = ByteWriter().u8((CMD_CLOSE shl 4) or cbId)
                writeId(rsp, cbId, id)
                send(rsp.toByteArray())
                ch.handler.onClose()
            }
        }
    }

    /** Сеанс завершён: все открытые каналы считаются закрытыми. */
    fun closeAll() {
        val channels = open.values.toList()
        open.clear()
        channels.forEach { runCatching { it.handler.onClose() } }
    }

    private fun deliverIfComplete(ch: Open) {
        if (ch.buffer.size < ch.expected) return
        val msg = ch.buffer.toByteArray()
        ch.buffer.reset()
        ch.expected = 0
        ch.handler.onMessage(msg)
    }

    /**
     * Отправляет сообщение канала. PDU динамического канала не длиннее фрагмента статического (1600 байт),
     * поэтому длинное сообщение уходит как DATA_FIRST с полной длиной и следом DATA с остатком.
     */
    private fun sendData(id: Long, cbId: Int, msg: ByteArray): Unit = synchronized(sendLock) {
        val idSize = when (cbId) { 0 -> 1; 1 -> 2; else -> 4 }
        if (1 + idSize + msg.size <= MAX_PDU) {
            val w = ByteWriter(msg.size + 5).u8((CMD_DATA shl 4) or cbId)
            writeId(w, cbId, id)
            send(w.bytes(msg).toByteArray())
            return
        }
        var off = 0
        while (off < msg.size) {
            val w = ByteWriter(MAX_PDU)
            val n: Int
            if (off == 0) {
                // Поле длины — 4 байта (sp = 2).
                w.u8((CMD_DATA_FIRST shl 4) or (2 shl 2) or cbId)
                writeId(w, cbId, id)
                w.u32le(msg.size.toLong())
                n = MAX_PDU - 1 - idSize - 4
            } else {
                w.u8((CMD_DATA shl 4) or cbId)
                writeId(w, cbId, id)
                n = minOf(MAX_PDU - 1 - idSize, msg.size - off)
            }
            send(w.bytes(msg, off, n).toByteArray())
            off += n
        }
    }

    private fun readId(r: ByteReader, cbId: Int): Long = when (cbId) {
        0 -> r.u8().toLong()
        1 -> r.u16le().toLong()
        else -> r.u32le()
    }

    private fun writeId(w: ByteWriter, cbId: Int, id: Long) {
        when (cbId) {
            0 -> w.u8(id.toInt())
            1 -> w.u16le(id.toInt())
            else -> w.u32le(id)
        }
    }
}
