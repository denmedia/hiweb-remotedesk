package com.hiweb.hiwebremotedesk

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.hiweb.hiwebremotedesk.rdp.AudioSource

/**
 * Микрофон планшета для канала audin: PCM 16 бит через AudioRecord в отдельном потоке.
 * Сервер сам выбирает частоту и число каналов, поэтому источник подстраивается под запрос,
 * вместо того чтобы диктовать свой формат.
 *
 * Звук ПК воспроизводится динамиками этого же планшета, поэтому при видеозвонке на ПК микрофон слышит голос
 * собеседника и отправляет его обратно (эхо). Против этого запись идёт с источника «голосовая связь» и с системными
 * эхоподавлением и шумоподавлением; если устройство их не даёт, остаётся обычный микрофон. Наушники эхо убирают полностью.
 */
class MicSource : AudioSource {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var effects: List<AudioEffect> = emptyList()

    @Volatile private var running = false

    @SuppressLint("MissingPermission") // разрешение RECORD_AUDIO спрашивает RemoteActivity до включения
    override fun start(sampleRate: Int, channels: Int, feed: (ByteArray) -> Unit): Boolean {
        stop()
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val bufferBytes = maxOf(min * 2, sampleRate * channels * 2 / 5)
        // Сначала источник с обработкой голоса; не поддержан (частота, число каналов) — обычный микрофон без эхоподавления.
        var voice = true
        var rec = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, channelMask, bufferBytes)
        if (rec == null) {
            voice = false
            rec = open(MediaRecorder.AudioSource.MIC, sampleRate, channelMask, bufferBytes) ?: return false
        }
        val attached = attachEffects(rec)
        Log.i(TAG, "mic: ${sampleRate} Гц, $channels кан., источник ${if (voice) "голосовая связь" else "микрофон"}, обработка: ${attached.ifEmpty { listOf("нет") }.joinToString()}")
        try {
            rec.startRecording()
        } catch (_: Exception) {
            releaseEffects()
            rec.release()
            return false
        }
        record = rec
        running = true
        // 40 мс звука: мельче — лишние накладные расходы на PDU, крупнее — заметная задержка речи.
        val chunk = (sampleRate * channels * 2 * CHUNK_MS / 1000).coerceAtLeast(channels * 2 * 160)
        thread = Thread({
            val buffer = ByteArray(chunk)
            try {
                while (running) {
                    val n = try {
                        rec.read(buffer, 0, buffer.size)
                    } catch (_: Exception) {
                        -1
                    }
                    if (n <= 0) {
                        // Пустой блок бывает при остановке записи: выходим, не занимая поток.
                        if (!running) break
                        continue
                    }
                    if (!running) break
                    // Каждому блоку — своя копия: буфер переиспользуется следующим чтением.
                    runCatching { feed(buffer.copyOf(n)) }
                }
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }, "rdp-mic").apply { isDaemon = true; start() }
        return true
    }

    private fun open(source: Int, sampleRate: Int, channelMask: Int, bufferBytes: Int): AudioRecord? {
        val rec = try {
            AudioRecord(source, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        } catch (_: Exception) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    /** Включает эхо- и шумоподавление платформы на этой записи; возвращает названия включённых. */
    private fun attachEffects(rec: AudioRecord): List<String> {
        val list = ArrayList<AudioEffect>()
        val names = ArrayList<String>()
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(rec.audioSessionId)?.let {
                    it.enabled = true
                    list += it
                    names += "эхоподавление"
                }
            }
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(rec.audioSessionId)?.let {
                    it.enabled = true
                    list += it
                    names += "шумоподавление"
                }
            }
        }
        effects = list
        return names
    }

    private fun releaseEffects() {
        effects.forEach { runCatching { it.release() } }
        effects = emptyList()
    }

    /**
     * Останавливает захват. stop() разблокирует текущее чтение, а освобождение устройства делает поток
     * записи: ждать его здесь нельзя — метод вызывается из потока сети и из интерфейса.
     */
    override fun stop() {
        if (!running && record == null) return
        running = false
        runCatching { record?.stop() }
        thread?.let { runCatching { it.join(JOIN_MS) } }
        thread = null
        record = null
        releaseEffects()
    }

    private companion object {
        const val TAG = "RDP"
        const val CHUNK_MS = 40
        const val JOIN_MS = 300L
    }
}
