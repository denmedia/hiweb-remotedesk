package com.hiweb.hiwebremotedesk.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hiweb.hiwebremotedesk.data.OsDetector
import com.hiweb.hiwebremotedesk.data.WakeOnLan
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.hiweb.hiwebremotedesk.data.StrengthLevels
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.FoundHost
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.SafTree
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.DangerRed
import com.hiweb.hiwebremotedesk.ui.theme.Outline
import com.hiweb.hiwebremotedesk.ui.theme.Surface
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Outline,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextSecondary,
    cursorColor = Accent,
)

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String = "Пароль", modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        colors = fieldColors(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null, tint = TextSecondary)
            }
        },
    )
}

/** Добавление и редактирование устройства (device == null — новое). */
@Composable
fun DeviceEditDialog(
    device: Device?,
    newId: () -> String,
    onSave: (Device) -> Unit,
    onDelete: (() -> Unit)?,
    onResetCertificate: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(device?.name ?: "") }
    var host by remember { mutableStateOf(device?.host ?: "") }
    var os by remember { mutableStateOf(device?.os ?: OsType.WINDOWS) }
    var port by remember { mutableStateOf((device?.port ?: 3389).toString()) }
    var user by remember { mutableStateOf(device?.username ?: "") }
    var pass by remember { mutableStateOf(device?.password ?: "") }
    var useNla by remember { mutableStateOf(device?.useNla ?: true) }
    var scale by remember { mutableStateOf((device?.scalePercent ?: 100).toFloat()) }
    var colorDepth by remember { mutableStateOf(device?.colorDepth ?: Device.DEFAULT_COLOR_DEPTH) }
    var scrollLevel by remember { mutableStateOf((device?.scrollLevel ?: StrengthLevels.DEFAULT).toFloat()) }
    var inertiaLevel by remember { mutableStateOf((device?.inertiaLevel ?: StrengthLevels.DEFAULT).toFloat()) }
    var fileTransfer by remember { mutableStateOf(device?.fileTransfer ?: true) }
    var driveEnabled by remember { mutableStateOf(device?.driveEnabled ?: false) }
    var driveUri by remember { mutableStateOf(device?.driveUri) }
    var driveName by remember { mutableStateOf(device?.driveName ?: Device.DEFAULT_DRIVE_NAME) }
    var driveReadOnly by remember { mutableStateOf(device?.driveReadOnly ?: false) }
    var wakeMac by remember { mutableStateOf(device?.wakeMac ?: "") }
    val wakeMacError = wakeMac.isNotBlank() && WakeOnLan.parseMac(wakeMac) == null
    var tried by remember { mutableStateOf(false) }
    // Ручной выбор имеет приоритет: после него автоопределение больше не меняет систему и порт.
    var osManual by remember { mutableStateOf(device != null) }
    var portManual by remember { mutableStateOf(device != null) }
    var detecting by remember { mutableStateOf(false) }
    var detectNote by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            // Права на выбранную папку нужно закрепить, иначе они пропадут при закрытии приложения.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            driveUri = uri.toString()
        }
    }
    val driveFolderName = remember(driveUri) {
        driveUri?.let { runCatching { SafTree(context, Uri.parse(it)).displayName() }.getOrNull() ?: "Выбрана" }
    }

    val scope = rememberCoroutineScope()
    suspend fun detect(applyPort: Boolean) {
        detecting = true
        detectNote = null
        val p = withContext(Dispatchers.IO) { OsDetector.detect(host) }
        detecting = false
        os = p.os
        useNla = true
        if (applyPort && p.rdpFound) port = p.port.toString()
        detectNote = if (p.rdpFound) "Определено автоматически: ${p.label}, порт ${p.port}"
        else "Служба удалённого рабочего стола не отвечает. Выбрана система: ${p.label}"
    }
    // Новое устройство: после ввода адреса система определяется сама, пока пользователь её не выбрал вручную.
    LaunchedEffect(host) {
        detectNote = null
        if (device == null && !osManual && host.length >= 3 && host.none { it.isWhitespace() }) {
            delay(700)
            if (!osManual) detect(applyPort = !portManual)
        }
    }

    val portNum = port.toIntOrNull()
    val hostError = tried && host.isBlank()
    val portError = tried && (portNum == null || portNum !in 1..65535)
    val driveError = tried && driveEnabled && driveUri == null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(if (device == null) "Новое устройство" else "Настройки устройства", color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Система", color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (detecting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                    } else {
                        TextButton(
                            enabled = host.isNotBlank(),
                            onClick = { osManual = false; scope.launch { detect(applyPort = true) } },
                        ) { Text("Определить", color = Accent) }
                    }
                }
                detectNote?.let { Text(it, color = TextSecondary, fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OsType.entries.forEach { o ->
                        RadioRow(o.title, os == o) {
                            osManual = true
                            // Порт меняем только пока он стоит по умолчанию для прежней системы.
                            if (os != o) {
                                if (port == os.defaultPort.toString()) port = o.defaultPort.toString()
                                os = o
                            }
                        }
                    }
                }
                if (os == OsType.UBUNTU) {
                    Text(
                        "Подключение к экрану, открытому на Ubuntu, через штатный «Удалённый рабочий стол» GNOME (Параметры → Совместный доступ). " +
                            "Логин и пароль здесь — те, что заданы в его настройках, они отдельные от пароля пользователя Ubuntu.",
                        color = TextSecondary, fontSize = 12.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        host, { host = it.trim() }, label = { Text("Адрес (IP или имя)") }, singleLine = true, isError = hostError,
                        colors = fieldColors(), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit).take(5); portManual = true }, label = { Text("Порт") }, singleLine = true, isError = portError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors(), modifier = Modifier.width(110.dp),
                    )
                }
                OutlinedTextField(
                    user, { user = it }, label = { Text("Пользователь (ДОМЕН\\имя)") }, singleLine = true,
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(pass, { pass = it }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    wakeMac, { wakeMac = it.trim().take(17) }, label = { Text("MAC-адрес для пробуждения по сети") }, singleLine = true,
                    isError = wakeMacError, placeholder = { Text("00:1A:2B:3C:4D:5E") },
                    supportingText = {
                        Text(
                            if (wakeMacError) "Неверный MAC-адрес"
                            else "Спящий или выключенный ПК будится при подключении (Wake-on-LAN). HiWeb Agent заполняет адрес сам.",
                        )
                    },
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Проверка подлинности NLA", color = TextPrimary, fontSize = 15.sp)
                        Text(
                            if (os == OsType.UBUNTU) "GNOME Remote Desktop всегда требует NLA: логин и пароль нужно указать в полях выше"
                            else "Выключите, если на ПК отключено требование NLA: пароль тогда вводится на экране входа Windows",
                            color = TextSecondary, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = useNla, onCheckedChange = { useNla = it })
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (os == OsType.UBUNTU) "Масштаб интерфейса" else "Масштаб Windows", color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("${scale.toInt()}%", color = Accent, fontSize = 15.sp)
                    }
                    Slider(
                        value = scale, onValueChange = { scale = it }, valueRange = 100f..300f, steps = 7,
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Outline),
                    )
                    Text("Увеличивает текст и элементы на удалённом ПК. Применяется при следующем подключении.", color = TextSecondary, fontSize = 12.sp)
                }
                Column {
                    Text("Глубина цвета", color = TextPrimary, fontSize = 15.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Device.COLOR_DEPTHS.forEach { depth ->
                            Row(
                                Modifier.selectable(selected = colorDepth == depth, role = Role.RadioButton, onClick = { colorDepth = depth }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = colorDepth == depth, onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextSecondary),
                                )
                                Text("$depth бит", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp))
                            }
                        }
                    }
                    Text(
                        "С 24 и 32 битами шрифты и градиенты выглядят ровнее, но трафика больше. Применяется при следующем подключении.",
                        color = TextSecondary, fontSize = 12.sp,
                    )
                }
                StrengthSlider(
                    title = "Сила скролла",
                    hint = "Скорость прокрутки двумя пальцами на тачпаде и сенсорном экране. Применяется при следующем подключении.",
                    value = scrollLevel,
                    onChange = { scrollLevel = it },
                )
                StrengthSlider(
                    title = "Сила инерции",
                    hint = "Как далеко прокрутка продолжается после отрыва пальцев. Применяется при следующем подключении.",
                    value = inertiaLevel,
                    onChange = { inertiaLevel = it },
                )
                Text("Обмен файлами", color = Accent, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Файлы через буфер обмена", color = TextPrimary, fontSize = 15.sp)
                        Text(
                            "Файлы, скопированные на ПК (Ctrl+C), можно сохранить на планшет, а файлы планшета — отправить на ПК " +
                                "кнопкой в меню сеанса и вставить в Проводнике по Ctrl+V. Применяется при следующем подключении.",
                            color = TextSecondary, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = fileTransfer, onCheckedChange = { fileTransfer = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Общий диск", color = TextPrimary, fontSize = 15.sp)
                        Text(
                            "Папка планшета появится в Проводнике Windows как сетевой диск. Подключается после входа в Windows. " +
                                "Применяется при следующем подключении.",
                            color = TextSecondary, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = driveEnabled, onCheckedChange = { driveEnabled = it })
                }
                if (driveEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Папка планшета", color = TextPrimary, fontSize = 15.sp)
                            Text(
                                driveFolderName ?: "Не выбрана",
                                color = if (driveError) DangerRed else TextSecondary, fontSize = 13.sp,
                            )
                        }
                        TextButton(onClick = { folderPicker.launch(null) }) { Text(if (driveUri == null) "Выбрать" else "Изменить", color = Accent) }
                    }
                    OutlinedTextField(
                        driveName,
                        { driveName = it.filter { c -> c.code in 0x21..0x7E && c !in ":<>\"/\\|" }.take(32) },
                        label = { Text("Имя диска в Windows") }, singleLine = true,
                        supportingText = { Text("Латинские буквы и цифры, без пробелов", color = TextSecondary, fontSize = 12.sp) },
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Только чтение", color = TextPrimary, fontSize = 15.sp)
                            Text("Windows сможет читать и копировать файлы, но не изменять и не удалять их", color = TextSecondary, fontSize = 12.sp)
                        }
                        Switch(checked = driveReadOnly, onCheckedChange = { driveReadOnly = it })
                    }
                }
                Text(if (os == OsType.UBUNTU) "Ubuntu · RDP" else "Windows · RDP", color = TextSecondary, fontSize = 12.sp)
                if (onResetCertificate != null && device?.certSha256 != null) {
                    TextButton(onClick = { onResetCertificate(); onDismiss() }) { Text("Сбросить доверие к сертификату", color = TextSecondary) }
                }
                if (onDelete != null) {
                    TextButton(onClick = { onDelete(); onDismiss() }) { Text("Удалить устройство", color = DangerRed) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (host.isNotBlank() && portNum != null && portNum in 1..65535 && !(driveEnabled && driveUri == null) && !wakeMacError) {
                    onSave(
                        (device ?: Device(newId(), "", "")).copy(
                            name = name.ifBlank { host },
                            host = host,
                            port = portNum,
                            os = os,
                            username = user.trim(),
                            password = pass,
                            useNla = useNla,
                            scalePercent = scale.toInt(),
                            colorDepth = colorDepth,
                            scrollLevel = scrollLevel.roundToInt(),
                            inertiaLevel = inertiaLevel.roundToInt(),
                            fileTransfer = fileTransfer,
                            driveEnabled = driveEnabled,
                            driveUri = driveUri,
                            driveName = driveName.ifBlank { Device.DEFAULT_DRIVE_NAME },
                            driveReadOnly = driveReadOnly,
                            wakeMac = WakeOnLan.parseMac(wakeMac)?.let(WakeOnLan::formatMac).orEmpty(),
                            // При смене адреса закреплённый сертификат больше не актуален.
                            certSha256 = if (device != null && device.host == host && device.port == portNum) device.certSha256 else null,
                        ),
                    )
                    onDismiss()
                }
            }) { Text("Сохранить", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) } },
    )
}

/** Запрос учётных данных перед подключением. */
@Composable
fun CredentialsDialog(
    device: Device,
    onConfirm: (user: String, password: String, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var user by remember { mutableStateOf(device.username) }
    var pass by remember { mutableStateOf("") }
    var saveCreds by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Вход на ${device.name}", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    user, { user = it }, label = { Text("Пользователь") }, singleLine = true,
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(pass, { pass = it }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = saveCreds, onCheckedChange = { saveCreds = it },
                        colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = TextSecondary),
                    )
                    Text("Запомнить на этом планшете", color = TextSecondary, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = user.isNotBlank() && pass.isNotEmpty(),
                onClick = { onConfirm(user.trim(), pass, saveCreds); onDismiss() },
            ) { Text("Подключиться", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) } },
    )
}

/** Результат автоматического поиска компьютеров в сети. */
@Composable
fun DiscoveryDialog(
    state: ScanState,
    onAdd: (List<FoundHost>) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Поиск устройств", color = TextPrimary) },
        text = {
            Column(Modifier.heightIn(min = 120.dp, max = 360.dp)) {
                when (state) {
                    is ScanState.Idle -> Unit
                    is ScanState.Running -> {
                        Text("Проверяем локальную сеть (порты RDP 3389 и 3390) и определяем систему…", color = TextSecondary, fontSize = 14.sp)
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { if (state.total == 0) 0f else state.done / state.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Outline,
                        )
                    }
                    is ScanState.Done -> when {
                        state.error != null -> Text(state.error, color = DangerRed)
                        state.hosts.isEmpty() -> Text(
                            "Новых компьютеров с включённым удалённым рабочим столом не найдено. " +
                                "Убедитесь, что планшет и ПК в одной сети, а RDP на ПК включён.",
                            color = TextSecondary, fontSize = 14.sp,
                        )
                        else -> LazyColumn {
                            items(state.hosts, key = { it.ip }) { h ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = h.ip in selected,
                                        onCheckedChange = { if (it) selected += h.ip else selected -= h.ip },
                                        colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = TextSecondary),
                                    )
                                    Column {
                                        Text(h.name, color = TextPrimary)
                                        Text("${h.ip} · ${h.profile.label}", color = TextSecondary, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state is ScanState.Done && state.hosts.isNotEmpty()) {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { onAdd(state.hosts.filter { it.ip in selected }); onDismiss() },
                ) { Text("Добавить выбранные", color = Accent) }
            } else if (state is ScanState.Done) {
                TextButton(onClick = onRescan) { Text("Повторить", color = Accent) }
            } else {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp), color = Accent, strokeWidth = 2.dp)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть", color = TextSecondary) } },
    )
}

@Composable
private fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextSecondary),
        )
        Text(title, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp))
    }
}

@Composable
private fun StrengthSlider(title: String, hint: String, value: Float, onChange: (Float) -> Unit) {
    val level = value.roundToInt()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                "${StrengthLevels.percent(level)}%" + if (level == StrengthLevels.DEFAULT) " · по умолчанию" else "",
                color = Accent, fontSize = 15.sp,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = StrengthLevels.MIN.toFloat()..StrengthLevels.MAX.toFloat(),
            steps = StrengthLevels.MAX - StrengthLevels.MIN - 1,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Outline),
        )
        Text(hint, color = TextSecondary, fontSize = 12.sp)
    }
}
