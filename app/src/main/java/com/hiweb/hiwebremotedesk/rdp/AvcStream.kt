package com.hiweb.hiwebremotedesk.rdp

import java.nio.ByteBuffer

/** Прямоугольник [left, right) × [top, bottom) в координатах поверхности RDPEGFX. */
class AvcRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Поток RFX_AVC420_BITMAP_STREAM (MS-RDPEGFX 2.2.4.4): число прямоугольников (u32), сами прямоугольники
 * (left, top, right, bottom по u16), по два байта качества на каждый (QP и quality), затем H.264 в формате Annex B.
 * Кадр H.264 покрывает поверхность целиком (ширина и высота кратны 16); прямоугольники — те её части, что изменились,
 * и считаются от левого верхнего угла поверхности, а не от прямоугольника назначения команды.
 */
class Avc420Stream(val regions: List<AvcRegion>, val bitstream: ByteArray) {
    companion object {
        /** Разбирает поток из [data] между [start] и [end]; всё после метаданных — битовый поток H.264. */
        fun parse(data: ByteArray, start: Int = 0, end: Int = data.size): Avc420Stream {
            val r = ByteReader(data, start, end)
            val count = r.u32le()
            // Каждому прямоугольнику нужно 10 байт (8 на координаты и 2 на качество): больше не поместится.
            if (count > r.remaining / 10) throw RdpException("AVC420: $count прямоугольников не помещаются в поток")
            val regions = List(count.toInt()) {
                val left = r.u16le()
                val top = r.u16le()
                val right = r.u16le()
                val bottom = r.u16le()
                AvcRegion(left, top, right, bottom)
            }
            r.skip(regions.size * 2)
            return Avc420Stream(regions, r.bytes(r.remaining))
        }
    }
}

/**
 * Поток RFX_AVC444_BITMAP_STREAM / AVC444v2 (MS-RDPEGFX 2.2.4.5, 2.2.4.6): слово из размера первого потока
 * (младшие 30 бит, вместе с его метаданными) и признака LC (старшие 2 бита), затем один или два потока AVC420.
 * LC=0 — основной вид и дополнительный; 1 — только основной (яркость); 2 — только дополнительный (цветность).
 * [luma] — основной вид (null при LC=2), [hasChroma] — пришёл ли дополнительный вид.
 */
class Avc444Stream(val lc: Int, val luma: Avc420Stream?, val hasChroma: Boolean) {
    companion object {
        fun parse(data: ByteArray): Avc444Stream {
            val r = ByteReader(data)
            val info = r.u32le()
            val size = (info and 0x3FFFFFFFL).toInt()
            val lc = (info shr 30).toInt()
            if (lc == 3) throw RdpException("AVC444: недопустимый LC=3")
            if (size > r.remaining) throw RdpException("AVC444: первый поток $size Б больше команды (${r.remaining} Б)")
            val first = Avc420Stream.parse(data, r.pos, r.pos + size)
            return Avc444Stream(lc, if (lc == 2) null else first, hasChroma = lc != 1)
        }
    }
}

/**
 * Плоскости кадра YUV 4:2:0, как их отдаёт декодер (`Image.Plane`): у каждой свой шаг строки, у цветоразностных —
 * ещё и шаг пикселя (1 — раздельные плоскости I420, 2 — чересстрочные NV12/NV21). Буферы читаются по абсолютным
 * индексам, позиция не меняется.
 */
class YuvPlanes(
    val width: Int,
    val height: Int,
    val y: ByteBuffer,
    val yStride: Int,
    val u: ByteBuffer,
    val uStride: Int,
    val uPixel: Int,
    val v: ByteBuffer,
    val vStride: Int,
    val vPixel: Int,
)

/**
 * Преобразование YUV 4:2:0 в ARGB. Формулы те же, что у FreeRDP: BT.709, полный диапазон, цвет — ближайший сосед.
 * Строки читаются из буферов кадра пачками (а не по байту), большие области делятся по строкам между потоками:
 * полноэкранный кадр 1920x1200 иначе занимает десятки миллисекунд и задерживает и картинку, и подтверждение кадра серверу.
 */
object YuvConverter {
    /** Области меньше этого числа точек считаются в вызывающем потоке: потоки на них только мешают. */
    private const val PARALLEL_MIN_PIXELS = 200_000
    private const val MIN_ROWS_PER_PART = 64

    private val parts = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    private val pool by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(maxOf(parts - 1, 1)) { r -> Thread(r, "yuv-convert").apply { isDaemon = true } }
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /** ARGB (непрозрачный) для области [left, right) × [top, bottom) кадра; область должна лежать внутри кадра. */
    fun toArgb(p: YuvPlanes, left: Int, top: Int, right: Int, bottom: Int): IntArray {
        val out = IntArray((right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0))
        toArgbInto(p, left, top, right, bottom, out, 0, right - left)
        return out
    }

    /** То же, но пишет прямо в [dst]: первая точка области — по индексу [dstOffset], шаг строки [dstStride]. */
    fun toArgbInto(p: YuvPlanes, left: Int, top: Int, right: Int, bottom: Int, dst: IntArray, dstOffset: Int, dstStride: Int) {
        val w = right - left
        val h = bottom - top
        require(w > 0 && h > 0 && left >= 0 && top >= 0 && right <= p.width && bottom <= p.height) {
            "область $left,$top-$right,$bottom вне кадра ${p.width}x${p.height}"
        }
        val count = if (w.toLong() * h < PARALLEL_MIN_PIXELS || parts < 2) 1 else minOf(parts, (h + MIN_ROWS_PER_PART - 1) / MIN_ROWS_PER_PART)
        if (count <= 1) {
            rows(p, left, top, w, dst, dstOffset, dstStride, 0, h)
            return
        }
        val per = (h + count - 1) / count
        val tasks = (1 until count).map { i ->
            pool.submit { rows(p, left, top, w, dst, dstOffset, dstStride, i * per, minOf(h, (i + 1) * per)) }
        }
        rows(p, left, top, w, dst, dstOffset, dstStride, 0, minOf(h, per))
        tasks.forEach { it.get() }
    }

    /** Читает строку цветоразностной плоскости (с шагом пикселя) в [out]; за концом буфера остаётся прежнее содержимое. */
    private fun readChroma(buf: java.nio.ByteBuffer, start: Int, span: Int, out: ByteArray) {
        buf.position(start)
        buf.get(out, 0, minOf(span, buf.remaining()))
    }

    private fun rows(p: YuvPlanes, left: Int, top: Int, w: Int, dst: IntArray, dstOffset: Int, dstStride: Int, rowFrom: Int, rowTo: Int) {
        val firstChroma = left shr 1
        val chromaCount = ((left + w - 1) shr 1) - firstChroma + 1
        val yRow = ByteArray(w)
        val uRaw = ByteArray((chromaCount - 1) * p.uPixel + 1)
        val vRaw = ByteArray((chromaCount - 1) * p.vPixel + 1)
        val cu = IntArray(chromaCount)
        val cv = IntArray(chromaCount)
        // Свои «окна» на буферы кадра: позиция у каждого потока своя.
        val yb = p.y.duplicate()
        val ub = p.u.duplicate()
        val vb = p.v.duplicate()
        var chromaRow = -1
        for (row in rowFrom until rowTo) {
            val sy = top + row
            val cy = sy shr 1
            if (cy != chromaRow) {
                chromaRow = cy
                readChroma(ub, cy * p.uStride + firstChroma * p.uPixel, uRaw.size, uRaw)
                readChroma(vb, cy * p.vStride + firstChroma * p.vPixel, vRaw.size, vRaw)
                for (i in 0 until chromaCount) {
                    cu[i] = (uRaw[i * p.uPixel].toInt() and 0xFF) - 128
                    cv[i] = (vRaw[i * p.vPixel].toInt() and 0xFF) - 128
                }
            }
            yb.position(sy * p.yStride + left)
            yb.get(yRow, 0, w)
            var o = dstOffset + row * dstStride
            var ci = 0 // индекс цветности первой точки области: firstChroma = left shr 1
            var odd = left and 1
            for (col in 0 until w) {
                val yy = (yRow[col].toInt() and 0xFF) shl 8
                val u = cu[ci]
                val v = cv[ci]
                val r = clamp((yy + 403 * v) shr 8)
                val g = clamp((yy - 48 * u - 120 * v) shr 8)
                val b = clamp((yy + 475 * u) shr 8)
                dst[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                if (odd == 1) { ci++; odd = 0 } else odd = 1
            }
        }
    }
}
