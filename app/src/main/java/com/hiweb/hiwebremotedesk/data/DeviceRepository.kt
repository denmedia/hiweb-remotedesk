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

    /**
     * Меняет сохранённый ПК. В отличие от [upsert] с копией из [get] не записывает учётные данные, введённые
     * без сохранения: в сеансе они лежат в [Device], но пользователь просил их не хранить.
     */
    fun update(id: String, change: (Device) -> Device) {
        val list = _devices.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = change(list[i])
        publish(list)
    }

    fun delete(id: String) {
        sessionPasswords.remove(id)
        // Удалённый ПК больше не может звать планшет командой «Переключиться на Планшет».
        com.hiweb.hiwebremotedesk.agent.AgentPairing(context).forget(id)
        PcStateStore.get(context).forget(id)
        GuardStore.get(context).forget(id)
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
                    .put("scroll_pct", d.scrollPercent)
                    .put("inertia_pct", d.inertiaPercent)
                    .put("mouse_pct", d.mouseSpeedPercent)
                    .put("wheel_pct", d.mouseWheelPercent)
                    .put("files", d.fileTransfer)
                    .put("mic", d.microphone)
                    .put("cam", d.camera)
                    .put("cam_off", JSONArray(d.cameraOff.toList()))
                    .put("cam_rot", JSONObject(d.cameraRotation.mapValues { it.value }))
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

    /**
     * Переводит сохранённые уровни 1–9 в проценты шкалы, где «100%» — настроенное значение: фактическое
     * поведение прежних записей не меняется (значения округляются до шага ползунка). Правит JSON на месте, не
     * трогая пароли.
     */
    private fun migrateLevels(raw: String): String {
        val from = prefs.getInt("scale_version", 1)
        if (from >= StrengthLevels.SCALE_VERSION) return raw
        val migrated = runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                o.put("scroll_pct", StrengthLevels.percentFromLevel(o.optInt("scroll_level", OLD_DEFAULT_LEVEL)))
                o.put("inertia_pct", StrengthLevels.inertiaFromLevel(o.optInt("inertia_level", OLD_DEFAULT_LEVEL), from))
                o.put("mouse_pct", StrengthLevels.mouseFromLevel(o.optInt("mouse_level", OLD_DEFAULT_LEVEL), from))
                o.put("wheel_pct", StrengthLevels.percentFromLevel(o.optInt("wheel_level", OLD_DEFAULT_LEVEL)))
                listOf("scroll_level", "inertia_level", "mouse_level", "wheel_level").forEach(o::remove)
            }
            arr.toString()
        }.getOrNull() ?: return raw
        prefs.edit().putString("list", migrated).putInt("scale_version", StrengthLevels.SCALE_VERSION).apply()
        return migrated
    }

    private fun load(): List<Device> {
        val raw = prefs.getString("list", null)?.let(::migrateLevels)
        if (raw == null) {
            // Пустое хранилище: уровни новых записей сразу пишутся по новой шкале.
            prefs.edit().putInt("scale_version", StrengthLevels.SCALE_VERSION).apply()
            return emptyList()
        }
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
                    scrollPercent = o.optInt("scroll_pct", StrengthLevels.DEFAULT).coerceIn(StrengthLevels.MIN, StrengthLevels.MAX),
                    inertiaPercent = o.optInt("inertia_pct", StrengthLevels.DEFAULT).coerceIn(StrengthLevels.MIN, StrengthLevels.MAX),
                    mouseSpeedPercent = o.optInt("mouse_pct", StrengthLevels.DEFAULT).coerceIn(StrengthLevels.MIN, StrengthLevels.MAX),
                    mouseWheelPercent = o.optInt("wheel_pct", StrengthLevels.DEFAULT).coerceIn(StrengthLevels.MIN, StrengthLevels.MAX),
                    fileTransfer = o.optBoolean("files", true),
                    microphone = o.optBoolean("mic", false),
                    camera = o.optBoolean("cam", false),
                    cameraOff = o.optJSONArray("cam_off")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                    cameraRotation = o.optJSONObject("cam_rot")?.let { r ->
                        r.keys().asSequence().associateWith { r.optInt(it, 0) }.filterValues { it in Device.CAMERA_ROTATIONS && it != 0 }
                    } ?: emptyMap(),
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
        /** Средний уровень шкалы 1–9, которая была до процентов (значение, когда ключа в записи нет). */
        private const val OLD_DEFAULT_LEVEL = 5

        @Volatile private var instance: DeviceRepository? = null
        fun get(context: Context): DeviceRepository =
            instance ?: synchronized(this) { instance ?: DeviceRepository(context.applicationContext).also { instance = it } }
    }
}
