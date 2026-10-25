package com.hiweb.hiwebremotedesk.debug

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Провайдер документов поверх папки кэша для проверки SAF-хранилища на устройстве (см. SafDriveStorageTest).
 * Ведёт себя как системный: идентификатор документа меняется при переименовании и перемещении,
 * а при совпадении имени создаваемому документу добавляется « (1)».
 */
class TestDocumentsProvider : DocumentsProvider() {
    private lateinit var base: File

    override fun onCreate(): Boolean {
        base = File(context!!.cacheDir, DIR_NAME).apply { mkdirs() }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(android.provider.DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_PROJECTION).also { addRow(it, file(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_PROJECTION)
        file(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { addRow(cursor, it) }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentDocumentId)
        var target = File(parent, displayName)
        var n = 1
        while (target.exists()) target = File(parent, "$displayName (${n++})")
        val ok = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!ok) throw FileNotFoundException("Не удалось создать $displayName")
        return idOf(target)
    }

    override fun deleteDocument(documentId: String) {
        if (!file(documentId).delete()) throw FileNotFoundException("Не удалось удалить $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = file(documentId)
        val target = File(source.parentFile, displayName)
        if (target.exists() || !source.renameTo(target)) throw FileNotFoundException("Не удалось переименовать $documentId")
        return idOf(target)
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val source = file(sourceDocumentId)
        val target = File(file(targetParentDocumentId), source.name)
        if (target.exists() || !source.renameTo(target)) throw FileNotFoundException("Не удалось переместить $sourceDocumentId")
        return idOf(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    private fun file(documentId: String): File {
        val f = if (documentId == ROOT_ID) base else File(base, documentId.removePrefix("$ROOT_ID/"))
        if (!f.exists()) throw FileNotFoundException("Нет документа $documentId")
        return f
    }

    private fun idOf(f: File): String =
        if (f == base) ROOT_ID else ROOT_ID + "/" + f.relativeTo(base).path.replace(File.separatorChar, '/')

    private fun addRow(cursor: MatrixCursor, f: File) {
        val dir = f.isDirectory
        val flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE or
            if (dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        val mime = if (dir) Document.MIME_TYPE_DIR else
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(f))
            .add(Document.COLUMN_DISPLAY_NAME, if (f == base) "root" else f.name)
            .add(Document.COLUMN_MIME_TYPE, mime)
            .add(Document.COLUMN_SIZE, if (dir) null else f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    companion object {
        const val AUTHORITY = "com.hiweb.hiwebremotedesk.testdocs"
        const val ROOT_ID = "root"
        const val DIR_NAME = "saf-test"

        private val DEFAULT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
