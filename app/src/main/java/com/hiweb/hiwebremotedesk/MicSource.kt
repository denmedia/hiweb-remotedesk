package com.hiweb.hiwebremotedesk

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.hiweb.hiwebremotedesk.rdp.AudioSource

/**
 * Микрофон планшета для канала audin: PCM 16 бит через AudioRecord в отдельном потоке.
 * Сервер сам выбирает частоту и число каналов, поэтому источник подстраивается под запрос,
 * вместо того чтобы диктовать свой формат.
 */
class MicSource : AudioSource {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    @SuppressLint("MissingPermission") // разрешение RECORD_AUDIO спрашивает RemoteActivity до включения
    override fun start(sampleRate: Int, channels: Int, feed: (ByteArray) -> Unit): Boolean {
        stop()
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, sampleRate * channels * 2 / 5),
            )
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (_: Exception) {
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
    }

    private companion object {
        const val CHUNK_MS = 40
        const val JOIN_MS = 300L
    }
}
