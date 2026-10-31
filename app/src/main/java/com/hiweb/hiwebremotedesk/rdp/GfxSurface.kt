package com.hiweb.hiwebremotedesk.rdp

/**
 * Поверхность графического конвейера RDPEGFX: ARGB-пиксели в памяти клиента. Все кодеки рисуют в неё,
 * на экран уходят только изменённые прямоугольники ([takeDirty]) — в конце кадра. Альфа-канал не используется:
 * поверхность всегда непрозрачна.
 */
class GfxSurface(val id: Int, val width: Int, val height: Int) {
    val pixels = IntArray(width * height).also { java.util.Arrays.fill(it, OPAQUE_BLACK) }
    var originX = 0
    var originY = 0
    @Volatile var mapped = false

    /** Декодер H.264 этой поверхности (создаётся при первом кадре AVC). */
    var avc: AvcDecoder? = null

    /** Декодер RemoteFX Progressive: хранит состояние уточнения тайлов, поэтому свой у каждой поверхности. */
    var progressive: RfxProgressive? = null

    private val dirty = ArrayList<AvcRegion>()

    companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()
        private const val MAX_DIRTY = 24
    }

    /** Область, обрезанная по поверхности, или null, если от неё ничего не осталось. */
    fun clip(left: Int, top: Int, right: Int, bottom: Int): AvcRegion? {
        val l = maxOf(left, 0)
        val t = maxOf(top, 0)
        val r = minOf(right, width)
        val b = minOf(bottom, height)
        return if (l < r && t < b) AvcRegion(l, t, r, b) else null
    }

    /** Отмечает область изменённой (с обрезкой по поверхности). Слишком много областей схлопываются в одну. */
    fun markDirty(left: Int, top: Int, right: Int, bottom: Int) {
        val c = clip(left, top, right, bottom) ?: return
        synchronized(dirty) {
            dirty += c
            if (dirty.size > MAX_DIRTY) {
                val box = AvcRegion(dirty.minOf { it.left }, dirty.minOf { it.top }, dirty.maxOf { it.right }, dirty.maxOf { it.bottom })
                dirty.clear()
                dirty += box
            }
        }
    }

    /** Забирает накопленные изменённые области. */
    fun takeDirty(): List<AvcRegion> = synchronized(dirty) {
        val out = ArrayList(dirty)
        dirty.clear()
        out
    }

    /**
     * Копирует [w]×[h] пикселей из [src] (шаг строки [srcStride], первая точка — [srcX], [srcY]) в точку ([dx], [dy])
     * поверхности. Выходящее за поверхность обрезается.
     */
    fun blit(src: IntArray, srcStride: Int, srcX: Int, srcY: Int, w: Int, h: Int, dx: Int, dy: Int) {
        val c = clip(dx, dy, dx + w, dy + h) ?: return
        val cw = c.width
        for (row in 0 until c.height) {
            val from = (srcY + c.top - dy + row) * srcStride + srcX + c.left - dx
            System.arraycopy(src, from, pixels, (c.top + row) * width + c.left, cw)
        }
        markDirty(c.left, c.top, c.right, c.bottom)
    }

    /** Заливает прямоугольник цветом [argb] (альфа принудительно непрозрачная). */
    fun fill(left: Int, top: Int, right: Int, bottom: Int, argb: Int) {
        val c = clip(left, top, right, bottom) ?: return
        val color = argb or OPAQUE_BLACK
        for (row in c.top until c.bottom) java.util.Arrays.fill(pixels, row * width + c.left, row * width + c.right, color)
        markDirty(c.left, c.top, c.right, c.bottom)
    }

    /** Вырезает прямоугольник в новый массив (с обрезкой по поверхности); null — вырезать нечего. */
    fun copyOut(left: Int, top: Int, right: Int, bottom: Int): Pair<AvcRegion, IntArray>? {
        val c = clip(left, top, right, bottom) ?: return null
        val out = IntArray(c.width * c.height)
        for (row in 0 until c.height) System.arraycopy(pixels, (c.top + row) * width + c.left, out, row * c.width, c.width)
        return c to out
    }
}
