package com.hiweb.hiwebremotedesk.agent

/**
 * Проверка команды «Переключиться на Планшет» от агента (UDP, см. windows-agent/PROTOCOL.md): подпись
 * HMAC-SHA256 общим ключом ПК, свежесть метки времени и однократность nonce (повтор перехваченной команды
 * не откроет сеанс второй раз).
 */
class SwitchCommand(private val maxSkewMs: Long = MAX_SKEW_MS) {
    enum class Result {
        /** Новая подлинная команда: подтвердить и открыть сеанс. */
        NEW,

        /** Копия уже принятой команды (агент шлёт её на несколько адресов и повторяет): только подтвердить. */
        REPEAT,

        /** Неверная подпись или устаревшая метка времени: не отвечать. */
        INVALID,
    }

    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun check(pc: String, ts: Long, nonce: String, mac: String, secret: ByteArray, nowMs: Long, type: String = "switch"): Result {
        if (nonce.length < 16 || kotlin.math.abs(nowMs - ts) > maxSkewMs) return Result.INVALID
        val expected = AgentPairing.mac(secret, "$type|$pc|$ts|$nonce")
        if (!java.security.MessageDigest.isEqual(expected.toByteArray(), mac.toByteArray())) return Result.INVALID
        seen.entries.removeAll { nowMs - it.value > 2 * maxSkewMs }
        if (seen.containsKey(nonce)) return Result.REPEAT
        seen[nonce] = nowMs
        return Result.NEW
    }

    companion object {
        /** Допустимое расхождение часов ПК и планшета. */
        const val MAX_SKEW_MS = 5 * 60_000L

        fun ackMac(secret: ByteArray, nonce: String) = replyMac(secret, "ack", nonce)

        /** Подпись ответа: «here» на поиск планшета, «ack» на переключение. */
        fun replyMac(secret: ByteArray, replyType: String, nonce: String) = AgentPairing.mac(secret, "$replyType|$nonce")
    }
}
