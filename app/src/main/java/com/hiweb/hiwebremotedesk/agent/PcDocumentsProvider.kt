package com.hiweb.hiwebremotedesk.agent

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import com.hiweb.hiwebremotedesk.R
import com.hiweb.hiwebremotedesk.data.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Папки ПК, открытые агентом, как «диски» в системном файловом менеджере и в выборе файлов любого приложения (Storage
 * Access Framework): по одному корню на папку. Файлы идут по связи с агентом ([AgentHub.fsCall]), поэтому работает и без
 * трансляции экрана — нужен только агент на связи. Режим каждой папки (только чтение или запись) задаёт агент на ПК:
 * здесь он лишь передаётся системе флагами, а запись, не разрешённую агентом, всё равно отклонил бы сам агент.
 *
 * Что не поддерживается: перемещение и копирование внутри ПК (система делает их чтением и записью через планшет),
 * поиск, миниатюры, скрытые и системные файлы Windows (в списках не показываются).
 *
 * Блокирующие вызовы системы идут в её потоках: ожидание ответа ПК — до 30 с, затем ошибка «ПК не ответил».
 */
class PcDocumentsProvider : DocumentsProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listings = ListingCache<List<FsEntry>>()
    private val stats = ListingCache<FsEntry>()
    @Volatile private var lastRoots: List<String> = emptyList()

    private val authority: String get() = authority(requireNotNull(context))

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        // Список «дисков» зависит от того, какие агенты на связи и что они открыли: система узнаёт об изменениях сама.
        scope.launch {
            AgentHub.presence.collect { presence ->
                val roots = rootsNow(ctx, presence).map { "${it.doc.rootId}|${it.title}|${it.write}" }
                if (roots != lastRoots) {
                    lastRoots = roots
                    ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
                }
            }
        }
        // Приложение могли поднять ради этого вызова: связь с агентами держит служба, без неё «дисков» нет.
        SwitchListenerService.startIfPaired(ctx)
        runCatching { stagingDir(ctx).listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > DAY_MS }?.forEach { it.delete() } }
        return true
    }

    // ------------------------------------------------------------------ корни и описание документов

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val ctx = requireNotNull(context)
        val result = MatrixCursor(projection ?: ROOT_PROJECTION)
        for (r in rootsNow(ctx, AgentHub.presence.value)) {
            result.newRow()
                .add(Root.COLUMN_ROOT_ID, r.doc.rootId)
                .add(Root.COLUMN_DOCUMENT_ID, r.doc.id)
                .add(Root.COLUMN_TITLE, r.title)
                .add(Root.COLUMN_SUMMARY, r.summary)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or if (r.write) Root.FLAG_SUPPORTS_CREATE else 0)
                .add(Root.COLUMN_ICON, R.drawable.ic_root_pc)
        }
        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val doc = parse(documentId)
        val (share, pc) = access(doc)
        val entry = if (doc.isRoot) null else guard(notFoundAsFile = true) { statOf(pc, doc) }
        return MatrixCursor(projection ?: DOCUMENT_PROJECTION).also { include(it, doc, entry, share) }
    }

    override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = parse(parentDocumentId)
        val (share, pc) = access(parent)
        val entries = list(pc, parent)
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        for (e in entries) {
            // Скрытые и системные файлы Windows (desktop.ini, папки AppData…) только мешают в списке.
            if (e.hidden) continue
            include(cursor, parent.child(e.name), e, share)
        }
        // Запись на ПК и пересылка с планшета сообщают об изменении папки: файловый менеджер перечитает её сам.
        cursor.setNotificationUri(requireNotNull(context).contentResolver, DocumentsContract.buildChildDocumentsUri(authority, parent.id))
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        val parent = PcDocId.parse(parentDocumentId) ?: return false
        val child = PcDocId.parse(documentId) ?: return false
        return parent.contains(child)
    }

    // ------------------------------------------------------------------ содержимое

    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor {
        val doc = parse(documentId)
        val (share, pc) = access(doc)
        if (mode == null || mode == "r") return openForRead(doc, pc)
        if (!share.write) throw SecurityException("Папка «${share.name}» открыта на ПК только для чтения.")
        return openForWrite(doc, ParcelFileDescriptor.parseMode(mode), pc)
    }

    /**
     * Чтение с произвольным доступом: система получает настоящий файловый дескриптор, а каждое её чтение превращается в
     * запрос куска к ПК ([ReadWindow]). Так видео перематываются, а архивы и PDF открываются без полной загрузки.
     */
    private fun openForRead(doc: PcDocId, pc: PcFiles): ParcelFileDescriptor {
        val entry = guard(notFoundAsFile = true) { statOf(pc, doc) }
        if (entry.dir) throw IllegalArgumentException("Это папка, а не файл.")
        val window = ReadWindow(entry.size) { offset, length -> pc.read(doc.share, doc.path, offset, length).data }
        val thread = HandlerThread("pc-read").apply { start() }
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = entry.size

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                window.read(offset, size, data)
            } catch (e: Exception) {
                Log.w(TAG, "read ${doc.path} @$offset failed: ${e.message}")
                throw ErrnoException("onRead", OsConstants.EIO)
            }

            override fun onRelease() {
                thread.quitSafely()
            }
        }
        val storage = requireNotNull(context).getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
    }

    /**
     * Запись через временный файл на планшете: приложение пишет в него как в обычный, а когда закрывает дескриптор,
     * файл целиком уходит на ПК и заменяет прежний (на ПК он появляется только после последнего куска, поэтому оборванная
     * загрузка не портит старый файл). Ошибку загрузки приложению уже не сообщить — она пишется в журнал.
     */
    private fun openForWrite(doc: PcDocId, flags: Int, pc: PcFiles): ParcelFileDescriptor {
        val ctx = requireNotNull(context)
        val staged = File.createTempFile("up-", ".part", stagingDir(ctx))
        try {
            // Режимы без усечения (rw, wa) правят существующий файл: сначала берём его содержимое.
            if (flags and ParcelFileDescriptor.MODE_TRUNCATE == 0) {
                val existing = runCatching { statOf(pc, doc) }.getOrNull()
                if (existing != null && !existing.dir) download(pc, doc, existing.size, staged)
            }
        } catch (e: Exception) {
            staged.delete()
            throw e
        }
        val thread = HandlerThread("pc-write").apply { start() }
        return ParcelFileDescriptor.open(staged, flags, Handler(thread.looper)) { error ->
            try {
                if (error == null) {
                    staged.inputStream().use { input -> pc.upload(doc.share, doc.path, overwrite = true, size = staged.length(), input = input) }
                    forget(doc)
                } else {
                    Log.w(TAG, "write ${doc.path} closed with error: ${error.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "upload ${doc.path} failed: ${e.message}")
            } finally {
                staged.delete()
                thread.quitSafely()
            }
        }
    }

    private fun download(pc: PcFiles, doc: PcDocId, size: Long, into: File) {
        FileOutputStream(into).use { out ->
            var offset = 0L
            while (offset < size) {
                val chunk = pc.read(doc.share, doc.path, offset, FsLimits.CHUNK)
                if (chunk.data.isEmpty()) break
                out.write(chunk.data)
                offset += chunk.data.size
                if (chunk.eof) break
            }
        }
    }

    // ------------------------------------------------------------------ изменения

    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String? {
        val parent = parse(parentDocumentId)
        val (share, pc) = access(parent)
        if (!share.write) throw SecurityException("Папка «${share.name}» открыта на ПК только для чтения.")
        val wanted = (displayName ?: "").trim().ifEmpty { "Новый файл" }
        val child = guard {
            val unique = PcDocId.uniqueName(wanted, list(pc, parent).map { it.name })
            val doc = parent.child(unique)
            if (mimeType == Document.MIME_TYPE_DIR) pc.mkdir(doc.share, doc.path)
            else pc.upload(doc.share, doc.path, overwrite = false, size = 0, input = java.io.ByteArrayInputStream(ByteArray(0)))
            doc
        }
        forget(child)
        return child.id
    }

    override fun deleteDocument(documentId: String?) {
        val doc = parse(documentId)
        val (share, pc) = access(doc)
        if (!share.write) throw SecurityException("Папка «${share.name}» открыта на ПК только для чтения.")
        guard { pc.delete(doc.share, doc.path) }
        forget(doc)
    }

    override fun renameDocument(documentId: String?, displayName: String?): String? {
        val doc = parse(documentId)
        val (share, pc) = access(doc)
        if (!share.write) throw SecurityException("Папка «${share.name}» открыта на ПК только для чтения.")
        val name = (displayName ?: "").trim()
        if (name.isEmpty() || doc.isRoot) throw IllegalArgumentException("Недопустимое имя.")
        guard { pc.rename(doc.share, doc.path, name) }
        forget(doc)
        return doc.parent.child(name).also { forget(it) }.id
    }

    // ------------------------------------------------------------------ вспомогательное

    /** Что изменилось на ПК: забыть кэш списков и сказать системе перечитать папку. */
    private fun forget(doc: PcDocId) {
        val parent = doc.parent
        listings.invalidate(cacheKey(parent))
        stats.invalidate(cacheKey(doc))
        stats.invalidate(cacheKey(parent))
        requireNotNull(context).contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parent.id), null)
    }

    private fun list(pc: PcFiles, parent: PcDocId): List<FsEntry> {
        val key = cacheKey(parent)
        listings.get(key)?.let { return it }
        val entries = guard { pc.list(parent.share, parent.path) }
        listings.put(key, entries)
        for (e in entries) stats.put(cacheKey(parent.child(e.name)), e)
        return entries
    }

    private fun statOf(pc: PcFiles, doc: PcDocId): FsEntry {
        val key = cacheKey(doc)
        stats.get(key)?.let { return it }
        return pc.stat(doc.share, doc.path).also { stats.put(key, it) }
    }

    private fun cacheKey(doc: PcDocId) = doc.id

    private fun include(cursor: MatrixCursor, doc: PcDocId, entry: FsEntry?, share: AgentShare) {
        val dir = entry?.dir ?: true
        var flags = 0
        if (share.write) {
            flags = if (dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
            if (!doc.isRoot) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        val name = if (doc.isRoot) share.name else entry?.name ?: doc.name
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, doc.id)
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, if (dir) Document.MIME_TYPE_DIR else mimeOf(name))
            .add(Document.COLUMN_SIZE, if (dir || entry == null) null else entry.size)
            .add(Document.COLUMN_LAST_MODIFIED, entry?.modified?.takeIf { it > 0 })
            .add(Document.COLUMN_FLAGS, flags)
    }

    /** Папка документа в настройках агента и средство общения с ПК; без связи или при выключенном доступе — понятная ошибка. */
    private fun access(doc: PcDocId): Pair<AgentShare, PcFiles> {
        val presence = AgentHub.presence.value[doc.device] ?: throw IllegalStateException(FsErrors.text("offline", null))
        val files = presence.files
        if (files == null || !files.enabled) throw IllegalStateException(FsErrors.text("off", null))
        val share = files.shares.firstOrNull { it.id == doc.share } ?: throw IllegalStateException("Эту папку на ПК больше не открыли для планшета.")
        return share to PcFiles { header, payload -> AgentHub.fsCall(doc.device, header, payload) }
    }

    private fun parse(id: String?): PcDocId = PcDocId.parse(id) ?: throw IllegalArgumentException("Неизвестный документ: $id")

    /** Ошибки обмена с ПК → исключения, которые система умеет показать; «нет такого файла» для вызовов, где она ждёт FileNotFoundException. */
    private fun <T> guard(notFoundAsFile: Boolean = false, block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        when {
            e.code == "nf" && notFoundAsFile -> throw FileNotFoundException(e.message)
            e.code == "ro" || e.code == "denied" -> throw SecurityException(e.message)
            else -> throw IllegalStateException(e.message)
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private const val TAG = "PcFiles"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** Адрес хранилища; тот же стоит в манифесте (`${applicationId}.pcfiles`). */
        fun authority(context: Context) = "${context.packageName}.pcfiles"

        private fun stagingDir(context: Context) = File(context.cacheDir, "pcfiles").apply { mkdirs() }

        private fun rootsNow(context: Context, presence: Map<String, AgentPresence>): List<PcRoots.Root> {
            val repo = DeviceRepository.get(context)
            return PcRoots.list(presence) { repo.get(it)?.name }
        }
    }
}
