package com.hiweb.hiwebremotedesk.rdp

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/** Минимальный DER-кодер/декодер для структур CredSSP. */
object Der {
    fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray =
        ByteWriter(content.size + 4).u8(tag).bytes(length(content.size)).bytes(content).toByteArray()

    fun sequence(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    fun context(n: Int, content: ByteArray) = tlv(0xA0 or n, content)
    fun octets(b: ByteArray) = tlv(0x04, b)
    fun integer(v: Int): ByteArray {
        val raw = when {
            v < 0x80 -> byteArrayOf(v.toByte())
            v < 0x8000 -> byteArrayOf((v ushr 8).toByte(), v.toByte())
            else -> byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        }
        return tlv(0x02, raw)
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val w = ByteWriter()
        parts.forEach { w.bytes(it) }
        return w.toByteArray()
    }

    class Node(val tag: Int, val data: ByteArray, val start: Int, val end: Int) {
        fun children(): List<Node> {
            val list = ArrayList<Node>()
            var p = start
            while (p < end) {
                val n = read(data, p, end)
                list += n
                p = n.nextPos
            }
            return list
        }

        internal var nextPos = end
        fun content(): ByteArray = data.copyOfRange(start, end)
    }

    fun read(data: ByteArray, pos: Int, limit: Int = data.size): Node {
        if (pos + 2 > limit) throw RdpException("DER: усечённые данные")
        var p = pos
        val tag = data[p++].toInt() and 0xFF
        var len = data[p++].toInt() and 0xFF
        if (len >= 0x80) {
            val n = len and 0x7F
            if (n == 0 || n > 3 || p + n > limit) throw RdpException("DER: неверная длина")
            len = 0
            repeat(n) { len = (len shl 8) or (data[p++].toInt() and 0xFF) }
        }
        if (p + len > limit) throw RdpException("DER: длина превышает данные")
        return Node(tag, data, p, p + len).also { it.nextPos = p + len }
    }

    /** Читает один полный DER-элемент из потока. */
    fun readFrom(input: InputStream): ByteArray {
        val w = ByteWriter()
        val tag = input.read()
        if (tag < 0) throw RdpException("Соединение закрыто во время CredSSP")
        w.u8(tag)
        var len = input.read()
        if (len < 0) throw RdpException("Соединение закрыто во время CredSSP")
        w.u8(len)
        if (len >= 0x80) {
            val n = len and 0x7F
            len = 0
            repeat(n) {
                val b = input.read()
                if (b < 0) throw RdpException("Соединение закрыто во время CredSSP")
                w.u8(b)
                len = (len shl 8) or b
            }
        }
        val body = ByteArray(len)
        var got = 0
        while (got < len) {
            val r = input.read(body, got, len - got)
            if (r < 0) throw RdpException("Соединение закрыто во время CredSSP")
            got += r
        }
        w.bytes(body)
        return w.toByteArray()
    }

    /** Достаёт RSAPublicKey (содержимое BIT STRING) из SubjectPublicKeyInfo. */
    fun subjectPublicKey(spki: ByteArray): ByteArray {
        val top = read(spki, 0)
        val kids = top.children()
        val bits = kids.getOrNull(1) ?: throw RdpException("Некорректный открытый ключ сертификата")
        val c = bits.content()
        return c.copyOfRange(1, c.size) // пропускаем байт «неиспользуемых бит»
    }
}

/**
 * CredSSP (MS-CSSP) поверх уже установленного TLS-канала с NTLM внутри.
 * Передаёт учётные данные серверу только после проверки привязки к открытому ключу TLS.
 */
object CredSsp {
    private const val MY_VERSION = 6
    private val CLIENT_BINDING = "CredSSP Client-To-Server Binding Hash\u0000".toByteArray(Charsets.US_ASCII)
    private val SERVER_BINDING = "CredSSP Server-To-Client Binding Hash\u0000".toByteArray(Charsets.US_ASCII)

    private class TsRequest(val version: Int, val negoToken: ByteArray?, val pubKeyAuth: ByteArray?, val errorCode: Long?)

    private fun encode(version: Int, negoToken: ByteArray? = null, authInfo: ByteArray? = null, pubKeyAuth: ByteArray? = null, nonce: ByteArray? = null): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts += Der.context(0, Der.integer(version))
        if (negoToken != null) parts += Der.context(1, Der.sequence(Der.sequence(Der.context(0, Der.octets(negoToken)))))
        if (authInfo != null) parts += Der.context(2, Der.octets(authInfo))
        if (pubKeyAuth != null) parts += Der.context(3, Der.octets(pubKeyAuth))
        if (nonce != null) parts += Der.context(5, Der.octets(nonce))
        return Der.sequence(*parts.toTypedArray())
    }

    private fun decode(raw: ByteArray): TsRequest {
        val top = Der.read(raw, 0)
        var version = 2
        var nego: ByteArray? = null
        var pub: ByteArray? = null
        var err: Long? = null
        for (n in top.children()) {
            when (n.tag) {
                0xA0 -> version = Der.read(n.data, n.start, n.end).content().fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xFF) }
                0xA1 -> {
                    val seq = Der.read(n.data, n.start, n.end).children().firstOrNull()
                    val ctx = seq?.children()?.firstOrNull()
                    nego = ctx?.children()?.firstOrNull()?.content()
                }
                0xA3 -> pub = Der.read(n.data, n.start, n.end).content()
                0xA4 -> err = Der.read(n.data, n.start, n.end).content().fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) } and 0xFFFFFFFFL
            }
        }
        return TsRequest(version, nego, pub, err)
    }

    private fun check(r: TsRequest) {
        val code = r.errorCode ?: return
        Log.w("RDP", "CredSSP errorCode=0x${code.toString(16)} version=${r.version}")
        throw RdpException(
            when (code) {
                0xC000006DL, 0xC000006AL -> "Неверное имя пользователя или пароль. Для учётной записи Microsoft нужен её пароль, а не PIN-код Windows Hello"
                0xC0000234L -> "Учётная запись заблокирована"
                0xC0000071L, 0xC0000224L -> "Срок действия пароля истёк"
                0xC000006EL, 0xC0000070L, 0xC0000072L -> "Вход запрещён для этой учётной записи"
                else -> "Ошибка проверки подлинности CredSSP (0x${code.toString(16)})"
            },
        )
    }

    fun authenticate(
        input: InputStream,
        output: OutputStream,
        serverPublicKey: ByteArray,
        user: String,
        domain: String,
        password: String,
        host: String,
        random: SecureRandom = SecureRandom(),
    ) {
        val ntlm = NtlmClient(user, domain, password, targetSpn = "TERMSRV/$host", random = random)

        output.write(encode(MY_VERSION, negoToken = ntlm.negotiate()))
        output.flush()

        val challenge = decode(Der.readFrom(input))
        check(challenge)
        val version = minOf(MY_VERSION, challenge.version)
        val token = challenge.negoToken ?: throw RdpException("CredSSP: сервер не прислал NTLM CHALLENGE")
        val authMsg = ntlm.authenticate(token)

        val nonce = if (version >= 5) ByteArray(32).also { random.nextBytes(it) } else null
        val bindingPlain = if (nonce != null) Crypto.sha256(CLIENT_BINDING, nonce, serverPublicKey) else serverPublicKey
        output.write(encode(version, negoToken = authMsg, pubKeyAuth = ntlm.seal(bindingPlain), nonce = nonce))
        output.flush()

        val reply = decode(Der.readFrom(input))
        check(reply)
        val pubAuth = reply.pubKeyAuth ?: throw RdpException("CredSSP: сервер не подтвердил ключ")
        val echoed = ntlm.unseal(pubAuth)
        val expected = if (nonce != null) {
            Crypto.sha256(SERVER_BINDING, nonce, serverPublicKey)
        } else {
            serverPublicKey.copyOf().also { it[0] = (it[0] + 1).toByte() }
        }
        if (!echoed.contentEquals(expected)) throw RdpException("CredSSP: ключ TLS не совпал, возможна подмена сервера")

        val pwdCreds = Der.sequence(
            Der.context(0, Der.octets(domain.toByteArray(Charsets.UTF_16LE))),
            Der.context(1, Der.octets(user.toByteArray(Charsets.UTF_16LE))),
            Der.context(2, Der.octets(password.toByteArray(Charsets.UTF_16LE))),
        )
        val tsCreds = Der.sequence(Der.context(0, Der.integer(1)), Der.context(1, Der.octets(pwdCreds)))
        output.write(encode(version, authInfo = ntlm.seal(tsCreds)))
        output.flush()
    }
}
