package com.hiweb.hiwebremotedesk.rdp

/**
 * Декодер ClearCodec (MS-RDPEGFX 2.2.4.1, кодек 0x0008): кодек без потерь для текста и значков, которым Windows
 * передаёт экран входа и мелкие обновления. Состояние (кэш глифов и вертикальных полос) общее для всех поверхностей
 * канала, как у FreeRDP, поэтому экземпляр живёт столько же, сколько канал.
 *
 * Картинка собирается слоями поверх [canvas] (содержимое поверхности под областью): остаток (залить цветными
 * прогонами всю область), полосы (вертикальные столбики из кэша), подкодеки (несжатый и RLEX; NSCodec не поддерживается).
 * Алгоритм сверен с FreeRDP (libfreerdp/codec/clear.c).
 */
class ClearCodec(private val log: (String) -> Unit = {}) {
    companion object {
        private const val FLAG_GLYPH_INDEX = 0x01
        private const val FLAG_GLYPH_HIT = 0x02
        private const val FLAG_CACHE_RESET = 0x04
        private const val GLYPHS = 4000
        private const val VBARS = 32768
        private const val SHORT_VBARS = 16384
        private const val MAX_VBAR_HEIGHT = 52
        private const val OPAQUE = 0xFF000000.toInt()

        private fun rgb(b: Int, g: Int, r: Int) = OPAQUE or (r shl 16) or (g shl 8) or b
    }

    private val glyphs = arrayOfNulls<IntArray>(GLYPHS)
    private val vbars = arrayOfNulls<IntArray>(VBARS)
    private val shortVbars = arrayOfNulls<IntArray>(SHORT_VBARS)
    private var vbarCursor = 0
    private var shortVbarCursor = 0
    private var nscodecWarned = false

    /**
     * Разбирает одну команду. [canvas] — пиксели области [width]×[height] до рисования (непрозрачные ARGB);
     * после вызова в нём готовая картинка.
     */
    fun decode(data: ByteArray, width: Int, height: Int, canvas: IntArray) {
        require(width > 0 && height > 0 && canvas.size >= width * height) { "ClearCodec: область ${width}x$height" }
        val r = ByteReader(data)
        val glyphFlags = r.u8()
        r.u8() // номер последовательности: сервер сам следит за порядком, сбой тут ничего не исправит
        if (glyphFlags and FLAG_CACHE_RESET != 0) {
            vbarCursor = 0
            shortVbarCursor = 0
        }
        var glyphIndex = -1
        if (glyphFlags and FLAG_GLYPH_INDEX != 0) {
            if (width.toLong() * height > 1024 * 1024) throw RdpException("ClearCodec: глиф ${width}x$height слишком велик")
            glyphIndex = r.u16le()
            if (glyphIndex >= GLYPHS) throw RdpException("ClearCodec: номер глифа $glyphIndex")
            if (glyphFlags and FLAG_GLYPH_HIT != 0) {
                val cached = glyphs[glyphIndex] ?: throw RdpException("ClearCodec: глиф $glyphIndex не в кэше")
                if (cached.size < width * height) throw RdpException("ClearCodec: глиф $glyphIndex меньше области")
                System.arraycopy(cached, 0, canvas, 0, width * height)
                if (r.remaining < 12) return
            }
        } else if (glyphFlags and FLAG_GLYPH_HIT != 0) {
            throw RdpException("ClearCodec: попадание в кэш глифов без индекса")
        }
        if (r.remaining < 12) throw RdpException("ClearCodec: нет заголовка составного потока")
        val residualBytes = r.u32le().toInt()
        val bandsBytes = r.u32le().toInt()
        val subcodecBytes = r.u32le().toInt()
        if (residualBytes < 0 || bandsBytes < 0 || subcodecBytes < 0 ||
            residualBytes.toLong() + bandsBytes + subcodecBytes > r.remaining
        ) throw RdpException("ClearCodec: размеры слоёв больше потока")
        if (residualBytes > 0) residual(ByteReader(data, r.pos, r.pos + residualBytes), width, height, canvas)
        r.skip(residualBytes)
        if (bandsBytes > 0) bands(ByteReader(data, r.pos, r.pos + bandsBytes), width, height, canvas)
        r.skip(bandsBytes)
        if (subcodecBytes > 0) subcodecs(ByteReader(data, r.pos, r.pos + subcodecBytes), width, height, canvas)
        // Новый глиф запоминается готовым; при попадании в кэш он уже лежит там.
        if (glyphIndex >= 0 && glyphFlags and FLAG_GLYPH_HIT == 0) glyphs[glyphIndex] = canvas.copyOf(width * height)
    }

    /** Длина прогона: байт, при 0xFF — слово, при 0xFFFF — двойное слово. */
    private fun runLength(r: ByteReader, first: Int): Int {
        var v = first
        if (v >= 0xFF) {
            v = r.u16le()
            if (v >= 0xFFFF) v = r.u32le().toInt()
        }
        if (v < 0) throw RdpException("ClearCodec: длина прогона")
        return v
    }

    private fun residual(r: ByteReader, width: Int, height: Int, canvas: IntArray) {
        val total = width * height
        var index = 0
        while (r.remaining > 0) {
            val b = r.u8()
            val g = r.u8()
            val rr = r.u8()
            val run = runLength(r, r.u8())
            if (run > total - index) throw RdpException("ClearCodec: остаток выходит за область ($index + $run > $total)")
            java.util.Arrays.fill(canvas, index, index + run, rgb(b, g, rr))
            index += run
        }
        if (index != total) throw RdpException("ClearCodec: остаток покрыл $index из $total точек")
    }

    private fun bands(r: ByteReader, width: Int, height: Int, canvas: IntArray) {
        while (r.remaining > 0) {
            val xStart = r.u16le()
            val xEnd = r.u16le()
            val yStart = r.u16le()
            val yEnd = r.u16le()
            val bkg = rgb(r.u8(), r.u8(), r.u8())
            if (xEnd < xStart || yEnd < yStart) throw RdpException("ClearCodec: перевёрнутая полоса")
            val barHeight = yEnd - yStart + 1
            if (barHeight > MAX_VBAR_HEIGHT) throw RdpException("ClearCodec: высота столбца $barHeight")
            for (i in 0..(xEnd - xStart)) {
                val header = r.u16le()
                var yOn = 0
                var shortPixels: IntArray? = null
                val entry: IntArray
                when {
                    header and 0xC000 == 0x4000 -> { // попадание в кэш коротких столбцов
                        shortPixels = shortVbars[header and 0x3FFF] ?: IntArray(0)
                        yOn = r.u8()
                        entry = storeVbar(compose(barHeight, yOn, shortPixels, bkg))
                    }
                    header and 0xC000 == 0x0000 -> { // промах: пиксели идут в потоке
                        yOn = header and 0xFF
                        val yOff = (header shr 8) and 0x3F
                        if (yOff < yOn) throw RdpException("ClearCodec: конец столбца $yOff раньше начала $yOn")
                        val count = yOff - yOn
                        if (count > MAX_VBAR_HEIGHT) throw RdpException("ClearCodec: короткий столбец из $count точек")
                        shortPixels = IntArray(count) { rgb(r.u8(), r.u8(), r.u8()) }
                        shortVbars[shortVbarCursor] = shortPixels
                        shortVbarCursor = (shortVbarCursor + 1) % SHORT_VBARS
                        entry = storeVbar(compose(barHeight, yOn, shortPixels, bkg))
                    }
                    header and 0x8000 == 0x8000 -> { // попадание в кэш полных столбцов
                        entry = vbars[header and 0x7FFF] ?: IntArray(barHeight)
                    }
                    else -> throw RdpException("ClearCodec: заголовок столбца 0x${header.toString(16)}")
                }
                if (i < width) {
                    val x = xStart + i
                    if (x >= width) throw RdpException("ClearCodec: столбец за областью")
                    val rows = minOf(entry.size, barHeight, height - yStart)
                    for (y in 0 until rows) canvas[(yStart + y) * width + x] = entry[y]
                }
            }
        }
    }

    /** Столбец высотой [height]: фон, затем короткий столбец с отступа [yOn], снова фон. */
    private fun compose(height: Int, yOn: Int, short: IntArray, bkgColor: Int): IntArray {
        val out = IntArray(height) { bkgColor }
        for (k in short.indices) {
            val y = yOn + k
            if (y < height) out[y] = short[k]
        }
        return out
    }

    private fun storeVbar(pixels: IntArray): IntArray {
        vbars[vbarCursor] = pixels
        vbarCursor = (vbarCursor + 1) % VBARS
        return pixels
    }

    private fun subcodecs(r: ByteReader, width: Int, height: Int, canvas: IntArray) {
        while (r.remaining > 0) {
            val xStart = r.u16le()
            val yStart = r.u16le()
            val w = r.u16le()
            val h = r.u16le()
            val size = r.u32le().toInt()
            val id = r.u8()
            if (size < 0 || size > r.remaining) throw RdpException("ClearCodec: подкодек больше потока")
            if (xStart + w > width || yStart + h > height) throw RdpException("ClearCodec: подкодек выходит за область")
            val body = ByteReader(r.data, r.pos, r.pos + size)
            r.skip(size)
            when (id) {
                0 -> { // несжатые B,G,R
                    if (size != w * h * 3) throw RdpException("ClearCodec: несжатый подкодек $size Б для ${w}x$h")
                    var p = body.pos
                    for (y in 0 until h) for (x in 0 until w) {
                        canvas[(yStart + y) * width + xStart + x] = rgb(r.data[p].toInt() and 0xFF, r.data[p + 1].toInt() and 0xFF, r.data[p + 2].toInt() and 0xFF)
                        p += 3
                    }
                }
                1 -> if (!nscodecWarned) {
                    nscodecWarned = true
                    log("ClearCodec: подкодек NSCodec не поддерживается")
                }
                2 -> rlex(body, xStart, yStart, w, h, width, canvas)
                else -> throw RdpException("ClearCodec: подкодек $id")
            }
        }
    }

    /** RLEX: палитра и прогоны «цвет, затем набор подряд идущих цветов палитры». */
    private fun rlex(r: ByteReader, xStart: Int, yStart: Int, w: Int, h: Int, stride: Int, canvas: IntArray) {
        val paletteCount = r.u8()
        if (paletteCount < 1 || paletteCount > 127) throw RdpException("ClearCodec: палитра из $paletteCount цветов")
        val palette = IntArray(paletteCount) { rgb(r.u8(), r.u8(), r.u8()) }
        val numBits = 32 - Integer.numberOfLeadingZeros(maxOf(paletteCount - 1, 1))
        val total = w * h
        var index = 0
        var x = 0
        var y = 0
        fun put(color: Int) {
            canvas[(yStart + y) * stride + xStart + x] = color
            if (++x >= w) { y++; x = 0 }
        }
        while (r.remaining > 0) {
            val tmp = r.u8()
            val run = runLength(r, r.u8())
            val suiteDepth = (tmp shr numBits) and ((1 shl (8 - numBits)) - 1)
            val stop = tmp and ((1 shl numBits) - 1)
            val start = stop - suiteDepth
            if (start < 0 || start >= paletteCount || stop >= paletteCount) throw RdpException("ClearCodec: индекс палитры $start..$stop из $paletteCount")
            if (run > total - index || suiteDepth + 1 > total - index - run) throw RdpException("ClearCodec: RLEX выходит за область")
            repeat(run) { put(palette[start]) }
            for (k in 0..suiteDepth) put(palette[start + k])
            index += run + suiteDepth + 1
        }
        if (index != total) throw RdpException("ClearCodec: RLEX покрыл $index из $total точек")
    }
}
