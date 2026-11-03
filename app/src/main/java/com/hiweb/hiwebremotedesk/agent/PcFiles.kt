package com.hiweb.hiwebremotedesk.agent

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Тело файлового сообщения до шифрования (см. FsWire в агенте): 4 байта длины заголовка (big-endian), заголовок JSON в
 * UTF-8, затем данные как есть. Шифрование и подпись строки делает [SecureLines].
 */
object FsWire {
    class Message(val header: JSONObject, val payload: ByteArray)

    fun pack(header: JSONObject, payload: ByteArray = ByteArray(0)): ByteArray {
        val h = header.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(4 + h.size + payload.size)
        out.write(h.size ushr 24)
        out.write(h.size ushr 16)
        out.write(h.size ushr 8)
        out.write(h.size)
        out.write(h)
        out.write(payload)
        return out.toByteArray()
    }

    /** Разбирает тело; null — оно испорчено (длина заголовка не сходится или заголовок не JSON). */
    fun unpack(plain: ByteArray): Message? {
        if (plain.size < 4) return null
        val length = ((plain[0].toInt() and 0xFF) shl 24) or ((plain[1].toInt() and 0xFF) shl 16) or
            ((plain[2].toInt() and 0xFF) shl 8) or (plain[3].toInt() and 0xFF)
        if (length < 2 || length > plain.size - 4) return null
        val header = runCatching { JSONObject(String(plain, 4, length, Charsets.UTF_8)) }.getOrNull() ?: return null
        return Message(header, plain.copyOfRange(4 + length, plain.size))
    }
}

/**
 * Ошибка файловой операции с понятным пользователю текстом. [code] — код агента (`off`, `nf`, `ro`, `bad`, `exists`,
 * `busy`, `denied`, `io`) либо собственный: `offline` — агент не на связи, `timeout` — агент не ответил.
 */
class FsException(val code: String, message: String) : Exception(message)

/** Тексты ошибок по кодам агента; собственный текст агента (поле `msg`) важнее. */
object FsErrors {
    fun text(code: String, agentMessage: String?): String {
        if (!agentMessage.isNullOrBlank()) return agentMessage
        return when (code) {
            "off" -> "Доступ к файлам на ПК выключен."
            "nf" -> "Файл или папка не найдены на ПК."
            "ro" -> "Эта папка на ПК открыта только для чтения."
            "bad" -> "Недопустимое имя или путь."
            "exists" -> "Такое имя на ПК уже занято."
            "busy" -> "Файл занят другой программой на ПК."
            "denied" -> "Windows не даёт доступ к этому файлу."
            "offline" -> "ПК не в сети: агент не на связи."
            "timeout" -> "ПК не ответил вовремя."
            else -> "Ошибка при работе с файлом на ПК."
        }
    }
}

/** Запись списка папки на ПК; [modified] — мс Unix. */
data class FsEntry(val name: String, val dir: Boolean, val size: Long, val modified: Long, val hidden: Boolean) {
    companion object {
        fun from(o: JSONObject) = FsEntry(
            name = o.optString("n"),
            dir = o.optBoolean("d"),
            size = o.optLong("s"),
            modified = o.optLong("m"),
            hidden = o.optBoolean("h"),
        )
    }
}

/** Кусок файла: [size] — размер всего файла, [eof] — этот кусок дошёл до конца. */
class FsChunk(val data: ByteArray, val size: Long, val eof: Boolean)

/**
 * Файловые операции над открытыми папками ПК поверх связи с агентом (протокол — windows-agent/PROTOCOL.md, «Файлы»).
 * [call] отправляет запрос и блокирует поток до ответа, бросая [FsException], если связи нет или ответа не дождались
 * (в приложении это `AgentHub.fsCall`); в тестах — подмена. Все методы блокирующие: звать из фонового потока.
 */
class PcFiles(private val call: (JSONObject, ByteArray) -> FsWire.Message) {
    private fun request(op: String, share: String, path: String? = null, payload: ByteArray = ByteArray(0), extra: JSONObject.() -> Unit = {}): FsWire.Message {
        val header = JSONObject().put("op", op).put("share", share)
        if (path != null) header.put("path", path)
        header.extra()
        val reply = call(header, payload)
        if (!reply.header.optBoolean("ok")) {
            val code = reply.header.optString("err", "io")
            throw FsException(code, FsErrors.text(code, reply.header.optString("msg").ifBlank { null }))
        }
        return reply
    }

    fun list(share: String, path: String): List<FsEntry> {
        val items = request("list", share, path).header.optJSONArray("items") ?: return emptyList()
        return List(items.length()) { FsEntry.from(items.getJSONObject(it)) }
    }

    /** Сведения об одном файле или папке; для корня [path] пустой, имя — название «диска». */
    fun stat(share: String, path: String): FsEntry {
        val item = request("stat", share, path).header.optJSONObject("item") ?: throw FsException("io", FsErrors.text("io", null))
        return FsEntry.from(item)
    }

    /** До [FsLimits.CHUNK] байт файла с [offset]; агент отдаёт не больше этого за раз. */
    fun read(share: String, path: String, offset: Long, length: Int): FsChunk {
        val reply = request("read", share, path) {
            put("off", offset)
            put("len", length.coerceAtMost(FsLimits.CHUNK))
        }
        return FsChunk(reply.payload, reply.header.optLong("size"), reply.header.optBoolean("eof"))
    }

    /** Свободное и общее место на диске папки; -1, если неизвестно. */
    fun space(share: String): Pair<Long, Long> {
        val h = request("space", share).header
        return h.optLong("free", -1) to h.optLong("total", -1)
    }

    fun mkdir(share: String, path: String) {
        request("mkdir", share, path)
    }

    /** Новое имя в той же папке (не путь). */
    fun rename(share: String, path: String, name: String) {
        request("rename", share, path) { put("name", name) }
    }

    /** Удаляет в корзину Windows. */
    fun delete(share: String, path: String) {
        request("delete", share, path)
    }

    /**
     * Загружает файл на ПК кусками: на месте цели он появляется только после последнего куска, поэтому оборванная
     * загрузка не портит прежний файл. [size] сверяется на ПК. Читает [input] до конца, но не закрывает.
     */
    fun upload(share: String, path: String, overwrite: Boolean, size: Long, input: InputStream, onProgress: (Long) -> Unit = {}) {
        val id = request("put_begin", share, path) { put("overwrite", overwrite); put("size", size) }.header.getString("up")
        try {
            val buffer = ByteArray(FsLimits.CHUNK)
            var offset = 0L
            while (true) {
                val n = input.readNBytes(buffer, 0, buffer.size)
                if (n <= 0) break
                request("put", share, path, buffer.copyOf(n)) { put("up", id); put("off", offset) }
                offset += n
                onProgress(offset)
            }
            request("put_end", share, path) { put("up", id); put("size", offset) }
        } catch (e: Throwable) {
            runCatching { request("put_abort", share, path) { put("up", id) } }
            throw e
        }
    }
}

object FsLimits {
    /** Самый большой кусок чтения и записи за одно сообщение (как FileService.MaxChunk в агенте). */
    const val CHUNK = 256 * 1024
}
