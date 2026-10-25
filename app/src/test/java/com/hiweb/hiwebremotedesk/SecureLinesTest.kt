package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.agent.SecureLines
import com.hiweb.hiwebremotedesk.agent.SwitchCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Связь с агентом по сети: подписи должны совпадать с теми, что считает агент на .NET (LanLink.cs). */
class SecureLinesTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val n1 = "1".repeat(32)
    private val n2 = "2".repeat(32)

    @Test
    fun handshakeMatchesAgent() {
        assertEquals("FhKQnMcxWNarEzNfTdPvXH5DXucHGEzEi9JIa6b0CVs=", SecureLines.authMac(secret, "pc-1", n1, n2))
        assertEquals("NrbfepYl8Pt8kDTKnfTp/IWzS3yvW1p+VU1LihTcX04=", SecureLines.okMac(secret, n1, n2))
    }

    @Test
    fun linesAreSignedPerDirectionAndSequence() {
        val lines = SecureLines(SecureLines.sessionKey(secret, n1, n2))
        assertEquals("eNVwmYRJpI9nurjkIoPbcoBh2F8FzRRCIKTF0Gvsnh4= {\"t\":\"ping\"}", lines.sign("{\"t\":\"ping\"}"))
        val fromAgent = "2KlbwGzwowgFr3J/XHabjiBulQ80gb4Siq9kUJgABVU= {\"t\":\"caps\"}"
        assertEquals("{\"t\":\"caps\"}", lines.verify(fromAgent))
        // Повтор той же строки уже не проходит: номер ожидаемой строки сдвинулся.
        assertNull(lines.verify(fromAgent))
    }

    @Test
    fun tamperedLineIsRejected() {
        val lines = SecureLines(SecureLines.sessionKey(secret, n1, n2))
        assertNull(lines.verify("2KlbwGzwowgFr3J/XHabjiBulQ80gb4Siq9kUJgABVU= {\"t\":\"capz\"}"))
    }

    @Test
    fun screenDecryptsLikeAgentEncrypts() {
        val lines = SecureLines(SecureLines.sessionKey(secret, n1, n2))
        val iv = ByteArray(16) { it.toByte() }
        // Шифротекст получен из собранного HiWebAgent.exe (SecureLines.Encrypt с тем же IV).
        val data = java.util.Base64.getDecoder().decode("WCO71rtZkEdZ3IGxMv5P2g==")
        assertEquals("hello screen", lines.decrypt(iv, data)?.toString(Charsets.UTF_8))
        data[0] = (data[0] + 1).toByte()
        assertNull(lines.decrypt(iv, data)?.toString(Charsets.UTF_8)?.takeIf { it == "hello screen" })
    }

    @Test
    fun discoveryMatchesAgent() {
        val cmd = SwitchCommand()
        val ts = 1_700_000_000_000L
        assertEquals(
            SwitchCommand.Result.NEW,
            cmd.check("pc-1", ts, "0123456789abcdef0123", "243Vo0M0xbnH7tnAXu5UzXN56Mln2gYqKDRw7mDII4k=", secret, ts, type = "where"),
        )
        assertEquals("dg4hKp8CdGcGEF8kksxk1zPv58G1e2EtjASJPComU50=", SwitchCommand.replyMac(secret, "here", "0123456789abcdef0123"))
    }
}
