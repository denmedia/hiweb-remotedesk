package com.hiweb.hiwebremotedesk.data

import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import com.hiweb.hiwebremotedesk.rdp.DriveException
import com.hiweb.hiwebremotedesk.rdp.DriveStorage
import com.hiweb.hiwebremotedesk.rdp.NtStatus
import java.io.IOException

/**
 * Диск для Windows поверх папки, выбранной через Storage Access Framework. Права на папку сохраняются
 * системой, поэтому дополнительные разрешения на доступ к памяти не нужны.
 *
 * Содержимое папок кэшируется на пару секунд: Проводник запрашивает одни и те же каталоги десятки раз подряд.
 */
class SafDriveStorage(private val tree: SafTree, override val readOnly: Boolean) : DriveStorage {
    constructor(context: Context, treeUri: android.net.Uri, readOnly: Boolean) : this(SafTree(context, treeUri), readOnly)

    private val root = SafNode(tree.rootId, "", true, 0L, 0L, 0)
    private val cache = HashMap<String, Pair<Long, List<SafNode>>>()

    override fun stat(path: List<String>): DriveStorage.Entry? = resolve(path)?.entry()

    override fun list(path: List<String>): List<DriveStorage.Entry> {
        val node = resolve(path) ?: throw DriveException(NtStatus.OBJECT_NAME_NOT_FOUND)
        if (!node.isDirectory) throw DriveException(NtStatus.NOT_A_DIRECTORY)
        return children(node.docId).map { it.entry() }
    }

    override fun open(path: List<String>, write: Boolean, truncate: Boolean): DriveStorage.Handle {
        val node = resolve(path) ?: throw DriveException(NtStatus.OBJECT_NAME_NOT_FOUND)
        if (node.isDirectory) throw DriveException(NtStatus.FILE_IS_A_DIRECTORY)
        val mode = if (truncate) "rwt" else if (write) "rw" else "r"
        val pfd = try {
            tree.openDescriptor(node.docId, mode)
        } catch (e: SecurityException) {
            throw DriveException(NtStatus.ACCESS_DENIED)
        } catch (e: IOException) {
            throw DriveException(NtStatus.UNSUCCESSFUL)
        }
        if (truncate) invalidate()
        return DescriptorHandle(pfd, write)
    }

    override fun createFile(path: List<String>) = create(path, directory = false)

    override fun createDirectory(path: List<String>) = create(path, directory = true)

    override fun delete(path: List<String>) {
        val node = resolve(path) ?: throw DriveException(NtStatus.OBJECT_NAME_NOT_FOUND)
        if (node === root) throw DriveException(NtStatus.ACCESS_DENIED)
        if (node.isDirectory && children(node.docId).isNotEmpty()) throw DriveException(NtStatus.DIRECTORY_NOT_EMPTY)
        val ok = try {
            tree.delete(node.docId)
        } catch (e: SecurityException) {
            false
        }
        invalidate()
        if (!ok) throw DriveException(NtStatus.ACCESS_DENIED)
    }

    override fun rename(from: List<String>, to: List<String>, replace: Boolean) {
        val source = resolve(from) ?: throw DriveException(NtStatus.OBJECT_NAME_NOT_FOUND)
        if (source === root || to.isEmpty()) throw DriveException(NtStatus.ACCESS_DENIED)
        val target = resolve(to)
        if (target != null && target.docId != source.docId) {
            if (!replace) throw DriveException(NtStatus.OBJECT_NAME_COLLISION)
            if (target.isDirectory) throw DriveException(NtStatus.ACCESS_DENIED)
            if (!tree.delete(target.docId)) throw DriveException(NtStatus.ACCESS_DENIED)
            invalidate()
        }
        val sourceParent = resolve(from.dropLast(1)) ?: throw DriveException(NtStatus.OBJECT_PATH_NOT_FOUND)
        val targetParent = resolve(to.dropLast(1))?.takeIf { it.isDirectory } ?: throw DriveException(NtStatus.OBJECT_PATH_NOT_FOUND)
        var id = source.docId
        if (sourceParent.docId != targetParent.docId) {
            if (source.flags and Document.FLAG_SUPPORTS_MOVE == 0) throw DriveException(NtStatus.NOT_SUPPORTED)
            id = tree.move(id, sourceParent.docId, targetParent.docId) ?: throw DriveException(NtStatus.ACCESS_DENIED)
        }
        if (source.name != to.last()) id = tree.rename(id, to.last()) ?: throw DriveException(NtStatus.ACCESS_DENIED)
        invalidate()
    }

    override fun space(): Pair<Long, Long> = try {
        val stat = StatFs(@Suppress("DEPRECATION") Environment.getExternalStorageDirectory().path)
        stat.blockCountLong * stat.blockSizeLong to stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: Exception) {
        // Объём неизвестен; нулевой размер диска заставил бы Проводник отказывать в копировании.
        UNKNOWN_SPACE
    }

    // ------------------------------------------------------------------ дерево

    private fun SafNode.entry() = DriveStorage.Entry(name, isDirectory, size, modifiedMs)

    private fun resolve(path: List<String>): SafNode? {
        var node = root
        for (name in path) {
            if (!node.isDirectory) return null
            node = DriveStorage.findByName(children(node.docId), name) { it.name } ?: return null
        }
        return node
    }

    private fun children(docId: String): List<SafNode> {
        val now = SystemClock.elapsedRealtime()
        synchronized(cache) {
            cache[docId]?.let { (time, list) -> if (now - time < CACHE_MS) return list }
        }
        val list = try {
            tree.children(docId)
        } catch (e: SecurityException) {
            throw DriveException(NtStatus.ACCESS_DENIED)
        } catch (e: IOException) {
            throw DriveException(NtStatus.UNSUCCESSFUL)
        }
        synchronized(cache) { cache[docId] = now to list }
        return list
    }

    private fun invalidate() = synchronized(cache) { cache.clear() }

    private fun create(path: List<String>, directory: Boolean) {
        if (path.isEmpty()) throw DriveException(NtStatus.ACCESS_DENIED)
        val parent = resolve(path.dropLast(1))?.takeIf { it.isDirectory } ?: throw DriveException(NtStatus.OBJECT_PATH_NOT_FOUND)
        val created = try {
            tree.create(parent.docId, path.last(), directory)
        } catch (e: SecurityException) {
            null
        }
        invalidate()
        if (created == null) throw DriveException(NtStatus.ACCESS_DENIED)
    }

    /** Файл провайдера документов: чтение и запись по смещению через дескриптор. */
    private inner class DescriptorHandle(private val pfd: ParcelFileDescriptor, private val writable: Boolean) : DriveStorage.Handle {
        private val reader = DescriptorReader(pfd)

        override fun read(position: Long, length: Int): ByteArray = reader.read(position, length)

        override fun write(position: Long, data: ByteArray) {
            try {
                var done = 0
                while (done < data.size) {
                    val n = Os.pwrite(pfd.fileDescriptor, data, done, data.size - done, position + done)
                    if (n <= 0) throw IOException("Запись не удалась")
                    done += n
                }
            } catch (e: ErrnoException) {
                throw IOException(e.message, e)
            }
        }

        override fun size(): Long = try {
            Os.fstat(pfd.fileDescriptor).st_size
        } catch (e: ErrnoException) {
            throw IOException(e.message, e)
        }

        override fun setSize(size: Long) {
            try {
                Os.ftruncate(pfd.fileDescriptor, size)
            } catch (e: ErrnoException) {
                throw IOException(e.message, e)
            }
        }

        override fun close() {
            reader.close()
            if (writable) invalidate()
        }
    }

    private companion object {
        const val CACHE_MS = 2000L
        val UNKNOWN_SPACE = (64L shl 30) to (32L shl 30)
    }
}
