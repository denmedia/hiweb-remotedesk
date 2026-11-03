package com.hiweb.hiwebremotedesk.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Настройки ПК одной строкой для QR-кода: «HWRD1:» + Base64 (URL-safe) сжатого JSON.
 * Не передаются: id (на другом планшете ПК добавляется как новый), закреплённый сертификат (там он закрепится заново),
 * выбранная папка общего диска (адрес документа Android имеет смысл только на этом планшете; диск там включают сами).
 * Значения по умолчанию в код не попадают: чем короче текст, тем реже модули и тем легче код сканируется с экрана.
 */
object DeviceShareCode {

    private const val PREFIX = "HWRD1:"
    private const val MAX_JSON_BYTES = 64 * 1024

    /** [includePassword] = false — пароль в код не попадает, его вводят при добавлении. */
    fun encode(device: Device, includePassword: Boolean): String {
        val json = toJson(device).apply {
            if (!includePassword) remove("pwd")
            val defaults = toJson(Device(id = "", name = "", host = ""))
            defaults.keys().forEach { key ->
                if (key != "name" && key != "host" && has(key) && get(key).toString() == defaults.get(key).toString()) remove(key)
            }
        }
        val packed = deflate(json.toString().toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
    }

    /** ПК из кода (с пустым id, как новый) или null, если это не код ПК этого приложения. */
    fun decode(text: String): Device? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        return runCatching {
            val packed = Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX))
            fromJson(JSONObject(String(inflate(packed), Charsets.UTF_8)))
        }.getOrNull()?.takeIf { it.host.isNotBlank() }
    }

    private fun toJson(d: Device): JSONObject = JSONObject()
        .put("name", d.name).put("host", d.host).put("port", d.port)
        .put("os", d.os.name).put("user", d.username).put("pwd", d.password)
        .put("nla", d.useNla).put("scale", d.scalePercent)
        .put("scroll_pct", d.scrollPercent).put("inertia_pct", d.inertiaPercent)
        .put("mouse_pct", d.mouseSpeedPercent).put("wheel_pct", d.mouseWheelPercent)
        .put("stylus_pct", d.stylusScrollPercent)
        .put("files", d.fileTransfer).put("h264", d.hardwareVideo).put("win_gfx", d.windowsGfx)
        .put("mic", d.microphone).put("snd_comp", d.audioCompression).put("loc", d.location)
        .put("cam", d.camera).put("cam_off", JSONArray(d.cameraOff.sorted()))
        .put("cam_rot", JSONObject(d.cameraRotation.toSortedMap()))
        .put("drive_name", d.driveName).put("drive_ro", d.driveReadOnly)
        .put("depth", d.colorDepth).put("wol_mac", d.wakeMac)
        .put("agent_port", d.agentPort ?: 0).put("ssh_port", d.sshPort ?: 0)
        .put("native_touch", d.nativeTouch ?: JSONObject.NULL)
        .put("stylus_mode", d.stylusMode.name).put("stylus_btn", d.stylusButton.name)
        .put("palm", d.palmRejection)

    /** Разбор с теми же допусками, что у [DeviceRepository]: чужой или повреждённый код не должен дать недопустимых значений. */
    private fun fromJson(o: JSONObject): Device {
        val os = runCatching { OsType.valueOf(o.optString("os")) }.getOrDefault(OsType.WINDOWS)
        fun pct(key: String) = o.optInt(key, StrengthLevels.DEFAULT).coerceIn(StrengthLevels.MIN, StrengthLevels.MAX)
        return Device(
            id = "",
            name = o.optString("name"),
            host = o.optString("host").trim(),
            port = o.optInt("port", os.defaultPort).takeIf { it in 1..65535 } ?: os.defaultPort,
            os = os,
            username = o.optString("user"),
            password = o.optString("pwd"),
            useNla = os == OsType.UBUNTU || o.optBoolean("nla", true),
            scalePercent = o.optInt("scale", 100).coerceIn(100, 500),
            scrollPercent = pct("scroll_pct"),
            inertiaPercent = pct("inertia_pct"),
            mouseSpeedPercent = pct("mouse_pct"),
            mouseWheelPercent = pct("wheel_pct"),
            stylusScrollPercent = pct("stylus_pct"),
            fileTransfer = o.optBoolean("files", true),
            hardwareVideo = o.optBoolean("h264", true),
            windowsGfx = o.optBoolean("win_gfx", false),
            microphone = o.optBoolean("mic", false),
            audioCompression = o.optBoolean("snd_comp", false),
            location = o.optBoolean("loc", false),
            camera = o.optBoolean("cam", false),
            cameraOff = o.optJSONArray("cam_off")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
            cameraRotation = o.optJSONObject("cam_rot")?.let { r ->
                r.keys().asSequence().associateWith { r.optInt(it, 0) }.filterValues { it in Device.CAMERA_ROTATIONS && it != 0 }
            } ?: emptyMap(),
            driveName = o.optString("drive_name", Device.DEFAULT_DRIVE_NAME).ifBlank { Device.DEFAULT_DRIVE_NAME },
            driveReadOnly = o.optBoolean("drive_ro", false),
            colorDepth = o.optInt("depth", Device.DEFAULT_COLOR_DEPTH).takeIf { it in Device.COLOR_DEPTHS } ?: Device.DEFAULT_COLOR_DEPTH,
            wakeMac = o.optString("wol_mac"),
            agentPort = o.optInt("agent_port", 0).takeIf { it in NetworkPorts.AGENT_RANGE && it != NetworkPorts.AGENT_DEFAULT },
            sshPort = o.optInt("ssh_port", 0).takeIf { it in NetworkPorts.SSH_RANGE && it != NetworkPorts.SSH_DEFAULT },
            nativeTouch = if (o.has("native_touch") && !o.isNull("native_touch")) o.optBoolean("native_touch") else null,
            stylusMode = runCatching { StylusMode.valueOf(o.optString("stylus_mode")) }.getOrDefault(StylusMode.PEN),
            stylusButton = runCatching { StylusButtonAction.valueOf(o.optString("stylus_btn")) }.getOrDefault(StylusButtonAction.RIGHT),
            palmRejection = o.optBoolean("palm", true),
        )
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) error("Код повреждён")
                out.write(buffer, 0, count)
                if (out.size() > MAX_JSON_BYTES) error("Слишком большой код")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
