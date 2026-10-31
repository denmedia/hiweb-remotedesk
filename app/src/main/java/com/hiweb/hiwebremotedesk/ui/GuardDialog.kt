package com.hiweb.hiwebremotedesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.GuardData
import com.hiweb.hiwebremotedesk.data.GuardState
import com.hiweb.hiwebremotedesk.data.GuardText
import com.hiweb.hiwebremotedesk.data.GuardThreat
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.BusyAmber
import com.hiweb.hiwebremotedesk.ui.theme.DangerRed
import com.hiweb.hiwebremotedesk.ui.theme.Outline
import com.hiweb.hiwebremotedesk.ui.theme.Surface
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceButton
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceCard
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary

/** Сколько записей журнала показывать сразу: полный журнал на планшете — до 500, но смотрят свежее. */
private const val JOURNAL_SHOWN = 200
private const val RESOLVED_SHOWN = 10

/**
 * Защита ПК: подозрительные подключения с кнопками «Заблокировать подсеть» / «Это я» / «Оставить», режим защиты и белый
 * список знакомых адресов и подсетей, заблокированные подсети и журнал подключений. Блокировку подтверждает запрос
 * Windows на самом ПК (права администратора).
 */
@Composable
fun GuardDialog(
    device: Device,
    data: GuardData,
    /** Агент на связи (по локальной сети или в сеансе): команды можно отправить. */
    linked: Boolean,
    onBlock: (cidr: String, enableFirewall: Boolean) -> Unit,
    onUnblock: (cidr: String) -> Unit,
    /** Занести в белый список адрес или подсеть («это я», «вся подсеть», ручной ввод). */
    onTrust: (target: String) -> Unit,
    onUntrust: (cidr: String) -> Unit,
    /** Режим защиты и запоминание адреса планшета; null — не менять. */
    onConfigure: (mode: String?, autoLearn: Boolean?, learnSubnet: Boolean?) -> Unit,
    onKeep: (threatId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var blocking by remember { mutableStateOf<GuardThreat?>(null) }
    var unblocking by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var strictAsk by remember { mutableStateOf(false) }
    val state = data.state
    val firewallOff = state != null && !state.firewallOn
    val open = data.openThreats.sortedByDescending { it.time }
    val resolved = data.threats.filter { !it.open }.sortedByDescending { it.time }.take(RESOLVED_SHOWN)
    val journal = data.entries.sortedByDescending { it.time }.take(JOURNAL_SHOWN)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 640.dp)
                .fillMaxHeight(0.9f)
                .clip(shape)
                .background(Surface)
                .border(1.dp, Outline, shape),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Защита подключений", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(device.name, color = TextSecondary, fontSize = 14.sp)
                }
                TextButton(onClick = onDismiss) { Text("Закрыть", color = TextSecondary) }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!linked) {
                    item {
                        Banner(
                            "Агент на ПК сейчас не на связи. Показаны последние полученные данные; блокировать можно, когда планшет в одной сети с ПК или в сеансе.",
                            BusyAmber,
                        )
                    }
                }
                if (firewallOff) {
                    item {
                        Banner(
                            "Брандмауэр Windows на ПК выключен: правила блокировки не действуют, пока он не включён. При блокировке можно включить его — RDP останется доступен.",
                            DangerRed,
                        )
                    }
                }
                if (state == null && data.entries.none { !it.fromApp }) {
                    item {
                        Text(
                            "Агент на ПК ещё не присылал журнал подключений. Нужен HiWeb Agent версии 1.8 или новее: откройте сеанс — приложение предложит обновить агент.",
                            color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                if (open.isNotEmpty()) {
                    item { Header("Требуют решения") }
                    items(open, key = { it.id }) { t ->
                        ThreatCard(
                            t, linked,
                            onBlock = { blocking = t },
                            onTrust = { onTrust(t.ip) },
                            onTrustSubnet = { onTrust(GuardText.subnetOf(t.ip)) },
                            onKeep = { onKeep(t.id) },
                        )
                    }
                }
                if (state != null && state.supportsWhitelist) {
                    item { Header("Режим защиты") }
                    item {
                        ModeCard(
                            state, linked,
                            onMode = { mode -> if (mode == GuardState.MODE_WHITELIST) strictAsk = true else onConfigure(mode, null, null) },
                            onLearn = { auto, subnet -> onConfigure(null, auto, subnet) },
                        )
                    }
                    item { Header("Белый список · ${state.whitelist.size}") }
                    if (state.whitelist.isEmpty()) {
                        item { Text("Пока пусто. Адрес появится здесь, когда вы подключитесь с планшета (если запоминание включено) или нажмёте «Это я» у угрозы.", color = TextSecondary, fontSize = 13.sp) }
                    }
                    items(state.whitelist.sortedByDescending { it.time }, key = { "w" + it.cidr }) { w ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceCard).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(GuardText.cidr(w.cidr), color = TextPrimary, fontSize = 15.sp)
                                Text(GuardText.whitelistSource(w.manual), color = TextSecondary, fontSize = 12.sp)
                            }
                            TextButton(onClick = { removing = w.cidr }, enabled = linked) {
                                Text("Убрать", color = if (linked) Accent else TextSecondary)
                            }
                        }
                    }
                    item {
                        PillButton("Добавить адрес или подсеть", SurfaceButton, enabled = linked, modifier = Modifier.fillMaxWidth(), onClick = { adding = true })
                    }
                } else if (state != null) {
                    item {
                        Text(
                            "Белый список и режим защиты есть у HiWeb Agent версии 1.9.5 и новее: откройте сеанс — приложение предложит обновить агент.",
                            color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                val blocked = state?.blocked.orEmpty()
                if (blocked.isNotEmpty()) {
                    item { Header("Заблокировано") }
                    items(blocked, key = { "b" + it.cidr }) { b ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceCard).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(b.cidr, color = TextPrimary, fontSize = 15.sp)
                                if (!b.enabled) Text("правило отключено", color = TextSecondary, fontSize = 12.sp)
                                else if (firewallOff) Text("брандмауэр выключен — не действует", color = DangerRed, fontSize = 12.sp)
                            }
                            TextButton(onClick = { unblocking = b.cidr }, enabled = linked) {
                                Text("Разблокировать", color = if (linked) Accent else TextSecondary)
                            }
                        }
                    }
                }
                if (resolved.isNotEmpty()) {
                    item { Header("Прошлые угрозы") }
                    items(resolved, key = { "r" + it.id }) { t ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceCard).padding(12.dp)) {
                            Text("${GuardText.threatTitle(t)} · ${GuardText.statusText(t.status)}", color = TextPrimary, fontSize = 14.sp)
                            Text("${GuardText.time(t.time)} · ${t.ip}", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
                item { Header("Журнал подключений") }
                if (journal.isEmpty()) {
                    item { Text("Пока пусто", color = TextSecondary, fontSize = 14.sp) }
                }
                items(journal, key = { "e" + it.id }) { e ->
                    val color = if (GuardText.entrySuspicious(e, state?.whitelistMode == true)) DangerRed else TextPrimary
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(GuardText.entryText(e), color = color, fontSize = 14.sp)
                        val address = GuardText.entryAddress(e)
                        Text(
                            GuardText.time(e.time) + if (address.isEmpty()) "" else " · $address",
                            color = TextSecondary, fontSize = 12.sp,
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    blocking?.let { t ->
        var enableFirewall by remember(t.id) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { blocking = null },
            containerColor = Surface,
            title = { Text("Заблокировать ${t.cidr}?", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Подключения к ПК с этой подсети перестанут проходить. Windows на ПК запросит права администратора — " +
                            "подтвердите запрос на ПК (в открытом сеансе он появится на рабочем столе).",
                        color = TextSecondary,
                    )
                    if (t.cidr.endsWith("/32") || t.cidr.endsWith("/128")) {
                        Text("Блокируется один адрес: в этой подсети есть адрес, с которого вы подключались.", color = TextSecondary)
                    }
                    if (firewallOff) {
                        Row(Modifier.fillMaxWidth().clickable { enableFirewall = !enableFirewall }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = enableFirewall, onCheckedChange = { enableFirewall = it },
                                colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = TextSecondary),
                            )
                            Text("Включить брандмауэр Windows (иначе правило не будет работать)", color = TextPrimary, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { blocking = null; onBlock(t.cidr, firewallOff && enableFirewall) }) { Text("Заблокировать", color = DangerRed) }
            },
            dismissButton = { TextButton(onClick = { blocking = null }) { Text("Отмена", color = TextSecondary) } },
        )
    }
    if (strictAsk) {
        val n = state?.whitelist?.size ?: 0
        AlertDialog(
            onDismissRequest = { strictAsk = false },
            containerColor = Surface,
            title = { Text("Включить белый список?", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Любая попытка входа с неизвестного адреса станет угрозой и сразу вызовет тревогу — не после нескольких неудач.", color = TextSecondary)
                    Text(
                        if (n == 0) "Сейчас список пуст: тревога будет и на ваши обычные подключения, пока адреса не занесены. Планшет заносит свой адрес сам, если включено запоминание."
                        else "Сейчас в списке записей: $n. Если вы подключаетесь с другого места, добавьте адрес заранее или подтвердите его при первом входе.",
                        color = TextSecondary,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { strictAsk = false; onConfigure(GuardState.MODE_WHITELIST, null, null) }) { Text("Включить", color = Accent) } },
            dismissButton = { TextButton(onClick = { strictAsk = false }) { Text("Отмена", color = TextSecondary) } },
        )
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        val valid = GuardText.looksLikeAddress(text)
        AlertDialog(
            onDismissRequest = { adding = false },
            containerColor = Surface,
            title = { Text("В белый список", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Адрес (198.51.100.71) или подсеть (198.51.100.0/24, IPv6 — 2001:db8:1:2::/64). Подсети шире /16 (IPv6 — /48) не принимаются; адреса своей сети и так считаются своими.",
                        color = TextSecondary, fontSize = 13.sp,
                    )
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Адрес или подсеть") },
                        isError = text.isNotBlank() && !valid,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Outline, focusedLabelColor = Accent,
                            unfocusedLabelColor = TextSecondary, cursorColor = Accent,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { adding = false; onTrust(text.trim()) }, enabled = valid) { Text("Добавить", color = if (valid) Accent else TextSecondary) } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена", color = TextSecondary) } },
        )
    }
    removing?.let { cidr ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Surface,
            title = { Text("Убрать ${GuardText.cidr(cidr)} из списка?", color = TextPrimary) },
            text = { Text("Подключения с этого адреса снова будут проверяться.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { removing = null; onUntrust(cidr) }) { Text("Убрать", color = DangerRed) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Отмена", color = TextSecondary) } },
        )
    }
    unblocking?.let { cidr ->
        AlertDialog(
            onDismissRequest = { unblocking = null },
            containerColor = Surface,
            title = { Text("Разблокировать $cidr?", color = TextPrimary) },
            text = { Text("Windows на ПК запросит права администратора — подтвердите запрос на ПК.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { unblocking = null; onUnblock(cidr) }) { Text("Разблокировать", color = Accent) } },
            dismissButton = { TextButton(onClick = { unblocking = null }) { Text("Отмена", color = TextSecondary) } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun Banner(text: String, color: Color) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text, color = TextPrimary, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.5f), shape).padding(12.dp),
    )
}

@Composable
private fun ThreatCard(t: GuardThreat, linked: Boolean, onBlock: () -> Unit, onTrust: () -> Unit, onTrustSubnet: () -> Unit, onKeep: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(SurfaceCard).border(1.dp, DangerRed.copy(alpha = 0.6f), shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(GuardText.threatTitle(t), color = DangerRed, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(GuardText.threatText(t), color = TextPrimary, fontSize = 14.sp)
        Text(GuardText.time(t.time), color = TextSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            PillButton("Заблокировать ${t.cidr}", DangerRed, enabled = linked, modifier = Modifier.weight(1f), onClick = onBlock)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Это я", SurfaceButton, enabled = linked, modifier = Modifier.weight(1f), onClick = onTrust)
            PillButton("Вся подсеть", SurfaceButton, enabled = linked, modifier = Modifier.weight(1f), onClick = onTrustSubnet)
            PillButton("Оставить", SurfaceButton, enabled = linked, modifier = Modifier.weight(1f), onClick = onKeep)
        }
    }
}

/** Режим защиты и запоминание адреса планшета: два выбора из нескольких вариантов с пояснением режима. */
@Composable
private fun ModeCard(state: GuardState, linked: Boolean, onMode: (String) -> Unit, onLearn: (autoLearn: Boolean, learnSubnet: Boolean) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(SurfaceCard).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Режим", color = TextSecondary, fontSize = 12.sp)
        Choice(
            listOf("Обычный", "Белый список"), if (state.whitelistMode) 1 else 0, linked,
        ) { onMode(if (it == 1) GuardState.MODE_WHITELIST else GuardState.MODE_NORMAL) }
        Text(
            if (state.whitelistMode) {
                "Тревога на любую попытку входа с адреса, которого нет в списке. Знакомые адреса, подсети и своя сеть не проверяются."
            } else {
                "Тревога после 3 неудачных попыток за 10 минут и при входе, к которому планшет не подключился. Адреса из списка не проверяются."
            },
            color = TextSecondary, fontSize = 12.sp,
        )
        Text("Запоминать адрес, с которого подключается планшет", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Choice(
            listOf("Нет", "Адрес", "Подсеть"), if (!state.autoLearn) 0 else if (state.learnSubnet) 2 else 1, linked,
        ) { onLearn(it != 0, it == 2) }
    }
}

/** Выбор одного из вариантов в строку (вместо выпадающего списка: вариантов два-три, все должны быть на виду). */
@Composable
private fun Choice(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(SurfaceButton).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Accent else Color.Transparent)
                    .clickable(enabled = enabled && !on) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (enabled || on) TextPrimary else TextSecondary, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PillButton(text: String, color: Color, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(if (enabled) color else SurfaceButton)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) TextPrimary else TextSecondary, fontSize = 14.sp, maxLines = 1)
    }
}
