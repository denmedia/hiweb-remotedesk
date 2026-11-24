package com.hiweb.hiwebremotedesk.widget

import com.hiweb.hiwebremotedesk.ui.ButtonHint
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Нажатие в виджете, которое выполняет экран списка ПК теми же окнами, что и карточка: подтверждение команды питания,
 * настройки, «Поделиться», ввод пароля перед подключением, подсказка неактивной кнопки. [WidgetLaunchActivity] кладёт
 * запрос сюда и поднимает `MainActivity`, `HomeScreen` выполняет его и сбрасывает.
 */
data class WidgetRequest(val deviceId: String, val action: String, val hint: ButtonHint? = null)

object WidgetRequests {
    val pending = MutableStateFlow<WidgetRequest?>(null)

    const val CONNECT = "connect"
    const val SETTINGS = "settings"
    const val SHARE = "share"
    const val GUARD = "guard"
    const val FILES = "files"
    const val HINT = "hint"

    /** Команда питания через сеанс RDP/SSH: `power:<PowerAction>`. */
    const val POWER_PREFIX = "power:"

    /** Команда питания через HiWeb Agent: `agent:<AgentPower>`. */
    const val AGENT_PREFIX = "agent:"
}
