package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.OsDetector
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.RdpReply
import org.junit.Assert.assertEquals
import org.junit.Test

class OsDetectorTest {
    private val ubuntuBanner = "SSH-2.0-OpenSSH_10.0p2 Ubuntu-5ubuntu5"

    @Test fun windowsWithoutSshIsWindows() {
        val p = OsDetector.decide(null, RdpReply.NLA_REQUIRED, RdpReply.NONE)
        assertEquals(OsType.WINDOWS, p.os)
        assertEquals(3389, p.port)
        assertEquals(true, p.rdpFound)
    }

    @Test fun windowsWithOpenSshIsStillWindows() {
        val p = OsDetector.decide("SSH-2.0-OpenSSH_for_Windows_9.5", RdpReply.TLS, RdpReply.NONE)
        assertEquals(OsType.WINDOWS, p.os)
    }

    @Test fun ubuntuWithOnlyPlainRdpOnDefaultPort() {
        val p = OsDetector.decide(ubuntuBanner, RdpReply.TLS, RdpReply.NONE)
        assertEquals(OsType.UBUNTU, p.os)
        assertEquals(3389, p.port)
    }

    @Test fun ubuntuWithGnomeOnly() {
        val p = OsDetector.decide(ubuntuBanner, RdpReply.NONE, RdpReply.NLA_REQUIRED)
        assertEquals(3390, p.port)
    }

    @Test fun ubuntuWithBothPrefersNlaPort() {
        val p = OsDetector.decide(ubuntuBanner, RdpReply.TLS, RdpReply.NLA_REQUIRED)
        assertEquals(3390, p.port)
    }

    @Test fun ubuntuWithoutRdpIsReportedNotFound() {
        val p = OsDetector.decide(ubuntuBanner, RdpReply.NONE, RdpReply.NONE)
        assertEquals(OsType.UBUNTU, p.os)
        assertEquals(false, p.rdpFound)
    }

    @Test fun parsesNegotiationReplies() {
        val tls = byteArrayOf(3, 0, 0, 19, 14, 0xD0.toByte(), 0, 0, 0x12, 0x34, 0, 2, 1, 8, 0, 1, 0, 0, 0)
        val fail = byteArrayOf(3, 0, 0, 19, 14, 0xD0.toByte(), 0, 0, 0x12, 0x34, 0, 3, 0, 8, 0, 5, 0, 0, 0)
        assertEquals(RdpReply.TLS, OsDetector.parseNegotiation(tls, 19))
        assertEquals(RdpReply.NLA_REQUIRED, OsDetector.parseNegotiation(fail, 19))
        assertEquals(RdpReply.NONE, OsDetector.parseNegotiation(ByteArray(10), 10))
    }
}
