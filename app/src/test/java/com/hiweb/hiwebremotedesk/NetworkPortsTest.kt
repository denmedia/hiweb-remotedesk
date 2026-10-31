package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.NetworkPorts
import com.hiweb.hiwebremotedesk.data.OsType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPortsTest {
    private val agent = NetworkPorts.AGENT_RANGE
    private val ssh = NetworkPorts.SSH_RANGE

    @Test
    fun emptyFieldIsValidAndMeansDefault() {
        assertTrue(NetworkPorts.isValid("", agent))
        assertTrue(NetworkPorts.isValid("  ", agent))
        assertNull(NetworkPorts.parse("", agent, NetworkPorts.AGENT_DEFAULT))
    }

    @Test
    fun agentPortCannotBePrivileged() {
        // Приложение Android не может слушать порты ниже 1024.
        assertFalse(NetworkPorts.isValid("1023", agent))
        assertTrue(NetworkPorts.isValid("1024", agent))
        assertTrue(NetworkPorts.isValid("65535", agent))
        assertFalse(NetworkPorts.isValid("65536", agent))
        assertFalse(NetworkPorts.isValid("0", ssh))
        assertTrue(NetworkPorts.isValid("22", ssh))
    }

    @Test
    fun defaultNumberIsStoredAsNull() {
        assertNull(NetworkPorts.parse("47853", agent, NetworkPorts.AGENT_DEFAULT))
        assertEquals(50000, NetworkPorts.parse("50000", agent, NetworkPorts.AGENT_DEFAULT))
        assertNull(NetworkPorts.parse("22", ssh, NetworkPorts.SSH_DEFAULT))
        assertEquals(2222, NetworkPorts.parse("2222", ssh, NetworkPorts.SSH_DEFAULT))
        assertNull(NetworkPorts.parse("abc", agent, NetworkPorts.AGENT_DEFAULT))
    }

    @Test
    fun deviceFallsBackToDefaults() {
        val d = Device("1", "PC", "198.51.100.7")
        assertEquals(NetworkPorts.AGENT_DEFAULT, d.agentPortNumber)
        assertEquals(NetworkPorts.SSH_DEFAULT, d.sshPortNumber)
        assertEquals(50000, d.copy(agentPort = 50000).agentPortNumber)
    }

    @Test
    fun listenedPortsAreDefaultPlusCustomOfWindowsDevices() {
        val devices = listOf(
            Device("1", "A", "198.51.100.1"),
            Device("2", "B", "198.51.100.2", agentPort = 50000),
            Device("3", "C", "198.51.100.3", agentPort = 50000),
            Device("4", "D", "198.51.100.4", agentPort = 50001),
            Device("5", "U", "198.51.100.5", os = OsType.UBUNTU, agentPort = 50002),
        )
        assertEquals(setOf(NetworkPorts.AGENT_DEFAULT, 50000, 50001), NetworkPorts.agentPorts(devices))
        assertEquals(setOf(NetworkPorts.AGENT_DEFAULT), NetworkPorts.agentPorts(emptyList()))
    }
}
