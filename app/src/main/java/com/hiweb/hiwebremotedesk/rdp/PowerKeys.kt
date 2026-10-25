package com.hiweb.hiwebremotedesk.rdp

import com.hiweb.hiwebremotedesk.data.OsType

enum class PowerAction(val title: String, val inProgress: String) {
    REBOOT("Перезагрузка", "Перезагрузка"),
    SHUTDOWN("Выключение", "Выключение"),
    LOGOUT("Выход из системы", "Выход из системы"),
}

/** Ввод в открытый сеанс, нужный для [PowerKeys]. */
interface PowerInput {
    fun key(scanCode: Int, extended: Boolean, down: Boolean)
    fun mouse(flags: Int, x: Int, y: Int)

    /** Пауза в мс: между шагами меню должно успеть открыться. */
    fun pause(ms: Long)
}

/**
 * Выключение и перезагрузка средствами самой ОС: в открытом сеансе открывается штатное меню питания.
 * На ПК ничего не устанавливается и не запускается.
 */
object PowerKeys {
    private const val WIN = 0x5B
    private const val TAB = 0x0F
    private const val ENTER = 0x1C
    private const val KEY_X = 0x2D
    private const val KEY_U = 0x16
    private const val KEY_R = 0x13
    private const val KEY_I = 0x17

    private fun PowerInput.tap(code: Int, extended: Boolean = false) {
        key(code, extended, true)
        key(code, extended, false)
    }

    private fun PowerInput.click(x: Int, y: Int) {
        mouse(RdpClient.PTR_MOVE, x, y)
        pause(200)
        mouse(RdpClient.PTR_DOWN or RdpClient.PTR_LEFT, x, y)
        mouse(RdpClient.PTR_LEFT, x, y)
    }

    /** Блокирует вызывающий поток на время пауз. [width] — ширина рабочего стола в пикселях. */
    fun run(os: OsType, action: PowerAction, width: Int, input: PowerInput) = with(input) {
        when (os) {
            OsType.WINDOWS -> {
                // Win+X открывает меню кнопки «Пуск»; U — подменю «Завершение работы», затем U — выключение или R — перезагрузка.
                key(WIN, true, true); tap(KEY_X); key(WIN, true, false)
                pause(900)
                tap(KEY_U)
                pause(700)
                // I — «Выход» (завершает сеанс пользователя).
                tap(when (action) { PowerAction.SHUTDOWN -> KEY_U; PowerAction.REBOOT -> KEY_R; PowerAction.LOGOUT -> KEY_I })
            }
            OsType.UBUNTU -> {
                // GNOME: системное меню в правом верхнем углу → кнопка питания → «Restart…» / «Power Off…» → диалог подтверждения
                // (фокус стоит на «Cancel», Tab переводит его на кнопку действия). Координаты отсчитываются от правого края.
                click(width - 25, 16)
                pause(900)
                click(width - 45, 76)
                pause(900)
                when (action) {
                    PowerAction.SHUTDOWN -> click(width - 324, 264)
                    PowerAction.REBOOT -> click(width - 333, 228)
                    PowerAction.LOGOUT -> click(width - 333, 319)
                }
                pause(1300)
                tap(TAB)
                pause(300)
                tap(ENTER)
            }
        }
    }
}
