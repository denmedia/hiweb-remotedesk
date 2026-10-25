package com.hiweb.hiwebremotedesk.data

/** Цвет точки состояния: доступен, доступен, но занят, спит, недоступен, ещё не известно. */
enum class StatusTone { UP, BUSY, ASLEEP, DOWN, UNKNOWN }

/** Состояние ПК на экране списка. */
enum class PcState(val title: String, val tone: StatusTone) {
    CHECKING("Проверка…", StatusTone.UNKNOWN),
    ONLINE("Онлайн", StatusTone.UP),
    ON_TABLET("Онлайн · сеанс на планшете", StatusTone.UP),
    IN_USE("Онлайн · за ПК работают", StatusTone.BUSY),
    REMOTE("Онлайн · подключён другой клиент RDP", StatusTone.BUSY),
    LOCKED("Онлайн · экран заблокирован", StatusTone.UP),
    SIGNED_OUT("Онлайн · вход не выполнен", StatusTone.UP),
    SLEEP("Сон", StatusTone.ASLEEP),
    HIBERNATE("Гибернация", StatusTone.ASLEEP),
    RESTARTING("Перезагружается…", StatusTone.DOWN),
    SHUTDOWN("Выключен", StatusTone.DOWN),
    OFFLINE("Оффлайн", StatusTone.DOWN),
    ;

    /** Рабочий стол можно открыть сразу. */
    val up: Boolean get() = tone == StatusTone.UP || tone == StatusTone.BUSY
}

/** [since] — когда ПК ушёл в это состояние (мс Unix), если известно. */
data class PcStatus(val state: PcState, val since: Long? = null)

/** Проводной адаптер ПК для Wake-on-LAN: MAC и широковещательный адрес его подсети. */
data class WakeTarget(val mac: String, val broadcast: String)

/**
 * Последнее состояние, присланное HiWeb Agent (по локальной сети или по каналу RDP), и когда оно получено.
 * [state] — active, locked, sleep, hibernate, shutdown, restart, logoff; [session] — tablet, remote, console, none.
 */
data class AgentReport(
    val state: String,
    val session: String,
    val idleSec: Int,
    val at: Long,
    val wake: List<WakeTarget> = emptyList(),
)

object PcStatusRules {
    /** Сколько секунд после последнего ввода на консоли считать, что за ПК работают. */
    const val BUSY_IDLE_SEC = 120

    /** Свежее сообщение агента важнее проверки порта: проверка идёт раз в 8 с и могла застать ПК ещё не уснувшим. */
    const val FRESH_MS = 20_000L

    /** Перезагрузка дольше этого — уже не перезагрузка, а недоступный ПК. */
    const val RESTART_MS = 5 * 60_000L

    /**
     * Состояние для карточки. [portOpen] — открыт ли порт RDP (null — ещё не проверяли), [agentLinked] —
     * агент на связи с планшетом по локальной сети, [report] — последнее сообщение агента о состоянии.
     */
    fun derive(portOpen: Boolean?, agentLinked: Boolean, report: AgentReport?, now: Long): PcStatus {
        val fresh = report != null && now - report.at < FRESH_MS
        when (report?.state) {
            "sleep", "hibernate", "shutdown" -> {
                // ПК, который снова отвечает, кто-то разбудил или включил: сообщение агента устарело.
                val back = (portOpen == true || agentLinked) && !fresh
                if (!back) {
                    val state = when (report.state) {
                        "sleep" -> PcState.SLEEP
                        "hibernate" -> PcState.HIBERNATE
                        else -> PcState.SHUTDOWN
                    }
                    return PcStatus(state, report.at)
                }
            }
            "restart" -> if (fresh || (portOpen != true && !agentLinked && now - report.at < RESTART_MS)) {
                return PcStatus(PcState.RESTARTING, report.at)
            }
            "logoff" -> if (!agentLinked) {
                return when (portOpen) {
                    true -> PcStatus(PcState.SIGNED_OUT, report.at)
                    false -> PcStatus(PcState.OFFLINE)
                    null -> PcStatus(PcState.CHECKING)
                }
            }
        }
        if (agentLinked) {
            if (report == null || report.state !in setOf("active", "locked")) return PcStatus(PcState.ONLINE)
            return PcStatus(
                when {
                    report.state == "locked" -> PcState.LOCKED
                    report.session == "tablet" -> PcState.ON_TABLET
                    report.session == "remote" -> PcState.REMOTE
                    report.session == "console" && report.idleSec < BUSY_IDLE_SEC -> PcState.IN_USE
                    else -> PcState.ONLINE
                },
            )
        }
        return when (portOpen) {
            true -> PcStatus(PcState.ONLINE)
            false -> PcStatus(PcState.OFFLINE)
            null -> PcStatus(PcState.CHECKING)
        }
    }

    /** Куда слать волшебный пакет: всем в своей сети, в подсеть ПК (по словам агента) и на адрес ПК. */
    fun wakeAddresses(host: String, localBroadcast: String?, report: AgentReport?): List<String> = buildList {
        add("255.255.255.255")
        localBroadcast?.let { add(it) }
        report?.wake?.forEach { add(it.broadcast) }
        add(host)
    }.filter { it.isNotBlank() }.distinct()
}
