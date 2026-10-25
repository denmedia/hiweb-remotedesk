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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.PcState
import com.hiweb.hiwebremotedesk.data.PcStatus
import com.hiweb.hiwebremotedesk.data.StatusTone
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.BusyAmber
import com.hiweb.hiwebremotedesk.ui.theme.SleepBlue
import com.hiweb.hiwebremotedesk.ui.theme.OfflineGray
import com.hiweb.hiwebremotedesk.ui.theme.OnlineGreen
import com.hiweb.hiwebremotedesk.ui.theme.Outline
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
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .height(64.dp)
            .clip(shape)
            .background(if (primary && enabled) Accent else SurfaceButton)
            .border(1.dp, if (primary && enabled) Accent else Outline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (enabled) TextPrimary else TextDisabled
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (enabled) (if (primary) TextPrimary else TextSecondary) else TextDisabled, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun PreviewBox(device: Device, status: PcStatus, thumbnail: ImageBitmap?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val state = status.state
    val offline = state.tone == StatusTone.ASLEEP || state.tone == StatusTone.DOWN
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(shape)
            .clickable(enabled = !offline || device.canWake, onClick = onClick),
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
    /** Действия питания через HiWeb Agent; null — агент на ПК не на связи. */
    agentPower: Set<com.hiweb.hiwebremotedesk.agent.AgentPower>? = null,
    onAgentPower: (com.hiweb.hiwebremotedesk.agent.AgentPower) -> Unit = {},
) {
    val shape = RoundedCornerShape(20.dp)
    val state = status.state
    // Подключиться можно и к спящему ПК, если его можно разбудить; команды через сеанс — только к доступному.
    val canConnect = state.up || state == PcState.CHECKING || device.canWake
    val canPower = state.up || state == PcState.CHECKING
    Column(
        modifier
            .clip(shape)
            .background(SurfaceCard)
            .border(1.dp, Outline, shape)
            .padding(14.dp),
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
        }
        Spacer(Modifier.height(10.dp))
        PreviewBox(device, status, thumbnail, onConnect)
        Spacer(Modifier.height(10.dp))
        Text(device.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(device.host, color = TextSecondary, fontSize = 14.sp, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        if (agentPower != null) {
            // Агент на ПК: питание через него, без открытия сеанса; показываем только доступные действия.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(Icons.Outlined.DesktopWindows, "Подключиться", Modifier.weight(2f), primary = true, onClick = onConnect)
                ActionButton(Icons.Outlined.Settings, "Настройки", Modifier.weight(1f), onClick = onSettings)
            }
            if (agentPower.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.hiweb.hiwebremotedesk.agent.AgentPower.entries.filter { it in agentPower }.forEach { p ->
                        val icon = when (p) {
                            com.hiweb.hiwebremotedesk.agent.AgentPower.SHUTDOWN -> Icons.Outlined.PowerSettingsNew
                            com.hiweb.hiwebremotedesk.agent.AgentPower.RESTART -> Icons.Outlined.RestartAlt
                            com.hiweb.hiwebremotedesk.agent.AgentPower.SLEEP -> Icons.Outlined.DarkMode
                            com.hiweb.hiwebremotedesk.agent.AgentPower.HIBERNATE -> Icons.Outlined.AcUnit
                        }
                        ActionButton(icon, p.title, Modifier.weight(1f)) { onAgentPower(p) }
                    }
                }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(
                Icons.Outlined.DesktopWindows, if (state.up || state == PcState.CHECKING) "Подключиться" else "Разбудить",
                Modifier.weight(1f), primary = true, enabled = canConnect, onClick = onConnect,
            )
            ActionButton(Icons.Outlined.RestartAlt, "Перезагрузка", Modifier.weight(1f), enabled = canPower, onClick = onReboot)
            ActionButton(Icons.Outlined.PowerSettingsNew, "Выключить", Modifier.weight(1f), enabled = canPower, onClick = onShutdown)
            ActionButton(Icons.AutoMirrored.Outlined.Logout, "Выйти", Modifier.weight(1f), enabled = canPower, onClick = onLogout)
            ActionButton(Icons.Outlined.Settings, "Настройки", Modifier.weight(1f), onClick = onSettings)
        }
    }
}
