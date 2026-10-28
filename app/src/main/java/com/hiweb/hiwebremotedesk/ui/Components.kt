package com.hiweb.hiwebremotedesk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.hiweb.hiwebremotedesk.agent.AgentPower
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.PcState
import com.hiweb.hiwebremotedesk.data.PcStatus
import com.hiweb.hiwebremotedesk.data.StatusTone
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.BusyAmber
import com.hiweb.hiwebremotedesk.ui.theme.DangerRed
import com.hiweb.hiwebremotedesk.ui.theme.SleepBlue
import com.hiweb.hiwebremotedesk.ui.theme.OfflineGray
import com.hiweb.hiwebremotedesk.ui.theme.OnlineGreen
import com.hiweb.hiwebremotedesk.ui.theme.Outline
import com.hiweb.hiwebremotedesk.ui.theme.Surface
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceButton
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceCard
import com.hiweb.hiwebremotedesk.ui.theme.TextDisabled
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary

/** Упрощённый «круг друзей» Ubuntu: кольцо и три точки. */
@Composable
fun UbuntuLogo(size: Dp = 20.dp, color: Color = Accent) {
    Canvas(Modifier.size(size)) {
        val d = this.size.minDimension
        val c = Offset(d / 2f, d / 2f)
        drawCircle(color, radius = d * 0.3f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = d * 0.1f))
        for (deg in listOf(180.0, 300.0, 60.0)) {
            val a = Math.toRadians(deg)
            drawCircle(color, radius = d * 0.11f, center = Offset(c.x + (d * 0.4f * Math.cos(a)).toFloat(), c.y + (d * 0.4f * Math.sin(a)).toFloat()))
        }
    }
}

@Composable
fun OsLogo(os: OsType, size: Dp = 20.dp, color: Color = Accent) {
    if (os == OsType.UBUNTU) UbuntuLogo(size, color) else WindowsLogo(size, color)
}

@Composable
fun WindowsLogo(size: Dp = 20.dp, color: Color = Accent) {
    Canvas(Modifier.size(size)) {
        val gap = this.size.minDimension * 0.07f
        val s = (this.size.minDimension - gap) / 2f
        val r = CornerRadius(s * 0.08f)
        for (row in 0..1) for (col in 0..1) {
            drawRoundRect(
                color = color,
                topLeft = Offset(col * (s + gap), row * (s + gap)),
                size = Size(s, s),
                cornerRadius = r,
            )
        }
    }
}

@Composable
fun StatusDot(online: Boolean?, size: Dp = 10.dp) {
    val color = when (online) {
        true -> OnlineGreen
        false -> OfflineGray
        null -> OfflineGray.copy(alpha = 0.5f)
    }
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.UP -> OnlineGreen
    StatusTone.BUSY -> BusyAmber
    StatusTone.ASLEEP -> SleepBlue
    StatusTone.DOWN -> OfflineGray
    StatusTone.UNKNOWN -> OfflineGray.copy(alpha = 0.5f)
}

@Composable
fun StatusDot(tone: StatusTone, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(toneColor(tone)))
}

/** «Сон · с 14:32»: для сна, выключения и перезагрузки показываем, с какого времени. */
fun statusText(status: PcStatus): String {
    val since = status.since ?: return status.state.title
    if (status.state.tone != StatusTone.ASLEEP && status.state.tone != StatusTone.DOWN) return status.state.title
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = since }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "d MMM, HH:mm"
    return status.state.title + " · с " + java.text.SimpleDateFormat(pattern, java.util.Locale("ru")).format(java.util.Date(since))
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    /** Телефон: подпись мельче и выше, чтобы шесть подписей влезали в 384 dp без обрезки. */
    compact: Boolean = false,
    /** Нажатие на неактивную кнопку: пояснение, почему она не работает и что сделать. */
    onDisabledClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .height(if (compact) 60.dp else 64.dp)
            .clip(shape)
            .background(if (primary && enabled) Accent else SurfaceButton)
            .border(1.dp, if (primary && enabled) Accent else Outline, shape)
            .clickable(enabled = enabled || onDisabledClick != null, onClick = if (enabled) onClick else onDisabledClick ?: {}),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (enabled) TextPrimary else TextDisabled
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (compact) 22.dp else 24.dp))
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        Text(
            label,
            color = if (enabled) (if (primary) TextPrimary else TextSecondary) else TextDisabled,
            fontSize = if (compact) 10.sp else 11.sp,
            maxLines = 1,
            // На телефоне подпись длиннее кнопки досжимается, а не обрезается многоточием.
            softWrap = compact,
            overflow = if (compact) TextOverflow.Visible else TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PreviewBox(device: Device, status: PcStatus, thumbnail: ImageBitmap?, onDisabledClick: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val state = status.state
    val offline = state.tone == StatusTone.ASLEEP || state.tone == StatusTone.DOWN
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(shape)
            .clickable(onClick = if (!offline || device.canWake) onClick else onDisabledClick),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "Превью ${device.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (offline) 0.35f else 1f),
            )
        } else {
            // Заглушка в стиле обоев Windows 11, пока нет сохранённого кадра.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        if (device.os == OsType.UBUNTU) listOf(Color(0xFF5E2750), Color(0xFFE95420), Color(0xFF2C001E))
                        else listOf(Color(0xFF0B3C9E), Color(0xFF1E6BF2), Color(0xFF0A1F5C)),
                    ),
                ).alpha(if (offline) 0.35f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                OsLogo(device.os, size = 56.dp, color = Color.White.copy(alpha = 0.85f))
            }
        }
        if (offline) {
            val icon = when (state) {
                PcState.SLEEP -> Icons.Outlined.DarkMode
                PcState.HIBERNATE -> Icons.Outlined.AcUnit
                PcState.SHUTDOWN -> Icons.Outlined.PowerSettingsNew
                PcState.RESTARTING -> Icons.Outlined.RestartAlt
                else -> Icons.Outlined.Cancel
            }
            val title = when (state) {
                PcState.SLEEP -> "Спящий режим"
                PcState.HIBERNATE -> "Гибернация"
                PcState.SHUTDOWN -> "Компьютер выключен"
                PcState.RESTARTING -> "Перезагрузка…"
                else -> "Устройство недоступно"
            }
            val hint = when {
                state == PcState.RESTARTING -> null
                device.canWake -> "Нажмите, чтобы разбудить по сети и подключиться"
                state.tone == StatusTone.ASLEEP -> "Чтобы будить ПК, укажите его MAC-адрес в настройках"
                else -> null
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = TextPrimary.copy(alpha = 0.8f), modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(6.dp))
                Text(title, color = TextPrimary.copy(alpha = 0.85f), fontSize = 13.sp)
                if (hint != null) Text(hint, color = TextPrimary.copy(alpha = 0.65f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: Device,
    status: PcStatus,
    thumbnail: ImageBitmap?,
    onConnect: () -> Unit,
    onReboot: () -> Unit,
    onShutdown: () -> Unit,
    onLogout: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Телефон: одна карточка в ряд, питание — в меню «⋮» (пять подписанных кнопок в 384 dp не влезают). */
    compact: Boolean = false,
    /** Действия питания через HiWeb Agent; null — агент на ПК не на связи. */
    agentPower: Set<com.hiweb.hiwebremotedesk.agent.AgentPower>? = null,
    onAgentPower: (com.hiweb.hiwebremotedesk.agent.AgentPower) -> Unit = {},
    /** Защита подключений: число нерешённых угроз; null — агент не присылал данных, значок неактивен. */
    guardThreats: Int? = null,
    /** Показывать ли щит (у ПК с агентом, то есть Windows); без данных он виден, но неактивен. */
    showGuard: Boolean = guardThreats != null,
    onGuard: () -> Unit = {},
) {
    val shape = RoundedCornerShape(20.dp)
    val state = status.state
    // Подключиться можно и к спящему ПК, если его можно разбудить; команды через сеанс — только к доступному.
    val canConnect = state.up || state == PcState.CHECKING || device.canWake
    val canPower = state.up || state == PcState.CHECKING
    var powerMenu by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<ButtonHint?>(null) }
    Column(
        modifier
            .clip(shape)
            .background(SurfaceCard)
            .border(1.dp, Outline, shape)
            .padding(if (compact) 12.dp else 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(state.tone, 14.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                statusText(status),
                color = if (state.tone == StatusTone.UNKNOWN || state.tone == StatusTone.DOWN) TextSecondary else toneColor(state.tone),
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            OsLogo(device.os, 20.dp)
            Spacer(Modifier.width(6.dp))
            Text(device.osLabel, color = TextSecondary, fontSize = 14.sp)
            if (showGuard) {
                Spacer(Modifier.width(6.dp))
                GuardBadge(guardThreats ?: 0, enabled = guardThreats != null, onDisabledClick = { hint = ButtonHints.guard() }, onClick = onGuard)
            }
        }
        Spacer(Modifier.height(10.dp))
        PreviewBox(device, status, thumbnail, { hint = ButtonHints.connect(state) }, onConnect)
        Spacer(Modifier.height(10.dp))
        Text(device.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(device.host, color = TextSecondary, fontSize = 14.sp, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        // Кнопки питания видны всегда; недоступные неактивны и включаются, как только агент сообщит о режиме.
        // Через агента (на связи) — без открытия сеанса и только режимы из GetPwrCapabilities; без агента сон и
        // гибернация недоступны, остальное — через сеанс RDP/SSH.
        val viaAgent = agentPower != null
        val power = com.hiweb.hiwebremotedesk.agent.AgentPower.entries.map { p ->
            when {
                viaAgent -> PowerButton(agentPowerIcon(p), p.title, p in agentPower!!, { onAgentPower(p) }, ButtonHints.power(device.os, p, true, state.up, state))
                p == com.hiweb.hiwebremotedesk.agent.AgentPower.SHUTDOWN -> PowerButton(agentPowerIcon(p), p.title, canPower, onShutdown, ButtonHints.sessionPower(state))
                p == com.hiweb.hiwebremotedesk.agent.AgentPower.RESTART -> PowerButton(agentPowerIcon(p), "Перезагрузка", canPower, onReboot, ButtonHints.sessionPower(state))
                else -> PowerButton(agentPowerIcon(p), p.title, false, {}, ButtonHints.power(device.os, p, false, state.up, state))
            }
        } + if (viaAgent) emptyList() else listOf(PowerButton(Icons.AutoMirrored.Outlined.Logout, "Выйти", canPower, onLogout, ButtonHints.sessionPower(state)))
        val connectLabel = if (viaAgent || state.up || state == PcState.CHECKING) "Подключиться" else "Разбудить"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(
                Icons.Outlined.DesktopWindows, connectLabel, Modifier.weight(if (viaAgent || !compact) 2f else 1f),
                primary = true, enabled = viaAgent || canConnect, compact = compact,
                onDisabledClick = { hint = ButtonHints.connect(state) }, onClick = onConnect,
            )
            ActionButton(Icons.Outlined.Settings, "Настройки", Modifier.weight(1f), compact = compact, onClick = onSettings)
            if (compact) Box {
                ActionButton(Icons.Outlined.MoreVert, "Питание", Modifier.width(64.dp), enabled = power.any { it.enabled }, compact = true,
                    onDisabledClick = { hint = power.first().hint }) { powerMenu = true }
                DropdownMenu(powerMenu, { powerMenu = false }, containerColor = Surface) {
                    power.forEach { b ->
                        DropdownMenuItem(
                            text = { Text(if (b.label == "Выйти") "Выйти из системы" else b.label, color = if (b.enabled) TextPrimary else TextDisabled) },
                            leadingIcon = { Icon(b.icon, null, tint = if (b.enabled) TextSecondary else TextDisabled) },
                            // Неактивный пункт остаётся нажимаемым — показывает пояснение.
                            onClick = { powerMenu = false; if (b.enabled) b.onClick() else hint = b.hint },
                        )
                    }
                }
            }
        }
        if (!compact) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                power.forEach { b -> ActionButton(b.icon, b.label, Modifier.weight(1f), enabled = b.enabled, onDisabledClick = { hint = b.hint }, onClick = b.onClick) }
            }
        }
        hint?.let { h ->
            AlertDialog(
                onDismissRequest = { hint = null },
                containerColor = Surface,
                title = { Text(h.title, color = TextPrimary) },
                text = { Text(h.text, color = TextSecondary, fontSize = 14.sp) },
                confirmButton = { TextButton(onClick = { hint = null }) { Text("Понятно") } },
            )
        }
    }
}

/** Кнопка питания карточки ПК: показывается всегда, [enabled] — доступно ли действие сейчас. */
private class PowerButton(
    val icon: ImageVector, val label: String, val enabled: Boolean, val onClick: () -> Unit,
    /** Что показать при нажатии, пока кнопка неактивна. */
    val hint: ButtonHint,
)

/** Щит защиты подключений; красный со счётчиком, пока есть угрозы; [enabled] false — агент ещё не присылал данных. */
@Composable
private fun GuardBadge(threats: Int, enabled: Boolean, onDisabledClick: () -> Unit, onClick: () -> Unit) {
    val alert = enabled && threats > 0
    Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = if (enabled) onClick else onDisabledClick), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Outlined.Shield, contentDescription = "Защита подключений",
            tint = if (alert) DangerRed else if (enabled) TextSecondary else TextDisabled, modifier = Modifier.size(22.dp),
        )
        if (alert) {
            Box(
                Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(DangerRed),
                contentAlignment = Alignment.Center,
            ) { Text(threats.coerceAtMost(9).toString(), color = TextPrimary, fontSize = 10.sp) }
        }
    }
}

private fun agentPowerIcon(p: com.hiweb.hiwebremotedesk.agent.AgentPower): ImageVector = when (p) {
    com.hiweb.hiwebremotedesk.agent.AgentPower.SHUTDOWN -> Icons.Outlined.PowerSettingsNew
    com.hiweb.hiwebremotedesk.agent.AgentPower.RESTART -> Icons.Outlined.RestartAlt
    com.hiweb.hiwebremotedesk.agent.AgentPower.SLEEP -> Icons.Outlined.DarkMode
    com.hiweb.hiwebremotedesk.agent.AgentPower.HIBERNATE -> Icons.Outlined.AcUnit
}
