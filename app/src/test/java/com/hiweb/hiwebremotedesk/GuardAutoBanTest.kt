package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.GuardBanNote
import com.hiweb.hiwebremotedesk.data.GuardData
import com.hiweb.hiwebremotedesk.data.GuardFailNote
import com.hiweb.hiwebremotedesk.data.GuardJson
import com.hiweb.hiwebremotedesk.data.GuardText
import com.hiweb.hiwebremotedesk.data.RecentIds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Автоблокировка на стороне планшета: разбор `guard_state`/`guard_fail`/`guard_ban`, тексты уведомлений, отсев дублей. */
class GuardAutoBanTest {
    private fun fail(
        count: Int = 3, local: Boolean = false, known: Boolean = false, autoBan: Boolean = true, banAfter: Int = 5, willBan: Boolean = false,
    ) = GuardFailNote("f", 1L, "198.51.100.7", "198.51.100.0/24", count, local, known, autoBan, banAfter, willBan)

    @Test
    fun `состояние агента 1_10 несёт автоблокировку и помощника`() {
        val state = GuardJson.state(JSONObject("""{"t":"guard_state","fw":true,"mode":"normal","autoBan":false,"banAfter":7,"helper":true}"""))
        assertTrue(state.supportsAutoBan)
        assertFalse(state.autoBan)
        assertEquals(7, state.banAfter)
        assertTrue(state.helper)
    }

    @Test
    fun `агент старше 1_10 автоблокировки не знает`() {
        val state = GuardJson.state(JSONObject("""{"t":"guard_state","fw":true,"mode":"normal"}"""))
        assertFalse(state.supportsAutoBan)
        assertFalse(state.helper)
    }

    @Test
    fun `автоблокировка переживает запись на диск`() {
        val state = GuardJson.state(JSONObject("""{"fw":true,"mode":"normal","autoBan":true,"banAfter":5,"helper":true}"""))
        val back = GuardJson.dataFrom(GuardJson.toJson(GuardData(state = state))).state!!
        assertEquals(5, back.banAfter)
        assertTrue(back.autoBan)
        assertTrue(back.helper)
    }

    @Test
    fun `сообщение guard_fail разбирается`() {
        val n = GuardJson.fail(
            JSONObject("""{"t":"guard_fail","id":"fail|1|198.51.100.7|5","ts":1000,"ip":"198.51.100.7","cidr":"198.51.100.0/24","count":5,"local":false,"known":false,"autoBan":true,"banAfter":5,"willBan":true}"""),
        )!!
        assertEquals(5, n.count)
        assertTrue(n.willBan)
        assertEquals("198.51.100.0/24", n.cidr)
        assertNull(GuardJson.fail(JSONObject("""{"t":"guard_fail"}""")))
    }

    @Test
    fun `сообщение guard_ban разбирается`() {
        val n = GuardJson.ban(
            JSONObject("""{"t":"guard_ban","id":"ban|x|1","ts":1000,"cidr":"198.51.100.0/24","ip":"198.51.100.7","count":5,"auto":true,"ok":true,"fwOff":true,"error":""}"""),
        )!!
        assertTrue(n.auto && n.ok && n.firewallOff)
        assertEquals(5, n.count)
        assertNull(GuardJson.ban(JSONObject("""{"id":"x"}""")))
    }

    @Test
    fun `текст попытки показывает счётчик и остаток до блокировки`() {
        assertTrue(GuardText.failText(fail(count = 3)).contains("№3"))
        assertTrue(GuardText.failText(fail(count = 3)).contains("До блокировки: 2"))
        assertTrue(GuardText.failText(fail(count = 5, willBan = true)).contains("блокируется"))
        assertTrue(GuardText.failText(fail(autoBan = false)).contains("Автоблокировка выключена"))
        assertEquals("Блокировка подсети после неудачных попыток входа", GuardText.failTitle(fail(willBan = true)))
    }

    @Test
    fun `свои и знакомые адреса отмечены и не обещают блокировку`() {
        val local = GuardText.failText(fail(count = 0, local = true))
        assertTrue(local.contains("своей сети"))
        assertFalse(local.contains("блокировк"))
        val known = GuardText.failText(fail(count = 0, known = true))
        assertTrue(known.contains("знакомый адрес"))
        assertFalse(known.contains("блокировк"))
    }

    @Test
    fun `тексты блокировки различают автоматическую ручную и неудачную`() {
        val auto = GuardBanNote("b", 1L, "198.51.100.0/24", "198.51.100.7", 5, auto = true, ok = true, firewallOff = false, error = "")
        assertEquals("Подсеть заблокирована автоматически", GuardText.banTitle(auto))
        assertTrue(GuardText.banText(auto).contains("после 5 неудачных попыток"))
        val manual = auto.copy(auto = false, count = 0)
        assertEquals("Подсеть заблокирована", GuardText.banTitle(manual))
        assertEquals("Заблокирована подсеть 198.51.100.0/24", GuardText.banText(manual))
        assertTrue(GuardText.banText(auto.copy(firewallOff = true)).contains("Брандмауэр Windows на ПК выключен"))
        val failed = auto.copy(ok = false, error = "Помощник блокировки не установлен")
        assertEquals("Не удалось заблокировать 198.51.100.0/24", GuardText.banTitle(failed))
        assertEquals("Помощник блокировки не установлен", GuardText.banText(failed))
        assertTrue(GuardText.banText(failed.copy(error = "")).contains("Не удалось"))
    }

    @Test
    fun `варианты порога включают заданное с ПК значение`() {
        assertEquals(listOf(2, 3, 5, 10, 20), GuardText.banAfterOptions(5))
        assertEquals(listOf(2, 3, 5, 7, 10, 20), GuardText.banAfterOptions(7))
        assertEquals(listOf(2, 3, 5, 10, 20), GuardText.banAfterOptions(0))
    }

    @Test
    fun `сообщение по двум каналам даёт одно уведомление`() {
        val ids = RecentIds(capacity = 3)
        assertTrue(ids.firstTime("pc|a"))
        assertFalse(ids.firstTime("pc|a"))
        assertTrue(ids.firstTime("pc|b"))
        assertTrue(ids.firstTime("pc|c"))
        assertTrue(ids.firstTime("pc|d"))
        // «a» вытеснено из памяти ёмкостью 3: считается новым.
        assertTrue(ids.firstTime("pc|a"))
    }
}
