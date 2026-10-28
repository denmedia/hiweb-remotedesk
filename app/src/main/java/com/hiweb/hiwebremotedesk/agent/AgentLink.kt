package com.hiweb.hiwebremotedesk.agent

import android.graphics.BitmapFactory
import android.util.Log
import com.hiweb.hiwebremotedesk.rdp.CursorImage
import com.hiweb.hiwebremotedesk.rdp.DvcHandler
import com.hiweb.hiwebremotedesk.rdp.RemoteCursor
import org.json.JSONObject

/**
 * Уведомление Windows, пересланное агентом. [ack] — агент ждёт ответа `notif_ack` (проверка пересылки в меню агента).
 * [iconPng] — иконка приложения Windows (PNG), если агент смог её получить.
 */
class PcNotification(
    val id: Long,
    val app: String,
    val title: String,
    val text: String,
    val timeMs: Long,
    val ack: Boolean = false,
    val iconPng: ByteArray? = null,
)

/**
 * Связь с HiWeb Agent на ПК через динамический канал RDP «HiWeb::Agent» (протокол — windows-agent/PROTOCOL.md).
 * Канал открывает агент; пока его нет, [connected] == false. Колбэки вызываются из потока RDP.
 */
class AgentLink(
    private val deviceName: String,
    private val appVersion: String,
    private val listener: Listener,
) : DvcHandler {
    interface Listener {
        /** Агент ответил: его версия и состояние доступа к уведомлениям Windows. */
        fun onAgentHello(version: String, notificationAccess: String)

        fun onAgentNotification(n: PcNotification)

        fun onAgentClosed()

        /** В меню агента выбрано «Переключиться на Планшет», пока сеанс открыт (приложение свёрнуто). */
        fun onAgentActivate()

        /** Сенсорная клавиатура Windows показана (прямоугольник в пикселях рабочего стола ПК) или скрыта (null). */
        fun onKeyboardPane(rect: android.graphics.Rect?)

        /** Состояние ПК (сообщение `status`): блокировка, сон, выключение — для карточки ПК в списке. */
        fun onAgentStatus(msg: JSONObject) {}

        /** Защита подключений: сообщения `guard_state`, `conn`, `threat`, `guard_result`. */
        fun onAgentGuard(msg: JSONObject) {}

        /** Форма курсора на ПК (Windows по RDP присылает только стрелку, поэтому её сообщает агент). */
        fun onAgentCursor(cursor: RemoteCursor) {}
    }

    @Volatile private var write: ((ByteArray) -> Unit)? = null

    // Запись в канал — это запись в сокет: из главного потока Android её запрещает (NetworkOnMainThreadException),
    // а состояние, клавиатура и заряд меняются именно там. Один поток сохраняет порядок сообщений.
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "agent-send").apply { isDaemon = true } }
    @Volatile var agentVersion: String? = null
        private set

    // Последнее отправленное состояние: повторяется агенту сразу после (пере)открытия канала.
    @Volatile private var foreground = true
    @Volatile private var battery: Pair<Int, Boolean>? = null
    @Volatile private var physicalKeyboard: Boolean? = null

    val connected: Boolean get() = write != null

    override fun onOpen(write: (ByteArray) -> Unit) {
        this.write = write
        Log.i(TAG, "agent channel opened")
        send(JSONObject().put("t", "hello").put("proto", PROTOCOL).put("app", appVersion).put("device", deviceName))
        send(stateMessage(foreground))
        battery?.let { (level, charging) -> send(batteryMessage(level, charging)) }
        physicalKeyboard?.let { send(keyboardMessage(it)) }
    }

    override fun onMessage(data: ByteArray) {
        val msg = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "hello" -> {
                val version = msg.optString("ver")
                agentVersion = version
                Log.i(TAG, "agent hello ver=$version proto=${msg.optInt("proto")}")
                listener.onAgentHello(version, msg.optString("notifAccess"))
            }
            "activate" -> listener.onAgentActivate()
            "status" -> listener.onAgentStatus(msg)
            "guard_state", "conn", "threat", "guard_result" -> listener.onAgentGuard(msg)
            "cursor" -> parseCursor(msg)?.let(listener::onAgentCursor)
            "tkb" -> listener.onKeyboardPane(
                if (msg.optBoolean("visible")) {
                    android.graphics.Rect(msg.optInt("left"), msg.optInt("top"), msg.optInt("right"), msg.optInt("bottom"))
                } else {
                    null
                },
            )
            "notif" -> listener.onAgentNotification(
                PcNotification(
                    id = msg.optLong("id"),
                    app = msg.optString("app"),
                    title = msg.optString("title"),
                    text = msg.optString("text"),
                    timeMs = msg.optLong("time"),
                    ack = msg.optBoolean("ack"),
                    iconPng = msg.optString("icon").takeIf { it.isNotEmpty() }
                        ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() },
                ),
            )
        }
    }

    override fun onClose() {
        if (write == null) return
        write = null
        agentVersion = null
        Log.i(TAG, "agent channel closed")
        listener.onAgentClosed()
    }

    /** Приложение на экране (true) или свёрнуто: свёрнутому агент пересылает уведомления. */
    fun setForeground(fg: Boolean) {
        foreground = fg
        send(stateMessage(fg))
    }

    fun setBattery(level: Int, charging: Boolean) {
        if (battery == level to charging) return
        battery = level to charging
        send(batteryMessage(level, charging))
    }

    /** Подключена ли к планшету физическая клавиатура: без неё агент показывает сенсорную клавиатуру Windows. */
    fun setPhysicalKeyboard(present: Boolean) {
        if (physicalKeyboard == present) return
        physicalKeyboard = present
        send(keyboardMessage(present))
    }

    /**
     * Передаёт агенту, как позвать планшет, когда тот не подключён: идентификатор ПК в приложении,
     * ключ подписи команд, UDP-порт и текущие адреса планшета. Канал RDP зашифрован TLS.
     */
    fun sendPairing(pcId: String, secret: ByteArray) {
        val ips = org.json.JSONArray()
        AgentPairing.localAddresses().forEach { ips.put(it) }
        send(
            JSONObject().put("t", "pair").put("pc", pcId).put("device", deviceName).put("port", AgentPairing.PORT)
                .put("ips", ips).put("secret", java.util.Base64.getEncoder().encodeToString(secret)),
        )
    }

    /** Курсор из сообщения `cursor`: PNG с точкой привязки либо `hidden`; null, если картинка не читается. */
    private fun parseCursor(msg: JSONObject): RemoteCursor? {
        if (msg.optBoolean("hidden")) return RemoteCursor.Hidden
        val png = runCatching { java.util.Base64.getDecoder().decode(msg.optString("png")) }.getOrNull() ?: return null
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val argb = IntArray(bmp.width * bmp.height)
        bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val cursor = CursorImage(
            bmp.width, bmp.height,
            msg.optInt("hx").coerceIn(0, bmp.width - 1), msg.optInt("hy").coerceIn(0, bmp.height - 1),
            argb, null,
        )
        bmp.recycle()
        return cursor
    }

    private fun stateMessage(fg: Boolean) = JSONObject().put("t", "state").put("fg", fg)

    private fun batteryMessage(level: Int, charging: Boolean) =
        JSONObject().put("t", "battery").put("level", level).put("charging", charging)

    private fun keyboardMessage(present: Boolean) = JSONObject().put("t", "keyboard").put("physical", present)

    /** Ответ на уведомление с `ack`: показано ли оно, а если нет — почему ([PcNotifications.BLOCKED_VISIBLE] и др.). */
    fun sendNotificationAck(id: Long, blockedBy: String?) {
        send(JSONObject().put("t", "notif_ack").put("id", id).put("posted", blockedBy == null).put("reason", blockedBy ?: ""))
    }

    /** Произвольное сообщение агенту (команды защиты); ничего не делает, пока канал не открыт. */
    fun sendRaw(msg: JSONObject) = send(msg)

    private fun send(msg: JSONObject) {
        val w = write ?: return
        val bytes = msg.toString().toByteArray(Charsets.UTF_8)
        sender.execute { runCatching { w(bytes) }.onFailure { Log.w(TAG, "agent send failed", it) } }
    }

    companion object {
        const val CHANNEL_NAME = "HiWeb::Agent"
        const val PROTOCOL = 1
        private const val TAG = "Agent"
    }
}
