package com.hiweb.hiwebremotedesk.rdp

/** Диск только для чтения из нескольких файлов в памяти (без папок): так ПК получает установщик агента. */
class MemoryDriveStorage(private val files: Map<String, ByteArray>) : DriveStorage {
    private val modifiedMs = System.currentTimeMillis()

    override val readOnly: Boolean get() = true

    private fun find(name: String): Map.Entry<String, ByteArray>? = DriveStorage.findByName(files.entries.toList(), name) { it.key }

    override fun stat(path: List<String>): DriveStorage.Entry? = when (path.size) {
        0 -> DriveStorage.Entry("", true, 0, modifiedMs)
        1 -> find(path[0])?.let { DriveStorage.Entry(it.key, false, it.value.size.toLong(), modifiedMs) }
        else -> null
    }

    override fun list(path: List<String>): List<DriveStorage.Entry> {
        if (path.isNotEmpty()) throw DriveException(NtStatus.OBJECT_PATH_NOT_FOUND)
        return files.map { (name, data) -> DriveStorage.Entry(name, false, data.size.toLong(), modifiedMs) }
    }

    override fun open(path: List<String>, write: Boolean, truncate: Boolean): DriveStorage.Handle {
        if (write || truncate) throw DriveException(NtStatus.ACCESS_DENIED)
        val data = (if (path.size == 1) find(path[0])?.value else null) ?: throw DriveException(NtStatus.OBJECT_NAME_NOT_FOUND)
        return object : DriveStorage.Handle {
            override fun read(position: Long, length: Int): ByteArray {
                if (position >= data.size) return ByteArray(0)
                val from = position.toInt()
                return data.copyOfRange(from, minOf(data.size, from + length))
            }

            override fun write(position: Long, data: ByteArray) = throw DriveException(NtStatus.ACCESS_DENIED)
            override fun size(): Long = data.size.toLong()
            override fun setSize(size: Long) = throw DriveException(NtStatus.ACCESS_DENIED)
            override fun close() {}
        }
    }

    override fun createFile(path: List<String>) = throw DriveException(NtStatus.ACCESS_DENIED)
    override fun createDirectory(path: List<String>) = throw DriveException(NtStatus.ACCESS_DENIED)
    override fun delete(path: List<String>) = throw DriveException(NtStatus.ACCESS_DENIED)
    override fun rename(from: List<String>, to: List<String>, replace: Boolean) = throw DriveException(NtStatus.ACCESS_DENIED)
    override fun space(): Pair<Long, Long> = files.values.sumOf { it.size.toLong() } to 0L
}
