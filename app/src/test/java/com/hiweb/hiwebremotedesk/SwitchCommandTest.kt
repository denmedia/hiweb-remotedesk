package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.agent.AgentPairing
import com.hiweb.hiwebremotedesk.agent.SwitchCommand
import com.hiweb.hiwebremotedesk.agent.SwitchCommand.Result
import org.junit.Assert.assertEquals
import org.junit.Test

/** Команда «Переключиться на Планшет»: подпись должна совпадать с той, что считает агент на .NET. */
class SwitchCommandTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val nonce = "0123456789abcdef0123"
    private val ts = 1_700_000_000_000L

    // Значения посчитаны HMACSHA256 + Convert.ToBase64String в .NET, как в TabletSwitch.cs.
    private val dotNetMac = "LAnQuIV399yHsqir16WFHQr2STbCvgUd00+6HJa1R3o="
    private val dotNetAck = "J8eh9FIJ4mBtUTttIOY47SFVPu0LkGYk7OPAXo+ppXs="

    @Test
    fun macMatchesAgent() {
        assertEquals(dotNetMac, AgentPairing.mac(secret, "switch|pc-1|$ts|$nonce"))
        assertEquals(dotNetAck, SwitchCommand.ackMac(secret, nonce))
    }

    @Test
    fun acceptsOnceThenTreatsCopiesAsRepeats() {
        val cmd = SwitchCommand()
        assertEquals(Result.NEW, cmd.check("pc-1", ts, nonce, dotNetMac, secret, ts + 2_000))
        assertEquals(Result.REPEAT, cmd.check("pc-1", ts, nonce, dotNetMac, secret, ts + 2_500))
    }

    @Test
    fun rejectsForgedStaleOrForeign() {
        val cmd = SwitchCommand()
        assertEquals(Result.INVALID, cmd.check("pc-1", ts, nonce, dotNetMac.replace('L', 'M'), secret, ts))
        assertEquals(Result.INVALID, cmd.check("pc-1", ts, nonce, dotNetMac, secret, ts + SwitchCommand.MAX_SKEW_MS + 1))
        assertEquals(Result.INVALID, cmd.check("pc-2", ts, nonce, dotNetMac, secret, ts))
        assertEquals(Result.INVALID, cmd.check("pc-1", ts, nonce, dotNetMac, ByteArray(32), ts))
    }
}
