package com.hiweb.hiwebremotedesk.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.hiweb.hiwebremotedesk.rdp.LocalFile
import com.hiweb.hiwebremotedesk.rdp.RangeReader

/** Превращает выбранные пользователем документы и папки в список файлов, предлагаемых ПК через буфер обмена. */
object LocalFiles {
    /** Предел числа объектов в одной отправке: список передаётся целиком. */
    const val MAX_ENTRIES = 5000
    private const val MAX_DEPTH = 24

    fun fromDocuments(context: Context, uris: List<Uri>): List<LocalFile> {
        val resolver = context.applicationContext.contentResolver
        val out = ArrayList<LocalFile>()
        for (uri in uris) {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val modified = if (DocumentsContract.isDocumentUri(context, uri)) {
                runCatching {
                    resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
                    }
                }.getOrNull() ?: 0L
            } else {
                0L
            }
            val open = { readerFor { resolver.openFileDescriptor(uri, "r") } }
            if (size < 0) size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
            out += LocalFile(name, false, size, modified, open)
        }
        return out
    }

    /** Папка целиком: сама папка, затем всё её содержимое с относительными путями. */
    fun fromTree(context: Context, treeUri: Uri): List<LocalFile> {
        val tree = SafTree(context, treeUri)
        val top = tree.displayName()?.ifBlank { null } ?: "Folder"
        val out = ArrayList<LocalFile>()
        out += LocalFile(top, true, 0L, 0L)
        fun walk(docId: String, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (child in tree.children(docId)) {
                if (out.size >= MAX_ENTRIES) return
                val path = "$prefix/${child.name}"
                if (child.isDirectory) {
                    out += LocalFile(path, true, 0L, child.modifiedMs)
                    walk(child.docId, path, depth + 1)
                } else {
                    out += LocalFile(path, false, child.size, child.modifiedMs) { readerFor { tree.openDescriptor(child.docId, "r") } }
                }
            }
        }
        walk(tree.rootId, top, 0)
        return out
    }

    private inline fun readerFor(open: () -> android.os.ParcelFileDescriptor?): RangeReader? =
        try {
            open()?.let { DescriptorReader(it) }
        } catch (_: Exception) {
            null
        }
}
