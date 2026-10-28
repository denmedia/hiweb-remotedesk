package com.hiweb.hiwebremotedesk.agent

import android.content.Context
import android.util.Log
import com.hiweb.hiwebremotedesk.data.DeviceRepository
import com.hiweb.hiwebremotedesk.data.GuardJournal
import com.hiweb.hiwebremotedesk.data.GuardJson
import com.hiweb.hiwebremotedesk.data.GuardStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Ответ агента на команду защиты (`guard_result`). */
data class GuardResult(val deviceId: String, val action: String, val cidr: String, val ok: Boolean, val error: String)

/**
 * Защита подключений к ПК на стороне планшета: принимает сообщения агента (`guard_state`, `conn`, `threat`,
 * `guard_result`) по обоим каналам связи — по локальной сети ([AgentHub]) и из сеанса RDP ([AgentLink]),
 * складывает их в [GuardStore], показывает уведомления об угрозах ([GuardNotifications]) и отправляет агенту
 * команды (заблокировать подсеть, «это я», оставить). Протокол — windows-agent/PROTOCOL.md.
 */
object GuardHub {
    private const val TAG = "Guard"

    private val _results = MutableSharedFlow<GuardResult>(extraBufferCapacity = 8)

    /** Ответы агента на команды — для сообщения в интерфейсе. */
    val results: SharedFlow<GuardResult> = _results.asSharedFlow()

    private val sessionLinks = ConcurrentHashMap<String, AgentLink>()
    private val syncedLinks: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))

    // Запись в канал — сетевая операция, из главного потока (кнопка уведомления, интерфейс) её делать нельзя.
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "guard-send").apply { isDaemon = true } }

    /** Канал агента в открытом сеансе RDP этого ПК: команды уходят по нему, пока он на связи. */
    fun attach(deviceId: String, link: AgentLink) {
        sessionLinks[deviceId] = link
    }

    fun detach(deviceId: String, link: AgentLink) {
        sessionLinks.remove(deviceId, link)
    }

    /**
     * Сообщение агента. [link] — канал, из которого оно пришло (по нему один раз просится досылка журнала),
     * [reply] — как ответить агенту. Возвращает false, если сообщение не про защиту.
     */
    fun onMessage(context: Context, deviceId: String, msg: JSONObject, link: Any, reply: (JSONObject) -> Unit): Boolean {
        val store = GuardStore.get(context)
        when (msg.optString("t")) {
            "guard_state" -> {
                store.applyState(deviceId, GuardJson.state(msg))
                // Состояние агент шлёт при подключении и при каждом изменении; досылку журнала просим один раз на канал.
                if (syncedLinks.add(link)) {
                    reply(JSONObject().put("t", "guard").put("do", "sync").put("since", GuardJournal.lastAgentTime(store.of(deviceId).entries)))
                }
            }
            "conn" -> store.applyEntries(deviceId, GuardJson.entries(msg.optJSONArray("events")))
            "threat" -> {
                val incoming = GuardJson.threats(msg.optJSONArray("threats"))
                val fresh = store.applyThreats(deviceId, incoming)
                val name = DeviceRepository.get(context).get(deviceId)?.name.orEmpty()
                val firewallOn = store.of(deviceId).state?.firewallOn ?: true
                fresh.forEach { GuardNotifications.postThreat(context, deviceId, name, it, firewallOn) }
                // Угрозу решили на самом ПК или с другого устройства: уведомление больше не нужно.
                incoming.filter { !it.open }.forEach { GuardNotifications.cancel(context, it.id) }
            }
            "guard_result" -> {
                val result = GuardResult(
                    deviceId, msg.optString("do"), msg.optString("cidr"), msg.optBoolean("ok"), msg.optString("error"),
                )
                val name = DeviceRepository.get(context).get(deviceId)?.name.orEmpty()
                GuardNotifications.postResult(context, deviceId, name, result)
                _results.tryEmit(result)
            }
            else -> return false
        }
        return true
    }

    /** Агент этого ПК сейчас на связи (по локальной сети или в открытом сеансе): команду есть кому принять. */
    fun reachable(deviceId: String): Boolean = sessionLinks[deviceId]?.connected == true || AgentHub.isLinked(deviceId)

    /**
     * Отправляет агенту команду. Результат придёт сообщением `guard_result` ([results]): блокировка ждёт ответа
     * Windows на запрос прав администратора на ПК. false — агента нет на связи, команда не отправлена.
     */
    fun command(deviceId: String, cmd: JSONObject): Boolean {
        val session = sessionLinks[deviceId]?.takeIf { it.connected }
        val lan = AgentHub.isLinked(deviceId)
        if (session == null && !lan) return false
        sender.execute {
            runCatching {
                if (session != null) session.sendRaw(cmd) else AgentHub.send(deviceId, cmd)
            }.onFailure { Log.w(TAG, "guard command failed", it) }
        }
        return true
    }

    fun block(deviceId: String, cidr: String, enableFirewall: Boolean): Boolean =
        command(deviceId, JSONObject().put("t", "guard").put("do", "block").put("cidr", cidr).put("enableFw", enableFirewall))

    fun unblock(deviceId: String, cidr: String): Boolean =
        command(deviceId, JSONObject().put("t", "guard").put("do", "unblock").put("cidr", cidr))

    fun trust(deviceId: String, ip: String): Boolean =
        command(deviceId, JSONObject().put("t", "guard").put("do", "trust").put("ip", ip))

    fun dismiss(deviceId: String, threatId: String): Boolean =
        command(deviceId, JSONObject().put("t", "guard").put("do", "dismiss").put("id", threatId))
}
