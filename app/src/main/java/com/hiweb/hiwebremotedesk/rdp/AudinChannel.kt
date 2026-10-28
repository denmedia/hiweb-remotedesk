package com.hiweb.hiwebremotedesk.rdp

/**
 * Источник звука для канала audin: PCM 16 бит little endian ровно в запрошенном формате
 * (сервер сам выбирает частоту и число каналов из списка, который ему предложил клиент).
 */
interface AudioSource {
    /**
     * Начинает захват; блоки PCM передаются в [feed] из потока захвата. Размер блока роли не играет:
     * канал сам нарежет его по framesPerPacket сервера. false — устройство не поддержало формат.
     */
    fun start(sampleRate: Int, channels: Int, feed: (ByteArray) -> Unit): Boolean

    /** Останавливает захват; после возврата [feed] больше не вызывается. */
    fun stop()
}

/**
 * Динамический канал «AUDIO_INPUT» (MS-RDPEAI): звук микрофона устройства уходит на ПК.
 * Поток односторонний — от клиента к серверу, поэтому Форматы, которые предлагает сервер, клиент
 * фильтрует до несжатого PCM 16 бит: кодировать в AAC/Opus не нужно, а Windows принимает PCM.
 * Передача включается только по просьбе пользователя: канал открыт, форматы согласованы, но пока
 * микрофон выключен, в сеть ничего не уходит.
 * Раскладки PDU взяты из MS-RDPEAI и сверены с channels/audin в FreeRDP.
 */
internal class AudinChannel(
    private val source: AudioSource,
    /** Отправляет сообщение динамического канала целиком; фрагментацию делает drdynvc. */
    private val log: (String) -> Unit = {},
) : DvcHandler {
    private class Format(val channels: Int, val sampleRate: Int, val bits: Int, val raw: ByteArray)

    private var write: ((ByteArray) -> Unit)? = null
    private var formats = emptyList<Format>()
    private var current: Format? = null
    private var framesPerPacket = 0

    /** Пользователь включил передачу; до прихода OPEN от сервера захват ждёт в [current]. */
    @Volatile private var enabled = false
    @Volatile private var capturing = false
    @Volatile private var closed = false

    override fun onOpen(write: (ByteArray) -> Unit) {
        this.write = write
        closed = false
        log("канал открыт")
    }

    override fun onMessage(data: ByteArray) {
        if (data.isEmpty()) return
        try {
            val r = ByteReader(data, 1)
            when (data[0].toInt() and 0xFF) {
                VERSION -> onVersion(r)
                FORMATS -> onFormats(r)
                OPEN -> onOpenRequest(r)
                FORMAT_CHANGE -> onFormatChange(r)
                // DATA_INCOMING и DATA клиент только отправляет; входящие подтверждения не нужны.
                DATA_INCOMING, DATA -> Unit
                else -> log("неизвестный PDU 0x${"%02x".format(data[0])}")
            }
        } catch (e: RdpException) {
            log("повреждённый PDU проигнорирован: ${e.message}")
        }
    }

    override fun onClose() {
        closed = true
        stopCapture()
        write = null
        log("канал закрыт")
    }

    /**
     * Включает или выключает передачу звука. Windows открывает канал только когда программа на ПК начинает
     * запись и закрывает, когда она закончила, поэтому включение до OPEN (и между записями) запоминается и
     * срабатывает при открытии потока. false — канал открыт, но микрофон не запустился.
     */
    fun setEnabled(value: Boolean): Boolean {
        if (!value) {
            enabled = false
            stopCapture()
            return true
        }
        enabled = true
        // Канал закрыт или формат ещё не выбран: сервер пришлёт OPEN, и захват начнётся в его формате.
        if (closed || write == null || current == null) return true
        return startCapture()
    }

    // ------------------------------------------------------------------ сообщения сервера

    private fun onVersion(r: ByteReader) {
        val server = r.u32le().toInt()
        log("версия сервера $server")
        // Более новая версия канала нам незнакома: не отвечаем, как и FreeRDP.
        if (server > CLIENT_VERSION) return
        reply(ByteWriter().u8(VERSION).u32le(CLIENT_VERSION).toByteArray())
    }

    private fun onFormats(r: ByteReader) {
        val count = r.u32le().toInt()
        r.u32le() // cbSizeFormatsPacket — размер пакета от сервера, для ответа не нужен
        if (count !in 1..MAX_FORMATS) {
            log("недопустимое число форматов $count")
            return
        }
        val accepted = ArrayList<Format>()
        repeat(count) {
            val tag = r.u16le()
            val channels = r.u16le()
            val rate = r.u32le().toInt()
            r.u32le() // nAvgBytesPerSec
            r.u16le() // nBlockAlign
            val bits = r.u16le()
            val extra = r.bytes(r.u16le())
            val raw = ByteWriter()
                .u16le(tag).u16le(channels).u32le(rate).u32le(rate.toLong() * channels * bits / 8)
                .u16le(channels * bits / 8).u16le(bits).u16le(extra.size).bytes(extra).toByteArray()
            if (tag == WAVE_FORMAT_PCM && bits == 16 && channels in 1..2 && rate in MIN_RATE..MAX_RATE) {
                accepted += Format(channels, rate, bits, raw)
            }
        }
        formats = accepted
        current = null
        log("предложено форматов $count, принято PCM ${accepted.size}")
        // Сервер ждёт подтверждения приёма данных, а затем список согласованных форматов.
        reply(byteArrayOf(DATA_INCOMING.toByte()))
        val body = ByteWriter()
            .u8(FORMATS).u32le(accepted.size).u32le((9 + accepted.sumOf { it.raw.size }).toLong())
        accepted.forEach { body.bytes(it.raw) }
        reply(body.toByteArray())
    }

    private fun onOpenRequest(r: ByteReader) {
        framesPerPacket = r.u32le().toInt().coerceIn(0, MAX_FRAMES_PER_PACKET)
        val initial = r.u32le().toInt()
        val format = formats.getOrNull(initial)
        if (format == null) {
            log("неизвестный начальный формат $initial")
            reply(ByteWriter().u8(OPEN_REPLY).u32le(RESULT_ERROR).toByteArray())
            return
        }
        current = format
        log("открытие: ${format.sampleRate} Гц, ${format.channels} кан., ${framesPerPacket} кадров в пакете")
        // Порядок важен: сначала формат, затем результат открытия (так делает и FreeRDP).
        sendFormatChange(initial)
        if (enabled) startCapture()
        reply(ByteWriter().u8(OPEN_REPLY).u32le(RESULT_OK).toByteArray())
    }

    private fun onFormatChange(r: ByteReader) {
        val index = r.u32le().toInt()
        val format = formats.getOrNull(index)
        if (format == null) {
            log("неизвестный формат $index")
            return
        }
        current = format
        log("смена формата: ${format.sampleRate} Гц, ${format.channels} кан.")
        // Захват идёт в формате, который выбрал сервер, поэтому его нужно перезапустить.
        if (enabled) {
            stopCapture()
            startCapture()
        }
        sendFormatChange(index)
    }

    // ------------------------------------------------------------------ захват и отправка

    private fun sendFormatChange(index: Int) {
        reply(ByteWriter().u8(FORMAT_CHANGE).u32le(index).toByteArray())
    }

    private fun startCapture(): Boolean {
        val format = current ?: return false
        if (closed) return false
        if (capturing) return true
        val started = runCatching { source.start(format.sampleRate, format.channels) { pcm -> sendPcm(pcm) } }
            .getOrDefault(false)
        capturing = started
        log(if (started) "микрофон включён: ${format.sampleRate} Гц, ${format.channels} кан." else "микрофон не запустился")
        if (!started) enabled = false
        return started
    }

    private fun stopCapture() {
        if (!capturing) return
        capturing = false
        runCatching { source.stop() }
        log("микрофон выключен")
    }

    /** Блок PCM уходит серверу частями по framesPerPacket кадров (так же делает FreeRDP). */
    private fun sendPcm(pcm: ByteArray) {
        val format = current ?: return
        val out = write ?: return
        if (closed || !enabled) return
        val frame = format.channels * format.bits / 8
        val chunk = if (framesPerPacket > 0) framesPerPacket * frame else pcm.size
        if (chunk <= 0) return
        var off = 0
        do {
            val n = minOf(chunk, pcm.size - off)
            // SNDIN_DATA_INCOMING сообщает серверу, что следом идут данные: без него Windows их не берёт.
            runCatching {
                out(byteArrayOf(DATA_INCOMING.toByte()))
                out(ByteWriter(n + 1).u8(DATA).bytes(pcm, off, n).toByteArray())
            }.onFailure { log("отправка звука прервана: ${it.message}") }
            off += n
        } while (off < pcm.size)
    }

    private fun reply(pdu: ByteArray) {
        val out = write ?: return
        runCatching { out(pdu) }.onFailure { log("не удалось отправить PDU: ${it.message}") }
    }

    companion object {
        const val CHANNEL_NAME = "AUDIO_INPUT"

        /// Версия канала: 2 — единственная, которую понимает Windows.
        private const val CLIENT_VERSION = 2

        private const val VERSION = 0x01
        private const val FORMATS = 0x02
        private const val OPEN = 0x03
        private const val OPEN_REPLY = 0x04
        private const val DATA_INCOMING = 0x05
        private const val DATA = 0x06
        private const val FORMAT_CHANGE = 0x07

        private const val RESULT_OK = 0L
        private const val RESULT_ERROR = 1L

        private const val WAVE_FORMAT_PCM = 1
        private const val MIN_RATE = 8000
        private const val MAX_RATE = 48000
        private const val MAX_FORMATS = 1000

        /** framesPerPacket больше этого не имеет смысла: это задержка в десятки секунд. */
        private const val MAX_FRAMES_PER_PACKET = 48000
    }
}
