package com.hiweb.hiwebremotedesk

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.hiweb.hiwebremotedesk.rdp.AudioSink
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Проигрывает PCM из удалённого сеанса через AudioTrack в отдельном потоке, чтобы не задерживать чтение сети. */
class AudioPlayer : AudioSink {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "rdp-audio").apply { isDaemon = true } }
    private val queuedBytes = AtomicInteger()
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0

    override fun play(sampleRate: Int, channels: Int, pcm: ByteArray, done: () -> Unit) {
        // При долгой задержке воспроизведения лучше потерять звук, чем отставать на секунды.
        if (queuedBytes.get() > sampleRate * channels * 2 * MAX_BACKLOG_SECONDS) {
            done()
            return
        }
        queuedBytes.addAndGet(pcm.size)
        try {
            executor.execute {
                try {
                    write(sampleRate, channels, pcm)
                } finally {
                    queuedBytes.addAndGet(-pcm.size)
                }
                // Подтверждение блока уходит в сеть из этого потока; если сеанс уже закрыт (выход, обрыв), запись в
                // сокет бросает исключение, а необработанное исключение потока роняет всё приложение.
                try {
                    done()
                } catch (_: Exception) {
                }
            }
        } catch (_: RejectedExecutionException) {
            queuedBytes.addAndGet(-pcm.size)
        }
    }

    override fun stop() {
        try {
            executor.execute { release() }
        } catch (_: RejectedExecutionException) {
        }
        executor.shutdown()
    }

    private fun write(sampleRate: Int, channels: Int, pcm: ByteArray) {
        if (track == null || sampleRate != trackRate || channels != trackChannels) {
            release()
            track = create(sampleRate, channels) ?: return
            trackRate = sampleRate
            trackChannels = channels
        }
        val t = track ?: return
        var off = 0
        while (off < pcm.size) {
            val n = t.write(pcm, off, pcm.size - off)
            if (n <= 0) return
            off += n
        }
    }

    private fun create(sampleRate: Int, channels: Int): AudioTrack? = try {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min * 2, sampleRate * channels * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    } catch (_: Exception) {
        null
    }

    private fun release() {
        track?.run { runCatching { stop() }; release() }
        track = null
    }

    private companion object {
        const val MAX_BACKLOG_SECONDS = 2
    }
}
