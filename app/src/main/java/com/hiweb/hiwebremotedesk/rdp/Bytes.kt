package com.hiweb.hiwebremotedesk.rdp

import java.io.ByteArrayOutputStream

/** Накопитель байтов с методами записи в little/big endian. */
class ByteWriter(capacity: Int = 256) {
    private val out = ByteArrayOutputStream(capacity)

    val size: Int get() = out.size()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16le(v: Int) = apply { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
    fun u16be(v: Int) = apply { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
    fun u32le(v: Long) = apply {
        for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }
    fun u32le(v: Int) = u32le(v.toLong() and 0xFFFFFFFFL)
    fun u32be(v: Int) = apply {
        for (i in 3 downTo 0) out.write((v ushr (8 * i)) and 0xFF)
    }
    fun u64le(v: Long) = apply {
        for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }
    fun bytes(b: ByteArray) = apply { out.write(b, 0, b.size) }
    fun bytes(b: ByteArray, off: Int, len: Int) = apply { out.write(b, off, len) }
    fun zeros(n: Int) = apply { repeat(n) { out.write(0) } }

    /** UTF-16LE без завершающего нуля. */
    fun utf16(s: String) = bytes(s.toByteArray(Charsets.UTF_16LE))

    fun reset() = out.reset()

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Последовательное чтение из массива, little endian по умолчанию. */
class ByteReader(val data: ByteArray, var pos: Int = 0, val end: Int = data.size) {
    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (pos + n > end) throw RdpException("Неожиданный конец данных (нужно $n, есть ${end - pos})")
    }

    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun u16le(): Int { need(2); val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }
    fun u16be(): Int { need(2); val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2; return v }
    fun u32le(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }
    fun u64le(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }
    fun bytes(n: Int): ByteArray { need(n); val r = data.copyOfRange(pos, pos + n); pos += n; return r }
    fun skip(n: Int) { need(n); pos += n }
}

class RdpException(message: String, cause: Throwable? = null) : Exception(message, cause)
