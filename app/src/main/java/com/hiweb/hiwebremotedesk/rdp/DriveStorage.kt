package com.hiweb.hiwebremotedesk.rdp

/** Коды NTSTATUS, которые канал диска возвращает серверу. */
internal object NtStatus {
    const val SUCCESS = 0x00000000L
    const val NO_MORE_FILES = 0x80000006L
    const val UNSUCCESSFUL = 0xC0000001L
    const val INVALID_HANDLE = 0xC0000008L
    const val INVALID_PARAMETER = 0xC000000DL
    const val NO_SUCH_DEVICE = 0xC000000EL
    const val NO_SUCH_FILE = 0xC000000FL
    const val ACCESS_DENIED = 0xC0000022L
    const val OBJECT_NAME_NOT_FOUND = 0xC0000034L
    const val OBJECT_NAME_COLLISION = 0xC0000035L
    const val OBJECT_PATH_NOT_FOUND = 0xC000003AL
    const val DISK_FULL = 0xC000007FL
    const val NOT_SUPPORTED = 0xC00000BBL
    const val FILE_IS_A_DIRECTORY = 0xC00000BAL
    const val DIRECTORY_NOT_EMPTY = 0xC0000101L
    const val NOT_A_DIRECTORY = 0xC0000103L
}

/** Ошибка операции с диском; [status] уходит серверу как результат запроса. */
class DriveException(internal val status: Long, message: String? = null) : Exception(message ?: "NTSTATUS 0x${status.toString(16)}")

/**
 * Папка устройства, видимая на ПК как диск. Пути — списки имён от корня (пустой список — сам корень).
 * Все методы синхронные и вызываются из одного потока; ошибки сообщаются через [DriveException].
 */
interface DriveStorage {
    class Entry(val name: String, val isDirectory: Boolean, val size: Long, val modifiedMs: Long)

    /** Открытый файл с произвольным доступом. */
    interface Handle {
        fun read(position: Long, length: Int): ByteArray
        fun write(position: Long, data: ByteArray)
        fun size(): Long
        fun setSize(size: Long)
        fun close()
    }

    val readOnly: Boolean

    /** Описание объекта или null, если его нет. Имена сравниваются без учёта регистра, как в Windows. */
    fun stat(path: List<String>): Entry?

    fun list(path: List<String>): List<Entry>

    fun open(path: List<String>, write: Boolean, truncate: Boolean): Handle

    /** Создаёт пустой файл; родительская папка должна существовать. */
    fun createFile(path: List<String>)

    fun createDirectory(path: List<String>)

    /** Удаляет файл или пустую папку. */
    fun delete(path: List<String>)

    fun rename(from: List<String>, to: List<String>, replace: Boolean)

    /** Общий и свободный объём в байтах; нули, если неизвестно. */
    fun space(): Pair<Long, Long>

    companion object {
        /** Ищет имя без учёта регистра, предпочитая точное совпадение. */
        fun <T> findByName(items: List<T>, name: String, nameOf: (T) -> String): T? =
            items.firstOrNull { nameOf(it) == name } ?: items.firstOrNull { nameOf(it).equals(name, ignoreCase = true) }
    }
}
