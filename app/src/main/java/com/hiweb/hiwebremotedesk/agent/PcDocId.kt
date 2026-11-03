package com.hiweb.hiwebremotedesk.agent

/**
 * Идентификатор документа в хранилище «ПК» ([PcDocumentsProvider]): `<id ПК>|<id папки>|<путь внутри папки через «/»>`.
 * Пустой путь — корень открытой папки (он же корень «диска» для системы). Чистая логика под юнит-тестами.
 */
data class PcDocId(val device: String, val share: String, val path: String) {
    val isRoot: Boolean get() = path.isEmpty()

    /** Идентификатор документа. */
    val id: String get() = "$device$SEP$share$SEP$path"

    /** Идентификатор корня «диска»: у корня документа путь пустой, поэтому они различаются только префиксом. */
    val rootId: String get() = "$device$SEP$share"

    /** Имя файла или папки; у корня — пустое (название «диска» берётся из настроек агента). */
    val name: String get() = path.substringAfterLast('/')

    /** Папка, где лежит документ; у корня — он сам. */
    val parent: PcDocId get() = if (path.isEmpty()) this else copy(path = path.substringBeforeLast('/', ""))

    fun child(name: String): PcDocId = copy(path = if (path.isEmpty()) name else "$path/$name")

    /** Лежит ли [other] внутри этого документа (на любой глубине); сам документ не в счёт. */
    fun contains(other: PcDocId): Boolean =
        other.device == device && other.share == share && when {
            other.path == path -> false
            path.isEmpty() -> true
            else -> other.path.startsWith("$path/")
        }

    companion object {
        private const val SEP = '|'

        fun root(device: String, share: String) = PcDocId(device, share, "")

        /** Разбирает идентификатор; null — он чужой или испорчен. Имена файлов могут содержать «|»: путь берётся целиком. */
        fun parse(id: String?): PcDocId? {
            if (id == null) return null
            val parts = id.split(SEP, limit = 3)
            if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty()) return null
            val path = parts[2]
            if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) return null
            return PcDocId(parts[0], parts[1], path)
        }

        /** Идентификатор корня «диска» → (ПК, папка). */
        fun parseRoot(rootId: String?): PcDocId? {
            if (rootId == null) return null
            val parts = rootId.split(SEP)
            return if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) root(parts[0], parts[1]) else null
        }

        /**
         * Имя, которого нет среди [taken] (без учёта регистра, как в Windows): «a.txt» → «a (1).txt» → «a (2).txt».
         * Расширение остаётся на месте; у имени из одних точек в начале («.gitignore») оно считается частью имени.
         */
        fun uniqueName(wanted: String, taken: Collection<String>): String {
            val used = taken.map { it.lowercase() }.toHashSet()
            if (wanted.lowercase() !in used) return wanted
            val dot = wanted.lastIndexOf('.')
            val stem = if (dot > 0) wanted.substring(0, dot) else wanted
            val ext = if (dot > 0) wanted.substring(dot) else ""
            var n = 1
            while (true) {
                val candidate = "$stem ($n)$ext"
                if (candidate.lowercase() !in used) return candidate
                n++
            }
        }
    }
}

/**
 * Недолгий кэш списков папок: файловый менеджер часто просит одну папку дважды подряд (открытие, обновление, сортировка),
 * а каждый запрос — обмен с ПК. Любая запись на ПК сбрасывает кэш этой папки и её родителей ([invalidate]).
 */
class ListingCache<T>(private val ttlMs: Long = 4000, private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry<T>(val at: Long, val value: T)

    private val entries = HashMap<String, Entry<T>>()

    @Synchronized
    fun get(key: String): T? {
        val e = entries[key] ?: return null
        if (clock() - e.at > ttlMs) {
            entries.remove(key)
            return null
        }
        return e.value
    }

    @Synchronized
    fun put(key: String, value: T) {
        if (entries.size > 64) entries.clear()
        entries[key] = Entry(clock(), value)
    }

    /** Забывает все списки, чей ключ начинается с [prefix]. */
    @Synchronized
    fun invalidate(prefix: String) {
        entries.keys.removeAll { it.startsWith(prefix) }
    }

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * Окно чтения для файла, открытого файловым менеджером: система читает кусками по 4–128 КБ, а обмен с ПК стоит
 * миллисекунд, поэтому с ПК берётся сразу большой кусок ([FsLimits.CHUNK]), а следующие чтения подряд обслуживаются из него.
 * [fetch] отдаёт кусок с указанного смещения (может быть короче запрошенного у конца файла).
 */
class ReadWindow(private val size: Long, private val fetch: (offset: Long, length: Int) -> ByteArray) {
    private var start = -1L
    private var window = ByteArray(0)

    /** Читает в [out] до [length] байт с [offset]; возвращает прочитанное (0 — конец файла). */
    @Synchronized
    fun read(offset: Long, length: Int, out: ByteArray): Int {
        var done = 0
        while (done < length) {
            val position = offset + done
            if (position >= size) break
            if (position < start || position >= start + window.size) {
                window = fetch(position, FsLimits.CHUNK)
                start = position
                if (window.isEmpty()) break
            }
            val from = (position - start).toInt()
            val n = minOf(length - done, window.size - from)
            System.arraycopy(window, from, out, done, n)
            done += n
        }
        return done
    }
}
