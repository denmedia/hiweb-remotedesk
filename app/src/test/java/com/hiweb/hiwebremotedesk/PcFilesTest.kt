package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.agent.AgentFiles
import com.hiweb.hiwebremotedesk.agent.AgentHub
import com.hiweb.hiwebremotedesk.agent.AgentPower
import com.hiweb.hiwebremotedesk.agent.AgentPresence
import com.hiweb.hiwebremotedesk.agent.AgentShare
import com.hiweb.hiwebremotedesk.agent.FilesAccess
import com.hiweb.hiwebremotedesk.agent.FsException
import com.hiweb.hiwebremotedesk.agent.FsLimits
import com.hiweb.hiwebremotedesk.agent.FsWire
import com.hiweb.hiwebremotedesk.agent.ListingCache
import com.hiweb.hiwebremotedesk.agent.PcDocId
import com.hiweb.hiwebremotedesk.agent.PcFiles
import com.hiweb.hiwebremotedesk.agent.PcRoots
import com.hiweb.hiwebremotedesk.agent.ReadWindow
import com.hiweb.hiwebremotedesk.agent.SecureLines
import com.hiweb.hiwebremotedesk.ui.ButtonHints
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/** Доступ к файлам ПК без экрана: формат сообщений (сверен с агентом), файловый клиент, идентификаторы, «диски» ПК. */
class PcFilesTest {

    // ------------------------------------------------------------------ формат и совместимость с агентом

    private val key = ByteArray(32) { it.toByte() }
    private val iv = ByteArray(16) { (0xA0 + it).toByte() }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun wirePacksHeaderLengthHeaderAndPayload() {
        val packed = FsWire.pack(JSONObject().put("op", "read"), byteArrayOf(9, 8, 7))
        val headerLength = ((packed[0].toInt() and 0xFF) shl 24) or ((packed[1].toInt() and 0xFF) shl 16) or
            ((packed[2].toInt() and 0xFF) shl 8) or (packed[3].toInt() and 0xFF)
        assertEquals("{\"op\":\"read\"}".length, headerLength)
        assertArrayEquals(byteArrayOf(9, 8, 7), packed.copyOfRange(4 + headerLength, packed.size))
        val back = FsWire.unpack(packed)!!
        assertEquals("read", back.header.getString("op"))
        assertArrayEquals(byteArrayOf(9, 8, 7), back.payload)
    }

    @Test
    fun wireKeepsUnicodeAndBigNumbers() {
        val packed = FsWire.pack(JSONObject().put("path", "Папка/файл 1.txt").put("off", 5_000_000_000L))
        val back = FsWire.unpack(packed)!!
        assertEquals("Папка/файл 1.txt", back.header.getString("path"))
        assertEquals(5_000_000_000L, back.header.getLong("off"))
        assertEquals(0, back.payload.size)
    }

    @Test
    fun wireRejectsBrokenMessages() {
        assertNull(FsWire.unpack(ByteArray(3)))
        assertNull(FsWire.unpack(byteArrayOf(0, 0, 1, 0, 1, 2)))
        assertNull(FsWire.unpack(byteArrayOf(0, 0, 0, 3, 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte())))
        assertNull(FsWire.unpack(byteArrayOf(-1, -1, -1, -1, 1, 2, 3)))
    }

    /** Строка, которую собрал и подписал настоящий LanLink агента (FsWire + SecureLines на .NET) — планшет должен её разобрать. */
    @Test
    fun readsMessageBuiltByAgent() {
        val line = "A8J96Q1t6+HF1o+femIsiKoba3+j5MEf+WnvobUectU= " +
            "{\"t\":\"fs\",\"id\":7,\"iv\":\"oKGio6SlpqeoqaqrrK2urw==\",\"data\":\"yaqdYfawBnWJWzFLn94QtUnV4HppRHZJiJclKVqwrIxclCE8W+LvteXPKEz/" +
            "WCfBa0N2Ew/RmfR1X+ehMacg+Mqu6wHXxL9Md53WAZUy+9/RGbkR1m5og6wqRr872GnpKDKXt212rYTh2La2nJlHxg==\"}"
        val lines = SecureLines(key)
        val body = lines.verify(line)
        assertNotNull("подпись агента не сошлась", body)
        val msg = JSONObject(body!!)
        assertEquals("fs", msg.getString("t"))
        assertEquals(7L, msg.getLong("id"))
        val b64 = java.util.Base64.getDecoder()
        val plain = lines.decrypt(b64.decode(msg.getString("iv")), b64.decode(msg.getString("data")))!!
        assertArrayEquals(
            hex(
                "000000577B226F6B223A747275652C226974656D73223A5B7B226E223A22D09ED182D187D191D1822E747874222C2264223A66616C73652C2273223A" +
                    "353030303030303030302C226D223A313730303030303030303132337D5D7D0102030405",
            ),
            plain,
        )
        val reply = FsWire.unpack(plain)!!
        assertTrue(reply.header.getBoolean("ok"))
        val entry = com.hiweb.hiwebremotedesk.agent.FsEntry.from(reply.header.getJSONArray("items").getJSONObject(0))
        assertEquals("Отчёт.txt", entry.name)
        assertFalse(entry.dir)
        assertEquals(5_000_000_000L, entry.size)
        assertEquals(1_700_000_000_123L, entry.modified)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), reply.payload)
    }

    /** Шифрование и подпись планшета дают те же байты, что .NET-агент: иначе агент не расшифрует запрос. */
    @Test
    fun encryptsAndSignsLikeAgent() {
        val raw = ByteArray(100) { it.toByte() }
        val expected = "f5ysUrofLI+qy50eO2apBf/v7n38sdrXktfvlJ2ABzjRir6DE9JcoO6ZOoNdSWcdiD6I4lfxiqgD+56m2m6qiHfMS92HaPRXfZ8VAszMHe1g+PDfh1WewjjLM8y3ooybmEUQ4AXSn0Drj0Vd6MJNdQ=="
        assertEquals(expected, java.util.Base64.getEncoder().encodeToString(SecureLines(key).encrypt(raw, iv)))
        assertEquals("bLDm0cpSxk7OlCNha3pXEKVfxVij9TAFY4W5pe0WoBk= {\"t\":\"ping\"}", SecureLines(key).sign("{\"t\":\"ping\"}"))
    }

    @Test
    fun encryptRoundTripsWithRandomIv() {
        val lines = SecureLines(key)
        val (iv1, c1) = lines.encrypt("секрет".toByteArray())
        val (iv2, c2) = lines.encrypt("секрет".toByteArray())
        assertNotEquals("iv должен быть случайным", iv1.toList(), iv2.toList())
        assertNotEquals(c1.toList(), c2.toList())
        assertEquals("секрет", String(lines.decrypt(iv1, c1)!!))
        assertNull(lines.decrypt(iv1, c1.copyOf(c1.size - 1)))
    }

    // ------------------------------------------------------------------ файловый клиент

    /** Подмена связи с агентом: запоминает запросы, отвечает заготовкой. */
    private class FakeAgent(val answer: (JSONObject, ByteArray) -> FsWire.Message) {
        val requests = mutableListOf<Pair<JSONObject, ByteArray>>()
        val files = PcFiles { header, payload ->
            requests += header to payload
            answer(header, payload)
        }
    }

    private fun ok(vararg fields: Pair<String, Any>, payload: ByteArray = ByteArray(0)) =
        FsWire.Message(JSONObject().put("ok", true).also { o -> fields.forEach { o.put(it.first, it.second) } }, payload)

    private fun err(code: String, msg: String? = null) =
        FsWire.Message(JSONObject().put("ok", false).put("err", code).also { if (msg != null) it.put("msg", msg) }, ByteArray(0))

    @Test
    fun listParsesEntries() {
        val agent = FakeAgent { _, _ ->
            val items = JSONArray()
                .put(JSONObject().put("n", "Фото").put("d", true).put("s", 0).put("m", 1700000000000L))
                .put(JSONObject().put("n", "a.txt").put("d", false).put("s", 12).put("m", 5).put("h", true))
            ok("items" to items)
        }
        val list = agent.files.list("s1", "Документы")
        assertEquals(2, list.size)
        assertTrue(list[0].dir)
        assertEquals("a.txt", list[1].name)
        assertEquals(12L, list[1].size)
        assertTrue(list[1].hidden)
        val request = agent.requests.single().first
        assertEquals("list", request.getString("op"))
        assertEquals("s1", request.getString("share"))
        assertEquals("Документы", request.getString("path"))
    }

    @Test
    fun readAsksForChunkAndReturnsData() {
        val agent = FakeAgent { _, _ -> ok("size" to 1000L, "eof" to true, payload = byteArrayOf(1, 2, 3)) }
        val chunk = agent.files.read("s1", "a/b.bin", 900, 10_000_000)
        assertArrayEquals(byteArrayOf(1, 2, 3), chunk.data)
        assertEquals(1000L, chunk.size)
        assertTrue(chunk.eof)
        val r = agent.requests.single().first
        assertEquals(900L, r.getLong("off"))
        assertEquals("слишком большой кусок урезается до предела агента", FsLimits.CHUNK, r.getInt("len"))
    }

    @Test
    fun errorsCarryCodeAndReadableText() {
        val agent = FakeAgent { _, _ -> err("ro") }
        val e = runCatching { agent.files.mkdir("s1", "x") }.exceptionOrNull() as FsException
        assertEquals("ro", e.code)
        assertTrue(e.message!!.contains("только для чтения"))
        val own = FakeAgent { _, _ -> err("io", "Слишком длинный путь.") }
        assertEquals("Слишком длинный путь.", (runCatching { own.files.delete("s1", "x") }.exceptionOrNull() as FsException).message)
        for (code in listOf("off", "nf", "bad", "exists", "busy", "denied", "offline", "timeout", "что-то новое")) {
            assertTrue(code, com.hiweb.hiwebremotedesk.agent.FsErrors.text(code, null).isNotBlank())
        }
    }

    @Test
    fun renameSendsNameNotPath() {
        val agent = FakeAgent { _, _ -> ok() }
        agent.files.rename("s1", "a/old.txt", "new.txt")
        val r = agent.requests.single().first
        assertEquals("rename", r.getString("op"))
        assertEquals("a/old.txt", r.getString("path"))
        assertEquals("new.txt", r.getString("name"))
    }

    @Test
    fun uploadSendsOrderedChunksThenCommits() {
        val agent = FakeAgent { header, _ -> if (header.getString("op") == "put_begin") ok("up" to "u1") else ok() }
        val data = ByteArray(FsLimits.CHUNK * 2 + 10) { it.toByte() }
        val progress = mutableListOf<Long>()
        agent.files.upload("s1", "d/f.bin", overwrite = true, size = data.size.toLong(), input = ByteArrayInputStream(data), onProgress = { progress += it })
        val ops = agent.requests.map { it.first.getString("op") }
        assertEquals(listOf("put_begin", "put", "put", "put", "put_end"), ops)
        assertTrue(agent.requests[0].first.getBoolean("overwrite"))
        val puts = agent.requests.filter { it.first.getString("op") == "put" }
        assertEquals(listOf(0L, FsLimits.CHUNK.toLong(), FsLimits.CHUNK * 2L), puts.map { it.first.getLong("off") })
        assertEquals("u1", puts[0].first.getString("up"))
        val joined = puts.flatMap { it.second.toList() }
        assertEquals(data.toList(), joined)
        assertEquals(data.size.toLong(), agent.requests.last().first.getLong("size"))
        assertEquals(data.size.toLong(), progress.last())
    }

    @Test
    fun emptyFileUploadHasNoChunks() {
        val agent = FakeAgent { header, _ -> if (header.getString("op") == "put_begin") ok("up" to "u1") else ok() }
        agent.files.upload("s1", "e.txt", overwrite = false, size = 0, input = ByteArrayInputStream(ByteArray(0)))
        assertEquals(listOf("put_begin", "put_end"), agent.requests.map { it.first.getString("op") })
        assertFalse(agent.requests[0].first.getBoolean("overwrite"))
    }

    @Test
    fun failedUploadIsAborted() {
        val agent = FakeAgent { header, _ ->
            when (header.getString("op")) {
                "put_begin" -> ok("up" to "u1")
                "put" -> err("io", "На диске ПК не хватает места.")
                else -> ok()
            }
        }
        val e = runCatching {
            agent.files.upload("s1", "f.bin", true, 5, ByteArrayInputStream(ByteArray(5)))
        }.exceptionOrNull()
        assertTrue(e is FsException)
        assertEquals(listOf("put_begin", "put", "put_abort"), agent.requests.map { it.first.getString("op") })
        assertEquals("u1", agent.requests.last().first.getString("up"))
    }

    @Test
    fun lostConnectionPropagates() {
        val agent = FakeAgent { _, _ -> throw FsException("offline", "ПК не в сети") }
        val e = runCatching { agent.files.list("s1", "") }.exceptionOrNull() as FsException
        assertEquals("offline", e.code)
    }

    // ------------------------------------------------------------------ идентификаторы документов

    @Test
    fun documentIdRoundTrips() {
        val doc = PcDocId("dev-1", "a1b2c3", "Папка/под папка/файл.txt")
        assertEquals("dev-1|a1b2c3|Папка/под папка/файл.txt", doc.id)
        assertEquals(doc, PcDocId.parse(doc.id))
        assertEquals("файл.txt", doc.name)
        assertEquals(PcDocId("dev-1", "a1b2c3", "Папка/под папка"), doc.parent)
        assertEquals(doc, doc.parent.child("файл.txt"))
        val root = PcDocId.root("dev-1", "a1b2c3")
        assertTrue(root.isRoot)
        assertEquals("dev-1|a1b2c3|", root.id)
        assertEquals("dev-1|a1b2c3", root.rootId)
        assertEquals(root, root.parent)
        assertEquals(root, PcDocId.parse("dev-1|a1b2c3|"))
        assertEquals("", root.name)
        assertEquals(root, PcDocId.parseRoot(root.rootId))
        assertEquals(root, PcDocId.parse(root.id))
    }

    @Test
    fun documentIdWithBarInFileName() {
        val doc = PcDocId.parse("dev|s1|a|b/c|d.txt")!!
        assertEquals("a|b/c|d.txt", doc.path)
        assertEquals("c|d.txt", doc.name)
    }

    @Test
    fun documentIdRejectsForeignOrBroken() {
        assertNull(PcDocId.parse(null))
        assertNull(PcDocId.parse(""))
        assertNull(PcDocId.parse("dev|s1"))
        assertNull(PcDocId.parse("|s1|a"))
        assertNull(PcDocId.parse("dev||a"))
        assertNull(PcDocId.parse("dev|s1|/a"))
        assertNull(PcDocId.parse("dev|s1|a/"))
        assertNull(PcDocId.parse("dev|s1|a//b"))
        assertNull(PcDocId.parseRoot("dev|s1|"))
        assertNull(PcDocId.parseRoot("dev"))
    }

    @Test
    fun containsMeansStrictDescendantInSameShare() {
        val root = PcDocId.root("d", "s")
        val a = root.child("a")
        val ab = a.child("b")
        assertTrue(root.contains(a))
        assertTrue(root.contains(ab))
        assertTrue(a.contains(ab))
        assertFalse("сам документ не его потомок", a.contains(a))
        assertFalse(ab.contains(a))
        assertFalse("«ab» — не потомок «a»", a.contains(root.child("ab")))
        assertFalse("другая папка", a.contains(PcDocId("d", "other", "a/b")))
        assertFalse("другой ПК", a.contains(PcDocId("x", "s", "a/b")))
    }

    @Test
    fun uniqueNameAddsNumberBeforeExtension() {
        assertEquals("a.txt", PcDocId.uniqueName("a.txt", listOf("b.txt")))
        assertEquals("a (1).txt", PcDocId.uniqueName("a.txt", listOf("a.txt")))
        assertEquals("a (2).txt", PcDocId.uniqueName("a.txt", listOf("a.txt", "a (1).txt")))
        assertEquals("регистр: Windows различает имена без учёта регистра", "A (1).TXT", PcDocId.uniqueName("A.TXT", listOf("a.txt")))
        assertEquals("папка (1)", PcDocId.uniqueName("папка", listOf("папка")))
        assertEquals(".gitignore (1)", PcDocId.uniqueName(".gitignore", listOf(".gitignore")))
        assertEquals("a.tar (1).gz", PcDocId.uniqueName("a.tar.gz", listOf("a.tar.gz")))
    }

    // ------------------------------------------------------------------ кэш и окно чтения

    @Test
    fun listingCacheExpiresAndInvalidates() {
        var now = 0L
        val cache = ListingCache<String>(ttlMs = 1000, clock = { now })
        cache.put("d|s|a", "list-a")
        cache.put("d|s|a/b", "list-ab")
        cache.put("d|s|c", "list-c")
        now = 500
        assertEquals("list-a", cache.get("d|s|a"))
        cache.invalidate("d|s|a")
        assertNull("сброс по префиксу задевает и вложенные папки", cache.get("d|s|a/b"))
        assertNull(cache.get("d|s|a"))
        assertEquals("list-c", cache.get("d|s|c"))
        now = 2000
        assertNull("устарело", cache.get("d|s|c"))
    }

    @Test
    fun readWindowServesSequentialReadsFromOneFetch() {
        val size = FsLimits.CHUNK * 3L + 100
        val file = ByteArray(size.toInt()) { (it * 31).toByte() }
        val fetches = mutableListOf<Long>()
        val window = ReadWindow(size) { offset, length ->
            fetches += offset
            file.copyOfRange(offset.toInt(), minOf(size, offset + length).toInt())
        }
        val out = ByteArray(65536)
        var position = 0L
        while (position < FsLimits.CHUNK) {
            val n = window.read(position, out.size, out)
            assertEquals(out.size, n)
            assertArrayEquals(file.copyOfRange(position.toInt(), position.toInt() + n), out.copyOf(n))
            position += n
        }
        assertEquals("четыре чтения по 64 КБ — один запрос к ПК", listOf(0L), fetches)
    }

    @Test
    fun readWindowCrossesWindowBoundaryAndStopsAtEnd() {
        val size = FsLimits.CHUNK + 50L
        val file = ByteArray(size.toInt()) { (it % 251).toByte() }
        val window = ReadWindow(size) { offset, length -> file.copyOfRange(offset.toInt(), minOf(size, offset + length).toInt()) }
        val out = ByteArray(200)
        val n = window.read(FsLimits.CHUNK - 100L, 200, out)
        assertEquals("чтение через границу окна склеивается", 150, n)
        assertArrayEquals(file.copyOfRange(FsLimits.CHUNK - 100, size.toInt()), out.copyOf(n))
        assertEquals("за концом файла — пусто", 0, window.read(size, 10, out))
        assertEquals(0, window.read(size + 1000, 10, out))
    }

    @Test
    fun readWindowRandomAccessRefetches() {
        val size = FsLimits.CHUNK * 4L
        val fetches = mutableListOf<Long>()
        val window = ReadWindow(size) { offset, length -> fetches += offset; ByteArray(minOf(length.toLong(), size - offset).toInt()) { 7 } }
        val out = ByteArray(16)
        window.read(FsLimits.CHUNK * 3L, 16, out)
        window.read(0, 16, out)
        window.read(FsLimits.CHUNK * 3L + 8, 16, out)
        assertEquals(listOf(FsLimits.CHUNK * 3L, 0L, FsLimits.CHUNK * 3L + 8), fetches)
    }

    @Test
    fun readWindowStopsWhenFileShrinks() {
        val window = ReadWindow(1000) { _, _ -> ByteArray(0) }
        assertEquals(0, window.read(0, 100, ByteArray(100)))
    }

    // ------------------------------------------------------------------ «диски» ПК и доступность

    private fun presence(files: AgentFiles?) = AgentPresence("1.11.0", "PC", setOf(AgentPower.SLEEP), files)

    @Test
    fun parsesFilesFromCaps() {
        assertNull("агент старше 1.11 не шлёт fs", AgentHub.parseFiles(null))
        val fs = JSONObject().put("on", true).put(
            "shares",
            JSONArray()
                .put(JSONObject().put("id", "a1").put("name", "Документы").put("w", false))
                .put(JSONObject().put("id", "b2").put("name", "Фото").put("w", true))
                .put(JSONObject().put("name", "без идентификатора")),
        )
        val files = AgentHub.parseFiles(fs)!!
        assertTrue(files.enabled)
        assertEquals(listOf(AgentShare("a1", "Документы", false), AgentShare("b2", "Фото", true)), files.shares)
        val off = AgentHub.parseFiles(JSONObject().put("on", false).put("shares", JSONArray()))!!
        assertFalse(off.enabled)
        assertTrue(off.shares.isEmpty())
    }

    @Test
    fun accessStatesFollowAgentReport() {
        assertEquals(FilesAccess.OFFLINE, FilesAccess.of(null))
        assertEquals(FilesAccess.OLD_AGENT, FilesAccess.of(presence(null)))
        assertEquals(FilesAccess.DISABLED, FilesAccess.of(presence(AgentFiles(false, emptyList()))))
        assertEquals("включено, но папок нет", FilesAccess.DISABLED, FilesAccess.of(presence(AgentFiles(true, emptyList()))))
        assertEquals(FilesAccess.READY, FilesAccess.of(presence(AgentFiles(true, listOf(AgentShare("a", "A", false))))))
    }

    @Test
    fun everyBlockedStateHasHint() {
        for (access in FilesAccess.entries.filter { it != FilesAccess.READY }) {
            val hint = ButtonHints.files(access)
            assertTrue(access.name, hint.title.isNotBlank() && hint.text.length > 40)
        }
        assertTrue(ButtonHints.files(FilesAccess.DISABLED).text.contains("Доступ к файлам"))
        assertTrue(ButtonHints.files(FilesAccess.OLD_AGENT).text.contains("1.11"))
        assertTrue(ButtonHints.filesManual("Офис").text.contains("Офис"))
    }

    @Test
    fun oneRootPerSharedFolderOfOnlinePcs() {
        val names = mapOf("pc-b" to "Офис", "pc-a" to "Домашний")
        val presence = mapOf(
            "pc-b" to presence(AgentFiles(true, listOf(AgentShare("s1", "Документы", true), AgentShare("s2", "Фото", false)))),
            "pc-a" to presence(AgentFiles(true, listOf(AgentShare("s9", "Музыка", false)))),
            "pc-off" to presence(AgentFiles(false, listOf(AgentShare("x", "Скрыто", true)))),
            "pc-old" to presence(null),
            "pc-gone" to presence(AgentFiles(true, listOf(AgentShare("y", "Удалён из списка", true)))),
        )
        val roots = PcRoots.list(presence) { names[it] }
        assertEquals("порядок: по названию ПК, затем как у агента", listOf("Музыка", "Документы", "Фото"), roots.map { it.title })
        assertEquals(listOf("pc-a|s9", "pc-b|s1", "pc-b|s2"), roots.map { it.doc.rootId })
        assertEquals("Домашний · только чтение", roots[0].summary)
        assertEquals("Офис", roots[1].summary)
        assertTrue(roots[1].write)
        assertFalse(roots[2].write)
        assertEquals("Офис · только чтение", roots[2].summary)
        assertTrue(roots.all { it.doc.isRoot })
    }
}
