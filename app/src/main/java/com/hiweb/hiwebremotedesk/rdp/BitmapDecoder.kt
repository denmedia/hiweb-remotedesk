package com.hiweb.hiwebremotedesk.rdp

/**
 * Декодер битмапов RDP (MS-RDPBCGR 2.2.9.1.1.3.1.2): несжатые данные и interleaved RLE
 * для 15/16/24 bpp. Результат: ARGB-пиксели в порядке сверху вниз.
 */
object BitmapDecoder {
    fun decode(src: ByteArray, off: Int, len: Int, width: Int, height: Int, bitsPerPixel: Int, compressed: Boolean): IntArray? {
        val bytesPerPixel = (bitsPerPixel + 7) / 8
        if (width <= 0 || height <= 0 || bytesPerPixel !in 2..4) return null
        return try {
            val raw: IntArray = if (compressed) {
                if (bytesPerPixel == 4) return null
                rle(src, off, off + len, width, height, bytesPerPixel) ?: return null
            } else {
                uncompressed(src, off, len, width, height, bytesPerPixel) ?: return null
            }
            toArgb(raw, bitsPerPixel)
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    private fun uncompressed(src: ByteArray, off: Int, len: Int, width: Int, height: Int, bpp: Int): IntArray? {
        val stride = width * bpp
        if (len < stride * height) return null
        val out = IntArray(width * height)
        for (row in 0 until height) {
            var p = off + (height - 1 - row) * stride
            var o = row * width
            for (x in 0 until width) {
                var v = 0
                for (b in 0 until bpp) v = v or ((src[p + b].toInt() and 0xFF) shl (8 * b))
                out[o++] = v
                p += bpp
            }
        }
        return out
    }

    private fun rle(src: ByteArray, start: Int, end: Int, width: Int, height: Int, bpp: Int): IntArray? {
        val out = IntArray(width * height)
        var ip = start
        var prev = -1
        var line = -1
        var x = width
        var rowsLeft = height
        var lastOpcode = -1
        var insertMix = false
        var bicolour = false
        val white = if (bpp == 2) 0xFFFF else 0xFFFFFF
        var mix = white
        var colour1 = 0
        var colour2 = 0
        var mask = 0
        var mixmask: Int
        var fomMask: Int

        fun px(): Int {
            var v = src[ip].toInt() and 0xFF
            v = v or ((src[ip + 1].toInt() and 0xFF) shl 8)
            if (bpp == 3) v = v or ((src[ip + 2].toInt() and 0xFF) shl 16)
            ip += bpp
            return v
        }

        while (ip < end) {
            fomMask = 0
            val code = src[ip++].toInt() and 0xFF
            var opcode = code ushr 4
            var count: Int
            val offset: Int
            when (opcode) {
                0xC, 0xD, 0xE -> { opcode -= 6; count = code and 0xF; offset = 16 }
                0xF -> {
                    opcode = code and 0xF
                    count = if (opcode < 9) {
                        val c = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
                        ip += 2
                        c
                    } else if (opcode < 0xB) 8 else 1
                    offset = 0
                }
                else -> { opcode = opcode ushr 1; count = code and 0x1F; offset = 32 }
            }
            if (offset != 0) {
                val fillOrMix = opcode == 2 || opcode == 7
                if (count == 0) {
                    count = (src[ip++].toInt() and 0xFF) + if (fillOrMix) 1 else offset
                } else if (fillOrMix) {
                    count = count shl 3
                }
            }

            when (opcode) {
                0 -> if (lastOpcode == opcode && !(x == width && prev < 0)) insertMix = true
                8 -> { colour1 = px(); colour2 = px() }
                3 -> colour2 = px()
                6, 7 -> { mix = px(); opcode -= 5 }
                9 -> { fomMask = 3; opcode = 2 }
                0xA -> { fomMask = 5; opcode = 2 }
            }
            lastOpcode = opcode
            mixmask = 0

            while (count > 0) {
                if (x >= width) {
                    if (rowsLeft <= 0) return null
                    x = 0
                    rowsLeft--
                    prev = line
                    line = rowsLeft * width
                }
                when (opcode) {
                    0 -> {
                        if (insertMix) {
                            out[line + x] = if (prev < 0) mix else out[prev + x] xor mix
                            insertMix = false
                            count--
                            x++
                        }
                        if (prev < 0) {
                            while (count > 0 && x < width) { out[line + x] = 0; count--; x++ }
                        } else {
                            while (count > 0 && x < width) { out[line + x] = out[prev + x]; count--; x++ }
                        }
                    }
                    1 -> while (count > 0 && x < width) {
                        out[line + x] = if (prev < 0) mix else out[prev + x] xor mix
                        count--; x++
                    }
                    2 -> while (count > 0 && x < width) {
                        mixmask = mixmask shl 1
                        if ((mixmask and 0xFF) == 0) {
                            mask = if (fomMask != 0) fomMask else (src[ip++].toInt() and 0xFF)
                            mixmask = 1
                        }
                        val base = if (prev < 0) 0 else out[prev + x]
                        out[line + x] = if ((mask and mixmask) != 0) base xor mix else base
                        count--; x++
                    }
                    3 -> while (count > 0 && x < width) { out[line + x] = colour2; count--; x++ }
                    4 -> while (count > 0 && x < width) { out[line + x] = px(); count--; x++ }
                    8 -> while (count > 0 && x < width) {
                        if (bicolour) {
                            out[line + x] = colour2
                            bicolour = false
                        } else {
                            out[line + x] = colour1
                            bicolour = true
                            count++
                        }
                        count--; x++
                    }
                    0xD -> while (count > 0 && x < width) { out[line + x] = white; count--; x++ }
                    0xE -> while (count > 0 && x < width) { out[line + x] = 0; count--; x++ }
                    else -> return null
                }
            }
        }
        return out
    }

    private fun toArgb(raw: IntArray, bitsPerPixel: Int): IntArray {
        when (bitsPerPixel) {
            16 -> for (i in raw.indices) {
                val v = raw[i]
                val r = (v shr 11) and 0x1F
                val g = (v shr 5) and 0x3F
                val b = v and 0x1F
                raw[i] = 0xFF000000.toInt() or (((r shl 3) or (r shr 2)) shl 16) or (((g shl 2) or (g shr 4)) shl 8) or ((b shl 3) or (b shr 2))
            }
            15 -> for (i in raw.indices) {
                val v = raw[i]
                val r = (v shr 10) and 0x1F
                val g = (v shr 5) and 0x1F
                val b = v and 0x1F
                raw[i] = 0xFF000000.toInt() or (((r shl 3) or (r shr 2)) shl 16) or (((g shl 3) or (g shr 2)) shl 8) or ((b shl 3) or (b shr 2))
            }
            else -> for (i in raw.indices) raw[i] = raw[i] or 0xFF000000.toInt()
        }
        return raw
    }
}
