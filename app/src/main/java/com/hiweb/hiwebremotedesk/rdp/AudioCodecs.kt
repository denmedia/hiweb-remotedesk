package com.hiweb.hiwebremotedesk.rdp

import kotlin.math.abs

/** Формат звука в виде WAVEFORMATEX (раскладка одна для rdpsnd и audin). */
internal class WaveFormat(
    val tag: Int,
    val channels: Int,
    val sampleRate: Int,
    val avgBytesPerSec: Long,
    val blockAlign: Int,
    val bits: Int,
    val extra: ByteArray,
) {
    /** Запись «как есть»: сервер сверяет формат с тем, что сам предлагал, поэтому поля не пересчитываются. */
    fun toBytes(): ByteArray = ByteWriter()
        .u16le(tag).u16le(channels).u32le(sampleRate).u32le(avgBytesPerSec)
        .u16le(blockAlign).u16le(bits).u16le(extra.size).bytes(extra).toByteArray()

    /** Кратко для журнала: по нему видно, какие кодеки предлагает Windows. */
    override fun toString() = "0x${tag.toString(16)}:${channels}ch/${sampleRate}Гц/${bits}бит/блок$blockAlign"

    companion object {
        fun read(r: ByteReader): WaveFormat {
            val tag = r.u16le()
            val channels = r.u16le()
            val rate = r.u32le().toInt()
            val avg = r.u32le()
            val align = r.u16le()
            val bits = r.u16le()
            val extra = r.bytes(r.u16le())
            return WaveFormat(tag, channels, rate, avg, align, bits, extra)
        }
    }
}

/** Разжимает данные формата в PCM 16 бит little endian (каналы чередуются). */
internal interface AudioDecoder {
    fun decode(data: ByteArray): ByteArray
}

/** Сжимает PCM 16 бит little endian; хвост, не дополняющий блок, копится до следующего вызова. */
internal interface AudioEncoder {
    fun encode(pcm: ByteArray): ByteArray

    /** Размер пакета канала в байтах (целое число блоков или кадров); 0 — не дробить. */
    fun packetBytes(framesPerPacket: Int): Int
}

internal interface AudioCodec : AudioDecoder, AudioEncoder

/**
 * Сжатые форматы звука, которые клиент умеет разбирать и выдавать сам, без MediaCodec: IMA и MS ADPCM (около 4:1)
 * и G.711 A-law/µ-law (2:1). Все они блочные или покадровые без состояния между блоками, поэтому потеря пакета
 * не портит остальной звук, а задержка не больше размера блока.
 */
internal object AudioCodecs {
    const val PCM = 0x0001
    const val MS_ADPCM = 0x0002
    const val ALAW = 0x0006
    const val MULAW = 0x0007
    const val IMA_ADPCM = 0x0011

    const val MIN_RATE = 8000
    const val MAX_RATE = 48000

    private const val MAX_BLOCK = 8192

    /**
     * Порядок предпочтения: меньше — лучше; null — формат не принимается. Несжатый PCM идёт последним, а при
     * [compressed] = false принимается только он (поведение без опции).
     */
    fun rank(f: WaveFormat, compressed: Boolean): Int? {
        if (f.channels !in 1..2 || f.sampleRate !in MIN_RATE..MAX_RATE) return null
        if (f.tag == PCM) return if (f.bits == 16) PCM_RANK else null
        if (!compressed) return null
        return when (f.tag) {
            IMA_ADPCM -> if (f.bits == 4 && ImaAdpcm.valid(f.channels, f.blockAlign)) 0 else null
            MS_ADPCM -> if (f.bits == 4 && MsAdpcm.valid(f.channels, f.blockAlign) && MsAdpcm.coefficients(f.extra) != null) 1 else null
            MULAW -> if (f.bits == 8) 2 else null
            ALAW -> if (f.bits == 8) 3 else null
            else -> null
        }
    }

    /** Новый кодек формата; null для несжатого PCM и неизвестных форматов. */
    fun codec(f: WaveFormat): AudioCodec? = when (f.tag) {
        IMA_ADPCM -> ImaAdpcm(f.channels, f.blockAlign)
        MS_ADPCM -> MsAdpcm.coefficients(f.extra)?.let { MsAdpcm(f.channels, f.blockAlign, it) }
        MULAW -> G711(alaw = false, channels = f.channels)
        ALAW -> G711(alaw = true, channels = f.channels)
        else -> null
    }

    private const val PCM_RANK = 10

    internal fun shortsToBytes(s: ShortArray, count: Int = s.size): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            out[2 * i] = s[i].toByte()
            out[2 * i + 1] = (s[i].toInt() shr 8).toByte()
        }
        return out
    }

    internal fun s16(d: ByteArray, o: Int): Int = ((d[o].toInt() and 0xFF) or (d[o + 1].toInt() shl 8)).toShort().toInt()

    internal fun clamp16(v: Int): Int = if (v > 32767) 32767 else if (v < -32768) -32768 else v

    internal fun sizeOk(blockAlign: Int) = blockAlign in 1..MAX_BLOCK
}

/** G.711: один байт на отсчёт, без состояния. */
internal class G711(private val alaw: Boolean, private val channels: Int) : AudioCodec {
    override fun decode(data: ByteArray): ByteArray {
        val out = ByteArray(data.size * 2)
        for (i in data.indices) {
            val v = if (alaw) decodeA(data[i].toInt() and 0xFF) else decodeMu(data[i].toInt() and 0xFF)
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    override fun encode(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            val s = AudioCodecs.s16(pcm, 2 * i)
            out[i] = (if (alaw) encodeA(s) else encodeMu(s)).toByte()
        }
        return out
    }

    override fun packetBytes(framesPerPacket: Int) = if (framesPerPacket > 0) framesPerPacket * channels else 0

    companion object {
        private const val BIAS = 0x84
        private const val CLIP = 32635
        private val SEG_END = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)

        fun decodeMu(code: Int): Int {
            val u = code.inv() and 0xFF
            val t = (((u and 0x0F) shl 3) + BIAS) shl ((u and 0x70) shr 4)
            return if (u and 0x80 != 0) BIAS - t else t - BIAS
        }

        fun encodeMu(sample: Int): Int {
            var pcm = sample
            val sign = if (pcm < 0) 0x80 else 0
            if (pcm < 0) pcm = -pcm
            if (pcm > CLIP) pcm = CLIP
            pcm += BIAS
            var exp = 7
            var mask = 0x4000
            while (exp > 0 && pcm and mask == 0) {
                exp--
                mask = mask shr 1
            }
            val mant = (pcm shr (exp + 3)) and 0x0F
            return (sign or (exp shl 4) or mant).inv() and 0xFF
        }

        fun decodeA(code: Int): Int {
            val a = code xor 0x55
            var t = (a and 0x0F) shl 4
            val seg = (a and 0x70) shr 4
            when (seg) {
                0 -> t += 8
                1 -> t += 0x108
                else -> {
                    t += 0x108
                    t = t shl (seg - 1)
                }
            }
            return if (a and 0x80 != 0) t else -t
        }

        fun encodeA(sample: Int): Int {
            var pcm = sample shr 3
            val mask: Int
            if (pcm >= 0) {
                mask = 0xD5
            } else {
                mask = 0x55
                pcm = -pcm - 1
            }
            var seg = 0
            while (seg < 8 && pcm > SEG_END[seg]) seg++
            if (seg >= 8) return 0x7F xor mask
            var a = seg shl 4
            a = a or (if (seg < 2) (pcm shr 1) and 0x0F else (pcm shr seg) and 0x0F)
            return a xor mask
        }
    }
}

/** Общая часть блочных ADPCM: нарезка потока на блоки при разборе и накопление отсчётов при сжатии. */
internal abstract class BlockAdpcm(protected val channels: Int, protected val blockAlign: Int) : AudioCodec {
    /** Отсчётов на канал в полном блоке. */
    abstract val samplesPerBlock: Int

    /** Разбирает один блок (возможно, неполный) в чередующиеся отсчёты по каналам. */
    protected abstract fun decodeBlock(d: ByteArray, off: Int, len: Int): ShortArray

    /** Сжимает ровно [samplesPerBlock] кадров в блок из [blockAlign] байт. */
    protected abstract fun encodeBlock(frames: ShortArray): ByteArray

    private var pending = ShortArray(0)
    private var pendingCount = 0

    override fun decode(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size * 4)
        var pos = 0
        while (data.size - pos >= headerBytes()) {
            val len = minOf(blockAlign, data.size - pos)
            val s = decodeBlock(data, pos, len)
            out.write(AudioCodecs.shortsToBytes(s))
            pos += len
        }
        return out.toByteArray()
    }

    protected abstract fun headerBytes(): Int

    override fun encode(pcm: ByteArray): ByteArray {
        val add = pcm.size / 2
        if (pendingCount + add > pending.size) pending = pending.copyOf(maxOf(pendingCount + add, pending.size * 2))
        for (i in 0 until add) pending[pendingCount + i] = AudioCodecs.s16(pcm, 2 * i).toShort()
        pendingCount += add
        val perBlock = samplesPerBlock * channels
        val out = java.io.ByteArrayOutputStream()
        var used = 0
        while (pendingCount - used >= perBlock) {
            out.write(encodeBlock(pending.copyOfRange(used, used + perBlock)))
            used += perBlock
        }
        if (used > 0) {
            System.arraycopy(pending, used, pending, 0, pendingCount - used)
            pendingCount -= used
        }
        return out.toByteArray()
    }

    override fun packetBytes(framesPerPacket: Int) = blockAlign
}

/** IMA (DVI) ADPCM, WAVE_FORMAT_DVI_ADPCM 0x11: заголовок блока — предсказание и индекс шага на канал. */
internal class ImaAdpcm(channels: Int, blockAlign: Int) : BlockAdpcm(channels, blockAlign) {
    private val groups = (blockAlign - 4 * channels) / (4 * channels)
    override val samplesPerBlock = groups * 8 + 1

    // Индекс шага переходит из блока в блок, чтобы кодер не начинал каждый блок с самого мелкого шага.
    private val encIndex = IntArray(channels)

    override fun headerBytes() = 4 * channels

    override fun decodeBlock(d: ByteArray, off: Int, len: Int): ShortArray {
        val pred = IntArray(channels)
        val idx = IntArray(channels)
        for (c in 0 until channels) {
            pred[c] = AudioCodecs.s16(d, off + 4 * c)
            idx[c] = (d[off + 4 * c + 2].toInt() and 0xFF).coerceIn(0, 88)
        }
        val g = minOf(groups, (len - 4 * channels) / (4 * channels))
        val out = ShortArray((g * 8 + 1) * channels)
        for (c in 0 until channels) out[c] = pred[c].toShort()
        for (gi in 0 until g) for (c in 0 until channels) {
            val base = off + 4 * channels + (gi * channels + c) * 4
            for (k in 0 until 4) {
                val b = d[base + k].toInt() and 0xFF
                val s = gi * 8 + k * 2
                for (h in 0..1) {
                    val nib = if (h == 0) b and 0x0F else b shr 4
                    val step = STEPS[idx[c]]
                    var diff = step shr 3
                    if (nib and 4 != 0) diff += step
                    if (nib and 2 != 0) diff += step shr 1
                    if (nib and 1 != 0) diff += step shr 2
                    pred[c] = AudioCodecs.clamp16(if (nib and 8 != 0) pred[c] - diff else pred[c] + diff)
                    idx[c] = (idx[c] + INDEX[nib]).coerceIn(0, 88)
                    out[(1 + s + h) * channels + c] = pred[c].toShort()
                }
            }
        }
        return out
    }

    override fun encodeBlock(frames: ShortArray): ByteArray {
        val out = ByteArray(blockAlign)
        val nibbles = Array(channels) { IntArray(samplesPerBlock - 1) }
        for (c in 0 until channels) {
            var pred = frames[c].toInt()
            var idx = encIndex[c]
            val o = 4 * c
            out[o] = pred.toByte()
            out[o + 1] = (pred shr 8).toByte()
            out[o + 2] = idx.toByte()
            for (j in 1 until samplesPerBlock) {
                val sample = frames[j * channels + c].toInt()
                val step = STEPS[idx]
                var diff = sample - pred
                var nib = 0
                if (diff < 0) {
                    nib = 8
                    diff = -diff
                }
                var vp = step shr 3
                var s = step
                if (diff >= s) { nib = nib or 4; diff -= s; vp += s }
                s = s shr 1
                if (diff >= s) { nib = nib or 2; diff -= s; vp += s }
                s = s shr 1
                if (diff >= s) { nib = nib or 1; vp += s }
                pred = AudioCodecs.clamp16(if (nib and 8 != 0) pred - vp else pred + vp)
                idx = (idx + INDEX[nib]).coerceIn(0, 88)
                nibbles[c][j - 1] = nib
            }
            encIndex[c] = idx
        }
        for (gi in 0 until groups) for (c in 0 until channels) {
            val base = 4 * channels + (gi * channels + c) * 4
            for (k in 0 until 4) {
                val j = gi * 8 + k * 2
                out[base + k] = (nibbles[c][j] or (nibbles[c][j + 1] shl 4)).toByte()
            }
        }
        return out
    }

    companion object {
        /** Блок целиком из заголовков и групп по 4 байта на канал, иначе раскладку данных не восстановить. */
        fun valid(channels: Int, blockAlign: Int) =
            AudioCodecs.sizeOk(blockAlign) && blockAlign >= 8 * channels && (blockAlign - 4 * channels) % (4 * channels) == 0

        private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
        private val STEPS = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107,
            118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
            1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894,
            6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
            32767,
        )
    }
}

/** Microsoft ADPCM, WAVE_FORMAT_ADPCM 0x02: коэффициенты предсказания берутся из extra формата. */
internal class MsAdpcm(channels: Int, blockAlign: Int, private val coefs: Array<IntArray>) : BlockAdpcm(channels, blockAlign) {
    override val samplesPerBlock = (blockAlign - 7 * channels) * 2 / channels + 2

    override fun headerBytes() = 7 * channels

    override fun decodeBlock(d: ByteArray, off: Int, len: Int): ShortArray {
        val p = IntArray(channels)
        val delta = IntArray(channels)
        val s1 = IntArray(channels)
        val s2 = IntArray(channels)
        var o = off
        for (c in 0 until channels) p[c] = (d[o++].toInt() and 0xFF).coerceAtMost(coefs.size - 1)
        for (c in 0 until channels) { delta[c] = AudioCodecs.s16(d, o); o += 2 }
        for (c in 0 until channels) { s1[c] = AudioCodecs.s16(d, o); o += 2 }
        for (c in 0 until channels) { s2[c] = AudioCodecs.s16(d, o); o += 2 }
        val nibbleCount = (len - 7 * channels) * 2 / channels * channels
        val frames = nibbleCount / channels + 2
        val out = ShortArray(frames * channels)
        for (c in 0 until channels) {
            out[c] = s2[c].toShort()
            out[channels + c] = s1[c].toShort()
        }
        for (k in 0 until nibbleCount) {
            val c = k % channels
            val b = d[o + k / 2].toInt() and 0xFF
            val nib = if (k % 2 == 0) b shr 4 else b and 0x0F
            val signed = if (nib >= 8) nib - 16 else nib
            val pred = (s1[c] * coefs[p[c]][0] + s2[c] * coefs[p[c]][1]) / 256
            val v = AudioCodecs.clamp16(pred + signed * delta[c])
            delta[c] = maxOf(16, ADAPT[nib] * delta[c] / 256)
            s2[c] = s1[c]
            s1[c] = v
            out[(2 + k / channels) * channels + c] = v.toShort()
        }
        return out
    }

    override fun encodeBlock(frames: ShortArray): ByteArray {
        val out = ByteArray(blockAlign)
        val p = IntArray(channels)
        val d0 = IntArray(channels)
        val nibbles = Array(channels) { IntArray(samplesPerBlock - 2) }
        for (c in 0 until channels) {
            val x = IntArray(samplesPerBlock) { frames[it * channels + c].toInt() }
            var bestErr = Long.MAX_VALUE
            for (pi in coefs.indices) {
                val trial = IntArray(samplesPerBlock - 2)
                val err = encodeChannel(x, pi, trial)
                if (err < bestErr) {
                    bestErr = err
                    p[c] = pi
                    System.arraycopy(trial, 0, nibbles[c], 0, trial.size)
                }
            }
            d0[c] = initialDelta(x)
        }
        var o = 0
        for (c in 0 until channels) out[o++] = p[c].toByte()
        for (c in 0 until channels) { out[o++] = d0[c].toByte(); out[o++] = (d0[c] shr 8).toByte() }
        for (c in 0 until channels) {
            val v = frames[channels + c].toInt()
            out[o++] = v.toByte(); out[o++] = (v shr 8).toByte()
        }
        for (c in 0 until channels) {
            val v = frames[c].toInt()
            out[o++] = v.toByte(); out[o++] = (v shr 8).toByte()
        }
        val total = (samplesPerBlock - 2) * channels
        var k = 0
        while (k < total) {
            val hi = nibbles[k % channels][k / channels]
            val lo = nibbles[(k + 1) % channels][(k + 1) / channels]
            out[o++] = ((hi shl 4) or lo).toByte()
            k += 2
        }
        return out
    }

    private fun initialDelta(x: IntArray) = maxOf(16, abs(x[1] - x[0]) / 4)

    /** Сжимает отсчёты одного канала предсказателем [pi]; возвращает сумму квадратов ошибки. */
    private fun encodeChannel(x: IntArray, pi: Int, nibbles: IntArray): Long {
        var s2 = x[0]
        var s1 = x[1]
        var delta = initialDelta(x)
        val c1 = coefs[pi][0]
        val c2 = coefs[pi][1]
        var err = 0L
        for (i in 2 until x.size) {
            val pred = (s1 * c1 + s2 * c2) / 256
            val e = x[i] - pred
            var q = if (e >= 0) (e + delta / 2) / delta else -((-e + delta / 2) / delta)
            q = q.coerceIn(-8, 7)
            val v = AudioCodecs.clamp16(pred + q * delta)
            val miss = (x[i] - v).toLong()
            err += miss * miss
            val nib = q and 0x0F
            nibbles[i - 2] = nib
            delta = maxOf(16, ADAPT[nib] * delta / 256)
            s2 = s1
            s1 = v
        }
        return err
    }

    companion object {
        /** Два байта на отсчёт заголовка, по 7 байт на канал; данных должно хватать хотя бы на пару отсчётов. */
        fun valid(channels: Int, blockAlign: Int) = AudioCodecs.sizeOk(blockAlign) && blockAlign > 7 * channels

        private val ADAPT = intArrayOf(230, 230, 230, 230, 307, 409, 512, 614, 768, 614, 512, 409, 307, 230, 230, 230)
        private val STANDARD = arrayOf(
            intArrayOf(256, 0), intArrayOf(512, -256), intArrayOf(0, 0), intArrayOf(192, 64),
            intArrayOf(240, 0), intArrayOf(460, -208), intArrayOf(392, -232),
        )

        /**
         * Коэффициенты из extra формата (отсчётов на блок, число пар, сами пары); без extra — стандартные семь.
         * null — extra повреждён.
         */
        fun coefficients(extra: ByteArray): Array<IntArray>? {
            if (extra.size < 4) return STANDARD
            val count = (extra[2].toInt() and 0xFF) or ((extra[3].toInt() and 0xFF) shl 8)
            if (count !in 1..16 || extra.size < 4 + count * 4) return null
            return Array(count) { AudioCodecs.s16(extra, 4 + it * 4).let { a -> intArrayOf(a, AudioCodecs.s16(extra, 6 + it * 4)) } }
        }
    }
}
