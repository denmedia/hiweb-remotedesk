package com.hiweb.hiwebremotedesk.rdp

/**
 * Файл или папка, которую ПК предлагает через буфер обмена. [index] — номер в списке сервера (по нему запрашивается
 * содержимое), [path] — относительный путь с разделителем '/', [size] — размер или -1, если сервер его не сообщил.
 */
class RemoteFile(val index: Int, val path: String, val isDirectory: Boolean, val size: Long, val modifiedMs: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/** Чтение диапазонов локального файла, который планшет отдаёт ПК. */
interface RangeReader {
    /** Возвращает не более [length] байт начиная с [position]; пустой массив — конец файла. */
    fun read(position: Long, length: Int): ByteArray

    fun close()
}

/** Файл или папка планшета, предлагаемые ПК через буфер обмена. */
class LocalFile(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedMs: Long,
    /** Открывает файл для чтения; у папок не вызывается. */
    val open: () -> RangeReader? = { null },
)

/** Формат FILEDESCRIPTORW из MS-RDPECLIP (список файлов в формате «FileGroupDescriptorW»). */
internal object FileDescriptors {
    const val FORMAT_NAME = "FileGroupDescriptorW"
    const val CONTENTS_FORMAT_NAME = "FileContents"

    private const val ENTRY_SIZE = 592
    private const val NAME_BYTES = 520
    private const val MAX_ENTRIES = 20_000

    private const val FD_ATTRIBUTES = 0x0004
    private const val FD_WRITESTIME = 0x0020
    private const val FD_FILESIZE = 0x0040
    private const val FD_PROGRESSUI = 0x4000

    private const val ATTR_DIRECTORY = 0x10
    private const val ATTR_NORMAL = 0x80

    private const val FILETIME_UNIX_EPOCH = 116_444_736_000_000_000L

    fun encode(files: List<LocalFile>): ByteArray {
        val w = ByteWriter(4 + files.size * ENTRY_SIZE)
        w.u32le(files.size)
        for (f in files) {
            w.u32le(FD_ATTRIBUTES or FD_WRITESTIME or FD_FILESIZE or FD_PROGRESSUI)
            w.zeros(32)
            w.u32le(if (f.isDirectory) ATTR_DIRECTORY else ATTR_NORMAL)
            w.zeros(16)
            w.u64le(if (f.modifiedMs > 0) f.modifiedMs * 10_000L + FILETIME_UNIX_EPOCH else 0L)
            val size = if (f.isDirectory) 0L else f.size.coerceAtLeast(0L)
            w.u32le((size ushr 32).toInt()).u32le(size.toInt())
            val name = f.path.replace('/', '\\').take(NAME_BYTES / 2 - 1).toByteArray(Charsets.UTF_16LE)
            w.bytes(name).zeros(NAME_BYTES - name.size)
        }
        return w.toByteArray()
    }

    /** Разбирает список файлов от ПК; записи с небезопасными путями пропускаются. Неизвестный размер — -1. */
    fun decode(data: ByteArray): List<RemoteFile> {
        if (data.size < 4) return emptyList()
        val r = ByteReader(data)
        val count = r.u32le()
        if (count > MAX_ENTRIES || count * ENTRY_SIZE > r.remaining) throw RdpException("Некорректный список файлов")
        val out = ArrayList<RemoteFile>(count.toInt())
        repeat(count.toInt()) { index ->
            val flags = r.u32le().toInt()
            r.skip(32)
            val attributes = r.u32le().toInt()
            r.skip(16)
            val written = r.u64le()
            val high = r.u32le()
            val low = r.u32le()
            val rawName = r.bytes(NAME_BYTES)
            var end = 0
            while (end + 1 < NAME_BYTES && (rawName[end].toInt() != 0 || rawName[end + 1].toInt() != 0)) end += 2
            val path = sanitizePath(String(rawName, 0, end, Charsets.UTF_16LE)) ?: return@repeat
            val isDir = flags and FD_ATTRIBUTES != 0 && attributes and ATTR_DIRECTORY != 0
            val size = if (isDir) 0L else if (flags and FD_FILESIZE != 0) (high shl 32) or low else -1L
            val modified = if (flags and FD_WRITESTIME != 0 && written > FILETIME_UNIX_EPOCH) (written - FILETIME_UNIX_EPOCH) / 10_000L else 0L
            out += RemoteFile(index, path, isDir, size, modified)
        }
        return out
    }

    /** Приводит путь от ПК к безопасному относительному виду; null, если путь выходит за пределы папки назначения. */
    fun sanitizePath(raw: String): String? {
        val parts = ArrayList<String>()
        for (segment in raw.replace('\\', '/').split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") return null
            val clean = segment.map { if (it < ' ' || it in ":*?\"<>|") '_' else it }.joinToString("").trimEnd('.', ' ')
            if (clean.isNotEmpty()) parts += clean
        }
        return if (parts.isEmpty()) null else parts.joinToString("/")
    }
}
