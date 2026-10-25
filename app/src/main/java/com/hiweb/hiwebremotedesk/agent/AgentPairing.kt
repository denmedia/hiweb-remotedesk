package com.hiweb.hiwebremotedesk.agent

import android.content.Context
import java.util.Base64
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Привязка планшета к агентам на ПК для команды «Переключиться на Планшет». Для каждого ПК (устройства в
 * списке) хранится случайный ключ; агент получает его по зашифрованному каналу RDP и подписывает им команды,
 * которые шлёт по локальной сети, когда планшет не подключён.
 */
class AgentPairing(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("agent_pairing", Context.MODE_PRIVATE)

    /** Ключ для ПК; создаётся при первом обращении. */
    fun secretFor(deviceId: String): ByteArray {
        prefs.getString(deviceId, null)?.let { return Base64.getDecoder().decode(it) }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(deviceId, Base64.getEncoder().encodeToString(secret)).apply()
        return secret
    }

    fun existingSecret(deviceId: String): ByteArray? = prefs.getString(deviceId, null)?.let { Base64.getDecoder().decode(it) }

    fun hasAny(): Boolean = prefs.all.isNotEmpty()

    fun forget(deviceId: String) = prefs.edit().remove(deviceId).apply()

    companion object {
        /** UDP-порт, на котором планшет ждёт команду «Переключиться на Планшет». */
        const val PORT = 47853

        fun mac(secret: ByteArray, text: String): String {
            val m = Mac.getInstance("HmacSHA256")
            m.init(SecretKeySpec(secret, "HmacSHA256"))
            return Base64.getEncoder().encodeToString(m.doFinal(text.toByteArray(Charsets.UTF_8)))
        }

        /** IPv4-адреса планшета в локальных сетях (агент пробует их до широковещательной рассылки). */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
