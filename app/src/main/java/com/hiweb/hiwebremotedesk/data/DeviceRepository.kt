package com.hiweb.hiwebremotedesk.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Список устройств в SharedPreferences; пароли хранятся только в зашифрованном виде. */
class DeviceRepository private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    /** Пароли, введённые без сохранения: живут только до закрытия процесса. */
    private val sessionPasswords = HashMap<String, Pair<String, String>>()

    fun get(id: String): Device? = _devices.value.firstOrNull { it.id == id }?.withSessionCredentials()

    fun newId(): String = UUID.randomUUID().toString()

    fun upsert(device: Device) {
        val list = _devices.value.toMutableList()
        val i = list.indexOfFirst { it.id == device.id }
        if (i >= 0) list[i] = device else list += device
        publish(list)
    }

    fun delete(id: String) {
        sessionPasswords.remove(id)
        // Удалённый ПК больше не может звать планшет командой «Переключиться на Планшет».
        com.hiweb.hiwebremotedesk.agent.AgentPairing(context).forget(id)
        PcStateStore.get(context).forget(id)
        publish(_devices.value.filterNot { it.id == id })
    }

    fun rememberForSession(id: String, username: String, password: String) {
        sessionPasswords[id] = username to password
    }

    private fun Device.withSessionCredentials(): Device {
        if (hasCredentials) return this
        val s = sessionPasswords[id] ?: return this
        return copy(username = s.first, password = s.second)
    }

    private fun publish(list: List<Device>) {
        _devices.value = list
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id).put("name", d.name).put("host", d.host).put("port", d.port)
                    .put("os", d.os.name).put("user", d.username)
                    .put("pwd", SecretBox.encrypt(d.password))
                    .put("cert", d.certSha256 ?: JSONObject.NULL)
                    .put("nla", d.useNla)
                    .put("scale", d.scalePercent)
                    .put("scroll_level", d.scrollLevel)
                    .put("inertia_level", d.inertiaLevel)
                    .put("files", d.fileTransfer)
                    .put("drive", d.driveEnabled)
                    .put("drive_uri", d.driveUri ?: JSONObject.NULL)
                    .put("drive_name", d.driveName)
                    .put("drive_ro", d.driveReadOnly)
                    .put("depth", d.colorDepth)
                    .put("wol_mac", d.wakeMac),
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun load(): List<Device> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val os = runCatching { OsType.valueOf(o.optString("os")) }.getOrDefault(OsType.WINDOWS)
                // Ubuntu работает через GNOME Remote Desktop (порт 3390, всегда NLA); записи прежних версий с портом 3389 относились к xrdp.
                val movedFromXrdp = os == OsType.UBUNTU && o.optInt("port", 3389) == 3389
                Device(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = if (movedFromXrdp) OsType.UBUNTU.defaultPort else o.optInt("port", 3389),
                    os = os,
                    username = o.optString("user"),
                    password = SecretBox.decrypt(o.optString("pwd")) ?: "",
                    certSha256 = if (movedFromXrdp || o.isNull("cert")) null else o.optString("cert"),
                    useNla = os == OsType.UBUNTU || o.optBoolean("nla", true),
                    scalePercent = o.optInt("scale", 100).coerceIn(100, 500),
                    scrollLevel = o.optInt("scroll_level", StrengthLevels.DEFAULT).coerceIn(StrengthLevels.MIN, StrengthLevels.MAX),
                    inertiaLevel = o.optInt("inertia_level", StrengthLevels.DEFAULT).coerceIn(StrengthLevels.MIN, StrengthLevels.MAX),
                    fileTransfer = o.optBoolean("files", true),
                    driveEnabled = o.optBoolean("drive", false),
                    driveUri = if (o.isNull("drive_uri")) null else o.optString("drive_uri").ifBlank { null },
                    driveName = o.optString("drive_name", Device.DEFAULT_DRIVE_NAME).ifBlank { Device.DEFAULT_DRIVE_NAME },
                    driveReadOnly = o.optBoolean("drive_ro", false),
                    colorDepth = o.optInt("depth", Device.DEFAULT_COLOR_DEPTH).takeIf { it in Device.COLOR_DEPTHS } ?: Device.DEFAULT_COLOR_DEPTH,
                    wakeMac = o.optString("wol_mac"),
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        @Volatile private var instance: DeviceRepository? = null
        fun get(context: Context): DeviceRepository =
            instance ?: synchronized(this) { instance ?: DeviceRepository(context.applicationContext).also { instance = it } }
    }
}
