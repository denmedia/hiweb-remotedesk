package com.hiweb.hiwebremotedesk.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Последние сообщения HiWeb Agent о состоянии ПК. Хранятся на диске: ПК, уснувший при планшете в домашней сети,
 * показывается спящим и после перезапуска приложения, и вне дома, где агент до планшета не достаёт.
 * Заодно запоминает MAC-адрес ПК для пробуждения, если в настройках ПК его ещё нет.
 */
class PcStateStore private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("pc_state", Context.MODE_PRIVATE)
    private val _reports = MutableStateFlow(load())
    val reports: StateFlow<Map<String, AgentReport>> = _reports.asStateFlow()

    /** Сообщение `status` агента (поля — windows-agent/PROTOCOL.md). */
    fun report(deviceId: String, msg: JSONObject) {
        val wake = msg.optJSONArray("wol")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val mac = WakeOnLan.parseMac(o.optString("mac")) ?: return@mapNotNull null
                WakeTarget(WakeOnLan.formatMac(mac), o.optString("bcast"))
            }
        }.orEmpty()
        val previous = _reports.value[deviceId]
        put(
            deviceId,
            AgentReport(
                state = msg.optString("state", "active"),
                session = msg.optString("session"),
                idleSec = msg.optInt("idle"),
                at = System.currentTimeMillis(),
                // Перед сном адаптер может уже не числиться подключённым: прежний список не теряем.
                wake = wake.ifEmpty { previous?.wake.orEmpty() },
            ),
        )
        wake.firstOrNull()?.let { learnMac(deviceId, it.mac) }
    }

    /** Команда питания, отправленная с планшета и принятая агентом: ПК уходит в [state] (sleep, hibernate, shutdown, restart). */
    fun record(deviceId: String, state: String) {
        val previous = _reports.value[deviceId]
        put(deviceId, AgentReport(state, previous?.session.orEmpty(), 0, System.currentTimeMillis(), previous?.wake.orEmpty()))
    }

    fun forget(deviceId: String) {
        _reports.update { it - deviceId }
        save()
    }

    private fun put(deviceId: String, r: AgentReport) {
        _reports.update { it + (deviceId to r) }
        save()
    }

    private fun learnMac(deviceId: String, mac: String) {
        val repo = DeviceRepository.get(context)
        val d = repo.devices.value.firstOrNull { it.id == deviceId } ?: return
        if (d.wakeMac.isBlank()) repo.upsert(d.copy(wakeMac = mac))
    }

    private fun save() {
        val root = JSONObject()
        _reports.value.forEach { (id, r) ->
            val wake = JSONArray()
            r.wake.forEach { wake.put(JSONObject().put("mac", it.mac).put("bcast", it.broadcast)) }
            root.put(
                id,
                JSONObject().put("state", r.state).put("session", r.session).put("idle", r.idleSec).put("at", r.at).put("wol", wake),
            )
        }
        prefs.edit().putString("reports", root.toString()).apply()
    }

    private fun load(): Map<String, AgentReport> = runCatching {
        val root = JSONObject(prefs.getString("reports", null) ?: return emptyMap())
        root.keys().asSequence().associateWith { id ->
            val o = root.getJSONObject(id)
            val arr = o.optJSONArray("wol")
            AgentReport(
                state = o.optString("state"),
                session = o.optString("session"),
                idleSec = o.optInt("idle"),
                at = o.optLong("at"),
                wake = (0 until (arr?.length() ?: 0)).map { i ->
                    arr!!.getJSONObject(i).let { WakeTarget(it.optString("mac"), it.optString("bcast")) }
                },
            )
        }
    }.getOrDefault(emptyMap())

    companion object {
        @Volatile private var instance: PcStateStore? = null
        fun get(context: Context): PcStateStore =
            instance ?: synchronized(this) { instance ?: PcStateStore(context.applicationContext).also { instance = it } }
    }
}
