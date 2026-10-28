package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.GuardData
import com.hiweb.hiwebremotedesk.data.GuardEntry
import com.hiweb.hiwebremotedesk.data.GuardJournal
import com.hiweb.hiwebremotedesk.data.GuardText
import com.hiweb.hiwebremotedesk.data.GuardThreat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Слияние журнала и угроз, присланных агентом, и тексты о них (без Android и org.json). */
class GuardJournalTest {
    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun entry(id: String, time: Long, kind: String = GuardEntry.FAIL, count: Int = 1) =
        GuardEntry(id, time, kind, ip = "198.51.100.71", user = "", count = count)

    private fun threat(id: String, time: Long = now, status: String = GuardThreat.STATUS_NEW, rule: String = GuardThreat.BRUTEFORCE) =
        GuardThreat(id, time, rule, ip = "198.51.100.71", cidr = "198.51.100.0/24", count = 7, user = "PC\\user", afterFail = false, status = status)

    @Test
    fun `слитая запись агента заменяется присланной с новым счётчиком`() {
        val old = listOf(entry("a", 1000, count = 10), entry("b", 2000, GuardEntry.LOGON))
        val merged = GuardJournal.mergeEntries(old, listOf(entry("a", 3000, count = 25)))
        assertEquals(listOf("b", "a"), merged.map { it.id })
        assertEquals(25, merged.first { it.id == "a" }.count)
    }

    @Test
    fun `журнал ограничен по числу записей и сохраняет свежие`() {
        val many = (1..GuardJournal.MAX_ENTRIES + 50).map { entry("e$it", it.toLong()) }
        val merged = GuardJournal.mergeEntries(emptyList(), many)
        assertEquals(GuardJournal.MAX_ENTRIES, merged.size)
        assertEquals("e${GuardJournal.MAX_ENTRIES + 50}", merged.last().id)
        assertFalse(merged.any { it.id == "e1" })
    }

    @Test
    fun `статус угрозы обновляется по идентификатору`() {
        val merged = GuardJournal.mergeThreats(listOf(threat("t1")), listOf(threat("t1", status = GuardThreat.STATUS_BLOCKED)))
        assertEquals(1, merged.size)
        assertEquals(GuardThreat.STATUS_BLOCKED, merged[0].status)
        assertFalse(merged[0].open)
    }

    @Test
    fun `об угрозе сообщается один раз`() {
        val data = GuardData()
        val (afterFirst, first) = GuardJournal.applyThreats(data, listOf(threat("t1")), now)
        assertEquals(listOf("t1"), first.map { it.id })
        // Агент досылает те же угрозы при каждом подключении: повторного уведомления быть не должно.
        val (afterSecond, second) = GuardJournal.applyThreats(afterFirst, listOf(threat("t1")), now + hour)
        assertTrue(second.isEmpty())
        assertEquals(1, afterSecond.threats.size)
    }

    @Test
    fun `решённые и устаревшие угрозы не вызывают уведомления`() {
        val incoming = listOf(
            threat("blocked", status = GuardThreat.STATUS_BLOCKED),
            threat("trusted", status = GuardThreat.STATUS_TRUSTED),
            threat("old", time = now - GuardJournal.ANNOUNCE_MAX_AGE_MS - hour),
            threat("fresh", time = now - hour),
        )
        val (data, fresh) = GuardJournal.applyThreats(GuardData(), incoming, now)
        assertEquals(listOf("fresh"), fresh.map { it.id })
        assertEquals(4, data.threats.size)
        // Устаревшая угроза остаётся в окне защиты нерешённой, хотя уведомление по ней не нужно.
        assertEquals(setOf("old", "fresh"), data.openThreats.map { it.id }.toSet())
    }

    @Test
    fun `угроза, решённая на ПК до первого уведомления, не объявляется`() {
        val (_, fresh) = GuardJournal.applyThreats(GuardData(), listOf(threat("t1", status = GuardThreat.STATUS_DISMISSED)), now)
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `досылка журнала просится от последней записи агента, записи приложения не в счёт`() {
        val entries = listOf(
            entry("a", 1000),
            entry("app", 9000, GuardEntry.APP_START),
            entry("b", 5000, GuardEntry.LOGON),
        )
        assertEquals(5000L, GuardJournal.lastAgentTime(entries))
        assertEquals(0L, GuardJournal.lastAgentTime(listOf(entry("app", 9000, GuardEntry.APP_END))))
        assertEquals(0L, GuardJournal.lastAgentTime(emptyList()))
    }

    @Test
    fun `список объявленных угроз не растёт бесконечно`() {
        var data = GuardData()
        for (i in 1..300) data = GuardJournal.withAnnounced(data, listOf("t$i"))
        assertTrue(data.announced.size <= 200)
        assertTrue("t300" in data.announced)
        assertFalse("t1" in data.announced)
    }

    @Test
    fun `тексты угроз`() {
        val brute = threat("t1")
        assertEquals("Подбор пароля по RDP", GuardText.threatTitle(brute))
        assertEquals("7 неудачных попыток входа за 10 минут с 198.51.100.71 (подсеть 198.51.100.0/24)", GuardText.threatText(brute))
        val logon = threat("t2", rule = GuardThreat.UNKNOWN_LOGON)
        assertEquals("Вход по RDP с неизвестного адреса", GuardText.threatTitle(logon))
        assertTrue(GuardText.threatText(logon).startsWith("PC\\user вошёл в сеанс с 198.51.100.71, но планшет"))
        assertFalse(GuardText.threatText(logon).contains("пароль могли подобрать"))
        assertTrue(GuardText.threatText(logon.copy(afterFail = true)).contains("пароль могли подобрать"))
        assertTrue(GuardText.threatText(logon.copy(user = "")).startsWith("Кто-то вошёл"))
    }

    @Test
    fun `тексты записей журнала`() {
        assertEquals("Неверный пароль × 103", GuardText.entryText(entry("a", 1, count = 103)))
        assertEquals("Неверный пароль", GuardText.entryText(entry("a", 1)))
        assertEquals("Вход в сеанс (PC\\user)", GuardText.entryText(entry("a", 1, GuardEntry.LOGON).copy(user = "PC\\user")))
        assertEquals("Подключение с этого устройства", GuardText.entryText(entry("a", 1, GuardEntry.APP_START)))
        assertEquals(
            "Не удалось подключиться с этого устройства: тайм-аут",
            GuardText.entryText(entry("a", 1, GuardEntry.APP_FAIL).copy(user = "тайм-аут")),
        )
    }

    @Test
    fun `подозрительны только неудачные попытки с чужих адресов`() {
        assertTrue(GuardText.entrySuspicious(entry("a", 1)))
        assertFalse(GuardText.entrySuspicious(entry("a", 1).copy(local = true)))
        assertFalse(GuardText.entrySuspicious(entry("a", 1).copy(trusted = true)))
        assertFalse(GuardText.entrySuspicious(entry("a", 1, GuardEntry.LOGON)))
        assertEquals("198.51.100.71", GuardText.entryAddress(entry("a", 1)))
        assertEquals("198.51.100.71 · своя сеть", GuardText.entryAddress(entry("a", 1).copy(local = true)))
        assertEquals("", GuardText.entryAddress(entry("a", 1, GuardEntry.APP_START)))
    }
}
