package com.hiweb.hiwebremotedesk.agent

/**
 * Подписанные строки связи с агентом по локальной сети (см. LanLink.cs): «mac json», где
 * mac = HMAC(K, dir|seq|json), K = HMAC(secret, session|n1|n2). Планшет пишет с dir «t», агент — с «a»;
 * seq — номер строки в своём направлении, поэтому подмена, повтор и пропуск строки обнаруживаются.
 * Строки не шифруются, кроме содержимого экрана: его агент шифрует AES-256-CBC ключом HMAC(K, enc) ([decrypt]).
 */
class SecureLines(private val key: ByteArray) {
    private var sent = 0L
    private var received = 0L
    private val encKey = hmac(key, "enc")

    /** Расшифровывает поле, зашифрованное агентом; null — данные испорчены. */
    fun decrypt(iv: ByteArray, data: ByteArray): ByteArray? = runCatching {
        val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(encKey, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        c.doFinal(data)
    }.getOrNull()

    @Synchronized
    fun sign(body: String): String {
        val mac = AgentPairing.mac(key, "t|$sent|$body")
        sent++
        return "$mac $body"
    }

    /** Тело строки от агента или null, если подпись не сходится. */
    @Synchronized
    fun verify(line: String): String? {
        val space = line.indexOf(' ')
        if (space <= 0) return null
        val body = line.substring(space + 1)
        val expected = AgentPairing.mac(key, "a|$received|$body")
        if (!java.security.MessageDigest.isEqual(expected.toByteArray(), line.substring(0, space).toByteArray())) return null
        received++
        return body
    }

    companion object {
        fun authMac(secret: ByteArray, pc: String, n1: String, n2: String) = AgentPairing.mac(secret, "auth|$pc|$n1|$n2")
        fun okMac(secret: ByteArray, n1: String, n2: String) = AgentPairing.mac(secret, "ok|$n1|$n2")
        fun sessionKey(secret: ByteArray, n1: String, n2: String): ByteArray = hmac(secret, "session|$n1|$n2")

        private fun hmac(key: ByteArray, text: String): ByteArray {
            val m = javax.crypto.Mac.getInstance("HmacSHA256")
            m.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
            return m.doFinal(text.toByteArray(Charsets.UTF_8))
        }
    }
}
