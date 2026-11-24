package com.hiweb.hiwebremotedesk.ui

import com.hiweb.hiwebremotedesk.agent.AgentPower
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.PcState
import com.hiweb.hiwebremotedesk.rdp.PowerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardActionsTest {
    private val pc = Device(id = "1", name = "PC", host = "198.51.100.10")

    @Test
    fun withoutAgent_sessionPowerAndLogout() {
        val slots = CardActions.powerSlots(pc, PcState.ONLINE, null)
        assertEquals(listOf("Выключить", "Перезагрузка", "Сон", "Гибернация", "Выйти"), slots.map { it.label })
        assertEquals(CardPower.Session(PowerAction.SHUTDOWN), slots[0].command)
        assertEquals(CardPower.Session(PowerAction.REBOOT), slots[1].command)
        assertEquals(CardPower.Session(PowerAction.LOGOUT), slots[4].command)
        assertEquals(listOf(true, true, false, false, true), slots.map { it.enabled })
    }

    @Test
    fun withoutAgent_offlinePcDisablesSessionPower() {
        val slots = CardActions.powerSlots(pc, PcState.OFFLINE, null)
        assertTrue(slots.none { it.enabled })
        assertEquals("Разбудить", CardActions.connectLabel(PcState.OFFLINE, null))
        assertFalse(CardActions.connectEnabled(pc, PcState.OFFLINE, null))
    }

    @Test
    fun withAgent_onlyReportedModes() {
        val slots = CardActions.powerSlots(pc, PcState.ONLINE, setOf(AgentPower.SHUTDOWN, AgentPower.SLEEP))
        assertEquals(4, slots.size)
        assertEquals(AgentPower.entries.map { CardPower.Agent(it) }, slots.map { it.command })
        assertEquals(listOf(true, false, true, false), slots.map { it.enabled })
        assertTrue(CardActions.connectEnabled(pc, PcState.SLEEP, emptySet()))
    }

    @Test
    fun wakeableSleepingPc_canConnect() {
        val wakeable = pc.copy(wakeMac = "00:11:22:33:44:55", os = OsType.WINDOWS)
        assertTrue(CardActions.connectEnabled(wakeable, PcState.SLEEP, null))
    }
}
