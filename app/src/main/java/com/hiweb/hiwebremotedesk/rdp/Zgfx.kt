package com.hiweb.hiwebremotedesk.rdp

import java.io.ByteArrayOutputStream

/**
 * Распаковка RDP8 bulk compression (ZGFX, MS-RDPEGFX 3.1.9). Каждое сообщение графического канала приходит
 * в виде сегментов; окно истории (2,5 МБ) общее для всех сообщений канала, поэтому экземпляр живёт столько же, сколько канал.
 */
class Zgfx {
    companion object {
        private const val HISTORY_SIZE = 2_500_000
        private const val DESCRIPTOR_SINGLE = 0xE0
        private const val DESCRIPTOR_MULTIPART = 0xE1
        private const val PACKET_COMPRESSED = 0x20

        // Таблица префиксных кодов: длина префикса, код, число битов значения, тип (0 — литерал, 1 — повтор), база значения.
        private val TOKENS = arrayOf(
            intArrayOf(1, 0, 8, 0, 0),
            intArrayOf(5, 17, 5, 1, 0),
            intArrayOf(5, 18, 7, 1, 32),
            intArrayOf(5, 19, 9, 1, 160),
            intArrayOf(5, 20, 10, 1, 672),
            intArrayOf(5, 21, 12, 1, 1696),
            intArrayOf(5, 24, 0, 0, 0x00),
            intArrayOf(5, 25, 0, 0, 0x01),
            intArrayOf(6, 44, 14, 1, 5792),
            intArrayOf(6, 45, 15, 1, 22176),
            intArrayOf(6, 52, 0, 0, 0x02),
            intArrayOf(6, 53, 0, 0, 0x03),
            intArrayOf(6, 54, 0, 0, 0xFF),
            intArrayOf(7, 92, 18, 1, 54944),
            intArrayOf(7, 93, 20, 1, 317088),
            intArrayOf(7, 110, 0, 0, 0x04),
            intArrayOf(7, 111, 0, 0, 0x05),
            intArrayOf(7, 112, 0, 0, 0x06),
            intArrayOf(7, 113, 0, 0, 0x07),
            intArrayOf(7, 114, 0, 0, 0x08),
            intArrayOf(7, 115, 0, 0, 0x09),
            intArrayOf(7, 116, 0, 0, 0x0A),
            intArrayOf(7, 117, 0, 0, 0x0B),
            intArrayOf(7, 118, 0, 0, 0x3A),
            intArrayOf(7, 119, 0, 0, 0x3B),
            intArrayOf(7, 120, 0, 0, 0x3C),
            intArrayOf(7, 121, 0, 0, 0x3D),
            intArrayOf(7, 122, 0, 0, 0x3E),
            intArrayOf(7, 123, 0, 0, 0x3F),
            intArrayOf(7, 124, 0, 0, 0x40),
            intArrayOf(7, 125, 0, 0, 0x80),
            intArrayOf(8, 188, 20, 1, 1365664),
            intArrayOf(8, 189, 21, 1, 2414240),
            intArrayOf(8, 252, 0, 0, 0x0C),
            intArrayOf(8, 253, 0, 0, 0x38),
            intArrayOf(8, 254, 0, 0, 0x39),
            intArrayOf(8, 255, 0, 0, 0x66),
            intArrayOf(9, 380, 22, 1, 4511392),
            intArrayOf(9, 381, 23, 1, 8705696),
            intArrayOf(9, 382, 24, 1, 17094304),
        )
    }

    private val history = ByteArray(HISTORY_SIZE)
    private var historyIndex = 0

    // Состояние побитового чтения текущего сегмента.
    private var input = ByteArray(0)
    private var inPos = 0
    private var inEnd = 0
    private var bitsRemaining = 0
    private var bitsCurrent = 0
    private var cBitsCurrent = 0
    private var lastBits = 0

    /** Возвращает распакованное сообщение. */
    fun decompress(data: ByteArray): ByteArray {
        if (data.isEmpty()) throw RdpException("ZGFX: пустое сообщение")
        val out = ByteArrayOutputStream(data.size * 2)
        when (data[0].toInt() and 0xFF) {
            DESCRIPTOR_SINGLE -> segment(data, 1, data.size - 1, out)
            DESCRIPTOR_MULTIPART -> {
                val r = ByteReader(data, 1)
                val count = r.u16le()
                r.u32le() // общий размер после распаковки
                repeat(count) {
                    val size = r.u32le().toInt()
                    if (size < 0 || size > r.remaining) throw RdpException("ZGFX: неверный размер сегмента")
                    segment(data, r.pos, size, out)
                    r.skip(size)
                }
            }
            else -> throw RdpException("ZGFX: неизвестный дескриптор")
        }
        return out.toByteArray()
    }

    private fun segment(data: ByteArray, start: Int, size: Int, out: ByteArrayOutputStream) {
        if (size < 2) throw RdpException("ZGFX: короткий сегмент")
        val flags = data[start].toInt() and 0xFF
        val bodyStart = start + 1
        val bodyLen = size - 1
        if (flags and PACKET_COMPRESSED == 0) {
            historyWrite(data, bodyStart, bodyLen)
            out.write(data, bodyStart, bodyLen)
            return
        }
        input = data
        inPos = bodyStart
        inEnd = bodyStart + bodyLen - 1          // последний байт — число неиспользуемых битов
        lastBits = data[inEnd].toInt() and 0xFF
        val bits = 8 * (bodyLen - 1)
        if (bits < lastBits) throw RdpException("ZGFX: неверный сегмент")
        bitsRemaining = bits - lastBits
        bitsCurrent = 0
        cBitsCurrent = 0
        val seg = ByteArrayOutputStream(65536)

        while (bitsRemaining > 0) {
            var haveBits = 0
            var prefix = 0
            var matched = false
            for (t in TOKENS) {
                while (haveBits < t[0]) {
                    prefix = (prefix shl 1) + getBits(1)
                    haveBits++
                }
                if (prefix != t[1]) continue
                matched = true
                if (t[3] == 0) {
                    val c = (t[4] + getBits(t[2])) and 0xFF
                    history[historyIndex] = c.toByte()
                    if (++historyIndex == HISTORY_SIZE) historyIndex = 0
                    seg.write(c)
                } else {
                    val distance = t[4] + getBits(t[2])
                    if (distance != 0) {
                        var count: Int
                        if (getBits(1) == 0) {
                            count = 3
                        } else {
                            count = 4
                            var extra = 2
                            while (getBits(1) == 1) {
                                count *= 2
                                extra++
                            }
                            count += getBits(extra)
                        }
                        val chunk = ByteArray(count)
                        historyRead(distance, chunk, count)
                        historyWrite(chunk, 0, count)
                        seg.write(chunk, 0, count)
                    } else {
                        val count = getBits(15)
                        bitsRemaining -= cBitsCurrent
                        cBitsCurrent = 0
                        bitsCurrent = 0
                        if (count > bitsRemaining / 8 || inPos + count > inEnd) throw RdpException("ZGFX: неверная длина литералов")
                        historyWrite(input, inPos, count)
                        seg.write(input, inPos, count)
                        inPos += count
                        bitsRemaining -= 8 * count
                    }
                }
                break
            }
            if (!matched) throw RdpException("ZGFX: неизвестный префикс")
        }
        seg.writeTo(out)
    }

    private fun getBits(n: Int): Int {
        if (n == 0) return 0
        while (cBitsCurrent < n) {
            bitsCurrent = bitsCurrent shl 8
            if (inPos < inEnd) bitsCurrent += input[inPos++].toInt() and 0xFF
            cBitsCurrent += 8
        }
        bitsRemaining -= n
        cBitsCurrent -= n
        val bits = bitsCurrent ushr cBitsCurrent
        bitsCurrent = bitsCurrent and ((1 shl cBitsCurrent) - 1)
        return bits
    }

    private fun historyWrite(src: ByteArray, off: Int, len: Int) {
        var o = off
        var n = len
        if (n <= 0) return
        if (n > HISTORY_SIZE) {
            val residue = n - HISTORY_SIZE
            o += residue
            n = HISTORY_SIZE
            historyIndex = (historyIndex + residue) % HISTORY_SIZE
        }
        if (historyIndex + n <= HISTORY_SIZE) {
            System.arraycopy(src, o, history, historyIndex, n)
            historyIndex += n
            if (historyIndex == HISTORY_SIZE) historyIndex = 0
        } else {
            val front = HISTORY_SIZE - historyIndex
            System.arraycopy(src, o, history, historyIndex, front)
            System.arraycopy(src, o + front, history, 0, n - front)
            historyIndex = n - front
        }
    }

    /** Копирует [count] байтов, начиная на [distance] байтов назад; при пересечении повторяет уже скопированное. */
    private fun historyRead(distance: Int, dst: ByteArray, count: Int) {
        val start = (historyIndex + HISTORY_SIZE - distance) % HISTORY_SIZE
        val first = minOf(count, distance)
        for (i in 0 until first) dst[i] = history[(start + i) % HISTORY_SIZE]
        var valid = first
        while (valid < count) {
            val n = minOf(valid, count - valid)
            System.arraycopy(dst, 0, dst, valid, n)
            valid += n
        }
    }
}
