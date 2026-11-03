package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.DeviceShareCode
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.StylusButtonAction
import com.hiweb.hiwebremotedesk.data.StylusMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.Deflater

class DeviceShareCodeTest {

    private val full = Device(
        id = "local-id", name = "Офис", host = "198.51.100.7", port = 3390, os = OsType.UBUNTU,
        username = "PC\\user", password = "секрет", certSha256 = "ab".repeat(32),
        scalePercent = 150, scrollPercent = 130, mouseSpeedPercent = 80, microphone = true, audioCompression = true,
        camera = true, cameraOff = setOf("1"), cameraRotation = mapOf("0" to 90),
        driveEnabled = true, driveUri = "content://tree/local", driveName = "Pad", driveReadOnly = true,
        colorDepth = 32, wakeMac = "00:11:22:33:44:55", agentPort = 50000, sshPort = 2222, nativeTouch = true,
        stylusMode = StylusMode.TOUCH, stylusButton = StylusButtonAction.UNDO, palmRejection = false,
    )

    @Test
    fun `настройки проходят через код без потерь`() {
        val back = DeviceShareCode.decode(DeviceShareCode.encode(full, includePassword = true))!!
        // id, сертификат и адрес выбранной папки на другом планшете не имеют смысла
        assertEquals(full.copy(id = "", certSha256 = null, driveEnabled = false, driveUri = null), back)
    }

    @Test
    fun `без пароля пароль в код не попадает, логин остаётся`() {
        val code = DeviceShareCode.encode(full, includePassword = false)
        val back = DeviceShareCode.decode(code)!!
        assertEquals("", back.password)
        assertEquals("PC\\user", back.username)
    }

    @Test
    fun `значения по умолчанию в код не попадают`() {
        val plain = Device(id = "x", name = "ПК", host = "203.0.113.5")
        val code = DeviceShareCode.encode(plain, includePassword = true)
        assertTrue("код короткий: $code", code.length < 90)
        assertEquals(plain.copy(id = ""), DeviceShareCode.decode(code))
    }

    @Test
    fun `чужие и повреждённые коды не принимаются`() {
        assertNull(DeviceShareCode.decode("https://example.com"))
        assertNull(DeviceShareCode.decode("HWRD1:!!!"))
        assertNull(DeviceShareCode.decode("HWRD1:" + Base64.getUrlEncoder().encodeToString("не сжато".toByteArray())))
        // код камеры из другого приложения
        assertNull(DeviceShareCode.decode("DMIPCAM1:eJwDAAAAAAE"))
    }

    @Test
    fun `код без адреса отбрасывается`() {
        assertNull(DeviceShareCode.decode(pack("""{"name":"ПК"}""")))
    }

    @Test
    fun `недопустимые значения приводятся к допустимым`() {
        val d = DeviceShareCode.decode(pack("""{"host":"h","port":70000,"scroll_pct":900,"depth":7,"os":"BSD","agent_port":80,"stylus_mode":"?"}"""))
        assertNotNull(d)
        d!!
        assertEquals(OsType.WINDOWS, d.os)
        assertEquals(3389, d.port)
        assertEquals(200, d.scrollPercent)
        assertEquals(Device.DEFAULT_COLOR_DEPTH, d.colorDepth)
        assertNull(d.agentPort)
        assertEquals(StylusMode.PEN, d.stylusMode)
    }

    private fun pack(json: String): String {
        val deflater = Deflater()
        deflater.setInput(json.toByteArray())
        deflater.finish()
        val buf = ByteArray(4096)
        val n = deflater.deflate(buf)
        return "HWRD1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf.copyOf(n))
    }
}
