package com.hiweb.hiwebremotedesk.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object Probe {
    /** Проверяет, принимает ли хост TCP-подключения на порту. */
    fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }
}

/** Периодически проверяет доступность устройств (null — ещё не проверено). */
class ReachabilityMonitor(private val scope: CoroutineScope) {
    private val _status = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val status: StateFlow<Map<String, Boolean>> = _status.asStateFlow()
    private var job: Job? = null

    fun start(devices: () -> List<Device>) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refresh(devices())
                delay(8000)
            }
        }
    }

    suspend fun refresh(devices: List<Device>) = coroutineScope {
        val results = devices.map { d -> async { d.id to Probe.isOpen(d.host, d.port, 1500) } }.awaitAll().toMap()
        _status.value = results
    }

    fun stop() { job?.cancel() }
}

/** Что ответил порт на запрос RDP (запрашивается только TLS, без NLA). */
enum class RdpReply { NONE, TLS, NLA_REQUIRED, OTHER }

/** Определённая система узла и подходящие для неё настройки подключения. */
data class HostProfile(val os: OsType, val port: Int, val rdpFound: Boolean) {
    val label: String get() = os.title
}

/**
 * Определяет ОС узла по открытым портам и ответам служб, без учётных данных:
 * баннер SSH (Windows с OpenSSH называет себя «OpenSSH_for_Windows») и ответ RDP-служб на портах 3389 и 3390.
 * Порт 445 не годится: на Ubuntu он открыт из-за Samba.
 * Порт Ubuntu выбирается по ответу: GNOME Remote Desktop сразу требует NLA, xrdp принимает «чистый» TLS.
 */
object OsDetector {
    private val RDP_PORTS = intArrayOf(3389, 3390)

    fun detect(host: String): HostProfile {
        val banner = sshBanner(host)
        val replies = RDP_PORTS.map { probeRdp(host, it) }
        return decide(banner, replies[0], replies[1])
    }

    fun decide(sshBanner: String?, rdp3389: RdpReply, rdp3390: RdpReply): HostProfile {
        val ssh = sshBanner.orEmpty()
        val linux = ssh.startsWith("SSH-") && !ssh.contains("windows", ignoreCase = true)
        if (!linux) {
            val found = rdp3389 != RdpReply.NONE
            return HostProfile(OsType.WINDOWS, 3389, found)
        }
        // Приложение работает с GNOME Remote Desktop: предпочитаем порт, который требует NLA, затем любой другой RDP.
        val replies = listOf(3390 to rdp3390, 3389 to rdp3389)
        replies.firstOrNull { it.second == RdpReply.NLA_REQUIRED }?.let { return HostProfile(OsType.UBUNTU, it.first, true) }
        replies.firstOrNull { it.second != RdpReply.NONE }?.let { return HostProfile(OsType.UBUNTU, it.first, true) }
        return HostProfile(OsType.UBUNTU, OsType.UBUNTU.defaultPort, false)
    }

    private fun sshBanner(host: String): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, 22), 600)
            s.soTimeout = 800
            s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()?.take(200)
        }
    } catch (_: Exception) {
        null
    }

    private fun probeRdp(host: String, port: Int): RdpReply = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 600)
            s.soTimeout = 1200
            // X.224 Connection Request, RDP_NEG_REQ: только TLS.
            s.getOutputStream().write(
                byteArrayOf(3, 0, 0, 19, 14, 0xE0.toByte(), 0, 0, 0, 0, 0, 1, 0, 8, 0, 1, 0, 0, 0),
            )
            val buf = ByteArray(64)
            val n = s.getInputStream().read(buf)
            parseNegotiation(buf, n)
        }
    } catch (_: Exception) {
        RdpReply.NONE
    }

    fun parseNegotiation(buf: ByteArray, n: Int): RdpReply {
        if (n < 6 || buf[0].toInt() != 3 || (buf[5].toInt() and 0xFF) != 0xD0) return RdpReply.NONE
        if (n < 19) return RdpReply.OTHER
        val value = buf[15].toInt() and 0xFF
        return when (buf[11].toInt()) {
            2 -> if (value == 1) RdpReply.TLS else if (value and 2 != 0) RdpReply.NLA_REQUIRED else RdpReply.OTHER
            3 -> if (value == 5) RdpReply.NLA_REQUIRED else RdpReply.OTHER
            else -> RdpReply.OTHER
        }
    }
}

data class FoundHost(val ip: String, val name: String, val profile: HostProfile)

/** Поиск компьютеров с открытым RDP-портом в локальной подсети (/24). */
object NetworkScanner {
    fun localIpv4(context: Context): Pair<Inet4Address, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp: LinkProperties = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        return (la.address as Inet4Address) to la.prefixLength
    }

    suspend fun scan(context: Context, onProgress: (Int, Int) -> Unit): List<FoundHost> {
        val (self, _) = localIpv4(context) ?: return emptyList()
        val base = self.address
        val hosts = (1..254).map { "${base[0].toInt() and 0xFF}.${base[1].toInt() and 0xFF}.${base[2].toInt() and 0xFF}.$it" }
            .filter { it != self.hostAddress }
        val gate = Semaphore(96)
        var done = 0
        return withContext(Dispatchers.IO) {
            val open = hosts.map { ip ->
                async {
                    val ok = gate.withPermit { Probe.isOpen(ip, 3389, 400) || Probe.isOpen(ip, 3390, 400) }
                    synchronized(this@NetworkScanner) { done++; onProgress(done, hosts.size) }
                    if (ok) ip else null
                }
            }.awaitAll().filterNotNull()
            open.map { ip ->
                async {
                    val name = withTimeoutOrNull(1500) { runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull() }
                    val profile = OsDetector.detect(ip)
                    FoundHost(ip, name?.takeIf { it != ip }?.substringBefore('.') ?: ip, profile)
                }
            }.awaitAll().filter { it.profile.rdpFound || Probe.isOpen(it.ip, 3389, 400) }.sortedBy { it.ip.substringAfterLast('.').toInt() }
        }
    }
}
