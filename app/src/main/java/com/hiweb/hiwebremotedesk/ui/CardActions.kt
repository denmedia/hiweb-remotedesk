package com.hiweb.hiwebremotedesk.ui

import com.hiweb.hiwebremotedesk.agent.AgentPower
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.PcState
import com.hiweb.hiwebremotedesk.rdp.PowerAction

/** Команда кнопки питания карточки: через HiWeb Agent (без сеанса) или через сеанс RDP / SSH. */
sealed interface CardPower {
    data class Agent(val power: AgentPower) : CardPower
    data class Session(val action: PowerAction) : CardPower
}

/** Значок кнопки питания (карточка рисует векторный значок Compose, виджет — ресурс). */
enum class PowerIcon { SHUTDOWN, RESTART, SLEEP, HIBERNATE, LOGOUT }

/** Кнопка питания карточки: видна всегда, [enabled] — доступно ли сейчас, [hint] — что показать при нажатии на неактивную. */
data class PowerSlot(val icon: PowerIcon, val label: String, val enabled: Boolean, val command: CardPower, val hint: ButtonHint)

/**
 * Какие кнопки и в каком состоянии показывает карточка ПК — общее правило для карточки в списке ([DeviceCard])
 * и виджета на главном экране (`widget/PcWidgets`), чтобы они не расходились. Чистая логика — под юнит-тестами.
 */
object CardActions {
    /** Подключиться можно и к спящему ПК, если его можно разбудить. */
    fun canConnect(device: Device, state: PcState): Boolean = state.up || state == PcState.CHECKING || device.canWake

    /** Команды через сеанс — только к доступному ПК. */
    fun canSessionPower(state: PcState): Boolean = state.up || state == PcState.CHECKING

    /** Кнопка подключения активна; [agentPower] не null — агент на связи. */
    fun connectEnabled(device: Device, state: PcState, agentPower: Set<AgentPower>?): Boolean = agentPower != null || canConnect(device, state)

    fun connectLabel(state: PcState, agentPower: Set<AgentPower>?): String =
        if (agentPower != null || state.up || state == PcState.CHECKING) "Подключиться" else "Разбудить"

    /**
     * Кнопки питания. Через агента (на связи) — без открытия сеанса и только режимы из GetPwrCapabilities; без агента сон и
     * гибернация недоступны, выключение и перезагрузка идут через сеанс RDP/SSH, плюс «Выйти» из системы.
     */
    fun powerSlots(device: Device, state: PcState, agentPower: Set<AgentPower>?): List<PowerSlot> {
        val viaAgent = agentPower != null
        val canPower = canSessionPower(state)
        val slots = AgentPower.entries.map { p ->
            val icon = when (p) {
                AgentPower.SHUTDOWN -> PowerIcon.SHUTDOWN
                AgentPower.RESTART -> PowerIcon.RESTART
                AgentPower.SLEEP -> PowerIcon.SLEEP
                AgentPower.HIBERNATE -> PowerIcon.HIBERNATE
            }
            when {
                viaAgent -> PowerSlot(icon, p.title, p in agentPower!!, CardPower.Agent(p), ButtonHints.power(device.os, p, true, state.up, state))
                p == AgentPower.SHUTDOWN ->
                    PowerSlot(icon, p.title, canPower, CardPower.Session(PowerAction.SHUTDOWN), ButtonHints.sessionPower(state))
                p == AgentPower.RESTART ->
                    PowerSlot(icon, "Перезагрузка", canPower, CardPower.Session(PowerAction.REBOOT), ButtonHints.sessionPower(state))
                else -> PowerSlot(icon, p.title, false, CardPower.Agent(p), ButtonHints.power(device.os, p, false, state.up, state))
            }
        }
        return if (viaAgent) slots
        else slots + PowerSlot(PowerIcon.LOGOUT, "Выйти", canPower, CardPower.Session(PowerAction.LOGOUT), ButtonHints.sessionPower(state))
    }
}
