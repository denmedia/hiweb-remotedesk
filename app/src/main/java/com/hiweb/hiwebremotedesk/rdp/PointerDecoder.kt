package com.hiweb.hiwebremotedesk.rdp

/** Курсор, который сервер хочет показать на клиенте (MS-RDPBCGR 2.2.9.1.1.4). */
sealed interface RemoteCursor {
    /** Системная стрелка (PTR_DEFAULT). */
    object Default : RemoteCursor

    /** Курсор скрыт (PTR_NULL). */
    object Hidden : RemoteCursor
}

/**
 * Форма курсора Windows. [argb] — цветные пиксели (сверху вниз), [invert] — пиксели, которые на ПК
 * инвертируют фон (например, текстовый курсор «I»); null, если таких нет.
 */
class CursorImage(
    val width: Int,
    val height: Int,
    val hotX: Int,
    val hotY: Int,
    val argb: IntArray,
    val invert: BooleanArray?,
) : RemoteCursor

object PointerDecoder {
    private const val MAX_SIZE = 384

    class Parsed(val cacheIndex: Int, val image: CursorImage)

    /**
     * Разбирает Color Pointer (24 bpp, [newFormat] = false), New Pointer (с полем xorBpp, [newFormat] = true)
     * или Large Pointer ([large] = true, длины масок 32 бита).
     * Порядок полей: [xorBpp], cacheIndex, hotSpot, размеры, длины масок, XOR-маска, AND-маска.
     */
    fun parse(r: ByteReader, newFormat: Boolean, large: Boolean = false): Parsed {
        val xorBpp = if (newFormat) r.u16le() else 24
        val cacheIndex = r.u16le()
        val hotX = r.u16le()
        val hotY = r.u16le()
        val width = r.u16le()
        val height = r.u16le()
        // У Large Pointer длины масок 32-битные.
        val andLen = if (large) r.u32le().toInt() else r.u16le()
        val xorLen = if (large) r.u32le().toInt() else r.u16le()
        if (width !in 1..MAX_SIZE || height !in 1..MAX_SIZE) throw RdpException("Недопустимый размер курсора ${width}x$height")
        val xor = r.bytes(xorLen)
        val and = r.bytes(minOf(andLen, r.remaining))
        return Parsed(cacheIndex, decode(xor, and, width, height, xorBpp, hotX.coerceAtMost(width - 1), hotY.coerceAtMost(height - 1)))
    }

    /** Маски хранятся снизу вверх, каждая строка выровнена до 2 байт. */
    fun decode(xor: ByteArray, and: ByteArray, width: Int, height: Int, xorBpp: Int, hotX: Int, hotY: Int): CursorImage {
        if (xorBpp !in intArrayOf(1, 8, 15, 16, 24, 32)) throw RdpException("Неподдерживаемая глубина курсора: $xorBpp")
        val xorStride = ((width * xorBpp + 15) / 16) * 2
        val andStride = ((width + 15) / 16) * 2
        if (xor.size < xorStride * height) throw RdpException("Повреждённые данные курсора")

        fun color(row: Int, x: Int): Int {
            val o = row * xorStride
            return when (xorBpp) {
                32 -> {
                    val p = o + x * 4
                    ((xor[p + 3].toInt() and 0xFF) shl 24) or ((xor[p + 2].toInt() and 0xFF) shl 16) or
                        ((xor[p + 1].toInt() and 0xFF) shl 8) or (xor[p].toInt() and 0xFF)
                }
                24 -> {
                    val p = o + x * 3
                    ((xor[p + 2].toInt() and 0xFF) shl 16) or ((xor[p + 1].toInt() and 0xFF) shl 8) or (xor[p].toInt() and 0xFF)
                }
                16, 15 -> {
                    val p = o + x * 2
                    val v = (xor[p].toInt() and 0xFF) or ((xor[p + 1].toInt() and 0xFF) shl 8)
                    if (xorBpp == 16) {
                        val r = (v shr 11) and 0x1F; val g = (v shr 5) and 0x3F; val b = v and 0x1F
                        ((r * 255 / 31) shl 16) or ((g * 255 / 63) shl 8) or (b * 255 / 31)
                    } else {
                        val r = (v shr 10) and 0x1F; val g = (v shr 5) and 0x1F; val b = v and 0x1F
                        ((r * 255 / 31) shl 16) or ((g * 255 / 31) shl 8) or (b * 255 / 31)
                    }
                }
                // Палитры нет, поэтому индекс показывается как оттенок серого.
                8 -> (xor[o + x].toInt() and 0xFF) * 0x010101
                else -> if ((xor[o + x / 8].toInt() shr (7 - x % 8)) and 1 != 0) 0xFFFFFF else 0
            }
        }

        fun maskBit(row: Int, x: Int): Boolean {
            val p = row * andStride + x / 8
            return p < and.size && (and[p].toInt() shr (7 - x % 8)) and 1 != 0
        }

        // У 32-битных курсоров с альфа-каналом прозрачность задаёт он, а не AND-маска.
        var hasAlpha = false
        if (xorBpp == 32) {
            loop@ for (row in 0 until height) for (x in 0 until width) {
                if (color(row, x) ushr 24 != 0) { hasAlpha = true; break@loop }
            }
        }

        val argb = IntArray(width * height)
        var invert: BooleanArray? = null
        for (y in 0 until height) {
            val row = height - 1 - y
            for (x in 0 until width) {
                val c = color(row, x)
                val i = y * width + x
                when {
                    hasAlpha -> argb[i] = c
                    !maskBit(row, x) -> argb[i] = 0xFF000000.toInt() or (c and 0xFFFFFF)
                    c and 0xFFFFFF == 0 -> {} // прозрачный пиксель
                    else -> {
                        if (invert == null) invert = BooleanArray(width * height)
                        invert[i] = true
                    }
                }
            }
        }
        return CursorImage(width, height, hotX, hotY, argb, invert)
    }
}
