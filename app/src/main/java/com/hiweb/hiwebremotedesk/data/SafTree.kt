package com.hiweb.hiwebremotedesk.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.webkit.MimeTypeMap
import com.hiweb.hiwebremotedesk.rdp.RangeReader
import java.io.IOException

/** Узел дерева документов Storage Access Framework. */
class SafNode(val docId: String, val name: String, val isDirectory: Boolean, val size: Long, val modifiedMs: Long, val flags: Int)

/** Обращения к дереву документов, которое пользователь выбрал через системный выбор папки. */
class SafTree(context: Context, val tree: Uri) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    fun uri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    /** Содержимое папки; бросает SecurityException, если доступ к папке отозван. */
    fun children(parentId: String): List<SafNode> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
        val out = ArrayList<SafNode>()
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw IOException("Папка недоступна")
        cursor.use { c ->
            while (c.moveToNext()) {
                out += SafNode(
                    docId = c.getString(0),
                    name = c.getString(1) ?: continue,
                    isDirectory = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                    modifiedMs = if (c.isNull(4)) 0L else c.getLong(4),
                    flags = if (c.isNull(5)) 0 else c.getInt(5),
                )
            }
        }
        return out
    }

    fun openDescriptor(docId: String, mode: String): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri(docId), mode) ?: throw IOException("Не удалось открыть файл")

    fun create(parentId: String, name: String, directory: Boolean): String? {
        val mime = if (directory) Document.MIME_TYPE_DIR else mimeFor(name)
        val created = DocumentsContract.createDocument(resolver, uri(parentId), mime, name) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    fun delete(docId: String): Boolean = DocumentsContract.deleteDocument(resolver, uri(docId))

    /** Переименовывает документ; возвращает его новый идентификатор или null при отказе. */
    fun rename(docId: String, newName: String): String? =
        DocumentsContract.renameDocument(resolver, uri(docId), newName)?.let { DocumentsContract.getDocumentId(it) }

    fun move(docId: String, fromParentId: String, toParentId: String): String? =
        DocumentsContract.moveDocument(resolver, uri(docId), uri(fromParentId), uri(toParentId))
            ?.let { DocumentsContract.getDocumentId(it) }

    /** Отображаемое имя выбранной папки. */
    fun displayName(): String? = runCatching {
        resolver.query(uri(rootId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** Папка доступна: права на неё сохранены и она открывается. */
    fun isAccessible(): Boolean = try {
        children(rootId)
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        /** MIME по расширению: провайдер иначе может дописать к имени своё расширение. */
        fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}

/** Чтение по смещению из файлового дескриптора (pread): работает и для файлов, отдаваемых провайдерами документов. */
internal class DescriptorReader(private val pfd: ParcelFileDescriptor) : RangeReader {
    override fun read(position: Long, length: Int): ByteArray {
        val fd = pfd.fileDescriptor
        try {
            val remaining = Os.fstat(fd).st_size - position
            if (remaining <= 0 || length <= 0) return ByteArray(0)
            val want = minOf(length.toLong(), remaining).toInt()
            val buf = ByteArray(want)
            var got = 0
            while (got < want) {
                val n = Os.pread(fd, buf, got, want - got, position + got)
                if (n <= 0) break
                got += n
            }
            return if (got == want) buf else buf.copyOf(got)
        } catch (e: ErrnoException) {
            throw IOException(e.message, e)
        }
    }

    override fun close() {
        runCatching { pfd.close() }
    }
}
