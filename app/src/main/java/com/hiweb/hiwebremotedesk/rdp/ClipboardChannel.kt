package com.hiweb.hiwebremotedesk.rdp

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Статический виртуальный канал «cliprdr» (MS-RDPECLIP): обмен текстом и файлами через буфер обмена.
 * Работает в обе стороны: локальный текст (или список файлов) предлагается серверу списком форматов и отдаётся
 * по запросу (Ctrl+V на ПК), а то, что скопировано на ПК, приходит в [onRemoteText] и [onRemoteFiles].
 *
 * Файлы передаются форматами «FileGroupDescriptorW» (список) и «FileContents» (содержимое по диапазонам).
 * Для скачивания с ПК [remoteFileSize] и [readRemoteFile] блокируют вызывающий поток до ответа сервера,
 * поэтому их нельзя вызывать из потока чтения сети.
 */
internal class ClipboardChannel(
    /** Отправляет один целый CLIPRDR PDU в канал; нарезку на фрагменты делает вызывающий. */
    private val send: (ByteArray) -> Unit,
    private val onRemoteText: (String) -> Unit,
    /** false — текст не предлагается и не принимается (канал нужен только для файлов). */
    private val textEnabled: Boolean = true,
    private val filesEnabled: Boolean = false,
    /** Список файлов, скопированных на ПК; null — на ПК файлов в буфере больше нет. */
    private val onRemoteFiles: (List<RemoteFile>?) -> Unit = {},
    /** Выполняет чтение локальных файлов вне потока чтения сети. */
    private val background: (Runnable) -> Unit = { it.run() },
) {
    @Volatile var localText: String? = null
    @Volatile var isReady = false
        private set

    @Volatile private var localFiles: List<LocalFile>? = null
    private val readers = HashMap<Int, RangeReader>()

    private var serverFlags = 0
    private var serverLongNames = false
    private val reassembly = ByteWriter()

    private enum class Pending { NONE, TEXT, FILES }
    private var pending = Pending.NONE
    @Volatile private var remoteHasFiles = false

    private val streamIds = AtomicInteger()
    private val waiters = ConcurrentHashMap<Int, ArrayBlockingQueue<ByteArray>>()

    /** Файловый обмен возможен, если он включён и сервер его поддерживает. */
    val filesActive: Boolean
        get() = filesEnabled && serverLongNames && serverFlags and CB_STREAM_FILECLIP_ENABLED != 0

    /** Принимает фрагмент данных канала (заголовок TS_CHANNEL_PDU_HEADER + часть PDU). */
    fun onChannelData(r: ByteReader) {
        if (r.remaining < 8) return
        r.u32le() // общая длина
        val flags = r.u32le().toInt()
        val chunk = r.bytes(r.remaining)
        if (flags and CHANNEL_FLAG_FIRST != 0) reassembly.reset()
        reassembly.bytes(chunk)
        if (flags and CHANNEL_FLAG_LAST == 0) return
        val data = reassembly.toByteArray()
        reassembly.reset()

        val pdu = ByteReader(data)
        while (pdu.remaining >= 8) {
            val type = pdu.u16le()
            val msgFlags = pdu.u16le()
            val len = pdu.u32le().toInt()
            if (len < 0 || len > pdu.remaining) return
            handle(type, msgFlags, pdu.bytes(len))
        }
    }

    /** Заменяет предложение локального буфера текстом (новое копирование на планшете отменяет предложенные файлы). */
    fun replaceLocalText(text: String?) {
        dropLocalFiles()
        localText = text
        announceLocal()
    }

    /** Предлагает ПК файлы планшета (null — снять предложение). Возвращает false, если сервер не поддерживает файлы. */
    fun offerFiles(files: List<LocalFile>?): Boolean {
        dropLocalFiles()
        if (files != null && !filesActive) {
            announceLocal()
            return false
        }
        localFiles = files?.takeIf { it.isNotEmpty() }
        if (localFiles != null) resetRemoteFiles()
        announceLocal()
        return true
    }

    /** Сообщает серверу, что у нас есть файлы, текст (или пустой список, если буфер пуст). */
    fun announceLocal() {
        if (!isReady) return
        val body = ByteWriter()
        if (localFiles != null && filesActive) {
            body.u32le(ID_FILE_GROUP_DESCRIPTOR).utf16(FileDescriptors.FORMAT_NAME).u16le(0)
            body.u32le(ID_FILE_CONTENTS).utf16(FileDescriptors.CONTENTS_FORMAT_NAME).u16le(0)
        } else if (textEnabled && localText != null) {
            body.u32le(CF_UNICODETEXT)
            if (serverLongNames) body.u16le(0) else body.zeros(32)
        }
        send(pdu(CB_FORMAT_LIST, 0, body.toByteArray()))
    }

    /** Размер скопированного на ПК файла с номером [index] в списке; блокирует поток. */
    fun remoteFileSize(index: Int): Long {
        val data = requestContents(index, FILECONTENTS_SIZE, 0L, 8)
        if (data.size < 8) throw RdpException("ПК вернул некорректный размер файла")
        return ByteReader(data).u64le()
    }

    /** Читает фрагмент скопированного на ПК файла; пустой результат — конец файла. Блокирует поток. */
    fun readRemoteFile(index: Int, position: Long, length: Int): ByteArray =
        requestContents(index, FILECONTENTS_RANGE, position, length)

    /** Освобождает локальные файлы и прерывает ожидающие запросы к ПК. */
    fun close() {
        dropLocalFiles()
        for (q in waiters.values) q.offer(FAILED)
    }

    private fun requestContents(index: Int, flags: Int, position: Long, length: Int): ByteArray {
        val id = streamIds.incrementAndGet()
        val reply = ArrayBlockingQueue<ByteArray>(1)
        waiters[id] = reply
        try {
            val body = ByteWriter(24).u32le(id).u32le(index).u32le(flags)
                .u32le(position.toInt()).u32le((position ushr 32).toInt()).u32le(length)
            send(pdu(CB_FILECONTENTS_REQUEST, 0, body.toByteArray()))
            val data = reply.poll(CONTENTS_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: throw RdpException("ПК не ответил на запрос файла")
            if (data === FAILED) throw RdpException("ПК отказал в чтении файла")
            return data
        } finally {
            waiters.remove(id)
        }
    }

    private fun handle(type: Int, flags: Int, body: ByteArray) {
        when (type) {
            CB_CLIP_CAPS -> {
                // cCapsSets(2), pad(2), затем наборы: тип(2), длина(2), версия(4), флаги(4)
                if (body.size >= 16) {
                    serverFlags = ByteReader(body, 12).u32le().toInt()
                    serverLongNames = serverFlags and CB_USE_LONG_FORMAT_NAMES != 0
                }
            }
            CB_MONITOR_READY -> {
                val ours = CB_USE_LONG_FORMAT_NAMES or
                    if (filesEnabled) CB_STREAM_FILECLIP_ENABLED or CB_FILECLIP_NO_FILE_PATHS or CB_HUGE_FILE_SUPPORT_ENABLED else 0
                val caps = ByteWriter().u16le(1).u16le(0).u16le(1).u16le(12).u32le(2).u32le(ours)
                send(pdu(CB_CLIP_CAPS, 0, caps.toByteArray()))
                isReady = true
                announceLocal()
            }
            CB_FORMAT_LIST -> onFormatList(body)
            CB_FORMAT_DATA_REQUEST -> onFormatDataRequest(body)
            CB_FORMAT_DATA_RESPONSE -> onFormatDataResponse(flags, body)
            CB_FILECONTENTS_REQUEST -> onFileContentsRequest(body)
            CB_FILECONTENTS_RESPONSE -> onFileContentsResponse(flags, body)
        }
    }

    private fun onFormatList(body: ByteArray) {
        send(pdu(CB_FORMAT_LIST_RESPONSE, CB_RESPONSE_OK, ByteArray(0)))
        val formats = parseFormats(body)
        val fileFormat = if (filesActive) formats.entries.firstOrNull { it.value == FileDescriptors.FORMAT_NAME }?.key else null
        when {
            fileFormat != null -> {
                pending = Pending.FILES
                send(pdu(CB_FORMAT_DATA_REQUEST, 0, ByteWriter().u32le(fileFormat).toByteArray()))
            }
            textEnabled && formats.containsKey(CF_UNICODETEXT) -> {
                pending = Pending.TEXT
                resetRemoteFiles()
                send(pdu(CB_FORMAT_DATA_REQUEST, 0, ByteWriter().u32le(CF_UNICODETEXT).toByteArray()))
            }
            else -> {
                pending = Pending.NONE
                resetRemoteFiles()
            }
        }
    }

    private fun onFormatDataRequest(body: ByteArray) {
        val id = if (body.size >= 4) ByteReader(body).u32le().toInt() else 0
        val text = localText
        val files = localFiles
        when {
            id == CF_UNICODETEXT && textEnabled && text != null && files == null -> {
                val crlf = text.replace("\r\n", "\n").replace("\n", "\r\n")
                val data = ByteWriter().utf16(crlf).u16le(0).toByteArray()
                send(pdu(CB_FORMAT_DATA_RESPONSE, CB_RESPONSE_OK, data))
            }
            id == ID_FILE_GROUP_DESCRIPTOR && files != null -> {
                send(pdu(CB_FORMAT_DATA_RESPONSE, CB_RESPONSE_OK, FileDescriptors.encode(files)))
            }
            else -> send(pdu(CB_FORMAT_DATA_RESPONSE, CB_RESPONSE_FAIL, ByteArray(0)))
        }
    }

    private fun onFormatDataResponse(flags: Int, body: ByteArray) {
        // Ответ без нашего запроса (список форматов не приходил) считаем текстом, как и раньше.
        val kind = if (pending == Pending.NONE && textEnabled) Pending.TEXT else pending
        pending = Pending.NONE
        if (flags and CB_RESPONSE_OK == 0) return
        when (kind) {
            Pending.TEXT -> {
                var end = body.size - (body.size % 2)
                while (end >= 2 && body[end - 2].toInt() == 0 && body[end - 1].toInt() == 0) end -= 2
                val text = String(body, 0, end, Charsets.UTF_16LE).replace("\r\n", "\n")
                if (text.isNotEmpty()) onRemoteText(text)
            }
            Pending.FILES -> {
                val files = try {
                    FileDescriptors.decode(body)
                } catch (_: RdpException) {
                    emptyList()
                }
                remoteHasFiles = files.isNotEmpty()
                onRemoteFiles(files.takeIf { it.isNotEmpty() })
            }
            Pending.NONE -> Unit
        }
    }

    private fun resetRemoteFiles() {
        if (!remoteHasFiles) return
        remoteHasFiles = false
        onRemoteFiles(null)
    }

    // ------------------------------------------------------------------ содержимое файлов

    /** Запрос ПК на размер или часть файла планшета; ответ формируется вне потока чтения сети. */
    private fun onFileContentsRequest(body: ByteArray) {
        if (body.size < 24) return
        val r = ByteReader(body)
        val streamId = r.u32le().toInt()
        val index = r.u32le().toInt()
        val flags = r.u32le().toInt()
        val low = r.u32le()
        val high = r.u32le()
        val wanted = r.u32le()
        background {
            val answer = try {
                localContents(index, flags, (high shl 32) or low, wanted)
            } catch (_: Exception) {
                null
            }
            if (answer == null) {
                send(pdu(CB_FILECONTENTS_RESPONSE, CB_RESPONSE_FAIL, ByteWriter().u32le(streamId).toByteArray()))
            } else {
                send(pdu(CB_FILECONTENTS_RESPONSE, CB_RESPONSE_OK, ByteWriter(answer.size + 4).u32le(streamId).bytes(answer).toByteArray()))
            }
        }
    }

    private fun localContents(index: Int, flags: Int, position: Long, wanted: Long): ByteArray? {
        val file = localFiles?.getOrNull(index) ?: return null
        if (flags and FILECONTENTS_SIZE != 0) return ByteWriter(8).u64le(if (file.isDirectory) 0L else file.size).toByteArray()
        if (flags and FILECONTENTS_RANGE == 0 || file.isDirectory || position < 0) return null
        val reader = synchronized(readers) { readers.getOrPut(index) { file.open() ?: return null } }
        return reader.read(position, minOf(wanted, MAX_RANGE.toLong()).toInt())
    }

    private fun onFileContentsResponse(flags: Int, body: ByteArray) {
        if (body.size < 4) return
        val id = ByteReader(body).u32le().toInt()
        val queue = waiters[id] ?: return
        queue.offer(if (flags and CB_RESPONSE_OK != 0) body.copyOfRange(4, body.size) else FAILED)
    }

    private fun dropLocalFiles() {
        localFiles = null
        val open = synchronized(readers) { readers.values.toList().also { readers.clear() } }
        if (open.isNotEmpty()) background { open.forEach { runCatching { it.close() } } }
    }

    /** Форматы списка: идентификатор и имя (имя есть только в «длинном» виде; в «коротком» оно не нужно). */
    private fun parseFormats(body: ByteArray): Map<Int, String> {
        val formats = LinkedHashMap<Int, String>()
        val r = ByteReader(body)
        if (serverLongNames) {
            while (r.remaining >= 6) {
                val id = r.u32le().toInt()
                val name = StringBuilder()
                while (r.remaining >= 2) {
                    val ch = r.u16le()
                    if (ch == 0) break
                    name.append(ch.toChar())
                }
                formats[id] = name.toString()
            }
        } else {
            while (r.remaining >= 36) {
                formats[r.u32le().toInt()] = ""
                r.skip(32)
            }
        }
        return formats
    }

    private fun pdu(type: Int, flags: Int, body: ByteArray): ByteArray =
        ByteWriter(8 + body.size).u16le(type).u16le(flags).u32le(body.size).bytes(body).toByteArray()

    companion object {
        const val CHANNEL_NAME = "cliprdr"
        const val CHANNEL_FLAG_FIRST = 0x01
        const val CHANNEL_FLAG_LAST = 0x02

        private const val CB_MONITOR_READY = 1
        private const val CB_FORMAT_LIST = 2
        private const val CB_FORMAT_LIST_RESPONSE = 3
        private const val CB_FORMAT_DATA_REQUEST = 4
        private const val CB_FORMAT_DATA_RESPONSE = 5
        private const val CB_CLIP_CAPS = 7
        private const val CB_FILECONTENTS_REQUEST = 8
        private const val CB_FILECONTENTS_RESPONSE = 9

        private const val CB_RESPONSE_OK = 1
        private const val CB_RESPONSE_FAIL = 2
        private const val CB_USE_LONG_FORMAT_NAMES = 2
        private const val CB_STREAM_FILECLIP_ENABLED = 4
        private const val CB_FILECLIP_NO_FILE_PATHS = 8
        private const val CB_HUGE_FILE_SUPPORT_ENABLED = 0x20
        private const val CF_UNICODETEXT = 13

        private const val FILECONTENTS_SIZE = 1
        private const val FILECONTENTS_RANGE = 2

        /** Собственные идентификаторы форматов (диапазон «зарегистрированных» форматов начинается с 0xC000). */
        private const val ID_FILE_GROUP_DESCRIPTOR = 0xC0F0
        private const val ID_FILE_CONTENTS = 0xC0F1

        private const val MAX_RANGE = 4 * 1024 * 1024
        private const val CONTENTS_TIMEOUT_SECONDS = 30L
        private val FAILED = ByteArray(0)
    }
}
