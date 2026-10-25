package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.DriveException
import com.hiweb.hiwebremotedesk.rdp.DriveStorage
import java.io.File
import java.io.RandomAccessFile

/** Диск поверх обычной папки: для проверки канала rdpdr без устройства. Поиск имён без учёта регистра, как в SAF-хранилище. */
class FileDriveStorage(private val root: File, override val readOnly: Boolean = false) : DriveStorage {
    private fun find(path: List<String>): File? {
        var file = root
        for (name in path) {
            if (!file.isDirectory) return null
            val children = file.listFiles()?.toList().orEmpty()
            file = DriveStorage.findByName(children, name) { it.name } ?: return null
        }
        return file
    }

    private fun entry(f: File, name: String = f.name) = DriveStorage.Entry(name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified())

    override fun stat(path: List<String>) = find(path)?.let { entry(it, if (path.isEmpty()) "" else it.name) }

    override fun list(path: List<String>): List<DriveStorage.Entry> =
        (find(path) ?: throw DriveException(0xC0000034L)).listFiles()?.map { entry(it) }.orEmpty()

    override fun open(path: List<String>, write: Boolean, truncate: Boolean): DriveStorage.Handle {
        val file = find(path) ?: throw DriveException(0xC0000034L)
        val raf = RandomAccessFile(file, if (write) "rw" else "r")
        if (truncate) raf.setLength(0)
        return object : DriveStorage.Handle {
            override fun read(position: Long, length: Int): ByteArray {
                val size = raf.length()
                if (position >= size) return ByteArray(0)
                val buf = ByteArray(minOf(length.toLong(), size - position).toInt())
                raf.seek(position)
                raf.readFully(buf)
                return buf
            }

            override fun write(position: Long, data: ByteArray) {
                raf.seek(position)
                raf.write(data)
            }

            override fun size() = raf.length()
            override fun setSize(size: Long) = raf.setLength(size)
            override fun close() = raf.close()
        }
    }

    override fun createFile(path: List<String>) {
        val parent = find(path.dropLast(1)) ?: throw DriveException(0xC000003AL)
        File(parent, path.last()).createNewFile()
    }

    override fun createDirectory(path: List<String>) {
        val parent = find(path.dropLast(1)) ?: throw DriveException(0xC000003AL)
        File(parent, path.last()).mkdir()
    }

    override fun delete(path: List<String>) {
        val file = find(path) ?: throw DriveException(0xC0000034L)
        if (!file.delete()) throw DriveException(0xC0000101L)
    }

    override fun rename(from: List<String>, to: List<String>, replace: Boolean) {
        val source = find(from) ?: throw DriveException(0xC0000034L)
        val parent = find(to.dropLast(1)) ?: throw DriveException(0xC000003AL)
        val target = File(parent, to.last())
        if (target.exists() && !replace) throw DriveException(0xC0000035L)
        if (!source.renameTo(target)) {
            target.delete()
            if (!source.renameTo(target)) throw DriveException(0xC0000022L)
        }
    }

    override fun space() = (1L shl 40) to (1L shl 39)
}
