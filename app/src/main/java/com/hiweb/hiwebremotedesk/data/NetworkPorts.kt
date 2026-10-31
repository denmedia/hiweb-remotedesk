package com.hiweb.hiwebremotedesk.data

/**
 * Порты, которые пользователь может переопределить для ПК. Пустое поле в форме — «стандартный порт»: в [Device]
 * это null, а сам номер форма показывает подсказкой в поле.
 */
object NetworkPorts {
    /** Порт, на котором планшет принимает HiWeb Agent (TCP) и его команду «Переключиться на Планшет» (UDP). */
    const val AGENT_DEFAULT = 47853

    /** SSH на Ubuntu: выключение и перезагрузка. */
    const val SSH_DEFAULT = 22

    /** Слушать порты ниже 1024 приложению Android нельзя, поэтому для агента они не допускаются. */
    val AGENT_RANGE = 1024..65535
    val SSH_RANGE = 1..65535

    /** Поле пустое (стандартный порт) или в нём допустимый номер. */
    fun isValid(text: String, range: IntRange): Boolean = text.isBlank() || text.toIntOrNull()?.let { it in range } == true

    /** Значение для хранения: null для пустого поля и для стандартного номера, иначе номер. Недопустимое тоже даёт null. */
    fun parse(text: String, range: IntRange, default: Int): Int? =
        text.toIntOrNull()?.takeIf { it in range && it != default }

    /**
     * Порты, которые планшет должен слушать для агентов: стандартный всегда (на нём ждут ПК без своего порта и
     * ПК, которые ещё не получили новый) и порты, заданные для ПК с Windows.
     */
    fun agentPorts(devices: List<Device>): Set<Int> =
        buildSet {
            add(AGENT_DEFAULT)
            devices.filter { it.os == OsType.WINDOWS }.forEach { d -> d.agentPort?.let { add(it) } }
        }
}
