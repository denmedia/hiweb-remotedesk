package com.hiweb.hiwebremotedesk.data

import com.hiweb.hiwebremotedesk.rdp.PowerAction
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

sealed interface SshPowerResult {
    data object Done : SshPowerResult

    /** SSH недоступен (порт закрыт, пароль не подошёл): можно выполнить команду через рабочий стол. */
    data class Unavailable(val reason: String) : SshPowerResult

    data class Failed(val message: String) : SshPowerResult
}

/**
 * Выключение, перезагрузка и выход из сеанса на Linux по SSH: надёжнее, чем кликать по интерфейсу GNOME.
 * Вход — тем же логином и паролем, что и для RDP. Ключ хоста запоминается при первом подключении и дальше сверяется
 * до отправки пароля.
 */
class LinuxSsh(
    private val pinnedKey: (host: String) -> String?,
    private val pinKey: (host: String, fingerprint: String) -> Unit,
    /** Для проверки на живом устройстве: systemctl только описывает действие и ничего не выполняет. */
    private val systemctlDryRun: Boolean = false,
) {
    fun run(host: String, user: String, password: String, action: PowerAction, port: Int = 22): SshPowerResult {
        val session = try {
            connect(host, user, password, port)
        } catch (e: HostKeyChanged) {
            return SshPowerResult.Failed("Ключ SSH устройства изменился. Возможна подмена, команда не отправлена.")
        } catch (e: JSchException) {
            val reason = if (e.message?.contains("Auth", ignoreCase = true) == true) "SSH: неверный логин или пароль" else "SSH недоступен"
            return SshPowerResult.Unavailable(reason)
        }
        try {
            return execute(session, password, action)
        } finally {
            session.disconnect()
        }
    }

    private class HostKeyChanged : Exception()

    private fun connect(host: String, user: String, password: String, port: Int): Session {
        val jsch = JSch()
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String, key: ByteArray): Int {
                val fp = Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
                val known = pinnedKey(host)
                if (known == null) {
                    pinKey(host, fp)
                    return HostKeyRepository.OK
                }
                return if (known == fp) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }

            override fun add(hostkey: HostKey, ui: UserInfo?) {}
            override fun remove(host: String?, type: String?) {}
            override fun remove(host: String?, type: String?, key: ByteArray?) {}
            override fun getKnownHostsRepositoryID(): String = "app"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        val session = jsch.getSession(user, host, port)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (e: JSchException) {
            // JSch сообщает об отказе по ключу хоста обычным исключением соединения.
            if (e.message?.contains("HostKey", ignoreCase = true) == true) throw HostKeyChanged()
            throw e
        }
        return session
    }

    private fun execute(session: Session, password: String, action: PowerAction): SshPowerResult {
        val systemctl = when (action) {
            PowerAction.REBOOT -> "reboot"
            PowerAction.SHUTDOWN -> "poweroff"
            PowerAction.LOGOUT -> null
        }
        if (systemctl != null) {
            // Через sudo команда работает и без графического сеанса и независимо от прав polkit у SSH-сеанса.
            val r = exec(session, "sudo -S -p '' systemctl ${if (systemctlDryRun) "--dry-run " else ""}$systemctl", password + "\n")
            if (r.ok) return SshPowerResult.Done
            // Без прав sudo просим сам сеанс GNOME: он вправе выключать компьютер.
            if (!r.output.contains("sudo", ignoreCase = true) && !r.output.contains("password", ignoreCase = true)) {
                return SshPowerResult.Failed(r.output.ifBlank { "Команда завершилась с ошибкой" })
            }
        }
        val quit = when (action) {
            PowerAction.REBOOT -> "--reboot"
            PowerAction.SHUTDOWN -> "--power-off"
            PowerAction.LOGOUT -> "--logout"
        }
        val r = exec(
            session,
            "export XDG_RUNTIME_DIR=/run/user/\$(id -u); export DBUS_SESSION_BUS_ADDRESS=unix:path=\$XDG_RUNTIME_DIR/bus; gnome-session-quit $quit --no-prompt",
            null,
        )
        return if (r.ok) SshPowerResult.Done else SshPowerResult.Failed(r.output.ifBlank { "Команда завершилась с ошибкой" })
    }

    private class ExecResult(val ok: Boolean, val output: String)

    private fun exec(session: Session, command: String, stdin: String?): ExecResult {
        val out = ByteArrayOutputStream()
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setInputStream(if (stdin != null) ByteArrayInputStream(stdin.toByteArray()) else null)
        channel.setOutputStream(out)
        channel.setErrStream(out)
        channel.connect(CONNECT_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + EXEC_TIMEOUT_MS
        while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val status = channel.exitStatus
        val text = out.toString(Charsets.UTF_8).trim()
        channel.disconnect()
        // Статус -1: канал закрыт без ответа, обычно потому что компьютер уже уходит на выключение.
        val ok = status == 0 || (status == -1 && !session.isConnected)
        return ExecResult(ok, text)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val EXEC_TIMEOUT_MS = 20_000L
    }
}
