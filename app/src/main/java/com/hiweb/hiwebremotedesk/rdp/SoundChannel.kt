package com.hiweb.hiwebremotedesk.rdp

/** Приёмник звука: проигрывает PCM 16 бит (little endian) и вызывает [done], когда блок принят буфером воспроизведения. */
interface AudioSink {
    fun play(sampleRate: Int, channels: Int, pcm: ByteArray, done: () -> Unit)
    fun stop()
}

/**
 * Статический виртуальный канал «rdpsnd» (MS-RDPEA): звук Windows на устройстве.
 * По умолчанию из форматов сервера принимается только несжатый PCM 16 бит (моно/стерео). С [compressed] клиент
 * принимает ещё IMA/MS ADPCM и G.711 (см. [AudioCodecs]) и ставит их выше PCM: Windows выбирает формат из списка
 * клиента, поэтому звук идёт сжатым, а на плеер он попадает уже разжатым в PCM.
 * Подтверждение блока уходит серверу после того, как [AudioSink] принял данные, — это и есть управление потоком.
 */
internal class SoundChannel(
    /** Отправляет один целый PDU канала; нарезку на фрагменты делает вызывающий. */
    private val send: (ByteArray) -> Unit,
    private val sink: AudioSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** Принимать сжатые форматы звука (настройка «Сжатие звука»). */
    private val compressed: Boolean = false,
) {
    private class Format(val channels: Int, val sampleRate: Int, val decoder: AudioDecoder?)

    private class PendingWave(val timestamp: Int, val formatNo: Int, val blockNo: Int, val head: ByteArray)

    private var formats = emptyList<Format>()
    private val reassembly = ByteWriter()
    private val sendLock = Any()

    /** Начало блока (WaveInfo), данные которого придут отдельным PDU без заголовка. */
    private var pending: PendingWave? = null

    fun onChannelData(r: ByteReader) {
        log("chunk ${r.remaining} bytes")
        if (r.remaining < 8) return
        r.u32le() // общая длина
        val flags = r.u32le().toInt()
        val chunk = r.bytes(r.remaining)
        if (flags and ClipboardChannel.CHANNEL_FLAG_FIRST != 0) reassembly.reset()
        reassembly.bytes(chunk)
        if (flags and ClipboardChannel.CHANNEL_FLAG_LAST == 0) return
        val data = reassembly.toByteArray()
        reassembly.reset()
        log("pdu ${data.size} bytes, flags=0x${flags.toString(16)}, head=${data.take(8).joinToString("") { "%02x".format(it) }}, waveExpected=${pending != null}")
        try {
            val wave = pending
            if (wave != null) {
                pending = null
                onWaveData(wave, data)
            } else {
                handle(data)
            }
        } catch (e: RdpException) {
            log("bad PDU ignored: ${e.message}")
            pending = null // повреждённый пакет звука не должен рвать сеанс
        }
    }

    private fun handle(data: ByteArray) {
        val r = ByteReader(data)
        if (r.remaining < 4) return
        val type = r.u8()
        r.u8()
        val size = r.u16le()
        val body = ByteReader(data, r.pos, minOf(data.size, r.pos + size))
        when (type) {
            SNDC_FORMATS -> onServerFormats(body)
            SNDC_TRAINING -> {
                val ts = body.u16le()
                val pack = body.u16le()
                reply(SNDC_TRAINING, ByteWriter().u16le(ts).u16le(pack).toByteArray())
            }
            SNDC_WAVE -> {
                val ts = body.u16le()
                val formatNo = body.u16le()
                val block = body.u8()
                body.skip(3)
                pending = PendingWave(ts, formatNo, block, body.bytes(minOf(4, body.remaining)))
            }
            SNDC_WAVE2 -> {
                val ts = body.u16le()
                val formatNo = body.u16le()
                val block = body.u8()
                body.skip(3 + 4) // выравнивание и dwAudioTimeStamp
                play(PendingWave(ts, formatNo, block, ByteArray(0)), body.bytes(body.remaining))
            }
            SNDC_CLOSE -> pending = null
        }
    }

    private fun onServerFormats(r: ByteReader) {
        r.skip(4 + 4 + 4 + 2) // флаги, громкость, высота тона, порт датаграмм
        val count = r.u16le()
        r.skip(1)
        val serverVersion = r.u16le()
        r.skip(1)
        val offered = ArrayList<WaveFormat>()
        repeat(count) { offered += WaveFormat.read(r) }
        // Порядок в ответе — предпочтение клиента, а номер формата в WaveInfo считается по ответу, а не по списку сервера.
        // Сортировка устойчивая: внутри одного ранга сохраняется порядок сервера.
        val chosen = offered.mapNotNull { f -> AudioCodecs.rank(f, compressed)?.let { it to f } }.sortedBy { it.first }.map { it.second }
        val accepted = ArrayList<Format>()
        val wire = ByteWriter()
        for (f in chosen) {
            accepted += Format(f.channels, f.sampleRate, AudioCodecs.codec(f))
            wire.bytes(f.toBytes())
        }
        formats = accepted
        log("server formats=$count version=$serverVersion: ${offered.joinToString()}")
        log("accepted=${accepted.size} (сжатые=${accepted.count { it.decoder != null }}, compressed=$compressed)")
        val version = minOf(serverVersion, CLIENT_VERSION)
        val body = ByteWriter()
            .u32le(TSSNDCAPS_ALIVE or TSSNDCAPS_VOLUME).u32le(0xFFFFFFFFL).u32le(0).u16le(0)
            .u16le(accepted.size).u8(0).u16le(version).u8(0)
            .bytes(wire.toByteArray())
        reply(SNDC_FORMATS, body.toByteArray())
        if (version >= 6) reply(SNDC_QUALITYMODE, ByteWriter().u16le(QUALITY_HIGH).u16le(0).toByteArray())
    }

    private fun onWaveData(wave: PendingWave, data: ByteArray) {
        // Первые 4 байта блока пришли в WaveInfo, а этот PDU вместо заголовка начинается с 4 байт выравнивания.
        val rest = if (data.size > 4) data.copyOfRange(4, data.size) else ByteArray(0)
        play(wave, wave.head + rest)
    }

    private fun play(wave: PendingWave, pcm: ByteArray) {
        val started = clock()
        val format = formats.getOrNull(wave.formatNo)
        val confirm = {
            val ts = (wave.timestamp + (clock() - started).toInt()) and 0xFFFF
            reply(SNDC_WAVECONFIRM, ByteWriter().u16le(ts).u8(wave.blockNo).u8(0).toByteArray())
        }
        // Разжатие — здесь, в потоке чтения сети: ADPCM и G.711 дешёвые, а плеер получает всегда обычный PCM.
        val samples = if (format?.decoder == null) pcm else runCatching { format.decoder.decode(pcm) }.getOrDefault(ByteArray(0))
        if (format == null || samples.isEmpty()) confirm() else sink.play(format.sampleRate, format.channels, samples, confirm)
    }

    private fun reply(type: Int, body: ByteArray) {
        val pdu = ByteWriter(4 + body.size).u8(type).u8(0).u16le(body.size).bytes(body).toByteArray()
        synchronized(sendLock) { send(pdu) }
    }

    companion object {
        const val CHANNEL_NAME = "rdpsnd"

        private const val SNDC_CLOSE = 1
        private const val SNDC_WAVE = 2
        private const val SNDC_WAVECONFIRM = 5
        private const val SNDC_TRAINING = 6
        private const val SNDC_FORMATS = 7
        private const val SNDC_QUALITYMODE = 12
        private const val SNDC_WAVE2 = 13

        private const val TSSNDCAPS_ALIVE = 1
        private const val TSSNDCAPS_VOLUME = 2
        private const val QUALITY_HIGH = 2
        private const val CLIENT_VERSION = 6
    }
}
