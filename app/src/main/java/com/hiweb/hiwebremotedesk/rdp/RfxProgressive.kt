package com.hiweb.hiwebremotedesk.rdp

/**
 * Декодер RemoteFX Progressive (MS-RDPEGFX 2.2.4.4, кодек CAPROGRESSIVE) в том виде, в каком его передаёт
 * GNOME Remote Desktop: простые тайлы 64x64 (PROGRESSIVE_TILE_SIMPLE), энтропийное кодирование RLGR1.
 * Тайлы FIRST и UPGRADE (постепенное уточнение) не поддерживаются.
 */
class RfxProgressive(private val onTile: (x: Int, y: Int, argb: IntArray) -> Unit) {
    companion object {
        private const val SYNC = 0xCCC0
        private const val FRAME_BEGIN = 0xCCC1
        private const val FRAME_END = 0xCCC2
        private const val CONTEXT = 0xCCC3
        private const val REGION = 0xCCC4
        private const val TILE_SIMPLE = 0xCCC5
        private const val TILE_FIRST = 0xCCC6
        private const val TILE_UPGRADE = 0xCCC7
        const val TILE = 64
        private const val COEFFS = 4096

        // Смещения диапазонов коэффициентов в тайле: HL1, LH1, HH1, HL2, LH2, HH2, HL3, LH3, HH3, LL3.
        private const val HL1 = 0
        private const val LH1 = 1024
        private const val HH1 = 2048
        private const val HL2 = 3072
        private const val LH2 = 3328
        private const val HH2 = 3584
        private const val HL3 = 3840
        private const val LH3 = 3904
        private const val HH3 = 3968
        private const val LL3 = 4032
    }

    /** Десять значений квантования одного компонента: LL3, HL3, LH3, HH3, HL2, LH2, HH2, HL1, LH1, HH1. */
    private class Quant(val v: IntArray)

    private val coeff = Array(3) { ShortArray(COEFFS) }
    private val dwtTmp = ShortArray(COEFFS)
    private var skippedTiles = 0

    /** Разбирает поток блоков одной команды WireToSurface и отдаёт готовые тайлы в [onTile]. */
    fun decode(data: ByteArray) {
        val r = ByteReader(data)
        var quants = emptyList<Quant>()
        while (r.remaining >= 6) {
            val type = r.u16le()
            val len = r.u32le().toInt()
            if (len < 6 || len - 6 > r.remaining) throw RdpException("RFX: неверная длина блока $len")
            val body = ByteReader(data, r.pos, r.pos + len - 6)
            r.skip(len - 6)
            when (type) {
                SYNC, CONTEXT, FRAME_BEGIN, FRAME_END -> Unit
                REGION -> quants = region(body)
                else -> throw RdpException("RFX: неожиданный блок 0x${type.toString(16)}")
            }
        }
    }

    private fun region(b: ByteReader): List<Quant> {
        val tileSize = b.u8()
        val numRects = b.u16le()
        val numQuant = b.u8()
        val numProgQuant = b.u8()
        b.u8() // flags: бит 0 — «extrapolate»; сервер GNOME его не использует
        val numTiles = b.u16le()
        b.u32le() // размер данных тайлов
        if (tileSize != TILE) throw RdpException("RFX: размер тайла $tileSize")
        b.skip(numRects * 8)
        val quants = List(numQuant) {
            val raw = b.bytes(5)
            val q = IntArray(10)
            q[0] = raw[0].toInt() and 0x0F; q[1] = (raw[0].toInt() and 0xFF) shr 4
            q[2] = raw[1].toInt() and 0x0F; q[3] = (raw[1].toInt() and 0xFF) shr 4
            q[4] = raw[2].toInt() and 0x0F; q[5] = (raw[2].toInt() and 0xFF) shr 4
            q[6] = raw[3].toInt() and 0x0F; q[7] = (raw[3].toInt() and 0xFF) shr 4
            q[8] = raw[4].toInt() and 0x0F; q[9] = (raw[4].toInt() and 0xFF) shr 4
            Quant(q)
        }
        b.skip(numProgQuant * 16)
        repeat(numTiles) {
            val type = b.u16le()
            val len = b.u32le().toInt()
            if (len < 6 || len - 6 > b.remaining) throw RdpException("RFX: неверная длина тайла")
            val tile = ByteReader(b.data, b.pos, b.pos + len - 6)
            b.skip(len - 6)
            when (type) {
                TILE_SIMPLE -> simpleTile(tile, quants)
                TILE_FIRST, TILE_UPGRADE -> skippedTiles++
                else -> throw RdpException("RFX: неожиданный блок тайла 0x${type.toString(16)}")
            }
        }
        return quants
    }

    private fun simpleTile(t: ByteReader, quants: List<Quant>) {
        val qy = t.u8(); val qcb = t.u8(); val qcr = t.u8()
        val xIdx = t.u16le(); val yIdx = t.u16le()
        t.u8() // flags
        val yLen = t.u16le(); val cbLen = t.u16le(); val crLen = t.u16le()
        val tail = t.u16le()
        val yOff = t.pos
        val cbOff = yOff + yLen
        val crOff = cbOff + cbLen
        if (crOff + crLen + tail > t.end) throw RdpException("RFX: данные тайла обрезаны")
        if (qy >= quants.size || qcb >= quants.size || qcr >= quants.size) throw RdpException("RFX: индекс квантования")
        component(t.data, yOff, yLen, quants[qy], coeff[0])
        component(t.data, cbOff, cbLen, quants[qcb], coeff[1])
        component(t.data, crOff, crLen, quants[qcr], coeff[2])
        onTile(xIdx * TILE, yIdx * TILE, toArgb())
    }

    private fun component(data: ByteArray, off: Int, len: Int, q: Quant, out: ShortArray) {
        rlgr1Decode(data, off, len, out)
        // Разностное кодирование диапазона LL3.
        for (i in 1 until 64) out[LL3 + i] = (out[LL3 + i] + out[LL3 + i - 1]).toShort()
        val v = q.v
        shift(out, HL1, 1024, v[7] - 1)
        shift(out, LH1, 1024, v[8] - 1)
        shift(out, HH1, 1024, v[9] - 1)
        shift(out, HL2, 256, v[4] - 1)
        shift(out, LH2, 256, v[5] - 1)
        shift(out, HH2, 256, v[6] - 1)
        shift(out, HL3, 64, v[1] - 1)
        shift(out, LH3, 64, v[2] - 1)
        shift(out, HH3, 64, v[3] - 1)
        shift(out, LL3, 64, v[0] - 1)
        idwt(out, HL3, 8)
        idwt(out, HL2, 16)
        idwt(out, HL1, 32)
    }

    private fun shift(buf: ShortArray, off: Int, n: Int, s: Int) {
        if (s <= 0) return
        for (i in off until off + n) buf[i] = (buf[i].toInt() shl s).toShort()
    }

    /** Один уровень обратного вейвлет-преобразования; результат записывается в тот же участок буфера. */
    private fun idwt(buf: ShortArray, base: Int, sub: Int) {
        val total = sub shl 1
        val area = sub * sub
        val llB = base + area * 3
        val hlB = base
        val lhB = base + area
        val hhB = base + area * 2
        val tmp = dwtTmp
        val lDst = 0
        val hDst = area * 2

        var ll = llB; var hl = hlB; var lh = lhB; var hh = hhB
        var l = lDst; var h = hDst
        for (y in 0 until sub) {
            tmp[l] = (buf[ll] - ((buf[hl] + buf[hl] + 1) shr 1)).toShort()
            tmp[h] = (buf[lh] - ((buf[hh] + buf[hh] + 1) shr 1)).toShort()
            for (n in 1 until sub) {
                val x = n shl 1
                tmp[l + x] = (buf[ll + n] - ((buf[hl + n - 1] + buf[hl + n] + 1) shr 1)).toShort()
                tmp[h + x] = (buf[lh + n] - ((buf[hh + n - 1] + buf[hh + n] + 1) shr 1)).toShort()
            }
            var n = 0
            while (n < sub - 1) {
                val x = n shl 1
                tmp[l + x + 1] = ((buf[hl + n].toInt() shl 1) + ((tmp[l + x] + tmp[l + x + 2]) shr 1)).toShort()
                tmp[h + x + 1] = ((buf[hh + n].toInt() shl 1) + ((tmp[h + x] + tmp[h + x + 2]) shr 1)).toShort()
                n++
            }
            val x = n shl 1
            tmp[l + x + 1] = ((buf[hl + n].toInt() shl 1) + tmp[l + x]).toShort()
            tmp[h + x + 1] = ((buf[hh + n].toInt() shl 1) + tmp[h + x]).toShort()
            ll += sub; hl += sub; l += total
            lh += sub; hh += sub; h += total
        }

        for (x in 0 until total) {
            var li = x
            var hi = x + sub * total
            var dst = base + x
            buf[dst] = (tmp[li] - ((tmp[hi] * 2 + 1) shr 1)).toShort()
            for (n in 1 until sub) {
                li += total
                hi += total
                buf[dst + 2 * total] = (tmp[li] - ((tmp[hi - total] + tmp[hi] + 1) shr 1)).toShort()
                buf[dst + total] = ((tmp[hi - total].toInt() shl 1) + ((buf[dst] + buf[dst + 2 * total]) shr 1)).toShort()
                dst += 2 * total
            }
            buf[dst + total] = ((tmp[hi].toInt() shl 1) + ((buf[dst] * 2) shr 1)).toShort()
        }
    }

    /** YCbCr (с 5 дробными битами) в ARGB 64x64. */
    private fun toArgb(): IntArray {
        val out = IntArray(TILE * TILE)
        val yb = coeff[0]; val cbb = coeff[1]; val crb = coeff[2]
        for (i in out.indices) {
            val y = (yb[i] + 4096) shl 16
            val cb = cbb[i].toInt()
            val cr = crb[i].toInt()
            val r = (y + cr * 91916) shr 21
            val g = (y - cb * 22527 - cr * 46819) shr 21
            val b = (y + cb * 115992) shr 21
            out[i] = 0xFF000000.toInt() or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
        }
        return out
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    // ------------------------------------------------------------------ RLGR1

    private class Bits(val data: ByteArray, off: Int, len: Int) {
        private var pos = off * 8
        private val endBit = (off + len) * 8
        val remaining: Int get() = endBit - pos

        fun skip(n: Int) { pos += n }

        fun read(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                v = (v shl 1) or ((data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }

        /** Считает подряд идущие биты со значением [bit], не выходя за конец данных (сами биты пропускаются). */
        fun run(bit: Int): Int {
            var n = 0
            while (pos < endBit && ((data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1) == bit) {
                pos++
                n++
            }
            return n
        }
    }

    private fun rlgr1Decode(data: ByteArray, off: Int, len: Int, out: ShortArray) {
        val bs = Bits(data, off, len)
        var k = 1
        var kp = k shl 3
        var kr = 1
        var krp = kr shl 3
        var o = 0
        while (bs.remaining > 0 && o < COEFFS) {
            if (k != 0) {
                var vk = bs.run(0)
                if (bs.remaining < 1) break
                bs.skip(1)
                var run = 0
                while (vk-- > 0) {
                    run += 1 shl k
                    kp = minOf(kp + 4, 80)
                    k = kp shr 3
                }
                if (bs.remaining < k) break
                run += bs.read(k)
                if (bs.remaining < 1) break
                val sign = bs.read(1)
                vk = bs.run(1)
                if (bs.remaining < 1) break
                bs.skip(1)
                if (bs.remaining < kr) break
                var code = (if (kr > 0) bs.read(kr) else 0) or (vk shl kr)
                code = code and 0xFFFF
                if (vk == 0) {
                    krp = maxOf(krp - 2, 0)
                    kr = krp shr 3
                } else if (vk != 1) {
                    krp = minOf(krp + vk, 80)
                    kr = krp shr 3
                }
                kp = maxOf(kp - 6, 0)
                k = kp shr 3
                val mag = if (sign == 1) -(code + 1) else code + 1
                val zeros = minOf(run, COEFFS - o)
                java.util.Arrays.fill(out, o, o + zeros, 0)
                o += zeros
                if (o < COEFFS) out[o++] = mag.toShort()
            } else {
                val vk = bs.run(1)
                if (bs.remaining < 1) break
                bs.skip(1)
                if (bs.remaining < kr) break
                val code = ((if (kr > 0) bs.read(kr) else 0) or (vk shl kr)) and 0xFFFF
                if (vk == 0) {
                    krp = maxOf(krp - 2, 0)
                    kr = krp shr 3
                } else if (vk != 1) {
                    krp = minOf(krp + vk, 80)
                    kr = krp shr 3
                }
                val mag: Int
                if (code == 0) {
                    kp = minOf(kp + 3, 80)
                    k = kp shr 3
                    mag = 0
                } else {
                    kp = maxOf(kp - 3, 0)
                    k = kp shr 3
                    mag = if (code and 1 != 0) -((code + 1) shr 1) else code shr 1
                }
                out[o++] = mag.toShort()
            }
        }
        if (o < COEFFS) java.util.Arrays.fill(out, o, COEFFS, 0)
    }
}
