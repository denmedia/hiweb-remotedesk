package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.AgentReport
import com.hiweb.hiwebremotedesk.data.PcState
import com.hiweb.hiwebremotedesk.data.PcStatusRules
import com.hiweb.hiwebremotedesk.data.WakeOnLan
import com.hiweb.hiwebremotedesk.data.WakeTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class PcStatusTest {
    private val now = 1_000_000_000L

    private fun report(state: String, session: String = "none", idle: Int = 600, ageMs: Long = 60_000) =
        AgentReport(state, session, idle, now - ageMs)

    private fun derive(port: Boolean?, linked: Boolean, r: AgentReport?) = PcStatusRules.derive(port, linked, r, now).state

    @Test
    fun parsesMacInCommonForms() {
        val expected = byteArrayOf(0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E)
        assertArrayEquals(expected, WakeOnLan.parseMac("00:1A:2B:3C:4D:5E"))
        assertArrayEquals(expected, WakeOnLan.parseMac("00-1a-2b-3c-4d-5e"))
        assertArrayEquals(expected, WakeOnLan.parseMac(" 001A2B3C4D5E "))
        assertEquals("00:1A:2B:3C:4D:5E", WakeOnLan.formatMac(expected))
        assertNull(WakeOnLan.parseMac("00:1A:2B:3C:4D"))
        assertNull(WakeOnLan.parseMac("GG:1A:2B:3C:4D:5E"))
        assertNull(WakeOnLan.parseMac("00:00:00:00:00:00"))
        assertNull(WakeOnLan.parseMac(""))
    }

    @Test
    fun magicPacketIsSixFfAndSixteenMacs() {
        val mac = WakeOnLan.parseMac("00:1A:2B:3C:4D:5E")!!
        val p = WakeOnLan.packet(mac)
        assertEquals(102, p.size)
        for (i in 0 until 6) assertEquals(0xFF.toByte(), p[i])
        for (n in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + n * 6, 12 + n * 6))
    }

    @Test
    fun broadcastAddressOfSubnet() {
        fun bcast(ip: String, prefix: Int) = WakeOnLan.broadcastOf(InetAddress.getByName(ip) as Inet4Address, prefix).hostAddress
        assertEquals("192.168.1.255", bcast("192.168.1.6", 24))
        assertEquals("10.0.15.255", bcast("10.0.8.1", 20))
        assertEquals("172.16.5.7", bcast("172.16.5.7", 32))
    }

    @Test
    fun wakeAddressesIncludeBroadcastsAndHost() {
        val r = AgentReport("sleep", "none", 0, now, listOf(WakeTarget("00:1A:2B:3C:4D:5E", "192.168.1.255")))
        assertEquals(
            listOf("255.255.255.255", "192.168.1.255", "my.example.net"),
            PcStatusRules.wakeAddresses("my.example.net", "192.168.1.255", r),
        )
    }

    @Test
    fun withoutAgentOnlyPortDecides() {
        assertEquals(PcState.CHECKING, derive(null, false, null))
        assertEquals(PcState.ONLINE, derive(true, false, null))
        assertEquals(PcState.OFFLINE, derive(false, false, null))
    }

    @Test
    fun linkedAgentDescribesSession() {
        assertEquals(PcState.ONLINE, derive(false, true, null))
        assertEquals(PcState.LOCKED, derive(true, true, report("locked", "console", idle = 5)))
        assertEquals(PcState.IN_USE, derive(true, true, report("active", "console", idle = 30)))
        assertEquals(PcState.ONLINE, derive(true, true, report("active", "console", idle = 600)))
        assertEquals(PcState.ON_TABLET, derive(true, true, report("active", "tablet")))
        assertEquals(PcState.REMOTE, derive(true, true, report("active", "remote")))
        assertEquals(PcState.ONLINE, derive(true, true, report("active", "none")))
        // Без связи с агентом подробности сеанса устарели: остаётся только «онлайн» по порту.
        assertEquals(PcState.ONLINE, derive(true, false, report("locked", "console")))
        assertEquals(PcState.OFFLINE, derive(false, false, report("active", "console")))
    }

    @Test
    fun sleepingPcStaysAsleepUntilItAnswers() {
        val sleep = report("sleep", ageMs = 3_600_000)
        assertEquals(PcState.SLEEP, derive(false, false, sleep))
        assertEquals(PcState.SLEEP, derive(null, false, sleep))
        assertEquals(now - 3_600_000, PcStatusRules.derive(false, false, sleep, now).since)
        assertEquals(PcState.HIBERNATE, derive(false, false, report("hibernate")))
        assertEquals(PcState.SHUTDOWN, derive(false, false, report("shutdown")))
        // Разбудили или включили без планшета: порт снова открыт.
        assertEquals(PcState.ONLINE, derive(true, false, sleep))
        // Только что сообщил о сне: проверка порта могла застать его ещё не уснувшим.
        assertEquals(PcState.SLEEP, derive(true, true, report("sleep", ageMs = 1_000)))
    }

    @Test
    fun restartAndLogoff() {
        assertEquals(PcState.RESTARTING, derive(false, false, report("restart", ageMs = 60_000)))
        assertEquals(PcState.OFFLINE, derive(false, false, report("restart", ageMs = 10 * 60_000)))
        assertEquals(PcState.ONLINE, derive(true, false, report("restart", ageMs = 60_000)))
        assertEquals(PcState.SIGNED_OUT, derive(true, false, report("logoff")))
        assertEquals(PcState.OFFLINE, derive(false, false, report("logoff")))
    }
}
