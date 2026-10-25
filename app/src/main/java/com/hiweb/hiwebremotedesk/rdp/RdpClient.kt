package com.hiweb.hiwebremotedesk.rdp

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class RdpConfig(
    val host: String,
    val port: Int = 3389,
    val username: String,
    val password: String,
    val width: Int = 1920,
    val height: Int = 1200,
    /** SHA-256 сертификата, которому доверяем (получен при первом подключении). */
    val pinnedCertSha256: String? = null,
    /** false — запрашиваем только TLS, учётные данные вводятся на экране входа Windows. */
    val useNla: Boolean = true,
    /** Синхронизация текстового буфера обмена (канал cliprdr). */
    val clipboard: Boolean = true,
    /** Обмен файлами через буфер обмена (тот же канал cliprdr). */
    val fileTransfer: Boolean = false,
    /** Имя диска, под которым папка устройства видна в Windows (канал rdpdr, если передан [DriveStorage]). */
    val driveName: String = "Tablet",
    /** Масштаб интерфейса Windows, % (100 — без масштабирования). */
    val scalePercent: Int = 100,
    /** Физический размер экрана в мм; Windows принимает значения 10–10000. */
    val physicalWidthMm: Int = 0,
    val physicalHeightMm: Int = 0,
    /** Глубина цвета, бит на пиксель: 16, 24 или 32. */
    val colorDepth: Int = 16,
    /**
     * true — только TLS 1.2 с обменом ключами RSA (нужно самоподписанному сертификату Windows).
     * false — стандартные настройки системы, как у xrdp и других серверов с обычным сертификатом.
     */
    val windowsTls: Boolean = true,
    /** Графический конвейер RDPEGFX (RemoteFX Progressive): единственный способ получить картинку от GNOME Remote Desktop. */
    val gfx: Boolean = false,
)

interface RdpListener {
    /** TLS-рукопожатие завершено; вызывается до проверки подлинности. */
    fun onCertificate(sha256: String) {}

    /** Сессия активна, можно рисовать и отправлять ввод. */
    fun onReady(width: Int, height: Int)

    /** Прямоугольник обновлённых пикселей (ARGB, сверху вниз). */
    fun onBitmap(left: Int, top: Int, width: Int, height: Int, argb: IntArray)

    /** На ПК скопирован текст. Вызывается из потока RDP. */
    fun onClipboardText(text: String) {}

    /** На ПК скопированы файлы (null — в буфере ПК файлов больше нет). Вызывается из потока RDP. */
    fun onRemoteFiles(files: List<RemoteFile>?) {}

    /** Курсор Windows изменился. Вызывается из потока RDP. */
    fun onCursor(cursor: RemoteCursor) {}

    /** Сервер сообщил размер рабочего стола (графический конвейер); обычно он отличается от запрошенного. */
    fun onDesktopSize(width: Int, height: Int) {}

    /** Соединение закрыто. [error] == null при штатном отключении. */
    fun onClosed(error: String?)
}

class CertificateChangedException(val newFingerprint: String) :
    Exception("Сертификат устройства изменился. Возможна подмена, подключение отменено.")

/**
 * Клиент RDP: X.224 → TLS → CredSSP/NTLM → MCS → обмен возможностями → битмап-обновления.
 * Поддерживается расширенная безопасность (TLS и NLA), графика через классические битмапы.
 */
class RdpClient(
    private val cfg: RdpConfig,
    private val listener: RdpListener,
    /** Если задан, звук Windows (канал rdpsnd) воспроизводится на устройстве. */
    private val audioSink: AudioSink? = null,
    /** Если задана, эта папка подключается к Windows как диск (канал rdpdr). */
    private val drive: DriveStorage? = null,
    /** Дополнительные динамические каналы по имени (например, канал агента на ПК). */
    private val dynamicChannels: Map<String, DvcHandler> = emptyMap(),
    /** Канал rdpdr нужен и без папки устройства: во время сеанса можно подключить диск через [attachDrive]. */
    private val attachableDrives: Boolean = false,
) {
    @Volatile private var socket: Socket? = null
    private lateinit var inp: InputStream
    private lateinit var out: OutputStream
    private val writeLock = Any()
    private val inputExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "rdp-input").apply { isDaemon = true } }
    private val random = SecureRandom()

    @Volatile private var closedByUser = false
    @Volatile private var ready = false
    /** Первая активация уже была: следующие Demand Active — смена размера, а не вход. */
    private var activated = false
    private var thread: Thread? = null

    /** Текущий размер рабочего стола: запрошенный при подключении или назначенный сервером позже. */
    @Volatile private var deskW = cfg.width
    @Volatile private var deskH = cfg.height
    private var display: DisplayControlChannel? = null

    private var userId = 0
    private var ioChannel = 1003
    private var clipChannelId = -1
    private var dvcChannelId = -1
    private var sndChannelId = -1
    private var driveChannelId = -1
    private var dvc: DynamicChannel? = null
    private var clip: ClipboardChannel? = null
    private var sound: SoundChannel? = null
    private var driveChannel: DriveChannel? = null
    private val channelSendLock = Any()
    private val clipUsed = cfg.clipboard || cfg.fileTransfer
    // Чтение и запись файлов не должны задерживать поток чтения сети: у каждого канала свой поток.
    private val fileExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "rdp-files").apply { isDaemon = true } }
    private val driveExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "rdp-drive").apply { isDaemon = true } }
    private var shareId = 0L
    private var licensed = false
    private var errorInfo = 0L
    private var lastToggle = -1
    @Volatile private var pendingClipboard: String? = null
    private val fragments = java.io.ByteArrayOutputStream()
    private var fragmentCode = 0
    private val pointerCache =arrayOfNulls<CursorImage>(POINTER_CACHE_SIZE)

    val isReady: Boolean get() = ready

    /** Текст локального буфера обмена; предлагается серверу и вставляется по Ctrl+V на ПК. */
    fun setClipboardText(text: String?) {
        pendingClipboard = text
        val c = clip ?: return
        try {
            inputExecutor.execute { runCatching { c.replaceLocalText(text) } }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** Файлы планшета предлагаются ПК (вставка в проводнике по Ctrl+V). Возвращает false, если обмен файлами недоступен. */
    fun offerFiles(files: List<LocalFile>): Boolean {
        val c = clip ?: return false
        if (!c.filesActive) return false
        try {
            inputExecutor.execute { runCatching { c.offerFiles(files) } }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return false
        }
        return true
    }

    /** Размер файла, скопированного на ПК; блокирует поток, вызывать не из потока интерфейса. */
    fun remoteFileSize(index: Int): Long = (clip ?: throw RdpException("Обмен файлами выключен")).remoteFileSize(index)

    /** Часть файла, скопированного на ПК; блокирует поток, вызывать не из потока интерфейса. */
    fun readRemoteFile(index: Int, position: Long, length: Int): ByteArray =
        (clip ?: throw RdpException("Обмен файлами выключен")).readRemoteFile(index, position, length)

    /** Подключает к Windows ещё один диск (id 2 и выше). Возвращает false, если канала дисков нет. */
    fun attachDrive(id: Int, name: String, storage: DriveStorage): Boolean {
        val ch = driveChannel ?: return false
        runInBackground(driveExecutor) { ch.attach(id, name, storage) }
        return true
    }

    fun detachDrive(id: Int) {
        val ch = driveChannel ?: return
        runInBackground(driveExecutor) { ch.detach(id) }
    }

    /** Диск объявлен серверу (виден в Windows как \\tsclient\имя). */
    fun isDriveAnnounced(id: Int): Boolean = driveChannel?.isAnnounced(id) == true

    fun start() {
        thread = Thread({ run() }, "rdp-session").apply { isDaemon = true; start() }
    }

    fun close() {
        closedByUser = true
        ready = false
        inputExecutor.shutdown()
        stopFileChannels()
        audioSink?.stop()
        runCatching { socket?.close() }
    }

    private fun stopFileChannels() {
        clip?.close()
        driveChannel?.close()
        fileExecutor.shutdown()
        driveExecutor.shutdown()
    }

    // ------------------------------------------------------------------ сессия

    private fun run() {
        var error: String? = null
        try {
            connect()
            readLoop()
        } catch (e: CertificateChangedException) {
            error = e.message
        } catch (e: Exception) {
            if (!closedByUser) Log.w(TAG, "Session failed", e)
            if (!closedByUser) error = errorInfoText(errorInfo) ?: friendly(e)
        } finally {
            ready = false
            runCatching { socket?.close() }
            inputExecutor.shutdown()
            stopFileChannels()
            audioSink?.stop()
            dvc?.closeAll()
            listener.onClosed(if (closedByUser) null else error ?: errorInfoText(errorInfo))
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is RdpException -> e.message ?: "Ошибка протокола RDP"
        is java.net.SocketTimeoutException -> "Устройство не отвечает"
        is java.net.ConnectException -> "Не удалось подключиться: отказано в соединении"
        is java.net.UnknownHostException -> "Неизвестный адрес устройства"
        is java.io.EOFException, is java.net.SocketException -> "Соединение разорвано"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun connect() {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(cfg.host, cfg.port), 7000)
        s.soTimeout = 20000
        inp = BufferedInputStream(s.getInputStream(), 1 shl 16)
        out = s.getOutputStream()

        Log.i(TAG, "TCP connected to ${cfg.host}:${cfg.port}")
        val protocol = x224Connect()
        Log.i(TAG, "X.224 negotiated protocol=$protocol")
        var plain: Socket = s
        if (protocol == PROTOCOL_SSL || protocol == PROTOCOL_HYBRID) {
            val ssl = upgradeTls(s)
            plain = ssl
            inp = BufferedInputStream(ssl.inputStream, 1 shl 16)
            out = ssl.outputStream
            socket = ssl
            Log.i(TAG, "TLS established")
            if (protocol == PROTOCOL_HYBRID) {
                val cert = ssl.session.peerCertificates[0] as X509Certificate
                val (domain, user) = splitUser(cfg.username)
                CredSsp.authenticate(inp, out, Der.subjectPublicKey(cert.publicKey.encoded), user, domain, cfg.password, cfg.host, random)
            }
        } else {
            throw RdpException("Сервер требует устаревшую защиту RDP, она не поддерживается")
        }

        Log.i(TAG, "Security layer done, MCS connect")
        mcsConnect(protocol)
        Log.i(TAG, "MCS done: userId=$userId io=$ioChannel; sending client info")
        sendClientInfo()
        plain.soTimeout = 0
    }

    private fun splitUser(raw: String): Pair<String, String> {
        val i = raw.indexOf('\\')
        return if (i > 0) raw.substring(0, i) to raw.substring(i + 1) else "" to raw
    }

    // ------------------------------------------------------------------ X.224 / TLS

    private fun x224Connect(): Int {
        val w = ByteWriter()
        w.u8(0x0E).u8(0xE0).u16be(0).u16be(0).u8(0)          // X.224 CR
        w.u8(1).u8(0).u16le(8).u32le(if (cfg.useNla) PROTOCOL_SSL or PROTOCOL_HYBRID else PROTOCOL_SSL) // RDP_NEG_REQ
        sendTpkt(w.toByteArray())

        val resp = readSlowPayload()
        if (resp.size < 6 || (resp[1].toInt() and 0xFF) != 0xD0) throw RdpException("Некорректный ответ сервера при подключении")
        if (resp.size < 15) throw RdpException("Сервер не поддерживает расширенную защиту RDP")
        val r = ByteReader(resp, 7)
        val type = r.u8()
        r.u8(); r.u16le()
        val value = r.u32le().toInt()
        if (type == 3) {
            throw RdpException(
                when (value) {
                    1 -> "Сервер требует TLS"
                    2 -> "Сервер не допускает TLS"
                    5 -> "Сервер требует проверку подлинности на уровне сети (NLA)"
                    6 -> "Для TLS нужны учётные данные пользователя"
                    else -> "Сервер отклонил подключение (код $value)"
                },
            )
        }
        if (type != 2) throw RdpException("Некорректный ответ согласования протокола")
        return value
    }

    private fun upgradeTls(s: Socket): SSLSocket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), random)
        val ssl = ctx.socketFactory.createSocket(s, cfg.host, cfg.port, true) as SSLSocket
        if (cfg.windowsTls) restrictToRsaSuites(ssl)
        ssl.startHandshake()
        Log.i(TAG, "TLS negotiated ${ssl.session.protocol} ${ssl.session.cipherSuite}")
        val cert = ssl.session.peerCertificates[0] as X509Certificate
        val fp = Crypto.sha256(cert.encoded).joinToString("") { "%02x".format(it) }
        listener.onCertificate(fp)
        val pinned = cfg.pinnedCertSha256
        if (pinned != null && !pinned.equals(fp, ignoreCase = true)) throw CertificateChangedException(fp)
        return ssl
    }

    private fun restrictToRsaSuites(ssl: SSLSocket) {
        // Самоподписанный сертификат Windows RDP разрешает только шифрование ключа (keyEncipherment) и не
        // имеет права подписи, поэтому ECDHE и TLS 1.3 отвергаются. Используем TLS 1.2 с обменом ключами по RSA.
        val rsaSuites = ssl.supportedCipherSuites
            .filter { it.startsWith("TLS_RSA_WITH_AES_") }
            .sortedBy { if (it.contains("GCM")) 0 else 1 }
        if (rsaSuites.isEmpty()) throw RdpException("Система не поддерживает наборы шифров, нужные для RDP")
        Log.i(TAG, "TLS suites: $rsaSuites")
        ssl.enabledProtocols = arrayOf("TLSv1.2")
        ssl.enabledCipherSuites = rsaSuites.toTypedArray()
    }

    // ------------------------------------------------------------------ MCS

    private fun mcsConnect(protocol: Int) {
        val gcc = clientDataBlocks(protocol)
        val ccr = ByteWriter()
        ccr.bytes(byteArrayOf(0, 5, 0, 0x14, 0x7C, 0, 1))
        ccr.u16be((gcc.size + 14) or 0x8000)
        ccr.bytes(byteArrayOf(0, 8, 0, 0x10, 0, 1, 0xC0.toByte(), 0, 0x44, 0x75, 0x63, 0x61))
        ccr.u16be(gcc.size or 0x8000)
        ccr.bytes(gcc)

        fun domainParams(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int) = Ber.sequence(
            Ber.integer(a), Ber.integer(b), Ber.integer(c), Ber.integer(d), Ber.integer(e), Ber.integer(f), Ber.integer(g), Ber.integer(h),
        )
        val initial = Ber.tlv(
            byteArrayOf(0x7F, 0x65),
            Ber.octets(byteArrayOf(1)) + Ber.octets(byteArrayOf(1)) + Ber.bool(true) +
                domainParams(34, 2, 0, 1, 0, 1, 65535, 2) +
                domainParams(1, 1, 1, 1, 0, 1, 1056, 2) +
                domainParams(65535, 65535, 65535, 1, 0, 1, 65535, 2) +
                Ber.octets(ccr.toByteArray()),
        )
        sendX224Data(initial)

        val resp = readMcs()
        // [APPLICATION 102] { result ENUMERATED, ... }
        if (resp.size < 8 || resp[0] != 0x7F.toByte() || resp[1] != 0x66.toByte()) throw RdpException("Некорректный MCS Connect-Response")
        var p = 2
        p += if ((resp[p].toInt() and 0x80) != 0) 1 + (resp[p].toInt() and 0x7F) else 1
        if (resp[p] != 0x0A.toByte() || resp[p + 2].toInt() != 0) throw RdpException("Сервер отклонил MCS-подключение")
        val net = parseServerNet(resp)
        ioChannel = net?.first ?: 1003
        // Сервер возвращает идентификаторы в том же порядке, в каком каналы запрошены в clientDataBlocks.
        val ids = net?.second.orEmpty()
        val byName = staticChannels().mapIndexed { i, ch -> ch.name to (ids.getOrNull(i) ?: -1) }.toMap()
        clipChannelId = byName[ClipboardChannel.CHANNEL_NAME] ?: -1
        dvcChannelId = byName[DynamicChannel.CHANNEL_NAME] ?: -1
        sndChannelId = byName[SoundChannel.CHANNEL_NAME] ?: -1
        driveChannelId = byName[DriveChannel.CHANNEL_NAME] ?: -1
        Log.i(TAG, "static channels: io=$ioChannel clip=$clipChannelId dvc=$dvcChannelId snd=$sndChannelId drive=$driveChannelId")
        if (sndChannelId > 0 && audioSink != null) {
            sound = SoundChannel(send = { sendChannel(sndChannelId, it) }, sink = audioSink, log = { Log.i(TAG, "rdpsnd: $it") })
        }
        if (driveChannelId > 0 && (drive != null || attachableDrives)) {
            driveChannel = DriveChannel(
                send = { sendChannel(driveChannelId, it) },
                storage = drive,
                driveName = cfg.driveName,
                io = { runInBackground(driveExecutor, it) },
                log = { Log.i(TAG, "rdpdr: $it") },
            )
        }
        if (dvcChannelId > 0) {
            dvc = DynamicChannel(
                send = { sendChannel(dvcChannelId, it) },
                handlers = buildMap {
                    put(MouseCursorChannel.CHANNEL_NAME, MouseCursorChannel { listener.onCursor(it) })
                    putAll(dynamicChannels)
                    if (cfg.gfx) {
                        // Без графического конвейера Windows по DisplayControl меняет размер у себя, но не
                        // сообщает его клиенту (нет повторного Demand Active), поэтому канал берём только с GFX.
                        put(DisplayControlChannel.CHANNEL_NAME, DisplayControlChannel { Log.i(TAG, it) }.also { display = it })
                        put(
                            GfxChannel.CHANNEL_NAME,
                            GfxChannel({ w, h ->
                                deskW = w; deskH = h
                                listener.onDesktopSize(w, h)
                            }) { l, t, w, h, argb -> listener.onBitmap(l, t, w, h, argb) },
                        )
                    }
                },
            )
        }
        if (clipChannelId > 0) {
            clip = ClipboardChannel(
                send = { sendChannel(clipChannelId, it) },
                onRemoteText = { listener.onClipboardText(it) },
                textEnabled = cfg.clipboard,
                filesEnabled = cfg.fileTransfer,
                onRemoteFiles = { listener.onRemoteFiles(it) },
                background = { runInBackground(fileExecutor, it) },
            ).also { it.localText = pendingClipboard }
        }

        sendX224Data(byteArrayOf(0x04, 0x01, 0x00, 0x01, 0x00)) // Erect Domain Request
        sendX224Data(byteArrayOf(0x28))                         // Attach User Request
        val att = readMcs()
        if (att.size < 4 || (att[0].toInt() and 0xFC) != 0x2C || att[1].toInt() != 0) throw RdpException("Сервер отказал в назначении MCS-пользователя")
        userId = (((att[2].toInt() and 0xFF) shl 8) or (att[3].toInt() and 0xFF)) + 1001

        val channels = listOf(userId, ioChannel, clipChannelId, dvcChannelId, sndChannelId, driveChannelId).filter { it > 0 }
        for (ch in channels) {
            sendX224Data(ByteWriter().u8(0x38).u16be(userId - 1001).u16be(ch).toByteArray())
            val c = readMcs()
            if (c.size < 3 || (c[0].toInt() and 0xFC) != 0x3C || c[1].toInt() != 0) throw RdpException("Сервер отказал в подключении к каналу $ch")
        }
    }

    /** Возвращает канал ввода-вывода и идентификаторы статических каналов в порядке запроса. */
    private fun parseServerNet(resp: ByteArray): Pair<Int, List<Int>>? {
        val marker = "McDn".toByteArray()
        var i = 0
        while (i < resp.size - 4 && !resp.copyOfRange(i, i + 4).contentEquals(marker)) i++
        if (i >= resp.size - 4) return null
        var p = i + 4
        p += if ((resp[p].toInt() and 0x80) != 0) 2 else 1
        while (p + 4 <= resp.size) {
            val type = (resp[p].toInt() and 0xFF) or ((resp[p + 1].toInt() and 0xFF) shl 8)
            val len = (resp[p + 2].toInt() and 0xFF) or ((resp[p + 3].toInt() and 0xFF) shl 8)
            if (len < 4) return null
            if (type == 0x0C03 && p + 6 <= resp.size) {
                val r = ByteReader(resp, p + 4, minOf(resp.size, p + len))
                val io = r.u16le()
                val count = if (r.remaining >= 2) r.u16le() else 0
                val ids = ArrayList<Int>()
                repeat(count) { if (r.remaining >= 2) ids += r.u16le() }
                return io to ids
            }
            p += len
        }
        return null
    }

    private fun clientDataBlocks(protocol: Int): ByteArray {
        val core = ByteWriter()
        val scaled = cfg.scalePercent != 100
        core.u16le(0xC001).u16le(if (scaled) 234 else 216)
        core.u32le(0x00080004)                 // RDP 5.0+
        core.u16le(cfg.width).u16le(cfg.height)
        core.u16le(0xCA01).u16le(0xAA03)       // 8 bpp (игнорируется), SAS
        core.u32le(0x0409)                     // раскладка: управляется сервером по скан-кодам
        core.u32le(2600)
        val name = "ANDROID".toByteArray(Charsets.UTF_16LE)
        core.bytes(name).zeros(32 - name.size)
        core.u32le(4).u32le(0).u32le(12)       // тип клавиатуры, функциональные клавиши
        core.zeros(64)                         // имя IME
        core.u16le(0xCA01).u16le(1).u32le(0)
        core.u16le(if (colorDepth == 32) 24 else colorDepth) // highColorDepth
        core.u16le(when (colorDepth) { 32 -> 0x0008 or 0x0001; 24 -> 0x0001; else -> 0x0002 }) // supportedColorDepths
        // ERRINFO_PDU [| WANT_32BPP_SESSION] [| SUPPORT_DYNVC_GFX_PROTOCOL]
        core.u16le(0x0001 or (if (colorDepth == 32) 0x0002 else 0) or (if (cfg.gfx) 0x0100 else 0))
        core.zeros(64)
        core.u8(0).u8(0)
        core.u32le(protocol)
        if (scaled) {
            // Расширение TS_UD_CS_CORE: физический размер, ориентация, масштаб рабочего стола и устройства.
            core.u32le(cfg.physicalWidthMm.coerceIn(10, 10000)).u32le(cfg.physicalHeightMm.coerceIn(10, 10000))
            core.u16le(0).u32le(cfg.scalePercent.coerceIn(100, 500)).u32le(100)
        }

        val sec = ByteWriter().u16le(0xC002).u16le(12).u32le(0).u32le(0)
        val channelDefs = ByteWriter()
        val channels = staticChannels()
        for (ch in channels) {
            val name = ch.name.toByteArray(Charsets.US_ASCII)
            channelDefs.bytes(name).zeros(8 - name.size).u32le(ch.options)
        }
        val netBlock = ByteWriter().u16le(0xC003).u16le(8 + channelDefs.size).u32le(channels.size).bytes(channelDefs.toByteArray())
        return core.toByteArray() + sec.toByteArray() + netBlock.toByteArray()
    }

    /** Графический конвейер работает только с 32 битами на пиксель. */
    private val colorDepth: Int get() = if (cfg.gfx) 32 else cfg.colorDepth

    private class StaticChannel(val name: String, val options: Long)

    /** Запрашиваемые статические каналы; идентификаторы сервер возвращает в этом же порядке. */
    private fun staticChannels(): List<StaticChannel> = buildList {
        // INITIALIZED | ENCRYPT_RDP | COMPRESS_RDP | SHOW_PROTOCOL
        if (clipUsed) add(StaticChannel(ClipboardChannel.CHANNEL_NAME, 0xC0A00000L))
        // INITIALIZED | ENCRYPT_RDP | COMPRESS_RDP
        add(StaticChannel(DynamicChannel.CHANNEL_NAME, 0xC0800000L))
        // INITIALIZED | ENCRYPT_RDP
        if (audioSink != null) add(StaticChannel(SoundChannel.CHANNEL_NAME, 0xC0000000L))
        if (drive != null || attachableDrives) add(StaticChannel(DriveChannel.CHANNEL_NAME, 0xC0800000L))
    }

    /** Ставит работу в очередь потока канала; после закрытия сеанса очередь уже остановлена, и работа отбрасывается. */
    private fun runInBackground(executor: java.util.concurrent.ExecutorService, work: Runnable) {
        try {
            executor.execute(work)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    // ------------------------------------------------------------------ Client Info / лицензия

    private fun sendClientInfo() {
        val (domain, user) = splitUser(cfg.username)
        // Автовход только если заданы и имя, и пароль; иначе сервер покажет экран входа Windows.
        val autoLogon = user.isNotEmpty() && cfg.password.isNotEmpty()
        // 0x80000 — INFO_NOAUDIOPLAYBACK: без звука сервер не создаёт в сеансе аудиоустройство.
        val flags = 0x01 or 0x02 or (if (autoLogon) 0x08 else 0) or 0x10 or 0x20 or 0x40 or 0x100 or 0x10000 or
            (if (audioSink == null) 0x80000 else 0)
        fun str(s: String) = s.toByteArray(Charsets.UTF_16LE)
        val d = str(if (autoLogon) domain else ""); val u = str(if (autoLogon) user else ""); val p = str(if (autoLogon) cfg.password else "")
        val w = ByteWriter()
        w.u16le(0x0040).u16le(0)                       // SEC_INFO_PKT
        w.u32le(0x0409).u32le(flags)
        w.u16le(d.size).u16le(u.size).u16le(p.size).u16le(0).u16le(0)
        w.bytes(d).u16le(0).bytes(u).u16le(0).bytes(p).u16le(0).u16le(0).u16le(0)
        // Extended info
        val addr = str("0.0.0.0")
        val dir = str("C:\\Windows\\System32\\mstscax.dll")
        w.u16le(2).u16le(addr.size + 2).bytes(addr).u16le(0)
        w.u16le(dir.size + 2).bytes(dir).u16le(0)
        w.zeros(172)
        w.u32le(0)
        // без анимации меню, перетаскивания окон с содержимым, тени курсора и особых схем курсора;
        // 0x80 — ENABLE_FONT_SMOOTHING: без него сервер рисует шрифты без сглаживания
        w.u32le(0xE6)
        w.u16le(0)
        sendMcsData(w.toByteArray())
    }

    // ------------------------------------------------------------------ основной цикл

    private fun readLoop() {
        while (true) {
            val b0 = inp.read()
            if (b0 < 0) throw java.io.EOFException()
            if (b0 == 0x03) {
                inp.read()
                val len = (inp.read() shl 8) or inp.read()
                val payload = readFully(len - 4)
                handleSlowPath(payload)
            } else {
                var len = inp.read()
                // Длина может быть записана двумя байтами и при значении до 127 (так делает xrdp).
                val twoByteLen = len and 0x80 != 0
                if (twoByteLen) len = ((len and 0x7F) shl 8) or inp.read()
                val hdr = if (twoByteLen) 3 else 2
                handleFastPath(readFully(len - hdr))
            }
        }
    }

    private fun handleSlowPath(payload: ByteArray) {
        // X.224 Data TPDU: LI, 0xF0, EOT
        if (payload.size < 3) return
        val mcs = payload.copyOfRange(3, payload.size)
        if (mcs.isEmpty()) return
        when (mcs[0].toInt() and 0xFC) {
            0x68 -> {
                val r = ByteReader(mcs, 1)
                r.skip(2)           // initiator
                val channel = r.u16be()
                r.u8()              // priority/segmentation
                var len = r.u8()
                if (len and 0x80 != 0) len = ((len and 0x7F) shl 8) or r.u8()
                val data = ByteReader(mcs, r.pos, minOf(mcs.size, r.pos + len))
                if (channel != ioChannel && channel != dvcChannelId) Log.i(TAG, "channel $channel data ${data.remaining} bytes")
                when {
                    channel == clipChannelId && channel > 0 -> clip?.onChannelData(data)
                    channel == dvcChannelId && channel > 0 -> dvc?.onChannelData(data)
                    channel == sndChannelId && channel > 0 -> sound?.onChannelData(data)
                    channel == driveChannelId && channel > 0 -> driveChannel?.onChannelData(data)
                    else -> {
                        if (channel != ioChannel) Log.i(TAG, "data on unexpected channel $channel (${data.remaining} bytes)")
                        handleUserData(data)
                    }
                }
            }
            0x20 -> throw RdpException(errorInfoText(errorInfo) ?: "Сервер завершил сеанс")
        }
    }

    private fun handleUserData(r: ByteReader) {
        if (!licensed) {
            val flags = r.u16le(); r.u16le()
            if (flags and 0x0080 != 0) {
                val msg = r.u8(); r.u8(); r.u16le()
                if (msg == 0xFF) {
                    val code = r.u32le()
                    if (code == 7L) { licensed = true; return }
                    throw RdpException("Ошибка лицензирования сервера (код $code)")
                }
                throw RdpException("Для этого сервера требуется лицензия клиента, она не поддерживается")
            }
            // Сервер пропустил лицензирование — это уже Share Control PDU.
            licensed = true
            r.pos -= 4
        }
        handleShareControl(r)
    }

    private fun handleShareControl(r: ByteReader) {
        if (r.remaining < 6) return
        val total = r.u16le()
        if (total == 0x8000) return
        val type = r.u16le() and 0x0F
        r.u16le()
        when (type) {
            1 -> onDemandActive(r)
            6 -> { ready = false }
            7 -> handleData(r)
        }
    }

    private fun handleData(r: ByteReader) {
        r.skip(4)
        r.skip(2)
        r.u16le()
        val type2 = r.u8()
        val compressed = r.u8()
        r.u16le()
        if (compressed and 0x20 != 0) return
        when (type2) {
            0x02 -> if (r.u16le() == 1) parseBitmapUpdate(r)
            0x1B -> handleSlowPathPointer(r)
            0x28 -> requestFullScreen()
            0x2F -> errorInfo = r.u32le()
        }
    }

    private fun handleSlowPathPointer(r: ByteReader) {
        val message = r.u16le(); r.u16le()
        when (message) {
            1 -> listener.onCursor(if (r.u32le() == 0L) RemoteCursor.Hidden else RemoteCursor.Default) // System Pointer
            6 -> handlePointer(POINTER_COLOR, r)
            7 -> handlePointer(POINTER_CACHED, r)
            8 -> handlePointer(POINTER_NEW, r)
        }
    }

    /** Курсор с формой хранится в кэше сервера по индексу; повреждённый курсор не должен рвать сеанс. */
    private fun handlePointer(kind: Int, r: ByteReader) {
        try {
            if (kind == POINTER_CACHED) {
                pointerCache.getOrNull(r.u16le())?.let { listener.onCursor(it) }
                return
            }
            val parsed = PointerDecoder.parse(r, newFormat = kind != POINTER_COLOR, large = kind == POINTER_LARGE)
            if (parsed.cacheIndex in pointerCache.indices) pointerCache[parsed.cacheIndex] = parsed.image
            listener.onCursor(parsed.image)
        } catch (e: RdpException) {
            Log.w(TAG, "pointer update ignored: ${e.message}")
        }
    }

    private fun handleFastPath(payload: ByteArray) {
        val r = ByteReader(payload)
        while (r.remaining >= 3) {
            val header = r.u8()
            val code = header and 0x0F
            val compression = (header shr 6) and 3
            if (compression != 0) r.u8()
            val size = r.u16le()
            if (size > r.remaining) return
            val data = ByteReader(payload, r.pos, r.pos + size)
            r.skip(size)
            // Фрагментация: 0 — целое обновление, 2 — первый фрагмент, 3 — средний, 1 — последний.
            when ((header shr 4) and 3) {
                0 -> handleFastPathUpdate(code, data)
                2 -> { fragments.reset(); fragments.write(payload, data.pos, size); fragmentCode = code }
                3 -> fragments.write(payload, data.pos, size)
                else -> {
                    fragments.write(payload, data.pos, size)
                    val whole = fragments.toByteArray()
                    fragments.reset()
                    handleFastPathUpdate(fragmentCode, ByteReader(whole))
                }
            }
        }
    }

    private fun handleFastPathUpdate(code: Int, data: ByteReader) {
        when (code) {
            1 -> if (data.u16le() == 1) parseBitmapUpdate(data)
            5 -> listener.onCursor(RemoteCursor.Hidden)
            6 -> listener.onCursor(RemoteCursor.Default)
            9 -> handlePointer(POINTER_COLOR, data)
            0x0A -> handlePointer(POINTER_CACHED, data)
            0x0B -> handlePointer(POINTER_NEW, data)
            0x0C -> handlePointer(POINTER_LARGE, data)
        }
    }

    private fun parseBitmapUpdate(r: ByteReader) {
        val count = r.u16le()
        repeat(count) {
            val left = r.u16le(); val top = r.u16le(); val right = r.u16le(); val bottom = r.u16le()
            val width = r.u16le(); val height = r.u16le()
            val bpp = r.u16le(); val flags = r.u16le()
            var len = r.u16le()
            val compressed = flags and 0x0001 != 0
            if (compressed && flags and 0x0400 == 0) { r.skip(8); len -= 8 }
            val start = r.pos
            r.skip(len)
            val pixels = BitmapDecoder.decode(r.data, start, len, width, height, bpp, compressed)
            if (pixels == null) Log.w(TAG, "bitmap decode failed: ${right - left + 1}x${bottom - top + 1} w=$width h=$height bpp=$bpp comp=$compressed len=$len flags=0x${flags.toString(16)}")
            if (pixels == null) return@repeat
            val visibleW = minOf(right - left + 1, width, deskW - left)
            val visibleH = minOf(bottom - top + 1, height, deskH - top)
            if (visibleW <= 0 || visibleH <= 0) return@repeat
            if (visibleW == width && visibleH == height) {
                listener.onBitmap(left, top, width, height, pixels)
            } else {
                val cropped = IntArray(visibleW * visibleH)
                for (y in 0 until visibleH) System.arraycopy(pixels, y * width, cropped, y * visibleW, visibleW)
                listener.onBitmap(left, top, visibleW, visibleH, cropped)
            }
        }
    }

    // ------------------------------------------------------------------ Demand/Confirm Active

    /**
     * Demand Active приходит при входе и повторно после Deactivate All, когда сервер меняет размер
     * рабочего стола (раскладка монитора по [resizeDesktop]). Размер берём из возможностей Bitmap.
     */
    private fun onDemandActive(r: ByteReader) {
        shareId = r.u32le()
        val srcLen = r.u16le()
        r.u16le()
        r.skip(srcLen)
        serverDesktopSize(r)?.let { (w, h) -> deskW = w; deskH = h }
        Log.i(TAG, "Demand Active, shareId=$shareId, desktop ${deskW}x$deskH")
        sendConfirmActive()
        sendData(0x1F, ByteWriter().u16le(1).u16le(1002).toByteArray())                 // Synchronize
        sendData(0x14, ByteWriter().u16le(4).u16le(0).u32le(0).toByteArray())           // Control: Cooperate
        sendData(0x14, ByteWriter().u16le(1).u16le(0).u32le(0).toByteArray())           // Control: Request
        sendData(0x27, ByteWriter().u16le(0).u16le(0).u16le(3).u16le(50).toByteArray()) // Font List
        if (!ready) {
            ready = true
            if (activated) {
                listener.onDesktopSize(deskW, deskH)
            } else {
                activated = true
                listener.onReady(deskW, deskH)
            }
        }
    }

    /** Ширина и высота из набора возможностей Bitmap (тип 2) в Demand Active. */
    private fun serverDesktopSize(r: ByteReader): Pair<Int, Int>? {
        if (r.remaining < 4) return null
        val count = r.u16le()
        r.u16le()
        repeat(count) {
            if (r.remaining < 4) return null
            val type = r.u16le()
            val len = r.u16le()
            if (len < 4 || len - 4 > r.remaining) return null
            if (type == 2 && len >= 16) {
                val body = ByteReader(r.data, r.pos, r.pos + len - 4)
                body.skip(8)
                val w = body.u16le()
                val h = body.u16le()
                return if (w > 0 && h > 0) w to h else null
            }
            r.skip(len - 4)
        }
        return null
    }

    /**
     * Просит сервер сменить размер рабочего стола (поворот планшета); новый размер придёт в
     * [RdpListener.onDesktopSize]. Если канал DisplayControl ещё не открыт, запрос ждёт его открытия;
     * сервер без этого канала размер не меняет. Возвращает false, если сеанс не открыт или канал
     * не используется (без графического конвейера размер меняют переподключением).
     */
    fun resizeDesktop(width: Int, height: Int, physicalWidthMm: Int, physicalHeightMm: Int): Boolean {
        if (!ready) return false
        if (width == deskW && height == deskH) return true
        val ch = display ?: return false
        if (width !in DisplayControlChannel.MIN_SIDE..DisplayControlChannel.MAX_SIDE || width % 2 != 0) return false
        // Вызывают из потока интерфейса, а запись в сокет там запрещена.
        runInBackground(inputExecutor) {
            runCatching { ch.requestSize(width, height, physicalWidthMm, physicalHeightMm, cfg.scalePercent) }
        }
        return true
    }

    /** Просим сервер разрешить обновления и перерисовать весь экран. */
    private fun requestFullScreen() {
        // Как и другие клиенты, сразу после Font Map отправляем синхронизацию ввода (Num Lock включён).
        sendData(0x1C, ByteWriter().u16le(1).u16le(0).u32le(0).u16le(0).u16le(0).u32le(2).toByteArray())
        val right = deskW - 1
        val bottom = deskH - 1
        // Suppress Output: allowDisplayUpdates = 1, затем прямоугольник рабочего стола
        sendData(0x23, ByteWriter().u8(1).zeros(3).u16le(0).u16le(0).u16le(right).u16le(bottom).toByteArray())
        // Refresh Rect: одна область
        sendData(0x21, ByteWriter().u8(1).zeros(3).u16le(0).u16le(0).u16le(right).u16le(bottom).toByteArray())
    }

    private fun sendConfirmActive() {
        val caps = ByteWriter()
        var n = 0
        fun cap(type: Int, body: ByteArray) {
            caps.u16le(type).u16le(body.size + 4).bytes(body); n++
        }
        cap(1, ByteWriter().u16le(1).u16le(3).u16le(0x0200).u16le(0).u16le(0).u16le(0x0405) // FASTPATH_OUTPUT_SUPPORTED | NO_BITMAP_COMPRESSION_HDR | LONG_CREDENTIALS_SUPPORTED
            .u16le(0).u16le(0).u16le(0).u16le(0).toByteArray())
        cap(2, ByteWriter().u16le(colorDepth).u16le(1).u16le(1).u16le(1).u16le(deskW).u16le(deskH).u16le(0)
            .u16le(0).u16le(1).u8(0).u8(0).u16le(1).u16le(0).toByteArray())
        // orderFlags 0x2A: NEGOTIATE_ORDER_SUPPORT, ZERO_BOUNDS_DELTA_SUPPORT и COLOR_INDEX_SUPPORT
        cap(3, ByteWriter().zeros(16).u32le(0x0F4240).u16le(1).u16le(20).u16le(0).u16le(1).u16le(0)
            .u16le(0x002A).zeros(32).u16le(0).u16le(0).u32le(0).u32le(0x38400).u32le(0).u16le(0).u16le(0).toByteArray())
        cap(4, ByteWriter().zeros(24).u16le(600).u16le(256).u16le(300).u16le(1024).u16le(262).u16le(4096).toByteArray())
        cap(8, ByteWriter().u16le(1).u16le(25).u16le(25).toByteArray())
        cap(13, ByteWriter().u16le(0x0001 or 0x0004 or 0x0010).u16le(0).u32le(0x0409).u32le(4).u32le(0).u32le(12).zeros(64).toByteArray())
        cap(9, ByteWriter().u16le(userId).u16le(0).toByteArray())
        cap(14, ByteWriter().u16le(1).u16le(0).toByteArray())
        cap(5, ByteWriter().u16le(0).u16le(0).u16le(2).u16le(2).toByteArray())
        cap(7, ByteWriter().u16le(0).u16le(0).u16le(0).u16le(0).toByteArray())
        cap(15, ByteWriter().u32le(0).toByteArray())
        cap(0x1B, ByteWriter().u16le(0x0003).toByteArray()) // Large Pointer: курсоры до 96x96 и 384x384 (при масштабе Windows курсоры больше 32x32)
        val glyph = ByteWriter()
        intArrayOf(254, 4, 254, 4, 254, 8, 254, 8, 254, 16, 254, 32, 254, 64, 254, 128, 254, 256, 64, 2048).forEach { glyph.u16le(it) }
        glyph.u32le(0x01000100).u16le(0).u16le(0)
        cap(16, glyph.toByteArray())

        val capBytes = caps.toByteArray()
        val source = "MSTSC\u0000".toByteArray(Charsets.US_ASCII)
        val body = ByteWriter()
        body.u32le(shareId).u16le(0x03EA).u16le(source.size).u16le(capBytes.size + 4)
        body.bytes(source).u16le(n).u16le(0).bytes(capBytes)
        val b = body.toByteArray()
        val pdu = ByteWriter().u16le(b.size + 6).u16le(0x13).u16le(userId).bytes(b).toByteArray()
        sendMcsData(pdu)
    }

    // ------------------------------------------------------------------ ввод

    /** [repeat] — автоповтор уже нажатой клавиши (KBDFLAGS_DOWN: «клавиша была нажата ранее»). */
    fun sendKey(scanCode: Int, extended: Boolean, down: Boolean, repeat: Boolean = false) = enqueueInput(
        ByteWriter().u32le(0).u16le(0x0004)
            .u16le((if (extended) 0x0100 else 0) or (if (!down) 0x8000 else if (repeat) 0x4000 else 0))
            .u16le(scanCode).u16le(0).toByteArray(),
    )

    fun sendUnicode(code: Int, down: Boolean) = enqueueInput(
        ByteWriter().u32le(0).u16le(0x0005).u16le(if (down) 0 else 0x8000).u16le(code).u16le(0).toByteArray(),
    )

    fun sendMouse(flags: Int, x: Int, y: Int) = enqueueInput(
        ByteWriter().u32le(0).u16le(0x8001).u16le(flags).u16le(x.coerceIn(0, deskW - 1)).u16le(y.coerceIn(0, deskH - 1)).toByteArray(),
    )

    /** Синхронизация индикаторов: Scroll=1, Num=2, Caps=4. */
    fun sendToggleState(flags: Int) {
        if (flags == lastToggle) return
        lastToggle = flags
        enqueueInput(ByteWriter().u32le(0).u16le(0x0000).u16le(0).u32le(flags).toByteArray())
    }

    private fun enqueueInput(event: ByteArray) {
        if (!ready) return
        try {
            inputExecutor.execute {
                if (!ready) return@execute
                val body = ByteWriter().u16le(1).u16le(0).bytes(event).toByteArray()
                try {
                    sendData(0x1C, body)
                } catch (_: Exception) {
                    // обрыв обнаружит читающий поток
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    // ------------------------------------------------------------------ отправка и чтение PDU

    private fun sendData(type2: Int, body: ByteArray) {
        val total = 18 + body.size
        val w = ByteWriter(total)
        w.u16le(total).u16le(0x17).u16le(userId)
        w.u32le(shareId).u8(0).u8(1).u16le(total - 14).u8(type2).u8(0).u16le(0)
        w.bytes(body)
        sendMcsData(w.toByteArray())
    }

    /** Отправляет PDU статического канала, нарезая на фрагменты по 1600 байт. */
    private fun sendChannel(channel: Int, pdu: ByteArray) {
        // PDU канала уходит фрагментами подряд: фрагменты разных потоков (чтение сети, ввод, файлы) нельзя перемешивать.
        synchronized(channelSendLock) {
            var off = 0
            do {
                val n = minOf(CHANNEL_CHUNK, pdu.size - off)
                val first = off == 0
                val last = off + n >= pdu.size
                val flags = (if (first) ClipboardChannel.CHANNEL_FLAG_FIRST else 0) or (if (last) ClipboardChannel.CHANNEL_FLAG_LAST else 0)
                val w = ByteWriter(n + 8)
                w.u32le(pdu.size).u32le(flags).bytes(pdu, off, n)
                sendMcsData(w.toByteArray(), channel)
                off += n
            } while (off < pdu.size)
        }
    }

    private fun sendMcsData(data: ByteArray, channel: Int = ioChannel) {
        val w = ByteWriter(data.size + 8)
        w.u8(0x64).u16be(userId - 1001).u16be(channel).u8(0x70)
        if (data.size < 0x80) w.u8(data.size) else w.u16be(data.size or 0x8000)
        w.bytes(data)
        sendX224Data(w.toByteArray())
    }

    private fun sendX224Data(mcs: ByteArray) = sendTpkt(ByteWriter(mcs.size + 3).u8(2).u8(0xF0).u8(0x80).bytes(mcs).toByteArray())

    private fun sendTpkt(payload: ByteArray) {
        val packet = ByteWriter(payload.size + 4).u8(3).u8(0).u16be(payload.size + 4).bytes(payload).toByteArray()
        synchronized(writeLock) {
            out.write(packet)
            out.flush()
        }
    }

    private fun readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(buf, got, n - got)
            if (r < 0) throw java.io.EOFException()
            got += r
        }
        return buf
    }

    /** Читает один TPKT и возвращает данные после заголовка TPKT. */
    private fun readSlowPayload(): ByteArray {
        val h = readFully(4)
        if (h[0].toInt() != 3) throw RdpException("Неожиданный формат пакета")
        val len = ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
        return readFully(len - 4)
    }

    /** Читает X.224 Data TPDU и возвращает MCS-часть. */
    private fun readMcs(): ByteArray = readSlowPayload().let { it.copyOfRange(minOf(3, it.size), it.size) }

    private fun errorInfoText(code: Long): String? = when (code) {
        0L -> null
        1L, 0xBL -> "Сеанс завершён администратором"
        2L, 0xCL -> "Пользователь вышел из системы"
        3L -> "Превышено время бездействия"
        4L -> "Превышено время входа в систему"
        5L -> "Подключился другой пользователь"
        7L -> "Сервер отклонил подключение"
        9L -> "Недостаточно прав для подключения"
        0x10L -> "Нужны новые учётные данные"
        else -> "Сеанс завершён сервером (код $code)"
    }

    companion object {
        private const val TAG = "RDP"
        private const val CHANNEL_CHUNK = 1600
        private const val POINTER_CACHE_SIZE = 20 // совпадает с размером кэша в Pointer Capability Set
        private const val POINTER_COLOR = 0
        private const val POINTER_CACHED = 1
        private const val POINTER_NEW = 2
        private const val POINTER_LARGE = 3
        const val PROTOCOL_SSL = 1
        const val PROTOCOL_HYBRID = 2

        // Флаги указателя для sendMouse
        const val PTR_MOVE = 0x0800
        const val PTR_DOWN = 0x8000
        const val PTR_LEFT = 0x1000
        const val PTR_RIGHT = 0x2000
        const val PTR_MIDDLE = 0x4000
        const val PTR_WHEEL = 0x0200
        const val PTR_HWHEEL = 0x0400
    }
}

/** BER-кодирование для MCS Connect-Initial. */
internal object Ber {
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
    }

    fun tlv(tag: ByteArray, content: ByteArray) = tag + len(content.size) + content
    fun sequence(vararg parts: ByteArray) = tlv(byteArrayOf(0x30), parts.fold(ByteArray(0)) { a, b -> a + b })
    fun octets(b: ByteArray) = tlv(byteArrayOf(0x04), b)
    fun bool(v: Boolean) = tlv(byteArrayOf(0x01), byteArrayOf(if (v) 0xFF.toByte() else 0))
    fun integer(v: Int): ByteArray {
        val raw = when {
            v < 0x80 -> byteArrayOf(v.toByte())
            v < 0x8000 -> byteArrayOf((v ushr 8).toByte(), v.toByte())
            else -> byteArrayOf(0, (v ushr 8).toByte(), v.toByte())
        }
        return tlv(byteArrayOf(0x02), raw)
    }
}

