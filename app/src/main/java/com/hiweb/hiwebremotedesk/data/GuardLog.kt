package com.hiweb.hiwebremotedesk.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись журнала подключений к ПК. Записи с адресом присылает HiWeb Agent из журналов Windows (неудачные попытки
 * подряд с одного адреса агент сливает в одну запись со счётчиком [count]); записи «app_*» — сеансы этого
 * приложения, их пишет само приложение.
 */
data class GuardEntry(
    val id: String,
    val time: Long,
    val kind: String,
    val ip: String,
    val user: String,
    val count: Int = 1,
    /** Адрес из своей сети (частные и локальные диапазоны). */
    val local: Boolean = false,
    /** Адрес, с которого планшет уже входил, или отмеченный как свой. */
    val trusted: Boolean = false,
) {
    val fromApp: Boolean get() = kind.startsWith("app_")

    companion object {
        const val FAIL = "fail"
        const val LOGON = "logon"
        const val LOGOFF = "logoff"
        const val DISCONNECT = "disconnect"
        const val APP_START = "app_start"
        const val APP_END = "app_end"
        const val APP_FAIL = "app_fail"
    }
}

/** Подозрительная активность, найденная агентом: подбор пароля, вход с неизвестного адреса, обращение с адреса вне белого списка. */
data class GuardThreat(
    val id: String,
    val time: Long,
    val rule: String,
    val ip: String,
    /** Что предлагается заблокировать: подсеть, а если в ней есть свой адрес — один адрес. */
    val cidr: String,
    val count: Int,
    val user: String,
    /** Успешный вход последовал за неудачными попытками: пароль могли подобрать. */
    val afterFail: Boolean,
    val status: String,
) {
    val open: Boolean get() = status == STATUS_NEW

    companion object {
        const val BRUTEFORCE = "bruteforce"
        const val UNKNOWN_LOGON = "unknown_logon"

        /** Режим белого списка: неудачная попытка входа с адреса, которого в списке нет (даже первая). */
        const val UNLISTED = "unlisted"

        /** Режим белого списка без автоматического занесения: вход с нового адреса, с которого подключился и планшет. */
        const val NEW_ADDRESS = "new_address"
        const val STATUS_NEW = "new"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_TRUSTED = "trusted"
        const val STATUS_DISMISSED = "dismissed"
    }
}

data class GuardBlocked(val cidr: String, val enabled: Boolean)

/**
 * Неудачная попытка входа на ПК (сообщение `guard_fail`): агент шлёт её на каждую попытку, планшет показывает уведомление.
 * [count] — попыток с этой подсети за час, считая эту (0 у своих и знакомых адресов); [willBan] — эта попытка достигла порога.
 */
data class GuardFailNote(
    val id: String,
    val time: Long,
    val ip: String,
    val cidr: String,
    val count: Int,
    val local: Boolean,
    val known: Boolean,
    val autoBan: Boolean,
    val banAfter: Int,
    val willBan: Boolean,
)

/** Подсеть заблокирована или автоблокировка не удалась (сообщение `guard_ban`). [count] = 0 — блокировка вручную. */
data class GuardBanNote(
    val id: String,
    val time: Long,
    val cidr: String,
    val ip: String,
    val count: Int,
    val auto: Boolean,
    val ok: Boolean,
    /** Правило записано, но брандмауэр на ПК выключен — оно не действует. */
    val firewallOff: Boolean,
    val error: String,
)

/** Запись белого списка на ПК: адрес или подсеть; [manual] — добавлена пользователем, иначе запомнена по подключению планшета. */
data class GuardWhitelistEntry(val cidr: String, val manual: Boolean, val time: Long = 0L)

/**
 * Состояние защиты на ПК: брандмауэр, заблокированные подсети, режим и белый список знакомых адресов и подсетей.
 * [mode] = null — агент старше 1.9.5, белого списка не знает (настройки в приложении скрыты).
 */
data class GuardState(
    val firewallOn: Boolean = true,
    val blocked: List<GuardBlocked> = emptyList(),
    val mode: String? = null,
    /** Запоминать адрес, с которого вошёл планшет. */
    val autoLearn: Boolean = true,
    /** Запоминать сразу всю подсеть, а не один адрес. */
    val learnSubnet: Boolean = false,
    val whitelist: List<GuardWhitelistEntry> = emptyList(),
    /** Блокировать подсеть автоматически после [banAfter] неудачных попыток. */
    val autoBan: Boolean = true,
    /** Порог автоблокировки; 0 — агент старше 1.10, автоблокировки у него нет (настройки в приложении скрыты). */
    val banAfter: Int = 0,
    /** Помощник блокировки установлен на ПК: блокировка идёт без запроса UAC, в том числе сама. */
    val helper: Boolean = false,
) {
    val supportsWhitelist: Boolean get() = mode != null
    val supportsAutoBan: Boolean get() = banAfter > 0
    val whitelistMode: Boolean get() = mode == MODE_WHITELIST

    companion object {
        const val MODE_NORMAL = "normal"
        const val MODE_WHITELIST = "whitelist"
    }
}

/** Всё, что планшет знает о защите одного ПК. [announced] — угрозы, о которых уже было уведомление. */
data class GuardData(
    val entries: List<GuardEntry> = emptyList(),
    val threats: List<GuardThreat> = emptyList(),
    val state: GuardState? = null,
    val announced: Set<String> = emptySet(),
) {
    val openThreats: List<GuardThreat> get() = threats.filter { it.open }
}

/** Слияние присланного агентом с накопленным; без Android, чтобы проверять в юнит-тестах. */
object GuardJournal {
    const val MAX_ENTRIES = 500
    const val MAX_THREATS = 100
    private const val MAX_ANNOUNCED = 200

    /** Об угрозе старше этого, впервые пришедшей на планшет, уведомление уже не нужно. */
    const val ANNOUNCE_MAX_AGE_MS = 48L * 60 * 60 * 1000

    /** Записи по идентификатору: присланная заменяет прежнюю (агент обновляет счётчик слитой записи). */
    fun mergeEntries(old: List<GuardEntry>, incoming: List<GuardEntry>): List<GuardEntry> =
        (old.associateBy { it.id } + incoming.associateBy { it.id }).values.sortedBy { it.time }.takeLast(MAX_ENTRIES)

    fun mergeThreats(old: List<GuardThreat>, incoming: List<GuardThreat>): List<GuardThreat> =
        (old.associateBy { it.id } + incoming.associateBy { it.id }).values.sortedBy { it.time }.takeLast(MAX_THREATS)

    /** Угрозы, о которых нужно уведомить: ещё не решены, не объявлялись и не устарели. */
    fun toAnnounce(threats: List<GuardThreat>, announced: Set<String>, now: Long): List<GuardThreat> =
        threats.filter { it.open && it.id !in announced && now - it.time < ANNOUNCE_MAX_AGE_MS }

    fun withAnnounced(data: GuardData, ids: Collection<String>): GuardData =
        data.copy(announced = (data.announced + ids).toList().takeLast(MAX_ANNOUNCED).toSet())

    /** Время последней записи агента: с него планшет просит досылку журнала. Записи приложения не в счёт. */
    fun lastAgentTime(entries: List<GuardEntry>): Long = entries.filter { !it.fromApp }.maxOfOrNull { it.time } ?: 0L

    /** Применяет присланное сообщением `threat`; возвращает новые данные и угрозы, о которых надо уведомить. */
    fun applyThreats(data: GuardData, incoming: List<GuardThreat>, now: Long): Pair<GuardData, List<GuardThreat>> {
        val merged = data.copy(threats = mergeThreats(data.threats, incoming))
        val fresh = toAnnounce(incoming, data.announced, now)
        return withAnnounced(merged, fresh.map { it.id }) to fresh
    }
}

/**
 * Недавно виденные идентификаторы сообщений. Агент рассылает неудачные входы и блокировки и по сеансу RDP, и по локальной
 * сети: планшет, на связи по обоим путям, получает каждое сообщение дважды, а уведомление нужно одно.
 */
class RecentIds(private val capacity: Int = 128) {
    private val ids = LinkedHashSet<String>()

    /** true — идентификатор видим впервые (и запоминается), false — повтор. */
    @Synchronized
    fun firstTime(id: String): Boolean {
        if (!ids.add(id)) return false
        while (ids.size > capacity) ids.remove(ids.first())
        return true
    }
}

/** Тексты о подключениях и угрозах для списка, диалога и уведомлений. */
object GuardText {
    fun time(ms: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(ms))

    fun threatTitle(t: GuardThreat): String = when (t.rule) {
        GuardThreat.BRUTEFORCE -> "Подбор пароля по RDP"
        GuardThreat.UNLISTED -> "Попытка входа с адреса вне белого списка"
        GuardThreat.NEW_ADDRESS -> "Вход с нового адреса: занести в белый список?"
        else -> "Вход по RDP с неизвестного адреса"
    }

    fun threatText(t: GuardThreat): String {
        if (t.rule == GuardThreat.BRUTEFORCE) {
            return "${t.count} неудачных попыток входа за 10 минут с ${t.ip} (подсеть ${t.cidr})"
        }
        if (t.rule == GuardThreat.UNLISTED) {
            return "Неудачных попыток входа за 10 минут: ${t.count}. Адрес ${t.ip} (подсеть ${t.cidr}) не входит в белый список"
        }
        val who = t.user.ifBlank { "Кто-то" }
        if (t.rule == GuardThreat.NEW_ADDRESS) {
            return "$who вошёл в сеанс с ${t.ip}, планшет к сеансу подключился. Адреса нет в белом списке; если это вы, занесите его."
        }
        val base = "$who вошёл в сеанс с ${t.ip}, но планшет к сеансу не подключился."
        return if (t.afterFail) "$base Перед этим было ${t.count} неудачных попыток входа — пароль могли подобрать!" else base
    }

    fun failTitle(n: GuardFailNote): String = when {
        n.willBan -> "Блокировка подсети после неудачных попыток входа"
        n.local -> "Неудачный вход по RDP (своя сеть)"
        n.known -> "Неудачный вход по RDP (знакомый адрес)"
        else -> "Неудачный вход по RDP"
    }

    fun failText(n: GuardFailNote): String {
        val who = when {
            n.local -> "Неверный пароль, адрес ${n.ip} из своей сети"
            n.known -> "Неверный пароль, знакомый адрес ${n.ip}"
            n.count > 1 -> "Неверный пароль, попытка №${n.count} с ${n.ip} (подсеть ${n.cidr})"
            else -> "Неверный пароль, попытка с ${n.ip} (подсеть ${n.cidr})"
        }
        if (n.local || n.known) return who
        return when {
            n.willBan -> "$who. Достигнут порог в ${n.banAfter}: подсеть блокируется"
            n.autoBan -> "$who. До блокировки: ${(n.banAfter - n.count).coerceAtLeast(0)}"
            else -> "$who. Автоблокировка выключена"
        }
    }

    fun banTitle(n: GuardBanNote): String = when {
        !n.ok -> "Не удалось заблокировать ${n.cidr}"
        n.auto -> "Подсеть заблокирована автоматически"
        else -> "Подсеть заблокирована"
    }

    fun banText(n: GuardBanNote): String {
        if (!n.ok) return n.error.ifBlank { "Не удалось заблокировать ${n.cidr}" }
        val base = if (n.auto) "${n.cidr} заблокирована после ${n.count} неудачных попыток входа" else "Заблокирована подсеть ${n.cidr}"
        return if (n.firewallOff) "$base. Брандмауэр Windows на ПК выключен — правило не действует, пока он не включён" else base
    }

    /** Значения порога автоблокировки для выбора: стандартные и, если с ПК задано другое, оно тоже. */
    fun banAfterOptions(current: Int): List<Int> = (BAN_AFTER_PRESETS + current).filter { it > 0 }.distinct().sorted()

    val BAN_AFTER_PRESETS = listOf(2, 3, 5, 10, 20)

    fun entryText(e: GuardEntry): String {
        val user = if (e.user.isBlank()) "" else " (${e.user})"
        return when (e.kind) {
            GuardEntry.FAIL -> "Неверный пароль" + if (e.count > 1) " × ${e.count}" else ""
            GuardEntry.LOGON -> "Вход в сеанс$user"
            GuardEntry.LOGOFF -> "Выход из сеанса$user"
            GuardEntry.DISCONNECT -> "Отключение от сеанса$user"
            GuardEntry.APP_START -> "Подключение с этого устройства"
            GuardEntry.APP_END -> "Отключение этого устройства"
            GuardEntry.APP_FAIL -> "Не удалось подключиться с этого устройства" + if (e.user.isBlank()) "" else ": ${e.user}"
            else -> e.kind
        }
    }

    /** Подсеть адреса для белого списка: /24 для IPv4 и /64 для IPv6 (границу и запись приводит к канону агент). */
    fun subnetOf(ip: String): String = if (ip.contains(':')) "$ip/64" else "$ip/24"

    /** Запись белого списка для показа: у одного адреса длину «/32» и «/128» не показываем. */
    fun cidr(cidr: String): String = cidr.removeSuffix("/32").removeSuffix("/128")

    /** Запись в поле ввода похожа на адрес или подсеть (окончательно проверяет агент). */
    fun looksLikeAddress(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val v4 = Regex("""\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?""")
        val v6 = Regex("""[0-9A-Fa-f:.]+(/\d{1,3})?""")
        return v4.matches(t) || (t.contains(':') && v6.matches(t))
    }

    fun whitelistSource(manual: Boolean): String = if (manual) "добавлен вручную" else "запомнен по подключению планшета"

    /** Адрес записи для подписи: у записей приложения его нет. */
    fun entryAddress(e: GuardEntry): String = when {
        e.fromApp -> ""
        e.local -> "${e.ip} · своя сеть"
        e.trusted -> "${e.ip} · свой адрес"
        else -> e.ip
    }

    /** Запись заслуживает внимания: попытки с чужого адреса, а в режиме белого списка — и входы с него. */
    fun entrySuspicious(e: GuardEntry, whitelistMode: Boolean = false): Boolean =
        (e.kind == GuardEntry.FAIL || whitelistMode && e.kind == GuardEntry.LOGON) && !e.local && !e.trusted

    fun statusText(status: String): String = when (status) {
        GuardThreat.STATUS_BLOCKED -> "заблокировано"
        GuardThreat.STATUS_TRUSTED -> "в белом списке"
        GuardThreat.STATUS_DISMISSED -> "оставлено"
        else -> "требует решения"
    }
}
