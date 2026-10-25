package com.hiweb.hiwebremotedesk.data

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Пробуждение ПК по сети (Wake-on-LAN): «волшебный пакет» — 6 байт 0xFF и 16 повторов MAC-адреса — в UDP.
 * Спящий ПК не отвечает на ARP, поэтому в локальной сети пакет шлётся широковещательно. Из внешней сети —
 * на адрес ПК (роутер должен пробросить UDP [PORT] на широковещательный адрес или держать постоянную запись ARP).
 */
object WakeOnLan {
    const val PORT = 9

    /** Порт 7 (echo) — запасной: часть сетевых карт и роутеров ждут пакет на нём. */
    private val PORTS = intArrayOf(PORT, 7)

    /** MAC в виде «00:1A:2B:3C:4D:5E», «00-1a-2b-3c-4d-5e» или «001A2B3C4D5E»; null — не MAC. */
    fun parseMac(text: String): ByteArray? {
        val hex = text.trim().filterNot { it == ':' || it == '-' || it == '.' || it == ' ' }
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val mac = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return mac.takeUnless { b -> b.all { it == 0.toByte() } || b.all { it == 0xFF.toByte() } }
    }

    /** Единый вид для хранения и показа: «00:1A:2B:3C:4D:5E». */
    fun formatMac(mac: ByteArray): String = mac.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    fun packet(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        val p = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
        for (i in 0 until 16) mac.copyInto(p, 6 + i * 6)
        return p
    }

    /** Широковещательный адрес подсети: адрес с единицами во всех битах узла. */
    fun broadcastOf(address: Inet4Address, prefixLength: Int): Inet4Address {
        val ip = address.address
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val bits = (prefixLength - i * 8).coerceIn(0, 8)
            val hostMask = (0xFF ushr bits) and 0xFF
            out[i] = ((ip[i].toInt() and 0xFF) or hostMask).toByte()
        }
        return InetAddress.getByAddress(out) as Inet4Address
    }

    /**
     * Шлёт пакет на все [targets] (адреса или имена) по портам 9 и 7. Блокирует поток (разрешение имён).
     * Возвращает число адресов, куда пакет ушёл.
     */
    fun send(mac: ByteArray, targets: Collection<String>): Int {
        val data = packet(mac)
        var sent = 0
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (target in targets.distinct()) {
                val address = runCatching { InetAddress.getByName(target) }.getOrNull() ?: continue
                var ok = false
                for (port in PORTS) {
                    runCatching { socket.send(DatagramPacket(data, data.size, address, port)) }.onSuccess { ok = true }
                }
                if (ok) sent++
            }
        }
        return sent
    }
}
