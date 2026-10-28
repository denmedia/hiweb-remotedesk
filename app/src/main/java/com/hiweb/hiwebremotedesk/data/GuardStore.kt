package com.hiweb.hiwebremotedesk.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Журнал подключений и угрозы по каждому ПК: то, что прислал HiWeb Agent (сообщения `conn`, `threat`,
 * `guard_state` — windows-agent/PROTOCOL.md), плюс сеансы самого приложения. Хранится на диске: планшет, который
 * был вне зоны связи, показывает историю и после перезапуска приложения.
 */
class GuardStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
    private val _data = MutableStateFlow(load())
    val data: StateFlow<Map<String, GuardData>> = _data.asStateFlow()

    fun of(deviceId: String): GuardData = _data.value[deviceId] ?: GuardData()

    fun applyEntries(deviceId: String, incoming: List<GuardEntry>) {
        if (incoming.isEmpty()) return
        update(deviceId) { it.copy(entries = GuardJournal.mergeEntries(it.entries, incoming)) }
    }

    /** Возвращает угрозы, о которых нужно уведомить (новые, нерешённые, ещё не объявленные). */
    @Synchronized
    fun applyThreats(deviceId: String, incoming: List<GuardThreat>): List<GuardThreat> {
        if (incoming.isEmpty()) return emptyList()
        val (next, fresh) = GuardJournal.applyThreats(of(deviceId), incoming, System.currentTimeMillis())
        put(deviceId, next)
        return fresh
    }

    fun applyState(deviceId: String, state: GuardState) = update(deviceId) { it.copy(state = state) }

    /** Сеанс самого приложения: подключение, отключение, неудача. */
    fun recordLocal(deviceId: String, kind: String, note: String = "") {
        val now = System.currentTimeMillis()
        applyEntries(deviceId, listOf(GuardEntry(id = "$kind|$now", time = now, kind = kind, ip = "", user = note)))
    }

    fun forget(deviceId: String) {
        _data.update { it - deviceId }
        save()
    }

    @Synchronized
    private fun update(deviceId: String, change: (GuardData) -> GuardData) = put(deviceId, change(of(deviceId)))

    private fun put(deviceId: String, data: GuardData) {
        _data.update { it + (deviceId to data) }
        save()
    }

    private fun save() {
        val root = JSONObject()
        _data.value.forEach { (id, d) -> root.put(id, GuardJson.toJson(d)) }
        prefs.edit().putString("data", root.toString()).apply()
    }

    private fun load(): Map<String, GuardData> = runCatching {
        val root = JSONObject(prefs.getString("data", null) ?: return emptyMap())
        root.keys().asSequence().associateWith { GuardJson.dataFrom(root.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    companion object {
        @Volatile private var instance: GuardStore? = null
        fun get(context: Context): GuardStore =
            instance ?: synchronized(this) { instance ?: GuardStore(context.applicationContext).also { instance = it } }
    }
}

/** Какой ПК открыть в окне защиты: выставляется по нажатию на уведомление и по значку на карточке. */
object GuardNavigation {
    val open = MutableStateFlow<String?>(null)
}

/** Разбор и сборка JSON-объектов защиты (поля — windows-agent/PROTOCOL.md). */
object GuardJson {
    fun entries(arr: JSONArray?): List<GuardEntry> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").ifEmpty { return@mapNotNull null }
        GuardEntry(
            id = id, time = o.optLong("ts"), kind = o.optString("kind"), ip = o.optString("ip"), user = o.optString("user"),
            count = o.optInt("count", 1).coerceAtLeast(1), local = o.optBoolean("local"), trusted = o.optBoolean("trusted"),
        )
    }

    fun threats(arr: JSONArray?): List<GuardThreat> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").ifEmpty { return@mapNotNull null }
        GuardThreat(
            id = id, time = o.optLong("ts"), rule = o.optString("rule"), ip = o.optString("ip"), cidr = o.optString("cidr"),
            count = o.optInt("count"), user = o.optString("user"), afterFail = o.optBoolean("afterFail"),
            status = o.optString("status", GuardThreat.STATUS_NEW),
        )
    }

    fun state(o: JSONObject): GuardState {
        val blocked = o.optJSONArray("blocked")
        val trusted = o.optJSONArray("trusted")
        return GuardState(
            firewallOn = o.optBoolean("fw", true),
            blocked = (0 until (blocked?.length() ?: 0)).mapNotNull { i ->
                blocked!!.optJSONObject(i)?.let { b -> GuardBlocked(b.optString("cidr"), b.optBoolean("on", true)) }
            },
            trusted = (0 until (trusted?.length() ?: 0)).map { trusted!!.optString(it) },
        )
    }

    fun toJson(d: GuardData): JSONObject {
        val entries = JSONArray()
        d.entries.forEach {
            entries.put(
                JSONObject().put("id", it.id).put("ts", it.time).put("kind", it.kind).put("ip", it.ip).put("user", it.user)
                    .put("count", it.count).put("local", it.local).put("trusted", it.trusted),
            )
        }
        val threats = JSONArray()
        d.threats.forEach {
            threats.put(
                JSONObject().put("id", it.id).put("ts", it.time).put("rule", it.rule).put("ip", it.ip).put("cidr", it.cidr)
                    .put("count", it.count).put("user", it.user).put("afterFail", it.afterFail).put("status", it.status),
            )
        }
        val o = JSONObject().put("entries", entries).put("threats", threats).put("announced", JSONArray(d.announced.toList()))
        d.state?.let { s ->
            val blocked = JSONArray()
            s.blocked.forEach { blocked.put(JSONObject().put("cidr", it.cidr).put("on", it.enabled)) }
            o.put("state", JSONObject().put("fw", s.firewallOn).put("blocked", blocked).put("trusted", JSONArray(s.trusted)))
        }
        return o
    }

    fun dataFrom(o: JSONObject): GuardData {
        val announced = o.optJSONArray("announced")
        return GuardData(
            entries = entries(o.optJSONArray("entries")),
            threats = threats(o.optJSONArray("threats")),
            state = o.optJSONObject("state")?.let { state(it) },
            announced = (0 until (announced?.length() ?: 0)).map { announced!!.optString(it) }.toSet(),
        )
    }
}
