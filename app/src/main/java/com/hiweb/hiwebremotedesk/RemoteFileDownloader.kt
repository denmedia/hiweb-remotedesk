package com.hiweb.hiwebremotedesk

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.hiweb.hiwebremotedesk.rdp.RdpClient
import com.hiweb.hiwebremotedesk.rdp.RdpException
import com.hiweb.hiwebremotedesk.rdp.RemoteFile

/**
 * Сохраняет файлы, скопированные на ПК, в «Загрузки/RemoteDesk» на планшете (MediaStore, права не нужны).
 * Содержимое запрашивается у ПК кусками, поэтому [download] нужно вызывать не из потока интерфейса.
 */
class RemoteFileDownloader(context: Context, private val client: RdpClient) {
    class Progress(val doneBytes: Long, val totalBytes: Long, val fileName: String)

    class Result(val files: Int, val bytes: Long)

    private val resolver = context.applicationContext.contentResolver

    @Volatile var cancelled = false

    fun download(files: List<RemoteFile>, onProgress: (Progress) -> Unit): Result {
        val toCopy = files.filter { !it.isDirectory }
        // Неизвестные размеры уточняем заранее, чтобы показывать общий ход.
        val sizes = toCopy.associate { it.index to (if (it.size >= 0) it.size else client.remoteFileSize(it.index)) }
        val total = sizes.values.sum()
        var done = 0L
        var lastReport = 0L
        var saved = 0
        for (file in toCopy) {
            ensureNotCancelled()
            val size = sizes.getValue(file.index)
            val target = createTarget(file)
            var finished = false
            try {
                resolver.openOutputStream(target)?.use { out ->
                    var position = 0L
                    while (position < size) {
                        ensureNotCancelled()
                        val chunk = client.readRemoteFile(file.index, position, minOf(CHUNK.toLong(), size - position).toInt())
                        if (chunk.isEmpty()) throw RdpException("Файл «${file.name}» оказался короче заявленного")
                        out.write(chunk)
                        position += chunk.size
                        done += chunk.size
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport >= REPORT_INTERVAL_MS) {
                            lastReport = now
                            onProgress(Progress(done, total, file.name))
                        }
                    }
                } ?: throw RdpException("Не удалось записать файл «${file.name}»")
                finished = true
            } finally {
                if (finished) {
                    resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } else {
                    runCatching { resolver.delete(target, null, null) }
                }
            }
            saved++
        }
        onProgress(Progress(done, total, ""))
        return Result(saved, done)
    }

    private fun ensureNotCancelled() {
        if (cancelled) throw RdpException("Передача отменена")
    }

    private fun createTarget(file: RemoteFile): android.net.Uri {
        val folder = file.path.substringBeforeLast('/', "")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + if (folder.isEmpty()) "" else "/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RdpException("Не удалось создать файл «${file.name}» в Загрузках")
    }

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    companion object {
        /** Папка внутри «Загрузок», куда попадают файлы с ПК. */
        const val FOLDER = "RemoteDesk"
        private const val CHUNK = 64 * 1024
        private const val REPORT_INTERVAL_MS = 150L
    }
}
