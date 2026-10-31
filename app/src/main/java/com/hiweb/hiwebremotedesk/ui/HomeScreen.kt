package com.hiweb.hiwebremotedesk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hiweb.hiwebremotedesk.R
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.PcState
import com.hiweb.hiwebremotedesk.data.PcStatus
import com.hiweb.hiwebremotedesk.data.StatusTone
import com.hiweb.hiwebremotedesk.data.SshPowerResult
import com.hiweb.hiwebremotedesk.agent.AgentPower
import com.hiweb.hiwebremotedesk.rdp.PowerAction
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.AccentSelected
import com.hiweb.hiwebremotedesk.ui.theme.Background
import com.hiweb.hiwebremotedesk.ui.theme.OfflineGray
import com.hiweb.hiwebremotedesk.ui.theme.OnlineGreen
import com.hiweb.hiwebremotedesk.ui.theme.Outline
import com.hiweb.hiwebremotedesk.ui.theme.Surface
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceButton
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(onLaunch: (deviceId: String, power: PowerAction?) -> Unit, vm: HomeViewModel = viewModel()) {
    val devices by vm.devices.collectAsState()
    val status by vm.status.collectAsState()
    val agents by vm.agents.collectAsState()
    val guard by vm.guard.collectAsState()
    val guardOpen by com.hiweb.hiwebremotedesk.data.GuardNavigation.open.collectAsState()
    val filter by vm.filter.collectAsState()
    val query by vm.query.collectAsState()
    val sort by vm.sort.collectAsState()
    val scan by vm.scan.collectAsState()
    val thumbVersion by vm.thumbnails.version.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Device?>(null) }
    var adding by remember { mutableStateOf(false) }
    var credTarget by remember { mutableStateOf<Device?>(null) }
    var credPower by remember { mutableStateOf<PowerAction?>(null) }
    var powerConfirm by remember { mutableStateOf<Pair<Device, PowerAction>?>(null) }
    var agentConfirm by remember { mutableStateOf<Pair<Device, AgentPower>?>(null) }
    var discovery by remember { mutableStateOf(false) }
    var permissionsOpen by remember { mutableStateOf(false) }
    var updateOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionsTick = rememberPermissionsTick()
    // После закрытия окна права могли измениться (выдали в настройках), поэтому пересчитываем и по нему.
    val permissionsMissing = remember(permissionsTick, permissionsOpen) { com.hiweb.hiwebremotedesk.data.AppPermissions.missing(context).size }

    val onlineCount = devices.count { status.isUp(it.id) == true }
    val offlineCount = devices.count { status.isUp(it.id) == false }

    val visible = remember(devices, status, filter, query, sort) {
        devices
            .filter {
                when (val f = filter) {
                    DeviceFilter.All -> true
                    DeviceFilter.Online -> status.isUp(it.id) == true
                    DeviceFilter.Offline -> status.isUp(it.id) == false
                    is DeviceFilter.Group -> it.os == f.os
                    is DeviceFilter.Single -> it.id == f.id
                }
            }
            .filter { query.isBlank() || it.name.contains(query, true) || it.host.contains(query, true) }
            .sortedWith(
                when (sort) {
                    SortOrder.NAME -> compareBy { it.name.lowercase() }
                    SortOrder.STATUS -> compareBy<Device> { if (status.isUp(it.id) == true) 0 else 1 }.thenBy { it.name.lowercase() }
                },
            )
    }

    /**
     * Linux: команда уходит по SSH без открытия рабочего стола; если SSH недоступен — через сеанс RDP.
     * Windows: только через сеанс RDP (меню питания Windows).
     */
    fun startPower(d: Device, power: PowerAction) {
        if (d.os != OsType.UBUNTU) {
            onLaunch(d.id, power)
            return
        }
        vm.powerViaSsh(d, power) { result ->
            when (result) {
                SshPowerResult.Done -> scope.launch { snackbar.showSnackbar("${power.title}: команда отправлена") }
                is SshPowerResult.Unavailable -> onLaunch(d.id, power)
                is SshPowerResult.Failed -> scope.launch { snackbar.showSnackbar("${power.title}: ${result.message}") }
            }
        }
    }

    /** [power] — не открывать рабочий стол, а выключить, перезагрузить устройство или завершить сеанс пользователя. */
    fun connect(d: Device, power: PowerAction? = null) {
        when {
            // Команда питания выполняется внутри сеанса, поэтому вход на экране Windows вручную невозможен.
            power != null && !d.hasCredentials && !d.useNla ->
                scope.launch { snackbar.showSnackbar("${power.title}: укажите логин и пароль в настройках устройства") }
            d.hasCredentials || !d.useNla -> if (power != null) startPower(d, power) else onLaunch(d.id, null)
            else -> { credPower = power; credTarget = d }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Background)) {
        // Телефон (Compact): боковая панель не помещается рядом со списком, поэтому фильтры
        // переезжают в строку чипов над списком, а действия «добавить/найти» — в кнопки поверх списка.
        val compact = maxWidth < CompactMaxWidth
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (compact) {
                TopBarCompact(
                    query = query, onQuery = vm::setQuery, sort = sort, onSort = vm::setSort, onRefresh = vm::refreshStatus,
                    permissionsMissing = permissionsMissing, onPermissions = { permissionsOpen = true },
                    onUpdate = { updateOpen = true },
                )
                FilterChips(
                    devices = devices,
                    status = status,
                    filter = filter,
                    onlineCount = onlineCount,
                    offlineCount = offlineCount,
                    onFilter = vm::setFilter,
                )
                Box(Modifier.fillMaxSize()) {
                    DeviceGrid(
                        visible = visible,
                        status = status,
                        agents = agents,
                        guard = guard,
                        onGuard = { com.hiweb.hiwebremotedesk.data.GuardNavigation.open.value = it.id },
                        thumbVersion = thumbVersion,
                        compact = true,
                        hasDevices = devices.isNotEmpty(),
                        onAdd = { adding = true },
                        onDiscover = { discovery = true; vm.startScan() },
                        vm = vm,
                        onConnect = { connect(it) },
                        onReboot = { powerConfirm = it to PowerAction.REBOOT },
                        onShutdown = { powerConfirm = it to PowerAction.SHUTDOWN },
                        onLogout = { powerConfirm = it to PowerAction.LOGOUT },
                        onSettings = { editing = it },
                        onAgentPower = { d, p -> agentConfirm = d to p },
                    )
                }
            } else {
                TopBar(
                    query = query,
                    onQuery = vm::setQuery,
                    sort = sort,
                    onSort = vm::setSort,
                    onRefresh = vm::refreshStatus,
                    permissionsMissing = permissionsMissing,
                    onPermissions = { permissionsOpen = true },
                    onUpdate = { updateOpen = true },
                )
                Row(Modifier.fillMaxSize()) {
                    Sidebar(
                        devices = devices,
                        status = status,
                        filter = filter,
                        onlineCount = onlineCount,
                        offlineCount = offlineCount,
                        onFilter = vm::setFilter,
                        onAdd = { adding = true },
                        onDiscover = { discovery = true; vm.startScan() },
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                    Box(Modifier.fillMaxSize()) {
                        DeviceGrid(
                            visible = visible,
                            status = status,
                            agents = agents,
                            guard = guard,
                            onGuard = { com.hiweb.hiwebremotedesk.data.GuardNavigation.open.value = it.id },
                            thumbVersion = thumbVersion,
                            compact = false,
                            hasDevices = devices.isNotEmpty(),
                            onAdd = { adding = true },
                            onDiscover = { discovery = true; vm.startScan() },
                            vm = vm,
                            onConnect = { connect(it) },
                            onReboot = { powerConfirm = it to PowerAction.REBOOT },
                            onShutdown = { powerConfirm = it to PowerAction.SHUTDOWN },
                            onLogout = { powerConfirm = it to PowerAction.LOGOUT },
                            onSettings = { editing = it },
                            onAgentPower = { d, p -> agentConfirm = d to p },
                        )
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
        )
    }

    if (permissionsOpen) PermissionsDialog(onDismiss = { permissionsOpen = false })
    if (updateOpen) UpdateDialog(onDismiss = { updateOpen = false })
    if (adding) {
        DeviceEditDialog(
            device = null, newId = vm.repo::newId, onSave = vm::save, onDelete = null,
            onResetCertificate = null, onDismiss = { adding = false },
        )
    }
    editing?.let { d ->
        DeviceEditDialog(
            device = d, newId = vm.repo::newId, onSave = vm::save,
            onDelete = { vm.delete(d.id) },
            onResetCertificate = { vm.save(d.copy(certSha256 = null)) },
            onDismiss = { editing = null },
        )
    }
    credTarget?.let { d ->
        CredentialsDialog(
            device = d,
            onConfirm = { user, pass, remember ->
                if (remember) vm.save(d.copy(username = user, password = pass)) else vm.repo.rememberForSession(d.id, user, pass)
                val action = credPower
                if (action != null) startPower(d.copy(username = user, password = pass), action) else onLaunch(d.id, null)
            },
            onDismiss = { credTarget = null; credPower = null },
        )
    }
    powerConfirm?.let { (d, action) ->
        val (title, confirm) = when (action) {
            PowerAction.REBOOT -> "Перезагрузить «${d.name}»?" to "Перезагрузить"
            PowerAction.SHUTDOWN -> "Выключить «${d.name}»?" to "Выключить"
            PowerAction.LOGOUT -> "Выйти из системы на «${d.name}»?" to "Выйти"
        }
        val viaSsh = d.os == OsType.UBUNTU
        AlertDialog(
            onDismissRequest = { powerConfirm = null },
            containerColor = Surface,
            title = { Text(title, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (viaSsh) "Команда будет отправлена на устройство по SSH. Несохранённые данные могут быть потеряны."
                        else "Приложение подключится к устройству, выполнит команду и отключится. Несохранённые данные могут быть потеряны.",
                        color = TextSecondary,
                    )
                    if (viaSsh && action == PowerAction.LOGOUT) {
                        Text(
                            "После выхода удалённый рабочий стол Ubuntu будет недоступен, пока кто-нибудь не войдёт в систему на самом компьютере.",
                            color = TextSecondary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { powerConfirm = null; connect(d, action) }) { Text(confirm, color = Accent) }
            },
            dismissButton = { TextButton(onClick = { powerConfirm = null }) { Text("Отмена", color = TextSecondary) } },
        )
    }
    agentConfirm?.let { (d, action) ->
        AlertDialog(
            onDismissRequest = { agentConfirm = null },
            containerColor = Surface,
            title = { Text("${action.question} «${d.name}»?", color = TextPrimary) },
            text = {
                Text(
                    "Команду выполнит HiWeb Agent на ПК, сеанс открываться не будет. " +
                        if (action == AgentPower.SLEEP || action == AgentPower.HIBERNATE) "Открытые программы сохранятся."
                        else "Несохранённые данные могут быть потеряны.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    agentConfirm = null
                    vm.powerViaAgent(d, action) { error ->
                        scope.launch { snackbar.showSnackbar(if (error == null) "${action.inProgress}: команда отправлена" else "${action.inProgress}: $error") }
                    }
                }) { Text(action.title, color = Accent) }
            },
            dismissButton = { TextButton(onClick = { agentConfirm = null }) { Text("Отмена", color = TextSecondary) } },
        )
    }
    // Ответы агента на команды защиты (блокировка ждёт подтверждения Windows на ПК и приходит не сразу).
    LaunchedEffect(Unit) {
        com.hiweb.hiwebremotedesk.agent.GuardHub.results.collect { r ->
            val text = when {
                r.action == "block" && r.ok -> "Подсеть ${r.cidr} заблокирована"
                r.action == "block" -> r.error.ifBlank { "Не удалось заблокировать ${r.cidr}" }
                r.action == "unblock" && r.ok -> "Подсеть ${r.cidr} разблокирована"
                r.action == "unblock" -> r.error.ifBlank { "Не удалось разблокировать ${r.cidr}" }
                else -> null
            }
            if (text != null) snackbar.showSnackbar(text)
        }
    }
    guardOpen?.let { id ->
        val d = devices.firstOrNull { it.id == id }
        if (d == null) {
            com.hiweb.hiwebremotedesk.data.GuardNavigation.open.value = null
        } else {
            fun send(sent: Boolean, ok: String) {
                scope.launch { snackbar.showSnackbar(if (sent) ok else "Агент на ПК сейчас недоступен") }
            }
            GuardDialog(
                device = d,
                data = guard[id] ?: com.hiweb.hiwebremotedesk.data.GuardData(),
                linked = com.hiweb.hiwebremotedesk.agent.GuardHub.reachable(id),
                onBlock = { cidr, enableFirewall ->
                    send(com.hiweb.hiwebremotedesk.agent.GuardHub.block(id, cidr, enableFirewall), "Подтвердите запрос Windows на ПК: $cidr")
                },
                onUnblock = { cidr -> send(com.hiweb.hiwebremotedesk.agent.GuardHub.unblock(id, cidr), "Подтвердите запрос Windows на ПК: $cidr") },
                onTrust = { ip -> send(com.hiweb.hiwebremotedesk.agent.GuardHub.trust(id, ip), "Адрес $ip отмечен как свой") },
                onKeep = { threat -> send(com.hiweb.hiwebremotedesk.agent.GuardHub.dismiss(id, threat), "Угроза оставлена без действий") },
                onDismiss = { com.hiweb.hiwebremotedesk.data.GuardNavigation.open.value = null },
            )
        }
    }
    if (discovery) {
        DiscoveryDialog(
            state = scan,
            onAdd = vm::addFound,
            onRescan = vm::startScan,
            onDismiss = { discovery = false; vm.finishScan() },
        )
    }
}

/**
 * Список карточек устройств. На телефоне — одна колонка и кнопки «Добавить»/«Найти» поверх списка
 * (вместо нижнего блока боковой панели), на планшете — сетка и пустое состояние с кнопкой.
 */
@Composable
private fun DeviceGrid(
    visible: List<Device>,
    status: Map<String, PcStatus>,
    agents: Map<String, com.hiweb.hiwebremotedesk.agent.AgentPresence>,
    guard: Map<String, com.hiweb.hiwebremotedesk.data.GuardData>,
    onGuard: (Device) -> Unit,
    thumbVersion: Int,
    compact: Boolean,
    hasDevices: Boolean,
    onAdd: () -> Unit,
    onDiscover: () -> Unit,
    vm: HomeViewModel,
    onConnect: (Device) -> Unit,
    onReboot: (Device) -> Unit,
    onShutdown: (Device) -> Unit,
    onLogout: (Device) -> Unit,
    onSettings: (Device) -> Unit,
    onAgentPower: (Device, AgentPower) -> Unit,
) {
    if (visible.isEmpty()) {
        EmptyState(hasDevices = hasDevices, onAdd = onAdd, onDiscover = onDiscover, compact = compact)
        return
    }
    Box(Modifier.fillMaxSize()) {
        val padding = if (compact) 12.dp else 16.dp
        LazyVerticalGrid(
            columns = if (compact) GridCells.Fixed(1) else GridCells.Adaptive(minSize = 420.dp),
            contentPadding = PaddingValues(start = padding, end = padding, top = padding, bottom = if (compact) 96.dp else padding),
            horizontalArrangement = Arrangement.spacedBy(padding),
            verticalArrangement = Arrangement.spacedBy(padding),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(visible, key = { it.id }) { d ->
                val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, d.id, thumbVersion) {
                    value = withContext(Dispatchers.IO) { vm.thumbnails.load(d.id) }
                }
                DeviceCard(
                    device = d,
                    status = status[d.id] ?: PcStatus(PcState.CHECKING),
                    thumbnail = thumb,
                    compact = compact,
                    onConnect = { onConnect(d) },
                    onReboot = { onReboot(d) },
                    onShutdown = { onShutdown(d) },
                    onLogout = { onLogout(d) },
                    onSettings = { onSettings(d) },
                    agentPower = agents[d.id]?.power,
                    onAgentPower = { onAgentPower(d, it) },
                    // Щит виден у всех ПК с Windows, активен — когда агент на связи или уже присылал журнал.
                    guardThreats = if (d.os == OsType.WINDOWS && (guard[d.id] != null || d.id in agents)) guard[d.id]?.openThreats?.size ?: 0 else null,
                    showGuard = d.os == OsType.WINDOWS,
                    onGuard = { onGuard(d) },
                )
            }
        }
        if (compact) {
            Column(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Поиск по сети: на телефоне это отдельная кнопка, а не половина нижнего блока панели.
                FloatingActionButton(
                    onClick = onDiscover,
                    containerColor = SurfaceButton,
                    contentColor = TextPrimary,
                ) { Icon(Icons.Filled.Sync, "Найти в сети") }
                FloatingActionButton(onClick = onAdd, containerColor = Accent, contentColor = TextPrimary) {
                    Icon(Icons.Filled.Add, "Добавить устройство")
                }
            }
        }
    }
}

/** Верхняя панель телефона: значок, название, поиск и меню (сортировка и обновление — в меню). */
@Composable
private fun TopBarCompact(
    query: String,
    onQuery: (String) -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    onRefresh: () -> Unit,
    permissionsMissing: Int,
    onPermissions: () -> Unit,
    onUpdate: () -> Unit,
) {
    var searching by remember { mutableStateOf(query.isNotEmpty()) }
    var moreMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(SurfaceButton)
                    .border(1.dp, Outline, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Поиск по имени или адресу", color = TextSecondary, fontSize = 15.sp)
                        inner()
                    },
                )
            }
            IconButton(onClick = { onQuery(""); searching = false; keyboard?.hide() }) {
                Icon(Icons.Outlined.Close, "Закрыть поиск", tint = TextPrimary)
            }
        } else {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) { Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.requiredSize(54.dp)) }
            Spacer(Modifier.width(10.dp))
            Text("RemoteDesk", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Поиск", tint = TextPrimary) }
        }
        Box {
            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "Меню", tint = TextPrimary) }
            DropdownMenu(moreMenu, { moreMenu = false }, containerColor = Surface) {
                DropdownMenuItem(
                    text = { Text("Обновить статус", color = TextPrimary) },
                    onClick = { onRefresh(); moreMenu = false },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (permissionsMissing > 0) "Права приложения · не выдано: $permissionsMissing" else "Права приложения",
                            color = TextPrimary,
                        )
                    },
                    onClick = { onPermissions(); moreMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("Проверить обновление", color = TextPrimary) },
                    onClick = { onUpdate(); moreMenu = false },
                )
                SortOrder.entries.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(if (o == sort) "✓ ${o.title}" else o.title, color = if (o == sort) Accent else TextPrimary) },
                        onClick = { onSort(o); moreMenu = false },
                    )
                }
            }
        }
    }
}

/** Фильтры телефона одной прокручиваемой строкой: «Все», «Онлайн», «Оффлайн» и системы ПК. */
@Composable
private fun FilterChips(
    devices: List<Device>,
    status: Map<String, PcStatus>,
    filter: DeviceFilter,
    onlineCount: Int,
    offlineCount: Int,
    onFilter: (DeviceFilter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip("Все · ${devices.size}", filter == DeviceFilter.All) { onFilter(DeviceFilter.All) }
        FilterChip("Онлайн · $onlineCount", filter == DeviceFilter.Online, dot = true) { onFilter(DeviceFilter.Online) }
        FilterChip("Оффлайн · $offlineCount", filter == DeviceFilter.Offline, dot = false) { onFilter(DeviceFilter.Offline) }
        OsType.entries.filter { os -> devices.any { it.os == os } }.forEach { os ->
            FilterChip(os.title, filter == DeviceFilter.Group(os)) { onFilter(DeviceFilter.Group(os)) }
        }
        // Карточка, открытая из списка на планшете, могла остаться единственным фильтром.
        if (filter is DeviceFilter.Single) {
            val d = devices.firstOrNull { it.id == filter.id }
            FilterChip(d?.name ?: "Устройство", true) { onFilter(DeviceFilter.All) }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, dot: Boolean? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accent else SurfaceButton)
            .border(1.dp, if (selected) Accent else Outline, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            StatusDot(dot, 9.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label, color = TextPrimary, fontSize = 14.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TopBar(
    query: String,
    onQuery: (String) -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    onRefresh: () -> Unit,
    permissionsMissing: Int,
    onPermissions: () -> Unit,
    onUpdate: () -> Unit,
) {
    var searching by remember { mutableStateOf(query.isNotEmpty()) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Иконка приложения: слои адаптивной иконки (её XML painterResource не читает).
        // Передний слой 108 dp, видимая часть — 72 dp, поэтому он крупнее рамки и обрезается.
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(colorResource(R.color.ic_launcher_background)),
            contentAlignment = Alignment.Center,
        ) { Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.requiredSize(66.dp)) }
        Spacer(Modifier.width(14.dp))
        Text("hiWeb RemoteDesk", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (searching) {
            LaunchedEffect(Unit) { focus.requestFocus() }
            Row(
                Modifier.width(360.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(SurfaceButton)
                    .border(1.dp, Outline, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Поиск по имени или адресу", color = TextSecondary, fontSize = 16.sp)
                        inner()
                    },
                )
            }
        }
        IconButton(onClick = { if (searching) onQuery(""); searching = !searching }) {
            Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Поиск", tint = TextPrimary)
        }
        IconButton(onClick = onPermissions) {
            BadgedBox(badge = { if (permissionsMissing > 0) Badge { Text("$permissionsMissing") } }) {
                Icon(Icons.Outlined.AdminPanelSettings, "Права приложения", tint = TextPrimary)
            }
        }
        Box {
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Outlined.Tune, "Сортировка", tint = TextPrimary) }
            DropdownMenu(sortMenu, { sortMenu = false }, containerColor = Surface) {
                SortOrder.entries.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(o.title, color = if (o == sort) Accent else TextPrimary) },
                        onClick = { onSort(o); sortMenu = false },
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "Меню", tint = TextPrimary) }
            DropdownMenu(moreMenu, { moreMenu = false }, containerColor = Surface) {
                DropdownMenuItem(
                    text = { Text("Обновить статус", color = TextPrimary) },
                    onClick = { onRefresh(); moreMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("Проверить обновление", color = TextPrimary) },
                    onClick = { onUpdate(); moreMenu = false },
                )
            }
        }
    }
}

/** Для фильтров «Онлайн»/«Оффлайн»: null — ещё не проверено. */
private fun Map<String, PcStatus>.isUp(id: String): Boolean? {
    val state = this[id]?.state ?: return null
    return if (state == PcState.CHECKING) null else state.up
}

@Composable
private fun Sidebar(
    devices: List<Device>,
    status: Map<String, PcStatus>,
    filter: DeviceFilter,
    onlineCount: Int,
    offlineCount: Int,
    onFilter: (DeviceFilter) -> Unit,
    onAdd: () -> Unit,
    onDiscover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(Surface).padding(top = 12.dp)) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            item {
                SidebarRow(
                    selected = filter == DeviceFilter.All,
                    onClick = { onFilter(DeviceFilter.All) },
                    leading = { Icon(Icons.Outlined.DesktopWindows, null, tint = TextPrimary, modifier = Modifier.size(24.dp)) },
                    title = "Все устройства", count = devices.size, big = true,
                )
                Spacer(Modifier.height(8.dp))
                SidebarRow(
                    selected = filter == DeviceFilter.Online, onClick = { onFilter(DeviceFilter.Online) },
                    leading = { StatusDot(true, 12.dp) }, title = "Онлайн", count = onlineCount,
                )
                SidebarRow(
                    selected = filter == DeviceFilter.Offline, onClick = { onFilter(DeviceFilter.Offline) },
                    leading = { StatusDot(false, 12.dp) }, title = "Оффлайн", count = offlineCount,
                )
                SectionTitle("Группы")
            }
            items(OsType.entries.filter { os -> devices.any { it.os == os } }, key = { it.name }) { os ->
                SidebarRow(
                    selected = filter == DeviceFilter.Group(os), onClick = { onFilter(DeviceFilter.Group(os)) },
                    leading = { Icon(Icons.Outlined.Folder, null, tint = TextSecondary, modifier = Modifier.size(22.dp)) },
                    title = os.title, count = devices.count { it.os == os },
                )
            }
            item { SectionTitle("Устройства") }
            items(devices, key = { "d" + it.id }) { d ->
                val tone = status[d.id]?.state?.tone ?: StatusTone.UNKNOWN
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (filter == DeviceFilter.Single(d.id)) SurfaceButton else Color.Transparent)
                        .clickable { onFilter(DeviceFilter.Single(d.id)) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.DesktopWindows, null, tint = TextSecondary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    StatusDot(tone, 9.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = TextPrimary, fontSize = 16.sp, maxLines = 1)
                        Text(d.host, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                    }
                    OsLogo(d.os, 18.dp, TextSecondary)
                    Spacer(Modifier.width(6.dp))
                    Text(d.osLabel, color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BottomAction(Icons.Outlined.AddCircleOutline, "Добавить", Modifier.weight(1f), onAdd)
            BottomAction(Icons.Filled.Sync, "Добавить\nавтоматически", Modifier.weight(1f), onDiscover)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp, top = 22.dp, bottom = 8.dp))
}

@Composable
private fun SidebarRow(
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    count: Int,
    big: Boolean = false,
) {
    val shape = RoundedCornerShape(if (big) 14.dp else 12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Brush.horizontalGradient(listOf(AccentSelected, Accent)) else SolidColor(Color.Transparent))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (big) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(10.dp))
        Text(title, color = TextPrimary, fontSize = if (big) 18.sp else 16.sp, fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (big) {
            Box(
                Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("$count", color = TextPrimary, fontSize = 15.sp) }
        } else {
            Text("$count", color = TextSecondary, fontSize = 15.sp)
        }
    }
}

@Composable
private fun BottomAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.height(88.dp).clip(shape).background(SurfaceButton).border(1.dp, Outline, shape).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextPrimary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 15.sp)
    }
}

@Composable
private fun EmptyState(hasDevices: Boolean, onAdd: () -> Unit, onDiscover: () -> Unit, compact: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = if (compact) 24.dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.DesktopWindows, null, tint = OfflineGray, modifier = Modifier.size(if (compact) 56.dp else 72.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            if (hasDevices) "Нет устройств по выбранному фильтру" else "Устройств пока нет",
            color = TextPrimary, fontSize = if (compact) 18.sp else 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (!hasDevices) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Добавьте компьютер с Windows вручную или найдите его в сети",
                color = TextSecondary, fontSize = 15.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Accent).clickable(onClick = onAdd).padding(horizontal = 24.dp, vertical = 12.dp),
            ) { Text("Добавить устройство", color = TextPrimary, fontSize = 16.sp) }
            if (compact) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(SurfaceButton).border(1.dp, Outline, RoundedCornerShape(12.dp))
                        .clickable(onClick = onDiscover).padding(horizontal = 24.dp, vertical = 12.dp),
                ) { Text("Найти в сети", color = TextPrimary, fontSize = 16.sp) }
            }
        }
    }
}
