package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.agent.AgentPower
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.PcState
import com.hiweb.hiwebremotedesk.ui.ButtonHints
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonHintsTest {
    @Test fun hibernateWithAgentExplainsPowercfg() {
        val h = ButtonHints.power(OsType.WINDOWS, AgentPower.HIBERNATE, agentOnline = true, pcUp = true, state = PcState.ONLINE)
        assertTrue(h.text.contains("powercfg /hibernate on"))
    }

    @Test fun noAgentOnlinePcExplainsInstall() {
        val h = ButtonHints.power(OsType.WINDOWS, AgentPower.SLEEP, agentOnline = false, pcUp = true, state = PcState.ONLINE)
        assertTrue(h.text.contains("Установить агент"))
    }

    @Test fun noAgentOfflinePcSaysPcIsDown() {
        val h = ButtonHints.power(OsType.WINDOWS, AgentPower.SLEEP, agentOnline = false, pcUp = false, state = PcState.SHUTDOWN)
        assertTrue(h.text.contains("выключен"))
    }

    @Test fun ubuntuSleepIsNotSupported() {
        val h = ButtonHints.power(OsType.UBUNTU, AgentPower.SLEEP, agentOnline = false, pcUp = true, state = PcState.ONLINE)
        assertTrue(h.title.contains("Ubuntu"))
    }

    @Test fun sleepingPcAsksForMac() {
        assertTrue(ButtonHints.connect(PcState.SLEEP).text.contains("MAC"))
    }
}
