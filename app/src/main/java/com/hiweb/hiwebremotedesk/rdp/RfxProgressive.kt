package com.hiweb.hiwebremotedesk.rdp

/**
 * Декодер RemoteFX Progressive (MS-RDPEGFX 2.2.4.4, кодек CAPROGRESSIVE) для одной поверхности.
 *
 * Тайлы 64x64, энтропийное кодирование RLGR1. Поддерживаются все три вида тайлов:
 *  - SIMPLE (GNOME Remote Desktop): готовый тайл целиком;
 *  - FIRST (Windows): первый проход с пониженным качеством — старшие биты коэффициентов;
 *  - UPGRADE (Windows): уточнение — недостающие младшие биты коэффициентов (SRL-поток для новых значимых
 *    коэффициентов, сырые биты для уже известных).
 * Режим «extrapolate» региона (Windows) меняет размеры поддиапазонов и вейвлет-преобразование, без него (GNOME) —
 * классические 1024/256/64 коэффициентов. Состояние уточнения хранится по тайлам внутри экземпляра, поэтому
 * экземпляр живёт столько же, сколько поверхность. Алгоритм сверен с FreeRDP (libfreerdp/codec/progressive.c).
 *
 * Готовые тайлы отдаются в [onBlit] как ARGB 64x64: области [srcX, srcY, w, h] нужно перенести в ([dx], [dy])
 * поверхности; область уже обрезана по прямоугольникам региона.
 */
class RfxProgressive(
    private val onBlit: (tile: IntArray, srcX: Int, srcY: Int, w: Int, h: Int, dx: Int, dy: Int) -> Unit,
) {
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
        private const val QUALITY_FULL = 0xFF
        private const val FLAG_TILE_DIFFERENCE = 0x01
        private const val FLAG_EXTRAPOLATE = 0x01

        // Поддиапазоны тайла в порядке HL1, LH1, HH1, HL2, LH2, HH2, HL3, LH3, HH3, LL3: смещения и размеры.
        // Без extrapolate — степени двойки; с ним уровни 1–3 имеют размеры 31x33, 16x17, 8x9 и LL3 9x9.
        private val OFFSETS = arrayOf(
            intArrayOf(0, 1024, 2048, 3072, 3328, 3584, 3840, 3904, 3968, 4032),
            intArrayOf(0, 1023, 2046, 3007, 3279, 3551, 3807, 3879, 3951, 4015),
        )
        private val LENGTHS = arrayOf(
            intArrayOf(1024, 1024, 1024, 256, 256, 256, 64, 64, 64, 64),
            intArrayOf(1023, 1023, 961, 272, 272, 256, 72, 72, 64, 81),
        )

        // Положение поддиапазона в векторе квантования LL3, HL3, LH3, HH3, HL2, LH2, HH2, HL1, LH1, HH1.
        private val QUANT_INDEX = intArrayOf(7, 8, 9, 4, 5, 6, 1, 2, 3, 0)
        private const val LL3_BAND = 9
    }

    /** Значения квантования трёх компонентов (Y, Cb, Cr): десять значений на компонент. */
    private class Quants(val y: IntArray, val cb: IntArray, val cr: IntArray) {
        fun of(c: Int) = when (c) { 0 -> y; 1 -> cb; else -> cr }
    }

    private class Region(
        val extrapolate: Boolean,
        val rects: List<IntArray>,
        val quants: List<IntArray>,
        val progressive: List<Quants>,
    )

    /** Состояние тайла между проходами: сырые значения (знаки) и накопленные коэффициенты. */
    private class TileState {
        val sign = Array(3) { ShortArray(COEFFS) }
        val current = Array(3) { ShortArray(COEFFS) }
        val bitPos = Array(3) { IntArray(10) }
    }

    private val tiles = HashMap<Int, TileState>()
    private val full = Quants(IntArray(10), IntArray(10), IntArray(10))
    private val work = Array(3) { ShortArray(COEFFS) }
    private val dwtTmp = ShortArray(COEFFS)

    /** Разбирает поток блоков одной команды WireToSurface и отдаёт готовые тайлы в [onBlit]. */
    fun decode(data: ByteArray) {
        val r = ByteReader(data)
        while (r.remaining >= 6) {
            val type = r.u16le()
            val len = r.u32le().toInt()
            if (len < 6 || len - 6 > r.remaining) throw RdpException("RFX: неверная длина блока $len")
            val body = ByteReader(data, r.pos, r.pos + len - 6)
            r.skip(len - 6)
            when (type) {
                SYNC, CONTEXT, FRAME_BEGIN, FRAME_END -> Unit
                REGION -> region(body)
                else -> throw RdpException("RFX: неожиданный блок 0x${type.toString(16)}")
            }
        }
    }

    private fun readQuant(b: ByteReader): IntArray {
        val raw = b.bytes(5)
        val q = IntArray(10)
        for (i in 0 until 5) {
            q[i * 2] = raw[i].toInt() and 0x0F
            q[i * 2 + 1] = (raw[i].toInt() and 0xFF) shr 4
        }
        return q
    }

    private fun region(b: ByteReader) {
        val tileSize = b.u8()
        val numRects = b.u16le()
        val numQuant = b.u8()
        val numProgQuant = b.u8()
        val flags = b.u8()
        val numTiles = b.u16le()
        b.u32le() // размер данных тайлов
        if (tileSize != TILE) throw RdpException("RFX: размер тайла $tileSize")
        val rects = List(numRects) { intArrayOf(b.u16le(), b.u16le(), b.u16le(), b.u16le()) }
        val quants = List(numQuant) { readQuant(b) }
        val progressive = List(numProgQuant) {
            b.u8() // качество (номер прохода задаёт сам тайл)
            Quants(readQuant(b), readQuant(b), readQuant(b))
        }
        val region = Region(flags and FLAG_EXTRAPOLATE != 0, rects, quants, progressive)
        repeat(numTiles) {
            val type = b.u16le()
            val len = b.u32le().toInt()
            if (len < 6 || len - 6 > b.remaining) throw RdpException("RFX: неверная длина тайла")
            val tile = ByteReader(b.data, b.pos, b.pos + len - 6)
            b.skip(len - 6)
            when (type) {
                TILE_SIMPLE -> firstTile(tile, region, simple = true)
                TILE_FIRST -> firstTile(tile, region, simple = false)
                TILE_UPGRADE -> upgradeTile(tile, region)
                else -> throw RdpException("RFX: неожиданный блок тайла 0x${type.toString(16)}")
            }
        }
    }

    /** Положение «битовой плоскости» каждого поддиапазона: значения региона плюс прогрессивные для качества [quality]. */
    private fun bitPositions(region: Region, tile: IntArray, quality: Int): Quants {
        for (c in 0..2) if (tile[c] >= region.quants.size) throw RdpException("RFX: индекс квантования ${tile[c]}")
        val prog = if (quality == QUALITY_FULL) full else region.progressive.getOrNull(quality) ?: throw RdpException("RFX: качество $quality")
        fun sum(base: IntArray, p: IntArray) = IntArray(10) { base[it] + p[it] }
        return Quants(sum(region.quants[tile[0]], prog.y), sum(region.quants[tile[1]], prog.cb), sum(region.quants[tile[2]], prog.cr))
    }

    private fun firstTile(t: ByteReader, region: Region, simple: Boolean) {
        val idx = intArrayOf(t.u8(), t.u8(), t.u8())
        val xIdx = t.u16le()
        val yIdx = t.u16le()
        val flags = t.u8()
        val quality = if (simple) QUALITY_FULL else t.u8()
        val yLen = t.u16le()
        val cbLen = t.u16le()
        val crLen = t.u16le()
        val tail = t.u16le()
        val yOff = t.pos
        val cbOff = yOff + yLen
        val crOff = cbOff + cbLen
        if (crOff + crLen + tail > t.end) throw RdpException("RFX: данные тайла обрезаны")
        val bitPos = bitPositions(region, idx, quality)
        val diff = flags and FLAG_TILE_DIFFERENCE != 0
        val key = (yIdx shl 16) or xIdx
        // Состояние нужно только тайлам, которые потом уточняются (качество ниже полного) или читаются как разность.
        val keep = quality != QUALITY_FULL
        val state = if (keep) tiles.getOrPut(key) { TileState() } else tiles[key]
        val lens = intArrayOf(yLen, cbLen, crLen)
        val offs = intArrayOf(yOff, cbOff, crOff)
        for (c in 0..2) {
            val buf = work[c]
            rlgr1Decode(t.data, offs[c], lens[c], buf)
            if (keep) {
                System.arraycopy(buf, 0, state!!.sign[c], 0, COEFFS)
                System.arraycopy(bitPos.of(c), 0, state.bitPos[c], 0, 10)
            }
            dequantize(buf, bitPos.of(c), region.extrapolate)
            if (diff && state != null) {
                // Тайл-разность: коэффициенты прибавляются к прежним, сумма становится и результатом, и новым состоянием.
                val cur = state.current[c]
                for (i in 0 until COEFFS) {
                    buf[i] = (buf[i] + cur[i]).toShort()
                    cur[i] = buf[i]
                }
            } else if (keep) {
                System.arraycopy(buf, 0, state!!.current[c], 0, COEFFS)
            }
            transform(buf, region.extrapolate)
        }
        emit(xIdx, yIdx, region)
    }

    private fun upgradeTile(t: ByteReader, region: Region) {
        val idx = intArrayOf(t.u8(), t.u8(), t.u8())
        val xIdx = t.u16le()
        val yIdx = t.u16le()
        val quality = t.u8()
        val srlLen = IntArray(3)
        val rawLen = IntArray(3)
        for (c in 0..2) {
            srlLen[c] = t.u16le()
            rawLen[c] = t.u16le()
        }
        val state = tiles[(yIdx shl 16) or xIdx] ?: throw RdpException("RFX: уточнение тайла $xIdx,$yIdx без первого прохода")
        val bitPos = bitPositions(region, idx, quality)
        var pos = t.pos
        for (c in 0..2) {
            val srlOff = pos
            val rawOff = srlOff + srlLen[c]
            pos = rawOff + rawLen[c]
            if (pos > t.end) throw RdpException("RFX: данные уточнения обрезаны")
            val numBits = IntArray(10)
            for (i in 0 until 10) {
                numBits[i] = state.bitPos[c][i] - bitPos.of(c)[i]
                if (numBits[i] < 0) throw RdpException("RFX: уточнение понижает качество")
            }
            upgrade(state.current[c], state.sign[c], t.data, srlOff, srlLen[c], rawOff, rawLen[c], bitPos.of(c), numBits, region.extrapolate)
            System.arraycopy(bitPos.of(c), 0, state.bitPos[c], 0, 10)
            System.arraycopy(state.current[c], 0, work[c], 0, COEFFS)
            transform(work[c], region.extrapolate)
        }
        emit(xIdx, yIdx, region)
    }

    /** Дифференциальное декодирование LL3 и сдвиг каждого поддиапазона на (положение − 1) бит. */
    private fun dequantize(buf: ShortArray, bitPos: IntArray, extrapolate: Boolean) {
        val mode = if (extrapolate) 1 else 0
        val off = OFFSETS[mode]
        val len = LENGTHS[mode]
        for (i in 1 until len[LL3_BAND]) buf[off[LL3_BAND] + i] = (buf[off[LL3_BAND] + i] + buf[off[LL3_BAND] + i - 1]).toShort()
        for (band in 0 until 10) {
            val s = bitPos[QUANT_INDEX[band]] - 1
            if (s <= 0) continue
            for (i in off[band] until off[band] + len[band]) buf[i] = (buf[i].toInt() shl s).toShort()
        }
    }

    // ------------------------------------------------------------------ уточнение

    /** Читатель битов от старшего к младшему; за концом данных отдаёт нули, как поток FreeRDP. */
    internal class BitSource(val data: ByteArray, off: Int, len: Int) {
        private var pos = off * 8
        private val endBit = (off + len) * 8

        fun bit(): Int {
            if (pos >= endBit) { pos++; return 0 }
            val v = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return v
        }

        fun bits(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = (v shl 1) or bit()
            return v
        }
    }

    /** Состояние SRL-декодера (упрощённое RLGR для новых значимых коэффициентов). */
    internal class Srl(val bits: BitSource) {
        var kp = 8
        var nz = 0
        var mode = 0

        fun read(numBits: Int): Int {
            if (nz > 0) { nz--; return 0 }
            val k = kp / 8
            if (mode == 0) {
                if (bits.bit() == 0) {
                    // «0»: не меньше (1 shl k) нулей подряд.
                    nz = 1 shl k
                    kp = minOf(kp + 4, 80)
                    nz--
                    return 0
                }
                // «1»: меньше (1 shl k) нулей, их число — следующие k бит; дальше идёт значение.
                nz = if (k > 0) bits.bits(k) else 0
                mode = 1
                if (nz > 0) { nz--; return 0 }
            }
            mode = 0
            val sign = bits.bit()
            kp = if (kp < 6) 0 else kp - 6
            if (numBits == 1) return if (sign == 1) -1 else 1
            var mag = 1
            val max = (1 shl numBits) - 1
            while (mag < max) {
                if (bits.bit() == 1) break
                mag++
            }
            return if (sign == 1) -mag else mag
        }
    }

    private fun upgrade(
        current: ShortArray, sign: ShortArray, data: ByteArray, srlOff: Int, srlLen: Int, rawOff: Int, rawLen: Int,
        bitPos: IntArray, numBits: IntArray, extrapolate: Boolean,
    ) {
        val mode = if (extrapolate) 1 else 0
        val off = OFFSETS[mode]
        val len = LENGTHS[mode]
        val srl = Srl(BitSource(data, srlOff, srlLen))
        val raw = BitSource(data, rawOff, rawLen)
        for (band in 0 until 10) {
            val bits = numBits[QUANT_INDEX[band]]
            if (bits < 1) continue
            val shift = bitPos[QUANT_INDEX[band]] - 1
            for (i in off[band] until off[band] + len[band]) {
                val input = when {
                    band == LL3_BAND -> raw.bits(bits)
                    sign[i] > 0 -> raw.bits(bits)
                    sign[i] < 0 -> -raw.bits(bits)
                    else -> srl.read(bits).also { sign[i] = it.toShort() }
                }
                current[i] = (current[i] + (input shl shift)).toShort()
            }
        }
    }

    // ------------------------------------------------------------------ преобразования

    /** Обратное вейвлет-преобразование коэффициентов [buf] → значения компонента (в нём же). */
    internal fun transform(buf: ShortArray, extrapolate: Boolean) {
        if (extrapolate) {
            idwtBlock(buf, 3807, 3)
            idwtBlock(buf, 3007, 2)
            idwtBlock(buf, 0, 1)
        } else {
            idwt(buf, 3840, 8)
            idwt(buf, 3072, 16)
            idwt(buf, 0, 32)
        }
    }

    private fun clamp16(v: Int) = (if (v < -32768) -32768 else if (v > 32767) 32767 else v).toShort()

    /**
     * Один уровень обратного преобразования в режиме extrapolate (FreeRDP: progressive_rfx_dwt_2d_decode_block):
     * блок лежит с [base]; порядок поддиапазонов HL, LH, HH, LL; результат записывается на то же место со строкой
     * (nBandL + nBandH).
     */
    private fun idwtBlock(buf: ShortArray, base: Int, level: Int) {
        val nBandL = (64 shr level) + 1
        val nBandH = if (level == 1) 31 else (64 + (1 shl (level - 1))) shr level
        val hl = base
        val lh = hl + nBandH * nBandL
        val hh = lh + nBandL * nBandH
        val ll = hh + nBandH * nBandH
        val step = nBandL + nBandH
        val l = 0
        val h = nBandL * step
        // горизонтально: LL + HL → L, LH + HH → H
        idwtX(buf, ll, nBandL, buf, hl, nBandH, dwtTmp, l, step, nBandL, nBandH, nBandL)
        idwtX(buf, lh, nBandL, buf, hh, nBandH, dwtTmp, h, step, nBandL, nBandH, nBandH)
        // вертикально: L + H → результат
        idwtY(dwtTmp, l, step, dwtTmp, h, step, buf, base, step, nBandL, nBandH, step)
    }

    private fun idwtX(
        low: ShortArray, lowBase: Int, lowStep: Int, high: ShortArray, highBase: Int, highStep: Int,
        dst: ShortArray, dstBase: Int, dstStep: Int, nLow: Int, nHigh: Int, rows: Int,
    ) {
        for (row in 0 until rows) {
            var pl = lowBase + row * lowStep
            var ph = highBase + row * highStep
            var px = dstBase + row * dstStep
            var h0 = high[ph++].toInt()
            var l0 = low[pl++].toInt()
            var x0 = clamp16(l0 - h0).toInt()
            var x2 = x0
            for (j in 0 until nHigh - 1) {
                val h1 = high[ph++].toInt()
                l0 = low[pl++].toInt()
                x2 = clamp16(l0 - (h0 + h1) / 2).toInt()
                val x1 = clamp16((x0 + x2) / 2 + 2 * h0).toInt()
                dst[px] = x0.toShort()
                dst[px + 1] = x1.toShort()
                px += 2
                x0 = x2
                h0 = h1
            }
            if (nLow <= nHigh + 1) {
                if (nLow <= nHigh) {
                    dst[px] = x2.toShort()
                    dst[px + 1] = clamp16(x2 + 2 * h0)
                } else {
                    l0 = low[pl].toInt()
                    x0 = clamp16(l0 - h0).toInt()
                    dst[px] = x2.toShort()
                    dst[px + 1] = clamp16((x0 + x2) / 2 + 2 * h0)
                    dst[px + 2] = x0.toShort()
                }
            } else {
                l0 = low[pl++].toInt()
                x0 = clamp16(l0 - h0 / 2).toInt()
                dst[px] = x2.toShort()
                dst[px + 1] = clamp16((x0 + x2) / 2 + 2 * h0)
                dst[px + 2] = x0.toShort()
                l0 = low[pl].toInt()
                dst[px + 3] = clamp16((x0 + l0) / 2)
            }
        }
    }

    private fun idwtY(
        low: ShortArray, lowBase: Int, lowStep: Int, high: ShortArray, highBase: Int, highStep: Int,
        dst: ShortArray, dstBase: Int, dstStep: Int, nLow: Int, nHigh: Int, cols: Int,
    ) {
        for (col in 0 until cols) {
            var pl = lowBase + col
            var ph = highBase + col
            var px = dstBase + col
            var h0 = high[ph].toInt()
            ph += highStep
            var l0 = low[pl].toInt()
            pl += lowStep
            var x0 = clamp16(l0 - h0).toInt()
            var x2 = x0
            for (j in 0 until nHigh - 1) {
                val h1 = high[ph].toInt()
                ph += highStep
                l0 = low[pl].toInt()
                pl += lowStep
                x2 = clamp16(l0 - (h0 + h1) / 2).toInt()
                val x1 = clamp16((x0 + x2) / 2 + 2 * h0).toInt()
                dst[px] = x0.toShort()
                px += dstStep
                dst[px] = x1.toShort()
                px += dstStep
                x0 = x2
                h0 = h1
            }
            if (nLow <= nHigh + 1) {
                if (nLow <= nHigh) {
                    dst[px] = x2.toShort()
                    px += dstStep
                    dst[px] = clamp16(x2 + 2 * h0)
                } else {
                    l0 = low[pl].toInt()
                    x0 = clamp16(l0 - h0).toInt()
                    dst[px] = x2.toShort()
                    px += dstStep
                    dst[px] = clamp16((x0 + x2) / 2 + 2 * h0)
                    px += dstStep
                    dst[px] = x0.toShort()
                }
            } else {
                l0 = low[pl].toInt()
                pl += lowStep
                x0 = clamp16(l0 - h0 / 2).toInt()
                dst[px] = x2.toShort()
                px += dstStep
                dst[px] = clamp16((x0 + x2) / 2 + 2 * h0)
                px += dstStep
                dst[px] = x0.toShort()
                px += dstStep
                l0 = low[pl].toInt()
                dst[px] = clamp16((x0 + l0) / 2)
            }
        }
    }

    /** Один уровень классического обратного преобразования (без extrapolate); результат — на том же месте буфера. */
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

    /** Переносит готовый тайл ([work], YCbCr с 5 дробными битами) на поверхность в пределах прямоугольников региона. */
    private fun emit(xIdx: Int, yIdx: Int, region: Region) {
        val tx = xIdx * TILE
        val ty = yIdx * TILE
        val argb = toArgb()
        for (r in region.rects) {
            val l = maxOf(r[0], tx)
            val t = maxOf(r[1], ty)
            val rt = minOf(r[0] + r[2], tx + TILE)
            val b = minOf(r[1] + r[3], ty + TILE)
            if (l < rt && t < b) onBlit(argb, l - tx, t - ty, rt - l, b - t, l, t)
        }
    }

    /** YCbCr (с 5 дробными битами) в ARGB 64x64. */
    private fun toArgb(): IntArray {
        val out = IntArray(TILE * TILE)
        val yb = work[0]; val cbb = work[1]; val crb = work[2]
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
