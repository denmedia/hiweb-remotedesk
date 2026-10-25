package com.hiweb.hiwebremotedesk.agent

import android.content.Context
import com.hiweb.hiwebremotedesk.rdp.MemoryDriveStorage
import com.hiweb.hiwebremotedesk.rdp.RdpClient

/**
 * Установка HiWeb Agent на ПК из открытого сеанса: exe из ресурсов приложения подключается к Windows как
 * временный диск \\tsclient\HiWeb, затем в окне «Выполнить» (Win+R) набирается путь к нему. Windows спрашивает
 * подтверждение запуска; программа сама копирует себя в профиль пользователя и включает автозапуск.
 */
object AgentInstaller {
    const val DRIVE_ID = 2
    const val DRIVE_NAME = "HiWeb"
    const val EXE_NAME = "HiWebAgent.exe"
    const val EXE_PATH = "\\\\tsclient\\$DRIVE_NAME\\$EXE_NAME"

    private const val ASSET_DIR = "agent"

    /** Версия агента, вложенного в приложение (assets/agent/version.txt). */
    fun bundledVersion(context: Context): String =
        runCatching { context.assets.open("$ASSET_DIR/version.txt").use { String(it.readBytes()).trim() } }.getOrDefault("0")

    /** true, если вложенная версия новее установленной на ПК. */
    fun isOutdated(installed: String, bundled: String): Boolean = compareVersions(bundled, installed) > 0

    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Клавиши, которыми установщик запускается на ПК. */
    interface Keys {
        fun key(scanCode: Int, extended: Boolean, down: Boolean)
        fun unicode(code: Int, down: Boolean)
        fun pause(ms: Long)
    }

    /**
     * Подключает диск с установщиком и запускает его через Win+R. Блокирует поток (вызывать не из UI).
     * Возвращает текст ошибки или null. Диск остаётся подключённым: его убирает [detach], когда агент ответил.
     */
    fun launch(context: Context, client: RdpClient, keys: Keys): String? {
        val exe = runCatching { context.assets.open("$ASSET_DIR/$EXE_NAME").use { it.readBytes() } }.getOrNull()
            ?: return "Установщик агента не найден в приложении"
        if (!client.attachDrive(DRIVE_ID, DRIVE_NAME, MemoryDriveStorage(mapOf(EXE_NAME to exe)))) {
            return "Сервер не поддерживает подключение дисков планшета"
        }
        // Диск объявляется после входа в Windows; ждём, пока сервер его примет.
        var waited = 0L
        while (!client.isDriveAnnounced(DRIVE_ID) && waited < 5_000) {
            keys.pause(100)
            waited += 100
        }
        if (!client.isDriveAnnounced(DRIVE_ID)) return "Windows не подключила диск планшета: войдите в систему и повторите"
        keys.pause(1_000)
        typeRunCommand(keys, EXE_PATH)
        return null
    }

    fun detach(client: RdpClient?) {
        client?.detachDrive(DRIVE_ID)
    }

    private fun typeRunCommand(keys: Keys, command: String) {
        // Win+R
        keys.key(SC_LWIN, true, true)
        keys.key(SC_R, false, true)
        keys.key(SC_R, false, false)
        keys.key(SC_LWIN, true, false)
        keys.pause(900)
        // Unicode-ввод не зависит от раскладки клавиатуры на ПК.
        for (ch in command) {
            keys.unicode(ch.code, true)
            keys.unicode(ch.code, false)
            keys.pause(8)
        }
        keys.pause(300)
        keys.key(SC_ENTER, false, true)
        keys.key(SC_ENTER, false, false)
    }

    private const val SC_LWIN = 0x5B
    private const val SC_R = 0x13
    private const val SC_ENTER = 0x1C
}
