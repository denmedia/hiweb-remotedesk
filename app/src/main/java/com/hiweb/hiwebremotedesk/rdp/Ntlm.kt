package com.hiweb.hiwebremotedesk.rdp

import java.security.SecureRandom

/**
 * Клиентская часть NTLMv2 (MS-NLMP) с расширенной защитой сеанса (sign/seal через RC4).
 * Используется как внутренний механизм CredSSP.
 */
class NtlmClient(
    private val user: String,
    private val domain: String,
    private val password: String,
    private val workstation: String = "ANDROID",
    private val targetSpn: String? = null,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Добавлять флаг MIC и SPN в AV-пары; отключается только в тестах со значениями из спецификации. */
    private val augmentTargetInfo: Boolean = true,
) {
    private lateinit var negotiateMsg: ByteArray

    private var sendSigningKey = ByteArray(0)
    private var recvSigningKey = ByteArray(0)
    private var sendRc4: Rc4? = null
    private var recvRc4: Rc4? = null
    private var sendSeq = 0
    private var recvSeq = 0

    fun negotiate(): ByteArray {
        val w = ByteWriter()
        w.bytes(SIGNATURE).u32le(1)
        w.u32le(NEGOTIATE_FLAGS)
        // DomainNameFields, WorkstationFields (пустые, смещение = 40)
        w.u16le(0).u16le(0).u32le(40)
        w.u16le(0).u16le(0).u32le(40)
        w.bytes(VERSION)
        negotiateMsg = w.toByteArray()
        return negotiateMsg
    }

    /** Строит AUTHENTICATE по CHALLENGE от сервера и инициализирует ключи sign/seal. */
    fun authenticate(challengeMsg: ByteArray, exportedKeyOverride: ByteArray? = null, clientChallengeOverride: ByteArray? = null): ByteArray {
        val r = ByteReader(challengeMsg)
        if (!r.bytes(8).contentEquals(SIGNATURE) || r.u32le() != 2L) throw RdpException("Некорректное NTLM CHALLENGE-сообщение")
        r.skip(8) // TargetNameFields
        val serverFlags = r.u32le()
        val serverChallenge = r.bytes(8)
        r.skip(8)
        val tiLen = r.u16le(); r.skip(2)
        val tiOff = r.u32le().toInt()
        val targetInfo = challengeMsg.copyOfRange(tiOff, tiOff + tiLen)

        val av = parseAvPairs(targetInfo)
        val serverTimestamp = av[AV_TIMESTAMP]
        val timestamp = serverTimestamp ?: unixToFiletime(clock()).let { t -> ByteWriter(8).u64le(t).toByteArray() }

        // Обновляем список AV-пар: флаг «MIC присутствует» и SPN цели.
        if (augmentTargetInfo) {
            val flagsOld = av[AV_FLAGS]?.let { ByteReader(it).u32le() } ?: 0L
            av[AV_FLAGS] = ByteWriter(4).u32le(flagsOld or 0x2L).toByteArray()
            if (targetSpn != null) av[AV_TARGET_NAME] = targetSpn.toByteArray(Charsets.UTF_16LE)
        }
        val serverName = encodeAvPairs(av)

        val clientChallenge = clientChallengeOverride ?: ByteArray(8).also { random.nextBytes(it) }
        val ntowf = ntowfV2(password, user, domain)
        val temp = ByteWriter()
            .u8(1).u8(1).zeros(6)
            .bytes(timestamp)
            .bytes(clientChallenge)
            .zeros(4)
            .bytes(serverName)
            .zeros(4)
            .toByteArray()
        val ntProof = Crypto.hmacMd5(ntowf, serverChallenge, temp)
        val ntResponse = ntProof + temp
        val sessionBaseKey = Crypto.hmacMd5(ntowf, ntProof)
        // Если сервер прислал метку времени, LM-ответ не отправляется.
        val lmResponse = ByteArray(24)

        val keyExch = (serverFlags and KEY_EXCH.toLong()) != 0L
        val exportedKey = if (keyExch) (exportedKeyOverride ?: ByteArray(16).also { random.nextBytes(it) }) else sessionBaseKey
        val encryptedKey = if (keyExch) Rc4(sessionBaseKey).process(exportedKey) else ByteArray(0)

        // Как и штатные клиенты, возвращаем согласованные сервером флаги.
        val flags = serverFlags

        val domainB = domain.toByteArray(Charsets.UTF_16LE)
        val userB = user.toByteArray(Charsets.UTF_16LE)
        val wsB = workstation.toByteArray(Charsets.UTF_16LE)

        val headerLen = 8 + 4 + 6 * 8 + 4 + 8 + 16
        var off = headerLen
        fun field(w: ByteWriter, len: Int): Int {
            w.u16le(len).u16le(len).u32le(off)
            val cur = off
            off += len
            return cur
        }
        val w = ByteWriter()
        w.bytes(SIGNATURE).u32le(3)
        field(w, lmResponse.size)
        field(w, ntResponse.size)
        field(w, domainB.size)
        field(w, userB.size)
        field(w, wsB.size)
        field(w, encryptedKey.size)
        w.u32le(flags)
        w.bytes(VERSION)
        val micPos = w.size
        w.zeros(16)
        w.bytes(lmResponse).bytes(ntResponse).bytes(domainB).bytes(userB).bytes(wsB).bytes(encryptedKey)
        val auth = w.toByteArray()

        val mic = Crypto.hmacMd5(exportedKey, negotiateMsg, challengeMsg, auth)
        mic.copyInto(auth, micPos)

        sendSigningKey = Crypto.md5(exportedKey, CLIENT_SIGN_MAGIC)
        recvSigningKey = Crypto.md5(exportedKey, SERVER_SIGN_MAGIC)
        sendRc4 = Rc4(Crypto.md5(exportedKey, CLIENT_SEAL_MAGIC))
        recvRc4 = Rc4(Crypto.md5(exportedKey, SERVER_SEAL_MAGIC))
        sendSeq = 0
        recvSeq = 0
        return auth
    }

    /** Шифрует сообщение: 16 байт подписи + зашифрованные данные (как EncryptMessage в SSPI). */
    fun seal(message: ByteArray): ByteArray {
        val rc4 = sendRc4 ?: throw IllegalStateException("NTLM: контекст не установлен")
        val seqBytes = ByteWriter(4).u32le(sendSeq).toByteArray()
        val sealed = rc4.process(message)
        val checksum = rc4.process(Crypto.hmacMd5(sendSigningKey, seqBytes, message).copyOf(8))
        sendSeq++
        return ByteWriter(16 + sealed.size).u32le(1).bytes(checksum).bytes(seqBytes).bytes(sealed).toByteArray()
    }

    /** Расшифровывает сообщение сервера и проверяет подпись. */
    fun unseal(data: ByteArray): ByteArray {
        val rc4 = recvRc4 ?: throw IllegalStateException("NTLM: контекст не установлен")
        if (data.size < 16) throw RdpException("NTLM: слишком короткое сообщение")
        val checksum = data.copyOfRange(4, 12)
        val seq = data.copyOfRange(12, 16)
        val plain = rc4.process(data.copyOfRange(16, data.size))
        val expected = rc4.process(Crypto.hmacMd5(recvSigningKey, seq, plain).copyOf(8))
        if (!expected.contentEquals(checksum)) throw RdpException("NTLM: неверная подпись сообщения сервера")
        recvSeq++
        return plain
    }

    companion object {
        private val SIGNATURE = "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII)
        private val VERSION = byteArrayOf(6, 1, 0xB1.toByte(), 0x1D, 0, 0, 0, 15)

        const val NEGOTIATE_UNICODE = 0x00000001
        const val REQUEST_TARGET = 0x00000004
        const val NEGOTIATE_SIGN = 0x00000010
        const val NEGOTIATE_SEAL = 0x00000020
        const val NEGOTIATE_NTLM = 0x00000200
        const val NEGOTIATE_ALWAYS_SIGN = 0x00008000
        const val NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000
        const val NEGOTIATE_VERSION = 0x02000000
        const val NEGOTIATE_128 = 0x20000000
        const val KEY_EXCH = 0x40000000
        const val NEGOTIATE_56 = 0x80000000.toInt()

        const val NEGOTIATE_FLAGS = NEGOTIATE_UNICODE or REQUEST_TARGET or NEGOTIATE_SIGN or NEGOTIATE_SEAL or
            NEGOTIATE_NTLM or NEGOTIATE_ALWAYS_SIGN or NEGOTIATE_EXTENDED_SESSIONSECURITY or NEGOTIATE_VERSION or
            NEGOTIATE_128 or KEY_EXCH or NEGOTIATE_56

        const val AV_EOL = 0
        const val AV_FLAGS = 6
        const val AV_TIMESTAMP = 7
        const val AV_TARGET_NAME = 9

        private fun magic(s: String) = (s + "\u0000").toByteArray(Charsets.US_ASCII)
        private val CLIENT_SIGN_MAGIC = magic("session key to client-to-server signing key magic constant")
        private val SERVER_SIGN_MAGIC = magic("session key to server-to-client signing key magic constant")
        private val CLIENT_SEAL_MAGIC = magic("session key to client-to-server sealing key magic constant")
        private val SERVER_SEAL_MAGIC = magic("session key to server-to-client sealing key magic constant")

        fun ntowfV2(password: String, user: String, domain: String): ByteArray {
            val nt = Crypto.md4(password.toByteArray(Charsets.UTF_16LE))
            return Crypto.hmacMd5(nt, (user.uppercase() + domain).toByteArray(Charsets.UTF_16LE))
        }

        /** Разбор AV-пар с сохранением порядка. */
        fun parseAvPairs(data: ByteArray): LinkedHashMap<Int, ByteArray> {
            val map = LinkedHashMap<Int, ByteArray>()
            val r = ByteReader(data)
            while (r.remaining >= 4) {
                val id = r.u16le()
                val len = r.u16le()
                if (id == AV_EOL) break
                map[id] = r.bytes(len)
            }
            return map
        }

        fun encodeAvPairs(map: Map<Int, ByteArray>): ByteArray {
            val w = ByteWriter()
            for ((id, v) in map) w.u16le(id).u16le(v.size).bytes(v)
            w.u16le(AV_EOL).u16le(0)
            return w.toByteArray()
        }

        fun unixToFiletime(ms: Long): Long = (ms + 11644473600000L) * 10000L
    }
}
