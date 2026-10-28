package com.hiweb.hiwebremotedesk

/** Операции над кадрами NV12 (Y целиком, затем чередующиеся пары U и V). */
object Nv12 {
    /**
     * Поворачивает кадр [src] размером [width]×[height] по часовой стрелке на [degrees] (0/90/180/270).
     * При 90 и 270 ширина и высота результата меняются местами. Размеры должны быть чётными.
     */
    fun rotate(src: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        val turns = ((degrees % 360) + 360) % 360
        if (turns == 0) return src
        val out = ByteArray(src.size)
        val ySize = width * height
        // Y: по одному байту на пиксель; UV: по паре байт на блок 2×2, то есть «пиксель» вдвое меньшей сетки.
        turn(src, 0, out, 0, width, height, 1, turns)
        turn(src, ySize, out, ySize, width / 2, height / 2, 2, turns)
        return out
    }

    /** Поворот плоскости [w]×[h] из ячеек по [cell] байт; результат кладётся в [dst] с отступа [dstOffset]. */
    private fun turn(src: ByteArray, srcOffset: Int, dst: ByteArray, dstOffset: Int, w: Int, h: Int, cell: Int, degrees: Int) {
        // Размеры результата: при 90/270 ширина — прежняя высота.
        val outW = if (degrees == 180) w else h
        val outH = if (degrees == 180) h else w
        for (row in 0 until outH) {
            for (col in 0 until outW) {
                val srcRow: Int
                val srcCol: Int
                when (degrees) {
                    90 -> { srcRow = h - 1 - col; srcCol = row }
                    180 -> { srcRow = h - 1 - row; srcCol = w - 1 - col }
                    else -> { srcRow = col; srcCol = w - 1 - row }
                }
                val from = srcOffset + (srcRow * w + srcCol) * cell
                val to = dstOffset + (row * outW + col) * cell
                for (k in 0 until cell) dst[to + k] = src[from + k]
            }
        }
    }
}
