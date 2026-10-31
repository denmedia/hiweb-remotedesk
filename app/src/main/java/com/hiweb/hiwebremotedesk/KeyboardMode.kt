package com.hiweb.hiwebremotedesk

/**
 * Режим экранной клавиатуры в сеансе (кнопка в панели сеанса, запоминается для каждого ПК). Клавиатуру показывает
 * Windows на самом ПК (сенсорная клавиатура, `TabTip`) по команде HiWeb Agent (поле `kbmode` сообщения `keyboard`,
 * см. PROTOCOL.md); без агента нужной версии (Ubuntu, агент старше [AGENT_VERSION]) кнопка открывает клавиатуру Android.
 *
 * - [SHOW] — клавиатура показана и остаётся, пока её не закроют;
 * - [HIDE] — клавиатура не показывается никогда;
 * - [AUTO] — клавиатура появляется при касании поля ввода на ПК и прячется при касании вне его (как было всегда);
 *   только пока к планшету не подключена физическая клавиатура.
 */
enum class KeyboardMode(val id: String, val title: String) {
    SHOW("show", "Клавиатура: показана"),
    HIDE("hide", "Клавиатура: скрыта"),
    AUTO("auto", "Клавиатура: автоматически"),
    ;

    /** Порядок переключения кнопкой: показать → скрыть → автоматически → снова показать. */
    fun next(): KeyboardMode = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = AUTO

        /** С этой версии агент понимает `kbmode`; старый агент сам решает, когда показывать клавиатуру Windows. */
        const val AGENT_VERSION = "1.9.1"

        fun parse(id: String?): KeyboardMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
