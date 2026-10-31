package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.GuardData
import com.hiweb.hiwebremotedesk.data.GuardEntry
import com.hiweb.hiwebremotedesk.data.GuardJson
import com.hiweb.hiwebremotedesk.data.GuardState
import com.hiweb.hiwebremotedesk.data.GuardText
import com.hiweb.hiwebremotedesk.data.GuardThreat
import com.hiweb.hiwebremotedesk.data.GuardWhitelistEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Белый список и режим защиты на стороне планшета: разбор `guard_state`, хранение, тексты (без Android). */
class GuardWhitelistTest {
    private fun threat(rule: String) =
        GuardThreat("t", 1L, rule, ip = "198.51.100.71", cidr = "198.51.100.0/24", count = 3, user = "PC\\user", afterFail = false, status = GuardThreat.STATUS_NEW)

    private fun entry(kind: String, local: Boolean = false, trusted: Boolean = false) =
        GuardEntry("e", 1L, kind, ip = "198.51.100.71", user = "", local = local, trusted = trusted)

    @Test
    fun `состояние агента с белым списком разбирается целиком`() {
        val state = GuardJson.state(
            JSONObject(
                """{"t":"guard_state","fw":false,"port":3389,"blocked":[{"cidr":"203.0.113.0/24","on":true}],
                   "mode":"whitelist","autoLearn":false,"learnSubnet":true,
                   "whitelist":[{"cidr":"198.51.100.71/32","src":"auto","ts":1000},{"cidr":"192.0.2.0/24","src":"manual","ts":2000},{"cidr":""}]}""",
            ),
        )
        assertFalse(state.firewallOn)
        assertEquals("whitelist", state.mode)
        assertTrue(state.supportsWhitelist)
        assertTrue(state.whitelistMode)
        assertFalse(state.autoLearn)
        assertTrue(state.learnSubnet)
        assertEquals(
            listOf(GuardWhitelistEntry("198.51.100.71/32", manual = false, time = 1000), GuardWhitelistEntry("192.0.2.0/24", manual = true, time = 2000)),
            state.whitelist,
        )
        assertEquals(1, state.blocked.size)
    }

    @Test
    fun `агент без белого списка узнаётся по отсутствию режима`() {
        val old = GuardJson.state(JSONObject("""{"t":"guard_state","fw":true,"blocked":[],"trusted":["198.51.100.71"]}"""))
        assertNull(old.mode)
        assertFalse(old.supportsWhitelist)
        assertFalse(old.whitelistMode)
        assertTrue(old.whitelist.isEmpty())
        // По умолчанию адрес планшета запоминается (так вёл себя агент и до режимов), подсеть — нет.
        assertTrue(old.autoLearn)
        assertFalse(old.learnSubnet)
    }

    @Test
    fun `обычный режим не считается белым списком`() {
        val state = GuardJson.state(JSONObject("""{"mode":"normal"}"""))
        assertTrue(state.supportsWhitelist)
        assertFalse(state.whitelistMode)
    }

    @Test
    fun `состояние с белым списком переживает запись на диск`() {
        val state = GuardState(
            firewallOn = true, mode = GuardState.MODE_WHITELIST, autoLearn = true, learnSubnet = true,
            whitelist = listOf(GuardWhitelistEntry("198.51.100.0/24", manual = true, time = 5L), GuardWhitelistEntry("2001:db8:1:2::/64", manual = false, time = 6L)),
        )
        val restored = GuardJson.dataFrom(GuardJson.toJson(GuardData(state = state))).state
        assertEquals(state, restored)
        // Состояние старого агента после записи и чтения остаётся «без белого списка».
        val old = GuardJson.dataFrom(GuardJson.toJson(GuardData(state = GuardState()))).state
        assertNull(old!!.mode)
    }

    @Test
    fun `тексты новых угроз`() {
        assertEquals("Попытка входа с адреса вне белого списка", GuardText.threatTitle(threat(GuardThreat.UNLISTED)))
        assertEquals(
            "Неудачных попыток входа за 10 минут: 3. Адрес 198.51.100.71 (подсеть 198.51.100.0/24) не входит в белый список",
            GuardText.threatText(threat(GuardThreat.UNLISTED)),
        )
        assertEquals("Вход с нового адреса: занести в белый список?", GuardText.threatTitle(threat(GuardThreat.NEW_ADDRESS)))
        val text = GuardText.threatText(threat(GuardThreat.NEW_ADDRESS))
        assertTrue(text.startsWith("PC\\user вошёл в сеанс с 198.51.100.71, планшет к сеансу подключился"))
        assertTrue(text.contains("занесите"))
        assertEquals("Подбор пароля по RDP", GuardText.threatTitle(threat(GuardThreat.BRUTEFORCE)))
        assertEquals("в белом списке", GuardText.statusText(GuardThreat.STATUS_TRUSTED))
    }

    @Test
    fun `в режиме белого списка подозрительны и входы с чужого адреса`() {
        assertTrue(GuardText.entrySuspicious(entry(GuardEntry.FAIL)))
        assertFalse(GuardText.entrySuspicious(entry(GuardEntry.LOGON)))
        assertTrue(GuardText.entrySuspicious(entry(GuardEntry.LOGON), whitelistMode = true))
        assertFalse(GuardText.entrySuspicious(entry(GuardEntry.LOGON, trusted = true), whitelistMode = true))
        assertFalse(GuardText.entrySuspicious(entry(GuardEntry.LOGON, local = true), whitelistMode = true))
        assertFalse(GuardText.entrySuspicious(entry(GuardEntry.LOGOFF), whitelistMode = true))
    }

    @Test
    fun `подсеть адреса для белого списка`() {
        assertEquals("198.51.100.71/24", GuardText.subnetOf("198.51.100.71"))
        assertEquals("2001:db8:1:2:3:4:5:6/64", GuardText.subnetOf("2001:db8:1:2:3:4:5:6"))
    }

    @Test
    fun `запись белого списка показывается без длины у одного адреса`() {
        assertEquals("198.51.100.71", GuardText.cidr("198.51.100.71/32"))
        assertEquals("2001:db8::1", GuardText.cidr("2001:db8::1/128"))
        assertEquals("198.51.100.0/24", GuardText.cidr("198.51.100.0/24"))
        assertEquals("запомнен по подключению планшета", GuardText.whitelistSource(false))
        assertEquals("добавлен вручную", GuardText.whitelistSource(true))
    }

    @Test
    fun `поле ввода принимает адреса и подсети и отсекает мусор`() {
        for (ok in listOf("198.51.100.71", "198.51.100.0/24", " 198.51.100.0/24 ", "2001:db8:1:2::/64", "2001:db8::1", "::ffff:198.51.100.7")) {
            assertTrue(ok, GuardText.looksLikeAddress(ok))
        }
        for (bad in listOf("", "   ", "abc", "198.51.100", "198.51.100.71/", "198.51.100.71/abc", "1.2.3.4.5", "example.com", "198.51.100.71 /24")) {
            assertFalse(bad, GuardText.looksLikeAddress(bad))
        }
    }
}
