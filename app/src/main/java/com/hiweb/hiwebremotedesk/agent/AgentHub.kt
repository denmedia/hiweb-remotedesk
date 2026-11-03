package com.hiweb.hiwebremotedesk.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Действия питания, которые агент умеет выполнять на ПК; [wire] — имя в протоколе. */
enum class AgentPower(val wire: String, val title: String, val question: String, val inProgress: String) {
    SHUTDOWN("shutdown", "Выключить", "Выключить", "Выключение"),
    RESTART("restart", "Перезагрузить", "Перезагрузить", "Перезагрузка"),
    SLEEP("sleep", "Сон", "Перевести в сон", "Переход в сон"),
    HIBERNATE("hibernate", "Гибернация", "Перевести в гибернацию", "Гибернация"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

/** Открытая на ПК папка: на планшете она «диск» в приложении «Файлы». [id] — идентификатор в протоколе, не путь на ПК. */
data class AgentShare(val id: String, val name: String, val write: Boolean)

/** Что агент сообщил о доступе к файлам: включён ли он на ПК и какие папки открыты. */
data class AgentFiles(val enabled: Boolean, val shares: List<AgentShare>)

/**
 * ПК, агент которого сейчас на связи по локальной сети. [files] — null, если агент старше 1.11 и файлов не умеет.
 */
data class AgentPresence(val version: String, val host: String, val power: Set<AgentPower>, val files: AgentFiles? = null)

/**
 * Агенты на ПК, подключившиеся к планшету по локальной сети (TCP, порты — [com.hiweb.hiwebremotedesk.data.NetworkPorts.agentPorts],
 * серверы запускает [SwitchListenerService]). Экран списка ПК показывает по [presence] кнопки питания через агента.
 */
object AgentHub {
    private const val TAG = "Agent"
    private const val READ_TIMEOUT_MS = 45_000
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    private const val PING_MS = 15_000L

    private val _presence = MutableStateFlow<Map<String, AgentPresence>>(emptyMap())
    val presence: StateFlow<Map<String, AgentPresence>> = _presence.asStateFlow()

    private class Link(val socket: Socket, val lines: SecureLines, val out: OutputStreamWriter) {
        val pending = ConcurrentHashMap<String, (String?) -> Unit>()
        /** Файловые запросы, ждущие ответа, по номеру запроса. */
        val fsPending = ConcurrentHashMap<Long, CompletableFuture<FsWire.Message>>()

        fun send(msg: JSONObject) = synchronized(this) {
            out.write(lines.sign(msg.toString()) + "\n")
            out.flush()
        }
    }

    private const val FS_TIMEOUT_MS = 30_000L
    private val fsIds = AtomicLong()

    private val links = ConcurrentHashMap<String, Link>()
    private val servers = ConcurrentHashMap<Int, ServerSocket>()
    @Volatile private var onStatus: (String, JSONObject) -> Unit = { _, _ -> }
    @Volatile private var onScreen: (String, ByteArray) -> Unit = { _, _ -> }
    @Volatile private var onGuard: (String, JSONObject, Any, (JSONObject) -> Unit) -> Unit = { _, _, _, _ -> }

    /** Агент этого ПК сейчас подключён к планшету по локальной сети. */
    fun isLinked(deviceId: String): Boolean = links.containsKey(deviceId)

    /** Шлёт сообщение агенту ПК; блокирует на записи в сокет, поэтому только из фонового потока. */
    fun send(deviceId: String, msg: JSONObject) {
        links[deviceId]?.send(msg)
    }

    /**
     * Принимает подключения агентов на порту [port], пока сервер не остановлен [stop] или [stopPort]. Блокирует поток;
     * для каждого порта вызывается отдельно. [onStatus] получает
     * сообщения агентов о состоянии ПК (id ПК, сообщение), [onScreen] — расшифрованные снимки экрана для превью
     * (id ПК, JPEG), [onGuard] — сообщения защиты подключений (id ПК, сообщение, канал, способ ответить);
     * все вызываются в потоке связи.
     */
    fun serve(
        port: Int,
        pairing: AgentPairing,
        knownDevice: (String) -> Boolean,
        onScreen: (String, ByteArray) -> Unit,
        onGuard: (String, JSONObject, Any, (JSONObject) -> Unit) -> Unit,
        onStatus: (String, JSONObject) -> Unit,
    ) {
        this.onStatus = onStatus
        this.onScreen = onScreen
        this.onGuard = onGuard
        val s = try {
            ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(port)) }
        } catch (e: Exception) {
            Log.w(TAG, "cannot listen TCP $port", e)
            return
        }
        servers.put(port, s)?.let { runCatching { it.close() } }
        while (!s.isClosed) {
            val socket = try { s.accept() } catch (_: Exception) { break }
            Thread({ runCatching { session(socket, pairing, knownDevice) }.onFailure { Log.w(TAG, "agent link failed", it) } }, "agent-link").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Перестаёт слушать порт; уже установленные связи остаются. */
    fun stopPort(port: Int) {
        servers.remove(port)?.let { runCatching { it.close() } }
    }

    fun stop() {
        servers.keys.toList().forEach(::stopPort)
        links.values.forEach { runCatching { it.socket.close() } }
    }

    /**
     * Файловый запрос агенту ПК (протокол — windows-agent/PROTOCOL.md, «Файлы»): шифрует и подписывает его ключом сеанса,
     * блокирует поток до ответа. Бросает [FsException]: `offline` — агент не на связи или связь оборвалась, `timeout` —
     * агент не ответил за [timeoutMs]. Ошибки самой операции (`ok: false`) разбирает [PcFiles]. Только из фонового потока.
     */
    fun fsCall(deviceId: String, header: org.json.JSONObject, payload: ByteArray = ByteArray(0), timeoutMs: Long = FS_TIMEOUT_MS): FsWire.Message {
        val link = links[deviceId] ?: throw FsException("offline", FsErrors.text("offline", null))
        val id = fsIds.incrementAndGet()
        val future = CompletableFuture<FsWire.Message>()
        link.fsPending[id] = future
        try {
            val (iv, data) = link.lines.encrypt(FsWire.pack(header, payload))
            val b64 = java.util.Base64.getEncoder()
            try {
                link.send(JSONObject().put("t", "fs").put("id", id).put("iv", b64.encodeToString(iv)).put("data", b64.encodeToString(data)))
            } catch (e: Exception) {
                throw FsException("offline", FsErrors.text("offline", null))
            }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw FsException("timeout", FsErrors.text("timeout", null))
        } catch (e: ExecutionException) {
            throw (e.cause as? FsException ?: FsException("io", FsErrors.text("io", null)))
        } finally {
            link.fsPending.remove(id)
        }
    }

    /**
     * Просит агент выполнить действие. [done] получает null при успехе или текст ошибки; вызывается из
     * фонового потока.
     */
    fun requestPower(deviceId: String, action: AgentPower, done: (String?) -> Unit) {
        val link = links[deviceId]
        if (link == null) {
            done("Агент на ПК не на связи")
            return
        }
        Thread({
            link.pending[action.wire] = done
            try {
                link.send(JSONObject().put("t", "power").put("action", action.wire))
            } catch (e: Exception) {
                link.pending.remove(action.wire)
                done("Связь с агентом прервалась")
            }
        }, "agent-power").start()
    }

    private fun session(socket: Socket, pairing: AgentPairing, knownDevice: (String) -> Boolean) {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        socket.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        val n1 = UUID.randomUUID().toString().replace("-", "")
        out.write(JSONObject().put("t", "challenge").put("nonce", n1).toString() + "\n")
        out.flush()

        val auth = JSONObject(reader.readLine() ?: return)
        val pc = auth.optString("pc")
        val n2 = auth.optString("nonce")
        val secret = pairing.existingSecret(pc)
        if (auth.optString("t") != "auth" || secret == null || n2.length < 16 || !knownDevice(pc) ||
            !java.security.MessageDigest.isEqual(SecureLines.authMac(secret, pc, n1, n2).toByteArray(), auth.optString("mac").toByteArray())
        ) {
            Log.w(TAG, "agent from ${socket.inetAddress} failed authentication")
            socket.close()
            return
        }
        out.write(JSONObject().put("t", "ok").put("mac", SecureLines.okMac(secret, n1, n2)).toString() + "\n")
        out.flush()

        val link = Link(socket, SecureLines(SecureLines.sessionKey(secret, n1, n2)), out)
        links.put(pc, link)?.let { old -> runCatching { old.socket.close() } }
        socket.soTimeout = READ_TIMEOUT_MS
        Log.i(TAG, "agent of $pc connected from ${socket.inetAddress}")
        val pinger = Thread({
            try {
                while (!socket.isClosed) {
                    Thread.sleep(PING_MS)
                    link.send(JSONObject().put("t", "ping"))
                }
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }, "agent-ping").apply { isDaemon = true; start() }
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val body = link.lines.verify(line) ?: break
                handle(pc, link, JSONObject(body))
            }
        } catch (_: Exception) {
        } finally {
            pinger.interrupt()
            runCatching { socket.close() }
            if (links.remove(pc, link)) _presence.update { it - pc }
            link.pending.values.forEach { it("Связь с агентом прервалась") }
            link.fsPending.values.forEach { it.completeExceptionally(FsException("offline", FsErrors.text("offline", null))) }
            Log.i(TAG, "agent of $pc disconnected")
        }
    }

    /** Поле `fs` сообщения caps; null (агент старше 1.11 его не шлёт) — файлов нет. */
    internal fun parseFiles(fs: JSONObject?): AgentFiles? {
        if (fs == null) return null
        val arr = fs.optJSONArray("shares")
        val shares = buildList {
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr!!.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotEmpty()) add(AgentShare(id, o.optString("name").ifBlank { id }, o.optBoolean("w")))
            }
        }
        return AgentFiles(fs.optBoolean("on"), shares)
    }

    private fun handle(pc: String, link: Link, msg: JSONObject) {
        when (msg.optString("t")) {
            "caps" -> {
                val arr = msg.optJSONArray("power")
                val power = buildSet { for (i in 0 until (arr?.length() ?: 0)) AgentPower.fromWire(arr!!.optString(i))?.let { add(it) } }
                val presence = AgentPresence(msg.optString("ver"), msg.optString("host"), power, parseFiles(msg.optJSONObject("fs")))
                _presence.update { it + (pc to presence) }
            }
            "fs" -> {
                val id = msg.optLong("id", -1)
                val future = link.fsPending[id] ?: return
                val b64 = java.util.Base64.getDecoder()
                val reply = runCatching {
                    link.lines.decrypt(b64.decode(msg.optString("iv")), b64.decode(msg.optString("data")))?.let(FsWire::unpack)
                }.getOrNull()
                if (reply == null) future.completeExceptionally(FsException("io", "Ответ ПК испорчен.")) else future.complete(reply)
            }
            "status" -> onStatus(pc, msg)
            "screen" -> {
                val jpeg = runCatching {
                    val b64 = java.util.Base64.getDecoder()
                    link.lines.decrypt(b64.decode(msg.optString("iv")), b64.decode(msg.optString("data")))
                }.getOrNull()
                if (jpeg == null) Log.w(TAG, "bad screen from $pc") else onScreen(pc, jpeg)
            }
            "power_result" -> {
                val done = link.pending.remove(msg.optString("action")) ?: return
                done(if (msg.optBoolean("ok")) null else msg.optString("error").ifBlank { "Агент не выполнил команду" })
            }
            "guard_state", "conn", "threat", "guard_result" -> onGuard(pc, msg, link) { reply -> runCatching { link.send(reply) } }
        }
    }
}
