package com.hiweb.hiwebremotedesk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InstallDesktop
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.DesktopAccessDisabled
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Window
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hiweb.hiwebremotedesk.agent.AgentInstaller
import com.hiweb.hiwebremotedesk.agent.AgentLink
import com.hiweb.hiwebremotedesk.agent.AgentPairing
import com.hiweb.hiwebremotedesk.agent.GuardHub
import com.hiweb.hiwebremotedesk.data.GuardEntry
import com.hiweb.hiwebremotedesk.data.GuardStore
import com.hiweb.hiwebremotedesk.agent.SwitchLauncher
import com.hiweb.hiwebremotedesk.agent.SwitchListenerService
import com.hiweb.hiwebremotedesk.agent.PcNotification
import com.hiweb.hiwebremotedesk.agent.PcNotifications
import com.hiweb.hiwebremotedesk.data.StrengthLevels
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.DeviceRepository
import com.hiweb.hiwebremotedesk.data.LocalFiles
import com.hiweb.hiwebremotedesk.data.NetworkScanner
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.PcStateStore
import com.hiweb.hiwebremotedesk.data.PcStatusRules
import com.hiweb.hiwebremotedesk.data.Probe
import com.hiweb.hiwebremotedesk.data.WakeOnLan
import com.hiweb.hiwebremotedesk.data.SafDriveStorage
import com.hiweb.hiwebremotedesk.data.SafTree
import com.hiweb.hiwebremotedesk.data.ThumbnailStore
import com.hiweb.hiwebremotedesk.rdp.PowerAction
import com.hiweb.hiwebremotedesk.rdp.PowerInput
import com.hiweb.hiwebremotedesk.rdp.PowerKeys
import com.hiweb.hiwebremotedesk.rdp.RdpClient
import com.hiweb.hiwebremotedesk.rdp.RdpConfig
import com.hiweb.hiwebremotedesk.rdp.LocalFile
import com.hiweb.hiwebremotedesk.rdp.RdpListener
import com.hiweb.hiwebremotedesk.rdp.RemoteFile
import com.hiweb.hiwebremotedesk.rdp.RemoteCursor
import com.hiweb.hiwebremotedesk.rdp.ScanCodes
import com.hiweb.hiwebremotedesk.rdp.ScanKey
import com.hiweb.hiwebremotedesk.ui.RotationButton
import com.hiweb.hiwebremotedesk.ui.StrengthSlider
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.DangerRed
import com.hiweb.hiwebremotedesk.ui.theme.HiWebRemoteDeskTheme
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceButton
import com.hiweb.hiwebremotedesk.ui.theme.Outline
import com.hiweb.hiwebremotedesk.ui.theme.Surface
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary
import kotlin.math.roundToInt

/** Состояние сохранения файлов с ПК на планшет. */
private sealed interface TransferState {
    data class Running(val done: Long, val total: Long, val name: String) : TransferState
    data class Done(val files: Int) : TransferState
    data class Failed(val message: String) : TransferState
}

private sealed interface SessionState {
    data object Connecting : SessionState
    data object Connected : SessionState
    data class Failed(val message: String) : SessionState
}

/**
 * Экран удалённого сеанса. Физическая клавиатура и трекпад работают напрямую, экранная клавиатура
 * не показывается: у представления нет InputConnection, а IME скрыт на уровне окна.
 */
class RemoteActivity : ComponentActivity() {
    private lateinit var device: Device
    private lateinit var remoteView: RemoteView
    private var client: RdpClient? = null
    private var state by mutableStateOf<SessionState>(SessionState.Connecting)

    /** Переподключение ради новой ориентации: вместо окна «Подключение…» остаётся анимация поворота картинки. */
    private var quietReconnect by mutableStateOf(false)

    /** Картинка при смене ориентации размыта и ждёт Windows: поверх неё крутится лоадер. */
    private var rotationWait by mutableStateOf(false)

    /** Поворот дисплея (Surface.ROTATION_*), по которому считается сдвиг картинки при смене ориентации. */
    private var lastRotation = 0

    /** Кнопка «Повернуть» зафиксировала ориентацию; после возврата в альбомную автоповорот системы снова работает. */
    private var rotationLocked = false
    private var togglesSynced = false
    private var keyCaptureActive by mutableStateOf(false)
    private var keyHintDismissed by mutableStateOf(false)

    // Тачпад: захват указателя (жесты тремя-четырьмя пальцами уходят на ПК, а не в систему) и его калибровка.
    private val uiPrefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private var captureOn by mutableStateOf(true)
    private var calibStep by mutableStateOf<TouchpadCalibrator.Step?>(null)
    private var touchpadCalibrated = false
    private var resumed = false
    private val handler = Handler(Looper.getMainLooper())
    private val thumbnails by lazy { ThumbnailStore.get(this) }
    private var pinnedCertSeen: String? = null

    // Выключение/перезагрузка из списка устройств: сеанс открывается только ради команды и сам закрывается.
    private var powerAction: PowerAction? = null
    private var powerStatus by mutableStateOf<String?>(null)
    @Volatile private var powerCancelled = false
    @Volatile private var powerSent = false
    @Volatile private var lastBitmapAt = 0L
    @Volatile private var desktopWidth = 0

    // Буфер обмена: текст планшета вставляется на ПК по Ctrl+V, скопированное на ПК попадает в буфер планшета.
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private var textFromRemote: String? = null

    /** Последний текст, согласованный с ПК (отправленный или полученный): повторно его не отправляем. */
    private var syncedText: String? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { pushClipboard() }

    // Файлы: предложенные ПК (скопированы там по Ctrl+C) и ход их сохранения на планшет.
    private var remoteFiles by mutableStateOf<List<RemoteFile>?>(null)
    private var transfer by mutableStateOf<TransferState?>(null)
    private var downloader: RemoteFileDownloader? = null

    // Микрофон планшета в сеансе: передача включается вручную и гаснет вместе с сеансом.
    private var micOn by mutableStateOf(false)
    private var micHint by mutableStateOf<String?>(null)
    private var micRequested by mutableStateOf(false)
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micRequested = false
        if (granted) setMic(true) else {
            micHint = "Без разрешения на микрофон передача звука невозможна"
            handler.postDelayed({ micHint = null }, 6_000)
        }
    }

    // Камера планшета: в отличие от микрофона канал объявляется только при подключении, поэтому
    // разрешение нужно получить заранее — без него сеанс просто откроется без камеры.
    /** true, когда программа на ПК сейчас читает камеру (канал rdpecam открыт и поток идёт). */
    private var cameraOn by mutableStateOf(false)

    /** Камеры планшета сейчас показываются Windows (кнопка «Камера» в меню сеанса; false — устройства убраны). */
    private var cameraShared by mutableStateOf(false)
    private var cameraSource: CameraSource? = null

    /** Текущие настройки камер сеанса: меняются в окне настроек сеанса и сохраняются для этого ПК. */
    private var cameraOffNow by mutableStateOf<Set<String>>(emptySet())
    private var cameraRotationNow by mutableStateOf<Map<String, Int>>(emptyMap())
    private var cameraHint by mutableStateOf<String?>(null)
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraHint = null
            // Канал камеры создаётся только при подключении: без переподключения Windows её не увидит.
            if (::device.isInitialized && device.camera && state != SessionState.Connecting) connect()
        } else {
            cameraHint = "Без разрешения на камеру она не будет доступна Windows"
            handler.postDelayed({ cameraHint = null }, 6_000)
        }
    }

    private fun hasCameraPermission(): Boolean = androidx.core.content.ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.CAMERA,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Кнопка «Камера»: показывает камеры планшета Windows или убирает их оттуда. Без разрешения Android
     * источника камер нет — просим разрешение (после выдачи сеанс переподключается).
     */
    private fun toggleCamera() {
        if (!device.camera) {
            cameraHint = "Камера планшета выключена в настройках устройства"
            handler.postDelayed({ cameraHint = null }, 6_000)
            return
        }
        if (cameraSource == null) {
            if (!hasCameraPermission()) requestCamera.launch(android.Manifest.permission.CAMERA)
            return
        }
        cameraShared = !cameraShared
        applyCameras(save = false)
    }

    private fun setCameraShared(id: String, shared: Boolean) {
        cameraOffNow = if (shared) cameraOffNow - id else cameraOffNow + id
        applyCameras(save = true)
    }

    private fun setCameraRotation(id: String, degrees: Int) {
        cameraRotationNow = if (degrees == 0) cameraRotationNow - id else cameraRotationNow + (id to degrees)
        applyCameras(save = true)
    }

    /** Передаёт набор камер источнику и Windows; настройки отдельных камер запоминаются для этого ПК. */
    private fun applyCameras(save: Boolean) {
        cameraSource?.configure(cameraShared, cameraOffNow, cameraRotationNow)
        client?.refreshCameras()
        if (!save) return
        val off = cameraOffNow
        val rotation = cameraRotationNow
        device = device.copy(cameraOff = off, cameraRotation = rotation)
        DeviceRepository.get(this).update(device.id) { it.copy(cameraOff = off, cameraRotation = rotation) }
    }

    /** Спрашивает разрешение на камеру один раз, если она включена в настройках устройства. */
    private fun askCameraPermission() {
        if (!device.camera || device.os != OsType.WINDOWS || hasCameraPermission()) return
        requestCamera.launch(android.Manifest.permission.CAMERA)
    }

    // HiWeb Agent на ПК (только Windows): заряд планшета в трее ПК и уведомления Windows в свёрнутом приложении.
    private var agent: AgentLink? = null
    private var sessionLogged = false
    private var agentVersion by mutableStateOf<String?>(null)
    private val bundledAgentVersion by lazy { AgentInstaller.bundledVersion(this) }
    private var agentConfirm by mutableStateOf(false)

    /** Окно настроек, которые можно менять в ходе сеанса (кнопка «Настройки» в панели сеанса). */
    private var settingsOpen by mutableStateOf(false)

    /** Режим экранной клавиатуры (кнопка в панели сеанса); хранится для каждого ПК, читается в onCreate. */
    private var keyboardMode by mutableStateOf(KeyboardMode.DEFAULT)
    private var overlayPrompt by mutableStateOf(false)

    // Сеанс открыт командой с ПК при заблокированном планшете: рабочий стол закрыт заставкой до разблокировки.
    private var lockedCover by mutableStateOf(false)
    private var agentStatus by mutableStateOf<String?>(null)
    @Volatile private var agentInstalling = false
    @Volatile private var visible = false
    private val agentTimeout = Runnable {
        if (!agentInstalling) return@Runnable
        agentInstalling = false
        AgentInstaller.detach(client)
        agentStatus = "Агент не ответил. Если на ПК появилось предупреждение Windows, нажмите «Запустить» и повторите установку."
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) = sendBattery(intent)
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) offerToPc { LocalFiles.fromDocuments(this, uris) }
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) offerToPc { LocalFiles.fromTree(this, uri) }
    }

    private fun readClipboardText(): String? = runCatching {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Передаёт текущий текст буфера планшета клиенту RDP (кроме только что полученного с ПК). */
    private fun pushClipboard() {
        val text = readClipboardText() ?: return
        if (text == textFromRemote || text == syncedText) return
        syncedText = text
        client?.setClipboardText(text)
    }

    /** Предлагает ПК файлы планшета: список собирается вне потока интерфейса (папки бывают большими). */
    private fun offerToPc(build: () -> List<LocalFile>) {
        Thread {
            val files = runCatching(build).getOrNull()
            runOnUiThread {
                val c = client
                val message = when {
                    files == null -> "Не удалось прочитать выбранные файлы"
                    files.isEmpty() -> "Нечего отправлять"
                    c == null || !c.offerFiles(files) -> "ПК не поддерживает обмен файлами через буфер обмена"
                    else -> "Готово к вставке на ПК (${files.size}): нажмите Ctrl+V в Проводнике Windows"
                }
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Сохраняет файлы, скопированные на ПК, в «Загрузки/RemoteDesk». */
    private fun downloadRemoteFiles() {
        val c = client ?: return
        val files = remoteFiles ?: return
        if (transfer is TransferState.Running) return
        val d = RemoteFileDownloader(this, c)
        downloader = d
        transfer = TransferState.Running(0, files.sumOf { it.size.coerceAtLeast(0L) }, "")
        Thread {
            val result = try {
                val r = d.download(files) { p ->
                    runOnUiThread { if (transfer is TransferState.Running) transfer = TransferState.Running(p.doneBytes, p.totalBytes, p.fileName) }
                }
                TransferState.Done(r.files)
            } catch (e: Exception) {
                TransferState.Failed(e.message ?: "Не удалось сохранить файлы")
            }
            runOnUiThread { transfer = result }
        }.start()
    }

    private fun cancelDownload() {
        downloader?.cancelled = true
    }

    /** Подключает папку устройства как диск Windows; без доступа к папке сеанс идёт без диска. */
    private fun createDriveStorage(): SafDriveStorage? {
        if (!device.driveEnabled) return null
        val uri = device.driveUri?.let { Uri.parse(it) } ?: return null
        val tree = runCatching { SafTree(this, uri) }.getOrNull()
        if (tree == null || !tree.isAccessible()) {
            android.widget.Toast.makeText(this, "Папка общего диска недоступна: выберите её заново в настройках устройства", android.widget.Toast.LENGTH_LONG).show()
            return null
        }
        return SafDriveStorage(tree, device.driveReadOnly)
    }

    /** Канал агента на этот сеанс; для Ubuntu агента нет. */
    private fun createAgentLink(): AgentLink? {
        if (device.os != OsType.WINDOWS || powerAction != null) return null
        val appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        return AgentLink(android.os.Build.MODEL, appVersion, object : AgentLink.Listener {
            override fun onAgentHello(version: String, notificationAccess: String) {
                // Передаём агенту ключ для команды «Переключиться на Планшет» и начинаем её ждать.
                agent?.sendPairing(device.id, AgentPairing(this@RemoteActivity).secretFor(device.id))
                runOnUiThread {
                    SwitchListenerService.startIfPaired(this@RemoteActivity)
                    if (!android.provider.Settings.canDrawOverlays(this@RemoteActivity) && !uiPrefs.getBoolean("overlay_asked", false)) {
                        overlayPrompt = true
                    }
                    agentVersion = version
                    applyKeyboardMode()
                    if (agentInstalling) {
                        agentInstalling = false
                        handler.removeCallbacks(agentTimeout)
                        AgentInstaller.detach(client)
                        agentStatus = "HiWeb Agent $version установлен на ПК"
                        handler.postDelayed({ agentStatus = null }, 6_000)
                    }
                    offerAgentUpdate(version)
                    if (notificationAccess.isNotEmpty() && notificationAccess != "Allowed") {
                        agentStatus = "Агенту на ПК запрещён доступ к уведомлениям: разрешите его в меню значка агента в трее Windows"
                    }
                }
            }

            override fun onAgentNotification(n: PcNotification) {
                // Агент шлёт уведомления, только пока приложение свёрнуто; на экране сеанса они не нужны.
                val blockedBy = if (visible) PcNotifications.BLOCKED_VISIBLE else PcNotifications.blocker(this@RemoteActivity)
                if (blockedBy == null) PcNotifications.post(this@RemoteActivity, device.id, device.name, n)
                android.util.Log.i("Agent", "pc notification ${n.id} from ${n.app}: ${blockedBy ?: "posted"}")
                if (n.ack) agent?.sendNotificationAck(n.id, blockedBy)
            }

            override fun onAgentClosed() {
                runOnUiThread {
                    agentVersion = null
                    remoteView.keyboardPane = null
                    remoteView.setAgentCursor(null)
                }
            }

            override fun onKeyboardPane(rect: android.graphics.Rect?) {
                runOnUiThread {
                    remoteView.keyboardPane = rect
                    // Клавиатуру Windows в режиме «показана» закрыли на самом ПК (крестик на ней): кнопка в панели должна
                    // показывать «скрыта». Пока приложение свёрнуто, агент прячет её сам — это закрытием не считается.
                    if (rect == null && keyboardMode == KeyboardMode.SHOW && visible && windowsKeyboard()) {
                        changeKeyboardMode(KeyboardMode.HIDE)
                    }
                }
            }

            override fun onAgentCursor(cursor: RemoteCursor) {
                remoteView.setAgentCursor(cursor)
            }

            override fun onAgentActivate() {
                runOnUiThread { if (!visible) SwitchLauncher.open(this@RemoteActivity, device.id) }
            }

            override fun onAgentStatus(msg: org.json.JSONObject) {
                PcStateStore.get(this@RemoteActivity).report(device.id, msg)
            }

            override fun onAgentGuard(msg: org.json.JSONObject) {
                GuardHub.onMessage(this@RemoteActivity, device.id, msg, agent ?: return, { reply -> agent?.sendRaw(reply) })
            }
        }).also { link ->
            GuardHub.attach(device.id, link)
            registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.let { sendBattery(it, link) }
            link.setKeyboardMode(keyboardMode.id)
            sendKeyboard(link)
        }
    }

    /**
     * Подключена ли физическая клавиатура — по конфигурации Android, а не по списку устройств: драйвер
     * тачпада чехла (sec_touchpad) виден и без чехла.
     */
    private fun hasPhysicalKeyboard(config: android.content.res.Configuration = resources.configuration): Boolean =
        config.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO

    private fun sendKeyboard(link: AgentLink? = agent, config: android.content.res.Configuration = resources.configuration) {
        link?.setPhysicalKeyboard(hasPhysicalKeyboard(config))
    }

    // Чехол-клавиатуру снимают и надевают во время сеанса (configChanges="keyboard|keyboardHidden"),
    // планшет поворачивают (configChanges="orientation|screenSize").
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        sendKeyboard(config = newConfig)
        val rotation = displayRotation()
        val delta = SessionRotation.delta(lastRotation, rotation)
        lastRotation = rotation
        // Картинка стола остаётся и поворачивается, пока Windows перестраивает рабочий стол (см. RemoteView.beginRotation).
        if (delta != 0 && ::remoteView.isInitialized && state == SessionState.Connected) remoteView.beginRotation(delta)
        // Вернулись в альбомную кнопкой: отдаём ориентацию системе, если у неё включён автоповорот.
        if (rotationLocked && !isPortrait() && autoRotateOn()) {
            rotationLocked = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        handler.removeCallbacks(applyOrientation)
        handler.postDelayed(applyOrientation, ORIENTATION_SETTLE_MS)
    }

    private fun displayRotation(): Int = display?.rotation ?: android.view.Surface.ROTATION_0

    private fun autoRotateOn(): Boolean =
        android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    /**
     * Кнопка «Повернуть»: из альбомной ориентации картинка поворачивается на 90° влево, из книжной — обратно
     * в альбомную. Ориентация фиксируется, поэтому кнопка работает и при выключенном автоповороте.
     */
    private fun rotateSession() {
        val rotation = displayRotation()
        val portrait = isPortrait()
        val target = SessionRotation.target(rotation, portrait)
        rotationLocked = true
        requestedOrientation = SessionRotation.orientationFor(target, SessionRotation.naturalPortrait(rotation, portrait))
    }

    /**
     * Устаревший агент на ПК: окно с предложением обновить. Новая версия агента предлагается сразу, а ту же версию
     * повторно — не чаще раза в сутки для каждого ПК (кнопка в панели сеанса остаётся, пока агент не обновлён).
     */
    private fun offerAgentUpdate(installed: String) {
        if (!visible || agentInstalling || !AgentInstaller.isOutdated(installed, bundledAgentVersion)) return
        val key = "agent_update_asked_" + device.id
        val versionKey = "agent_update_version_" + device.id
        val now = System.currentTimeMillis()
        val sameVersion = uiPrefs.getString(versionKey, null) == bundledAgentVersion
        if (sameVersion && now - uiPrefs.getLong(key, 0) < AGENT_UPDATE_INTERVAL_MS) return
        uiPrefs.edit().putLong(key, now).putString(versionKey, bundledAgentVersion).apply()
        agentConfirm = true
    }

    private fun sendBattery(intent: android.content.Intent, link: AgentLink? = agent) {
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        link?.setBattery(level * 100 / scale, charging)
    }

    /** Запускает установщик агента на ПК (Win+R → \\tsclient\HiWeb\HiWebAgent.exe) и ждёт ответа агента. */
    private fun installAgent() {
        val c = client ?: return
        if (agentInstalling) return
        agentInstalling = true
        agentStatus = "Запуск установщика на ПК… Если Windows спросит, нажмите «Запустить»."
        Thread({
            val error = try {
                AgentInstaller.launch(this, c, object : AgentInstaller.Keys {
                    override fun key(scanCode: Int, extended: Boolean, down: Boolean) = c.sendKey(scanCode, extended, down)
                    override fun unicode(code: Int, down: Boolean) = c.sendUnicode(code, down)
                    override fun pause(ms: Long) = Thread.sleep(ms)
                })
            } catch (_: InterruptedException) {
                "Установка прервана"
            }
            runOnUiThread {
                if (error != null) {
                    agentInstalling = false
                    AgentInstaller.detach(c)
                    agentStatus = error
                } else {
                    handler.removeCallbacks(agentTimeout)
                    handler.postDelayed(agentTimeout, AGENT_INSTALL_TIMEOUT_MS)
                }
            }
        }, "agent-install").start()
    }

    /** Разрешение на уведомления нужно, чтобы показывать уведомления с ПК; спрашиваем один раз. */
    private fun askNotificationPermission() {
        if (device.os != OsType.WINDOWS || PcNotifications.canPost(this)) return
        if (uiPrefs.getBoolean("notif_asked", false)) return
        uiPrefs.edit().putBoolean("notif_asked", true).apply()
        requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasMicPermission() = androidx.core.content.ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Включает и выключает передачу звука микрофона на ПК. Windows открывает канал audin, только когда
     * программа начинает запись, поэтому включение запоминается и срабатывает при его открытии.
     * Для Ubuntu канала нет: там setMicrophone отказывает.
     */
    private fun setMic(on: Boolean) {
        val c = client ?: return
        micHint = null
        if (on) {
            if (!hasMicPermission()) {
                micRequested = true
                requestMic.launch(android.Manifest.permission.RECORD_AUDIO)
                return
            }
            if (!c.setMicrophone(true)) {
                micHint = "Микрофон недоступен: включите «Микрофон планшета» в настройках устройства " +
                    "и переподключитесь (для Ubuntu перенаправление микрофона не поддерживается)"
                handler.postDelayed({ micHint = null }, 8_000)
                return
            }
            micOn = true
        } else {
            c.setMicrophone(false)
            micOn = false
        }
    }

    /**
     * Кнопка «Микрофон» в меню сеанса. На телефоне панель тесная: там же предлагается разрешение,
     * поэтому подсказка о причине отказа выводится баннером, а не тостом.
     */
    private fun toggleMic() {
        if (!device.microphone) {
            micHint = "Микрофон планшета выключен в настройках устройства"
            handler.postDelayed({ micHint = null }, 6_000)
            return
        }
        setMic(!micOn)
    }

    private val periodicThumbnail = object : Runnable {
        override fun run() {
            saveThumbnail()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_DEVICE_ID)
        val found = id?.let { DeviceRepository.get(this).get(it) }
        if (found == null) {
            leaveSession()
            return
        }
        device = found
        powerAction = intent.getStringExtra(EXTRA_POWER_ACTION)?.let { name -> PowerAction.entries.firstOrNull { it.name == name } }
        keyHintDismissed = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("key_hint_dismissed", false)
        keyboardMode = KeyboardMode.parse(uiPrefs.getString(keyboardModeKey(), null))

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        // Рисуем под вырезом камеры, но элементы управления и сам рабочий стол смещаем по его границе (см. RemoteScreen).
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Поворот показывает сам RemoteView (картинка стола поворачивается и мутнеет): системная анимация мешала бы.
            it.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
        }
        lastRotation = displayRotation()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        remoteView = RemoteView(this)
        remoteView.onRotationWait = { rotationWait = it }
        // Сила прокрутки и инерции задаётся в настройках этого ПК
        remoteView.setScrollSettings(StrengthLevels.factor(device.scrollPercent), StrengthLevels.inertiaFactor(device.inertiaPercent))
        remoteView.setMouseSpeed(StrengthLevels.mouseFactor(device.mouseSpeedPercent))
        remoteView.setMouseWheelSpeed(StrengthLevels.factor(device.mouseWheelPercent))
        remoteView.setDesktopProfile(if (device.os == OsType.UBUNTU) DesktopProfile.GNOME else DesktopProfile.WINDOWS)
        captureOn = uiPrefs.getBoolean("tp_capture", true)
        if (uiPrefs.getBoolean("tp_cal", false)) {
            remoteView.touchpadAxes = TouchpadAxes(
                swap = uiPrefs.getBoolean("tp_swap", false),
                signH = uiPrefs.getInt("tp_sh", 1),
                signV = uiPrefs.getInt("tp_sv", 1),
            )
            touchpadCalibrated = true
        }
        remoteView.onCalibrationStep = { calibStep = it }
        remoteView.onCalibrated = { axes ->
            uiPrefs.edit()
                .putBoolean("tp_cal", true).putBoolean("tp_swap", axes.swap)
                .putInt("tp_sh", axes.signH).putInt("tp_sv", axes.signV).apply()
            touchpadCalibrated = true
        }
        setContent {
            HiWebRemoteDeskTheme {
                RemoteScreen()
            }
        }
        PcNotifications.ensureChannels(this)
        registerReceiver(batteryReceiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        handleSwitchIntent(intent)
        // Разрешение нужно до подключения: канал камеры объявляется Windows только при его наличии.
        askCameraPermission()
        connectWaking()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleSwitchIntent(intent)
    }

    /**
     * Команда «Переключиться на Планшет»: включаем экран и просим разблокировать планшет. Пока он
     * заблокирован, окно показывается поверх экрана блокировки, но рабочий стол ПК закрыт заставкой.
     */
    private fun handleSwitchIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SWITCH, false) != true) return
        intent.removeExtra(EXTRA_SWITCH)
        getSystemService(android.app.NotificationManager::class.java).cancel(PcNotifications.SWITCH_REQUEST_ID)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return
        lockedCover = true
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        keyguard.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = unlockCover()
            override fun onDismissCancelled() {
                setShowWhenLocked(false)
            }
        })
    }

    private fun unlockCover() {
        lockedCover = false
        setShowWhenLocked(false)
        setTurnScreenOn(false)
        updateCapture()
    }

    /** Длинная сторона рабочего стола 1920, короткая — по пропорциям экрана; в книжной ориентации стороны меняются местами. */
    private fun desktopSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        val a = maxOf(m.widthPixels, m.heightPixels).toFloat()
        val b = minOf(m.widthPixels, m.heightPixels).toFloat()
        val long = 1920
        val short = ((long * b / a / 8f).roundToInt() * 8).coerceIn(600, 1440)
        return if (isPortrait()) short to long else long to short
    }

    private fun isPortrait(): Boolean = resources.displayMetrics.let { it.heightPixels > it.widthPixels }

    /** Физический размер экрана планшета в мм (по плотности пикселей) — нужен серверу вместе с масштабом. */
    private fun physicalMm(): Pair<Int, Int> {
        val m = resources.displayMetrics
        val longPx = maxOf(m.widthPixels, m.heightPixels)
        val shortPx = minOf(m.widthPixels, m.heightPixels)
        val dpi = maxOf(m.xdpi, 100f)
        val longMm = (longPx / dpi * 25.4f).toInt()
        val shortMm = (shortPx / dpi * 25.4f).toInt()
        return if (isPortrait()) shortMm to longMm else longMm to shortMm
    }

    /** Размер рабочего стола, запрошенный для текущей ориентации (при подключении или поворотом). */
    private var requestedSize: Pair<Int, Int>? = null

    /** Номер подключения: события закрытого при переподключении клиента не должны трогать экран. */
    @Volatile private var sessionSeq = 0

    // Поворот планшета: рабочий стол перестраивается под новую ориентацию. Ждём, пока поворот закончится,
    // чтобы не слать серверу промежуточные размеры.
    private val applyOrientation = Runnable { settleOrientation(afterReady = false) }

    /**
     * [afterReady] — вызов сразу после подключения: размер уже совпадает потому, что подключение было именно ради него,
     * и прежнюю картинку трогать нельзя — Windows ещё показывает экран входа, а не рабочий стол.
     */
    private fun settleOrientation(afterReady: Boolean) {
        val c = client ?: return
        if (state != SessionState.Connected) return
        val size = desktopSize()
        if (size == requestedSize) {
            if (!afterReady) remoteView.finishRotation()
            return
        }
        val (pw, ph) = physicalMm()
        if (c.resizeDesktop(size.first, size.second, pw, ph)) {
            requestedSize = size
            return
        }
        if (powerAction != null) return
        // Windows без графического конвейера по DisplayControl меняет размер у себя, но не присылает
        // клиенту новый размер (проверено: касания мимо, окна не разворачиваются). Поэтому переподключаемся
        // с новым размером: сеанс Windows сохраняется, подключение занимает доли секунды.
        android.util.Log.i("RDP", "reconnecting for desktop ${size.first}x${size.second}")
        connect(quiet = true)
    }

    // Пробуждение ПК по сети перед подключением: строка для экрана подключения, null — не будим.
    private var wakeStatus by mutableStateOf<String?>(null)
    @Volatile private var wakeSeq = 0

    /**
     * Подключение с пробуждением: если порт RDP не отвечает, а MAC-адрес ПК известен, шлём «волшебный пакет»
     * (повторяя его) и ждём, пока порт откроется. ПК без MAC подключается как обычно и покажет ошибку.
     */
    private fun connectWaking() {
        val mac = WakeOnLan.parseMac(device.wakeMac)
        if (mac == null || powerAction != null) {
            connect()
            return
        }
        val seq = ++wakeSeq
        state = SessionState.Connecting
        val host = device.host
        val port = device.port
        Thread({
            if (Probe.isOpen(host, port, WAKE_PROBE_MS)) {
                runOnUiThread { if (seq == wakeSeq && !isFinishing) connect() }
                return@Thread
            }
            val local = NetworkScanner.localIpv4(this)?.let { (address, prefix) -> WakeOnLan.broadcastOf(address, prefix).hostAddress }
            val targets = PcStatusRules.wakeAddresses(host, local, PcStateStore.get(this).reports.value[device.id])
            android.util.Log.i("RDP", "wake-on-lan ${device.wakeMac} -> $targets")
            val start = SystemClock.uptimeMillis()
            var sentAt = 0L
            while (seq == wakeSeq && !isFinishing) {
                val now = SystemClock.uptimeMillis()
                if (now - start > WAKE_TIMEOUT_MS) break
                if (sentAt == 0L || now - sentAt >= WAKE_RESEND_MS) {
                    runCatching { WakeOnLan.send(mac, targets) }.onFailure { android.util.Log.w("RDP", "wake-on-lan failed", it) }
                    sentAt = now
                }
                val seconds = (now - start) / 1000
                runOnUiThread { if (seq == wakeSeq) wakeStatus = "Пробуждение ${device.name}… $seconds с" }
                if (Probe.isOpen(host, port, WAKE_PROBE_MS)) {
                    // Порт открывается чуть раньше, чем Windows готова принять вход.
                    Thread.sleep(WAKE_SETTLE_MS)
                    runOnUiThread {
                        if (seq != wakeSeq || isFinishing) return@runOnUiThread
                        wakeStatus = null
                        connect()
                    }
                    return@Thread
                }
                Thread.sleep(1000)
            }
            runOnUiThread {
                if (seq != wakeSeq || isFinishing) return@runOnUiThread
                wakeStatus = null
                state = SessionState.Failed(
                    "ПК не проснулся по сигналу Wake-on-LAN. Проверьте, что пробуждение по сети включено в BIOS и в свойствах " +
                        "сетевой карты, а при подключении из внешней сети — что роутер пробрасывает UDP-порт ${WakeOnLan.PORT}.",
                )
            }
        }, "wake").apply { isDaemon = true }.start()
    }

    private fun connect(quiet: Boolean = false) {
        val seq = ++sessionSeq
        quietReconnect = quiet
        state = SessionState.Connecting
        togglesSynced = false
        syncedText = null
        remoteFiles = null
        transfer = null
        val (w, h) = desktopSize()
        requestedSize = w to h
        val cfg = RdpConfig(
            host = device.host, port = device.port, username = device.username, password = device.password,
            width = w, height = h, pinnedCertSha256 = device.certSha256, useNla = device.useNla,
            scalePercent = device.scalePercent, physicalWidthMm = physicalMm().first, physicalHeightMm = physicalMm().second,
            fileTransfer = device.fileTransfer, driveName = device.driveName, colorDepth = device.colorDepth,
            windowsTls = device.os == OsType.WINDOWS,
            gfx = device.os == OsType.UBUNTU || device.windowsGfx,
            hardwareVideo = device.hardwareVideo,
            gfxClassic = device.os == OsType.WINDOWS,
        )
        agentInstalling = false
        agentVersion = null
        val link = createAgentLink()
        link?.setForeground(visible)
        agent = link
        val c = RdpClient(cfg, object : RdpListener {
            override fun onCertificate(sha256: String) { pinnedCertSeen = sha256 }

            override fun onClipboardText(text: String) {
                runOnUiThread {
                    textFromRemote = text
                    syncedText = text
                    clipboard.setPrimaryClip(ClipData.newPlainText("RDP", text))
                }
            }

            override fun onRemoteFiles(files: List<RemoteFile>?) {
                runOnUiThread {
                    // Новое копирование на ПК отменяет прежнее предложение; идущее сохранение не прерываем.
                    remoteFiles = files
                    if (files != null && transfer !is TransferState.Running) transfer = null
                }
            }

            override fun onReady(width: Int, height: Int) {
                if (seq != sessionSeq) return
                remoteView.initDesktop(width, height)
                desktopWidth = width
                powerAction?.let { if (!powerSent) startPowerSequence(it) }
                runOnUiThread {
                    quietReconnect = false
                    state = SessionState.Connected
                    pushClipboard()
                    updateCapture()
                    // Для Windows клавиатуру подбирает applyKeyboardMode, когда агент назовёт версию (onAgentHello); без агента — кнопкой.
                    if (device.os != OsType.WINDOWS) applyKeyboardMode()
                    // Закрепляем сертификат при первом успешном подключении.
                    val fp = pinnedCertSeen
                    if (device.certSha256 == null && fp != null) {
                        device = device.copy(certSha256 = fp)
                        val repo = DeviceRepository.get(this@RemoteActivity)
                        // Берём запись из хранилища, чтобы не сохранить пароль, введённый только для этого сеанса.
                        repo.devices.value.firstOrNull { it.id == device.id }?.let { repo.upsert(it.copy(certSha256 = fp)) }
                    }
                    handler.removeCallbacks(periodicThumbnail)
                    handler.postDelayed(periodicThumbnail, 10_000)
                    // Планшет могли повернуть, пока шло подключение.
                    settleOrientation(afterReady = true)
                    if (powerAction == null) {
                        SessionService.start(this@RemoteActivity, device.id, device.name)
                        askNotificationPermission()
                        // Повторные подключения при повороте экрана — тот же сеанс: в журнал он попадает один раз.
                        if (!sessionLogged) {
                            sessionLogged = true
                            GuardStore.get(this@RemoteActivity).recordLocal(device.id, GuardEntry.APP_START)
                        }
                    }
                }
            }

            override fun onDesktopSize(width: Int, height: Int) {
                if (seq != sessionSeq) return
                desktopWidth = width
                // Размер сменился внутри сеанса (графический конвейер): кадры идут и дальше, затишья ждать нельзя.
                remoteView.initDesktop(width, height, live = true)
            }

            override fun onBitmapRegion(argb: IntArray, offset: Int, stride: Int, left: Int, top: Int, width: Int, height: Int) {
                if (seq != sessionSeq) return
                lastBitmapAt = SystemClock.uptimeMillis()
                remoteView.applyBitmap(left, top, width, height, argb, offset, stride)
            }

            override fun onBitmap(left: Int, top: Int, width: Int, height: Int, argb: IntArray) {
                if (seq != sessionSeq) return
                lastBitmapAt = SystemClock.uptimeMillis()
                remoteView.applyBitmap(left, top, width, height, argb)
            }

            override fun onCursor(cursor: RemoteCursor) {
                if (seq == sessionSeq) remoteView.setRemoteCursor(cursor)
            }

            override fun onCameraActive(active: Boolean) {
                if (seq == sessionSeq) runOnUiThread { cameraOn = active }
            }

            override fun onClosed(error: String?) {
                runOnUiThread {
                    // Прежний клиент закрыт ради переподключения (поворот): сеанс продолжается в новом.
                    if (seq != sessionSeq) return@runOnUiThread
                    handler.removeCallbacks(periodicThumbnail)
                    handler.removeCallbacks(agentTimeout)
                    agentInstalling = false
                    agentVersion = null
                    SessionService.stop(this@RemoteActivity)
                    // После команды питания сервер сам обрывает соединение: это успех, а не ошибка.
                    if (error == null || powerSent) {
                        if (!isFinishing) leaveSession()
                    } else {
                        quietReconnect = false
                        state = SessionState.Failed(error)
                        updateCapture()
                        if (powerAction == null) GuardStore.get(this@RemoteActivity).recordLocal(device.id, GuardEntry.APP_FAIL, error)
                    }
                }
            }
        }, AudioPlayer(), createDriveStorage(),
            dynamicChannels = link?.let { mapOf(AgentLink.CHANNEL_NAME to it) }.orEmpty(),
            attachableDrives = device.os == OsType.WINDOWS,
            // Микрофон и камера — только там, где Windows ждёт перенаправление: у Ubuntu этих каналов нет.
            audioSource = if (device.microphone && device.os == OsType.WINDOWS) MicSource() else null,
            camera = if (device.camera && device.os == OsType.WINDOWS && hasCameraPermission()) CameraSource(this, device.cameraOff, device.cameraRotation).also { cameraSource = it } else null,
        )
        // Windows открывает канал микрофона сама, когда программа начинает запись, — кнопку «Микрофон»
        // успеть нажать до этого нельзя, поэтому при включённой настройке передача взводится сразу.
        micOn = device.microphone && device.os == OsType.WINDOWS && hasMicPermission() && c.setMicrophone(true)
        cameraOn = false
        if (!(device.camera && device.os == OsType.WINDOWS && hasCameraPermission())) cameraSource = null
        cameraShared = cameraSource != null
        cameraOffNow = device.cameraOff
        cameraRotationNow = device.cameraRotation
        cameraHint = when {
            !device.camera || device.os != OsType.WINDOWS -> null
            !hasCameraPermission() -> "Камера планшета выключена: нет разрешения Android"
            else -> null
        }
        client?.close()
        client = c
        remoteView.client = c
        c.start()
    }

    /**
     * Ждёт, пока после входа рабочий стол успокоится (кадры перестали приходить), и открывает штатное меню питания.
     * Сеанс закрывается сам; отмена доступна на плашке до отправки последней клавиши.
     */
    private fun startPowerSequence(action: PowerAction) {
        val c = client ?: return
        val readyAt = SystemClock.uptimeMillis()
        lastBitmapAt = readyAt
        powerCancelled = false
        runOnUiThread { powerStatus = "${action.inProgress}: ожидание рабочего стола…" }
        Thread({
            try {
                while (c.isReady) {
                    val now = SystemClock.uptimeMillis()
                    if (powerCancelled) return@Thread
                    if ((now - readyAt >= POWER_MIN_WAIT_MS && now - lastBitmapAt >= POWER_QUIET_MS) || now - readyAt >= POWER_MAX_WAIT_MS) break
                    Thread.sleep(200)
                }
                if (!c.isReady || powerCancelled) return@Thread
                runOnUiThread { powerStatus = "${action.inProgress}: отправка команды…" }
                PowerKeys.run(device.os, action, desktopWidth, object : PowerInput {
                    override fun key(scanCode: Int, extended: Boolean, down: Boolean) = c.sendKey(scanCode, extended, down)
                    override fun mouse(flags: Int, x: Int, y: Int) = c.sendMouse(flags, x, y)
                    override fun pause(ms: Long) {
                        if (powerCancelled) throw InterruptedException()
                        Thread.sleep(ms)
                    }
                })
                powerSent = true
                runOnUiThread { powerStatus = "${action.inProgress}: команда отправлена" }
                Thread.sleep(POWER_AFTER_MS)
                runOnUiThread { if (!isFinishing) leaveSession() }
            } catch (_: InterruptedException) {
            }
        }, "power-action").start()
    }

    private fun saveThumbnail() {
        // Снимок экрана входа или меню питания не должен заменять превью устройства.
        if (state != SessionState.Connected || powerAction != null) return
        val frame = remoteView.snapshot() ?: return
        Thread { runCatching { thumbnails.save(device.id, frame) }; frame.recycle() }.start()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean = handleKeyEvent(e) || super.dispatchKeyEvent(e)

    /**
     * Ввод: события экранной клавиатуры разбирает [forwardImeKey], физической — обычный [forwardKey].
     * Признак виртуальной клавиатуры — нет скан-кода или устройство VIRTUAL_KEYBOARD: у настоящей
     * клавиатуры скан-код всегда есть, даже если приложение открыло экранную.
     */
    private fun handleKeyEvent(e: KeyEvent): Boolean {
        if (mouseButtonAsKey(e)) return true
        val virtual = e.scanCode == 0 || e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD
        return if (imeOn && virtual) forwardImeKey(e) else forwardKey(e)
    }

    /**
     * Кнопки мыши, превращённые системой в клавиши: Samsung по настройкам «Мышь» (у планшета `mouse_middle_button_option`)
     * отдаёт среднюю кнопку как Home/«Недавние», а «назад»/«вперёд» — как Back/Forward, и без перехвата планшет сворачивает
     * приложение или выходит из сеанса вместо щелчка в Windows. Признак мыши — скан-код кнопки (BTN_MIDDLE 0x112, BTN_SIDE…
     * BTN_BACK 0x113–0x116) либо событие без скан-кода от источника «мышь»; клавиши Home/Back на клавиатуре чехла имеют свой
     * скан-код и остаются системными. Каждое такое событие пишется в журнал (тег Input): по нему видно, как планшет
     * отдаёт кнопку.
     */
    private fun mouseButtonAsKey(e: KeyEvent): Boolean {
        val c = client
        if (c == null || !c.isReady || state != SessionState.Connected) return false
        val scan = e.scanCode
        val mouseOrigin = scan in BTN_MIDDLE_SCAN..BTN_TASK_SCAN || (scan == 0 && e.isFromSource(android.view.InputDevice.SOURCE_MOUSE))
        if (!mouseOrigin) return false
        val button = when {
            scan == BTN_MIDDLE_SCAN -> android.view.MotionEvent.BUTTON_TERTIARY
            scan == BTN_SIDE_SCAN || scan == BTN_BACK_SCAN -> android.view.MotionEvent.BUTTON_BACK
            scan == BTN_EXTRA_SCAN || scan == BTN_FORWARD_SCAN -> android.view.MotionEvent.BUTTON_FORWARD
            e.keyCode == KeyEvent.KEYCODE_HOME || e.keyCode == KeyEvent.KEYCODE_APP_SWITCH -> android.view.MotionEvent.BUTTON_TERTIARY
            e.keyCode == KeyEvent.KEYCODE_BACK -> android.view.MotionEvent.BUTTON_BACK
            e.keyCode == KeyEvent.KEYCODE_FORWARD -> android.view.MotionEvent.BUTTON_FORWARD
            else -> 0
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            android.util.Log.i("Input", "mouse key ${KeyEvent.keyCodeToString(e.keyCode)} scan=$scan source=0x${e.source.toString(16)} device=${e.device?.name} -> ${if (button != 0) "button $button" else "ignored"}")
        }
        if (button == 0) return false
        if (!lockedCover) remoteView.mouseButtonKey(button, e.action == KeyEvent.ACTION_DOWN)
        return true
    }

    // Экранная клавиатура. У RemoteView нет InputConnection, поэтому символы приходят сюда как KeyEvent,
    // а не как текст: KEYCODE_A — обычный скан-код, остальные символы — только через KEYCODE_UNKNOWN (scanCode 0).
    // Состояние модификаторов держим сами: Android шлёт события шифта, а на ПК его нужно удержать на время символа.

    /** Модификаторы, зажатые сейчас на удалённом ПК. */
    private var ctrlDown = false
    private var altDown = false
    private var switchDown = false
    private var shiftDown = false

    /** Отправляет символ на ПК: позиционные символы — скан-кодом (работают с Ctrl/Alt), прочие — Unicode. */
    private fun sendCharacter(ch: Char) {
        val c = client ?: return
        val base = ScanCodes.unshifted(ch)
        val shift = ch.isUpperCase() || base != null
        val key = ScanCodes.forChar(base ?: ch.lowercaseChar())
        if (shift && !shiftDown) c.sendKey(SHIFT_SCAN, false, true)
        if (key != null) {
            c.sendKey(key.code, key.extended, true)
            c.sendKey(key.code, key.extended, false)
        } else {
            c.sendUnicode(ch.code, true)
            c.sendUnicode(ch.code, false)
        }
        if (shift && !shiftDown) c.sendKey(SHIFT_SCAN, false, false)
    }

    /** Sym/Shift+Enter и прочие сочетания экранной клавиатуры, для которых нет печатного символа. */
    private fun sendCombined(sym: Boolean, key: KeyEvent) {
        val c = client ?: return
        if (sym && !ctrlDown) c.sendKey(CTRL_SCAN, false, true)
        if (key.isShiftPressed && !shiftDown) c.sendKey(SHIFT_SCAN, false, true)
        val scan = ScanCodes.fromEvent(key)
        if (scan != null) {
            c.sendKey(scan.code, scan.extended, true)
            c.sendKey(scan.code, scan.extended, false)
        }
        if (key.isShiftPressed && !shiftDown) c.sendKey(SHIFT_SCAN, false, false)
        if (sym && !ctrlDown) c.sendKey(CTRL_SCAN, false, false)
    }

    /** Идёт ввод символа. Возвращает true, если событие не должно уходить дальше. */
    private fun forwardImeKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        if (!down && e.action != KeyEvent.ACTION_UP) return false
        if (e.repeatCount > 0) return true
        val sym = e.hasNoModifiers() && e.metaState and KeyEvent.META_CTRL_MASK != 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_UNKNOWN -> {
                val chars = e.characters
                if (down && !chars.isNullOrEmpty()) chars.forEach(::sendCharacter)
                return true
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                shiftDown = down
                client?.sendKey(SHIFT_SCAN, false, down)
                return true
            }
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                ctrlDown = down
                client?.sendKey(CTRL_SCAN, false, down)
                return true
            }
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                altDown = down
                client?.sendKey(ALT_SCAN, false, down)
                return true
            }
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> {
                switchDown = down
                client?.sendKey(WIN_SCAN, true, down)
                return true
            }
            // Стрелки, Tab, Backspace, Delete, Enter. Sym → Ctrl, Shift+Enter → Shift+Enter.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            -> {
                if (down) sendCombined(sym, e)
                return true
            }
            else -> {
                val unicode = e.unicodeChar
                if (unicode != 0 && unicode and KeyCharacterMap.COMBINING_ACCENT_MASK == 0) {
                    if (down) sendCharacter(unicode.toChar())
                    return true
                }
                return false
            }
        }
    }

    private fun openKeyboard() {
        if (!imeAttached()) {
            (window.decorView as android.view.ViewGroup).addView(ensureImeEdit(), android.view.ViewGroup.LayoutParams(2, 2))
        }
        imeOn = true
        imeEdit?.requestFocus()
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.ime())
        // Клавиатура может перекрыть рабочий стол: жесты в этот момент не перехватываем.
        handler.postDelayed({ updateCapture() }, 200)
    }

    private fun keyboardModeKey() = "kbd_mode_" + device.id

    /**
     * Переключает режим экранной клавиатуры и запоминает его для этого ПК: агент показывает или прячет клавиатуру Windows
     * ([KeyboardMode]), без подходящего агента работает клавиатура Android ([applyKeyboardMode]).
     */
    private fun changeKeyboardMode(mode: KeyboardMode) {
        keyboardMode = mode
        uiPrefs.edit().putString(keyboardModeKey(), mode.id).apply()
        agent?.setKeyboardMode(mode.id)
        applyKeyboardMode()
    }

    /** Агент на ПК понимает режимы клавиатуры (1.9.0+): экранной клавиатурой управляет он, а не планшет. */
    private fun windowsKeyboard(): Boolean {
        val installed = agentVersion ?: return false
        return !AgentInstaller.isOutdated(installed, KeyboardMode.AGENT_VERSION)
    }

    /**
     * С агентом нужной версии клавиатура Android не нужна (её убираем). Без него (Ubuntu, агента нет или он старый)
     * режим «показана» открывает клавиатуру Android, остальные закрывают её.
     */
    private fun applyKeyboardMode() {
        if (windowsKeyboard() || keyboardMode != KeyboardMode.SHOW) {
            if (imeAttached()) closeKeyboard()
        } else if (state == SessionState.Connected && !imeAttached()) {
            openKeyboard()
        }
    }

    /** На ПК стоит агент, а вложенный в приложение новее. */
    private fun agentUpdateAvailable(): Boolean {
        val installed = agentVersion ?: return false
        return AgentInstaller.isOutdated(installed, bundledAgentVersion)
    }

    /** Прячет поле и клавиатуру, возвращая фокус экрану сеанса (иначе жесты идут в пустое поле). */
    private fun closeKeyboard() {
        imeOn = false
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        (imeEdit?.parent as? android.view.ViewGroup)?.removeView(imeEdit)
        remoteView.requestFocus()
        updateCapture()
    }

    /** Нажатые на ПК клавиши: нужны, чтобы отпустить их, если окно потеряло фокус посреди сочетания. */
    private val pressedKeys = HashSet<Int>()

    private fun releaseAllKeys() {
        stopRepeat()
        val c = client
        if (c != null) pressedKeys.forEach { c.sendKey(it and 0xFF, it and 0x100 != 0, false) }
        pressedKeys.clear()
    }

    // Автоповтор. Когда клавиши получает служба перехвата, Android сам повторов не создаёт, поэтому повторяем
    // здесь, как это делает клавиатура Windows: пауза перед первым повтором, затем частые повторы последней клавиши.
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatKey: ScanKey? = null
    private val repeatTick = object : Runnable {
        override fun run() {
            val k = repeatKey ?: return
            val c = client
            if (c == null || !c.isReady) { repeatKey = null; return }
            c.sendKey(k.code, k.extended, down = true, repeat = true)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private fun startRepeat(key: ScanKey) {
        repeatHandler.removeCallbacks(repeatTick)
        repeatKey = key
        repeatHandler.postDelayed(repeatTick, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        repeatHandler.removeCallbacks(repeatTick)
        repeatKey = null
    }

    /** Модификаторы и «замки» не повторяются. */
    private fun isRepeatable(keyCode: Int) = !KeyEvent.isModifierKey(keyCode) &&
        keyCode != KeyEvent.KEYCODE_CAPS_LOCK && keyCode != KeyEvent.KEYCODE_NUM_LOCK && keyCode != KeyEvent.KEYCODE_SCROLL_LOCK

    /**
     * Отправляет клавишу на удалённый ПК. Возвращает true, если клавиша обработана и не должна попасть
     * ни в Android, ни в само приложение. Вызывается и из службы перехвата клавиш (раньше системы),
     * и из обычной доставки событий окна.
     */
    private fun forwardKey(e: KeyEvent): Boolean {
        val c = client
        if (c == null || !c.isReady || state != SessionState.Connected) return false
        if (lockedCover) return true // планшет ещё заблокирован: на ПК ничего не уходит
        if (ScanCodes.isLocalOnly(e.keyCode)) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        if (!down && e.action != KeyEvent.ACTION_UP) return false

        val key = ScanCodes.fromEvent(e)
        if (key != null) {
            val isLock = e.keyCode == KeyEvent.KEYCODE_CAPS_LOCK || e.keyCode == KeyEvent.KEYCODE_NUM_LOCK || e.keyCode == KeyEvent.KEYCODE_SCROLL_LOCK
            if (!togglesSynced && !isLock) {
                togglesSynced = true
                c.sendToggleState((if (e.isScrollLockOn) 1 else 0) or (if (e.isNumLockOn) 2 else 0) or (if (e.isCapsLockOn) 4 else 0))
            }
            val id = key.code or (if (key.extended) 0x100 else 0)
            if (down) {
                // Системные повторы игнорируем: повторяем сами одинаково с любым путём доставки клавиш.
                if (e.repeatCount > 0) return true
                pressedKeys += id
                c.sendKey(key.code, key.extended, true)
                if (isRepeatable(e.keyCode)) startRepeat(key)
            } else {
                pressedKeys -= id
                if (repeatKey == key) stopRepeat()
                c.sendKey(key.code, key.extended, false)
            }
            return true
        }
        // Символ без скан-кода (например, ввод с нестандартного источника) — отправляем как Unicode.
        val ch = e.unicodeChar
        if (ch != 0 && ch and KeyCharacterMap.COMBINING_ACCENT_MASK == 0) {
            if (down) { c.sendUnicode(ch, true); c.sendUnicode(ch, false) }
            return true
        }
        return false
    }

    private fun sendCtrlAltEnd() {
        val c = client ?: return
        // RDP-эквивалент Ctrl+Alt+Del — Ctrl+Alt+End.
        c.sendKey(0x1D, false, true); c.sendKey(0x38, false, true)
        c.sendKey(0x4F, true, true); c.sendKey(0x4F, true, false)
        c.sendKey(0x38, false, false); c.sendKey(0x1D, false, false)
    }

    /** Alt+Tab с телефона: без физической клавиатуры переключать окна иначе нечем. */
    private fun sendAltTab() {
        val c = client ?: return
        c.sendKey(0x38, false, true)
        c.sendKey(0x0F, false, true)
        c.sendKey(0x0F, false, false)
        c.sendKey(0x38, false, false)
    }

    /**
     * Поле ввода для системной клавиатуры на телефоне. У RemoteView нет InputConnection, поэтому клавиатура
     * показывается только когда это поле получает фокус; его KeyEvent перехватывает [handleKeyEvent].
     * Создаётся в потоке интерфейса: событие агенту может прийти раньше, чем пользователь откроет клавиатуру.
     */
    private var imeEdit: android.widget.EditText? = null

    private fun ensureImeEdit(): android.widget.EditText = imeEdit ?: android.widget.EditText(this).apply {
        isSingleLine = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setTextColor(android.graphics.Color.TRANSPARENT)
        alpha = 0.01f
        imeEdit = this
    }

    /** Поле ввода уже в окне: значит, экранная клавиатура открыта (или открывается). */
    private fun imeAttached(): Boolean = imeEdit?.parent != null

    /** Экранная клавиатура готовится принимать ввод (поле уже в окне). */
    @Volatile private var imeOn = false

    /** Экранная клавиатура на экране: по ней решается, что делает кнопка «назад». */
    private val imeOpen: Boolean
        get() = imeAttached() && imeVisible()

    /**
     * Видимость клавиатуры: инсеты окна знают о ней, пока клавиатура показана.
     * (WindowInsetsController.isVisible в этой версии core недоступен.)
     */
    private fun imeVisible(): Boolean {
        val root = window.decorView
        val insets = root.rootWindowInsets ?: return false
        return WindowInsetsCompat.toWindowInsetsCompat(insets, root).isVisible(WindowInsetsCompat.Type.ime())
    }

    private fun tapWinKey() {
        val c = client ?: return
        c.sendKey(0x5B, true, true); c.sendKey(0x5B, true, false)
    }

    /** Захват тачпада включён, пока сеанс открыт и окно на переднем плане; при первом запуске — калибровка. */
    private fun updateCapture() {
        if (!::remoteView.isInitialized) return
        val active = captureOn && resumed && state == SessionState.Connected && !lockedCover && !fingerInput()
        remoteView.captureEnabled = active
        if (!active) remoteView.cancelCalibration()
        else if (!touchpadCalibrated && calibStep == null) remoteView.startCalibration()
    }

    /**
     * Сенсорный ввод телефонного масштаба: у смартфона нет ни чехла-тачпада, ни мыши, поэтому захват
     * указателя и калибровка тачпада ему не нужны — управление идёт пальцами прямо по рабочему столу.
     */
    private fun fingerInput(): Boolean = resources.configuration.smallestScreenWidthDp < 600

    /** Есть ли смысл в калибровке тачпада: на телефоне его нет. */
    private val capturedByTouchpad: Boolean get() = !fingerInput()

    private fun toggleCapture() {
        captureOn = !captureOn
        uiPrefs.edit().putBoolean("tp_capture", captureOn).apply()
        updateCapture()
    }

    private fun recalibrate() {
        if (!captureOn) {
            captureOn = true
            uiPrefs.edit().putBoolean("tp_capture", true).apply()
        }
        touchpadCalibrated = false
        remoteView.cancelCalibration()
        updateCapture()
    }

    private fun skipCalibration() {
        remoteView.cancelCalibration()
        captureOn = false
        uiPrefs.edit().putBoolean("tp_capture", false).apply()
        updateCapture()
    }

    private fun disconnect() {
        powerCancelled = true
        saveThumbnail()
        // Поток сохранения копирует кадр заранее, поэтому закрывать соединение можно сразу.
        client?.close()
        leaveSession()
    }

    /**
     * Закрывает экран сеанса и оставляет пользователя в приложении. Сеанс, открытый с ПК («Переключиться на
     * планшет») или из уведомления, — единственное окно своей задачи: простой `finish()` свернул бы приложение
     * на рабочий стол, поэтому в таком случае сначала открывается главный экран со списком ПК.
     */
    private fun leaveSession() {
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /** Новая сила прокрутки, инерции, скорость мыши и её колёсика действуют сразу и запоминаются для этого ПК. */
    private fun applyInputLevels(scroll: Int, inertia: Int, mouse: Int, wheel: Int) {
        if (scroll == device.scrollPercent && inertia == device.inertiaPercent && mouse == device.mouseSpeedPercent &&
            wheel == device.mouseWheelPercent
        ) return
        device = device.copy(scrollPercent = scroll, inertiaPercent = inertia, mouseSpeedPercent = mouse, mouseWheelPercent = wheel)
        remoteView.setScrollSettings(StrengthLevels.factor(scroll), StrengthLevels.inertiaFactor(inertia))
        remoteView.setMouseSpeed(StrengthLevels.mouseFactor(mouse))
        remoteView.setMouseWheelSpeed(StrengthLevels.factor(wheel))
        DeviceRepository.get(this).update(device.id) {
            it.copy(scrollPercent = scroll, inertiaPercent = inertia, mouseSpeedPercent = mouse, mouseWheelPercent = wheel)
        }
    }

    override fun onStart() {
        super.onStart()
        if (::remoteView.isInitialized) clipboard.addPrimaryClipChangedListener(clipListener)
        visible = true
        agent?.setForeground(true)
    }

    override fun onPause() {
        // Служба перехвата клавиш работает только пока открыт экран сеанса.
        KeyForwarder.handler = null
        releaseAllKeys()
        resumed = false
        updateCapture()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        KeyForwarder.handler = { e -> handleKeyEvent(e) }
        resumed = true
        if (lockedCover && !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) unlockCover()
        updateCapture()
        // Клавиатуру могли закрыть системной кнопкой: поле без неё держать не нужно.
        if (imeAttached() && !imeVisible()) closeKeyboard()
        keyCaptureActive = KeyForwarder.serviceConnected
        // Буфер можно прочитать только в активном приложении, поэтому синхронизируем при возврате.
        if (::remoteView.isInitialized && state == SessionState.Connected) window.decorView.post { pushClipboard() }
    }

    override fun onStop() {
        if (::remoteView.isInitialized) clipboard.removePrimaryClipChangedListener(clipListener)
        // Приложение свёрнуто (или экран погас): агент начинает пересылать уведомления Windows.
        visible = false
        agent?.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        powerCancelled = true
        handler.removeCallbacks(agentTimeout)
        if (::device.isInitialized) {
            runCatching { unregisterReceiver(batteryReceiver) }
            SessionService.stop(this)
            agent?.let { GuardHub.detach(device.id, it) }
            if (sessionLogged) GuardStore.get(this).recordLocal(device.id, GuardEntry.APP_END)
        }
        cancelDownload()
        handler.removeCallbacks(periodicThumbnail)
        handler.removeCallbacks(applyOrientation)
        client?.close()
        super.onDestroy()
    }

    @Composable
    private fun RemoteScreen() {
        // На планшете панель свёрнута в «ручку» сверху и сама сворачивается через 5 с без нажатий; на телефоне всегда на виду.
        var toolbar by remember { mutableStateOf(false) }
        var menuOpen by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(0) }
        LaunchedEffect(toolbar, menuOpen, busy) {
            if (toolbar && !menuOpen) {
                kotlinx.coroutines.delay(5000)
                toolbar = false
            }
        }
        BackHandler {
            if (menuOpen) menuOpen = false
            else if (settingsOpen) settingsOpen = false
            else if (imeOpen) { closeKeyboard(); if (keyboardMode == KeyboardMode.SHOW) changeKeyboardMode(KeyboardMode.HIDE) }
            else disconnect()
        }

        // На телефоне физических клавиш нет, а панель сеанса в книжной ориентации не помещается по ширине:
        // кнопки уходят вниз под палец и прокручиваются, а сама панель живёт поверх системной навигации.
        val phone = LocalConfiguration.current.screenWidthDp < 600

        Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout)) {
            AndroidView(factory = { remoteView }, modifier = Modifier.fillMaxSize())

            // Лоадер на размытой картинке, пока Windows перестраивает разрешение под новую ориентацию.
            AnimatedVisibility(
                visible = rotationWait,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(tween(300, delayMillis = 300)),
                exit = fadeOut(tween(250)),
            ) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Surface.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(36.dp))
                }
            }

            if (state == SessionState.Connected) {
                SessionToolbar(
                    phone = phone,
                    expanded = toolbar || phone,
                    onExpand = { toolbar = true },
                    onCollapse = { toolbar = false },
                    menuOpen = menuOpen,
                    onMenu = { menuOpen = it },
                    onBusy = { busy++ },
                )
            }

            if (state == SessionState.Connected) powerStatus?.let { PowerBanner(it) }
            if (state == SessionState.Connected) micHint?.let { MicBanner(it, phone) }
            if (state == SessionState.Connected) {
                cameraHint?.let { CameraBanner(it, phone, onGrant = { requestCamera.launch(android.Manifest.permission.CAMERA) }.takeIf { !hasCameraPermission() }) }
            }
            if (state == SessionState.Connected) FileTransferBanner(phone)
            if (state == SessionState.Connected && settingsOpen) SessionSettings(phone)
            if (state == SessionState.Connected && agentConfirm) AgentConfirm(phone)
            if (state == SessionState.Connected && !agentConfirm) agentStatus?.let { AgentBanner(it, phone) }
            if (state == SessionState.Connected && overlayPrompt && !agentConfirm) OverlayPrompt(phone)
            if (lockedCover) LockedCover()
            // Подсказка про перехват клавиш нужна только с физической клавиатурой: на телефоне сочетаний нет.
            if (state == SessionState.Connected && !keyCaptureActive && !keyHintDismissed && !fingerInput()) KeyCaptureHint(phone)
            calibStep?.let { CalibrationPrompt(it, phone) }

            when (val s = state) {
                SessionState.Connected -> Unit
                SessionState.Connecting -> if (!quietReconnect) Box(Modifier.fillMaxSize().background(Color(0xCC081326)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(16.dp))
                        val waking = wakeStatus
                        Text(waking ?: "Подключение к ${device.name}…", color = TextPrimary, fontSize = 18.sp, textAlign = TextAlign.Center)
                        Text(
                            if (waking != null) "ПК в спящем режиме или выключен: отправлен сигнал пробуждения по сети" else device.host,
                            color = TextSecondary, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(20.dp))
                        PanelButton("Отмена", primary = false) { disconnect() }
                    }
                }
                is SessionState.Failed -> Box(Modifier.fillMaxSize().background(Color(0xE6081326)), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.widthIn(max = 420.dp).fillMaxWidth(0.9f).clip(RoundedCornerShape(20.dp)).background(Surface)
                            .border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Не удалось подключиться", color = TextPrimary, fontSize = 20.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(s.message, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PanelButton("Назад", primary = false) { leaveSession() }
                            PanelButton("Повторить", primary = true) { connectWaking() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Компактная панель сеанса: таблетка с иконками (клавиатура, микрофон, камера, Win, «ещё») и отдельная красная
     * кнопка «Отключиться». Остальное — в меню «⋮». На планшете сверху, свёрнута в «ручку» и сама сворачивается; на
     * телефоне всегда внизу, над клавиатурой. Кнопки-переключатели (клавиатура, микрофон, камера) панель не закрывают,
     * чтобы было видно новое состояние; остальные действия сворачивают её.
     */
    @Composable
    private fun BoxScope.SessionToolbar(
        phone: Boolean,
        expanded: Boolean,
        onExpand: () -> Unit,
        onCollapse: () -> Unit,
        menuOpen: Boolean,
        onMenu: (Boolean) -> Unit,
        onBusy: () -> Unit,
    ) {
        val placement = if (phone) {
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 10.dp)
        } else {
            Modifier.align(Alignment.TopCenter).padding(top = if (expanded) 8.dp else 4.dp)
        }
        Box(placement) {
            if (!expanded) {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Surface.copy(alpha = 0.92f))
                        .border(1.dp, Outline, RoundedCornerShape(16.dp)).clickable(onClick = onExpand)
                        .padding(horizontal = 16.dp, vertical = 1.dp),
                ) {
                    Icon(Icons.Outlined.ExpandMore, "Меню сеанса", tint = Color.White, modifier = Modifier.size(20.dp))
                    // Точка на «ручке»: в свёрнутой панели видно, что есть обновление агента.
                    if (agentUpdateAvailable()) Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(CircleShape).background(Accent))
                }
                return@Box
            }
            val pill = RoundedCornerShape(28.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.shadow(6.dp, pill).clip(pill).background(SurfaceButton).padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    KeyboardModeButton(onBusy)
                    if (device.microphone) {
                        BarIcon(
                            if (micOn) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                            if (micOn) "Микрофон включён" else "Микрофон",
                            active = micOn,
                        ) { toggleMic(); onBusy() }
                    }
                    // Кнопка показывает камеры планшета Windows; сам поток включает программа на ПК.
                    if (device.camera) {
                        BarIcon(
                            if (cameraShared) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff,
                            when { cameraOn -> "Камера: ПК смотрит"; cameraShared -> "Камера включена"; else -> "Камера" },
                            active = cameraShared,
                        ) { toggleCamera(); onBusy() }
                    }
                    BarIcon(Icons.Outlined.Window, "Win") { tapWinKey(); onCollapse() }
                    // Пока на ПК агент старее вложенного в приложение, кнопка всегда на виду (подсвечена).
                    if (agentUpdateAvailable()) {
                        BarIcon(Icons.Outlined.SystemUpdate, "Обновить агент", active = true) { agentConfirm = true; onCollapse() }
                    }
                    Box {
                        BarIcon(Icons.Outlined.MoreVert, "Ещё", active = menuOpen) { onMenu(true) }
                        SessionMenu(phone, menuOpen, onMenu, onCollapse)
                    }
                }
                Box(
                    Modifier.size(52.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                        .background(DangerRed).clickable { disconnect() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.DesktopAccessDisabled, "Отключиться", tint = Color.White, modifier = Modifier.size(26.dp)) }
            }
        }
    }

    /** Меню «⋮»: всё, что не поместилось в таблетку. */
    @Composable
    private fun SessionMenu(phone: Boolean, open: Boolean, onMenu: (Boolean) -> Unit, onCollapse: () -> Unit) {
        // Закрывает меню и панель, затем выполняет действие.
        fun act(action: () -> Unit) = { onMenu(false); onCollapse(); action() }
        DropdownMenu(expanded = open, onDismissRequest = { onMenu(false) }, modifier = Modifier.background(SurfaceButton)) {
            BarMenuItem(Icons.Outlined.Settings, "Настройки", act { settingsOpen = true })
            BarMenuItem(Icons.Outlined.ScreenRotation, "Повернуть", act { rotateSession() })
            if (capturedByTouchpad) {
                BarMenuItem(Icons.Outlined.TouchApp, if (captureOn) "Жесты: ПК" else "Жесты: планшет", act { toggleCapture() })
            }
            BarMenuItem(Icons.Outlined.Lock, "Ctrl+Alt+Del", act { sendCtrlAltEnd() })
            // На планшете с чехлом Alt+Tab есть на самой клавиатуре; без неё переключать окна иначе нечем.
            if (phone) BarMenuItem(Icons.AutoMirrored.Outlined.List, "Alt+Tab", act { sendAltTab() })
            if (device.os == OsType.WINDOWS && powerAction == null) {
                val installed = agentVersion
                val label = when {
                    installed == null -> "Установить агент"
                    AgentInstaller.isOutdated(installed, bundledAgentVersion) -> "Обновить агент"
                    else -> null
                }
                if (label != null) BarMenuItem(Icons.Outlined.InstallDesktop, label, act { agentConfirm = true })
            }
        }
    }

    @Composable
    private fun BarMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label, color = TextPrimary, fontSize = 15.sp) },
            leadingIcon = { Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(22.dp)) },
            onClick = onClick,
        )
    }

    /**
     * Кнопка экранной клавиатуры с тремя состояниями ([KeyboardMode]): «показана» — подсвечена, «скрыта» — перечёркнутая
     * клавиатура, «автоматически» — клавиатура со значком «A». Каждое нажатие переходит к следующему и коротко
     * подписывает режим.
     */
    @Composable
    private fun KeyboardModeButton(onBusy: () -> Unit) {
        val mode = keyboardMode
        BarIcon(
            if (mode == KeyboardMode.HIDE) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
            mode.title,
            active = mode == KeyboardMode.SHOW,
            badge = if (mode == KeyboardMode.AUTO) "A" else null,
        ) {
            changeKeyboardMode(mode.next())
            android.widget.Toast.makeText(this, keyboardMode.title, android.widget.Toast.LENGTH_SHORT).show()
            onBusy()
        }
    }

    /** Иконка-кнопка таблетки: круг 44 dp (с запасом для пальца), [active] подсвечивает включённый режим, [badge] — буква в углу. */
    @Composable
    private fun BarIcon(icon: ImageVector, description: String, active: Boolean = false, badge: String? = null, onClick: () -> Unit) {
        // Внешний Box не обрезается: значок-буква сидит в углу квадрата 44 dp, а круглая обрезка срезала бы его (так и было).
        Box(Modifier.size(44.dp)) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(if (active) Accent else Color.Transparent).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, description, tint = TextPrimary, modifier = Modifier.size(24.dp)) }
            if (badge != null) {
                // Кольцо цвета таблетки отделяет букву от иконки; высота строки обрезана по глифу, иначе «A» уезжает вверх.
                Box(
                    Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(SurfaceButton).padding(1.5.dp)
                        .clip(CircleShape).background(Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        badge, color = TextPrimary, fontWeight = FontWeight.Bold,
                        style = TextStyle(
                            fontSize = 11.sp, lineHeight = 11.sp,
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                        ),
                    )
                }
            }
        }
    }

    /** Ход выключения или перезагрузки. */
    @Composable
    private fun BoxScope.PowerBanner(text: String) {
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(16.dp))
                .background(Surface.copy(alpha = 0.97f)).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text, color = TextPrimary, fontSize = 15.sp)
            if (!powerSent) PanelButton("Отмена", primary = false) { disconnect() }
        }
    }

    /** Почему микрофон не включился: нет разрешения, канал закрыт сервером или функция выключена в настройках. */
    @Composable
    private fun BoxScope.MicBanner(text: String, phone: Boolean) = HintBanner(Icons.Outlined.MicOff, text, phone)

    /** Почему камера недоступна: нет разрешения или она выключена в настройках устройства. */
    @Composable
    private fun BoxScope.CameraBanner(text: String, phone: Boolean, onGrant: (() -> Unit)?) {
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (phone) 72.dp else 24.dp).panelWidth(phone, 520.dp)
                .clip(RoundedCornerShape(16.dp)).background(Surface.copy(alpha = 0.97f))
                .border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.VideocamOff, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
                Text(text, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
            if (onGrant != null) {
                Spacer(Modifier.height(10.dp))
                PanelButton("Разрешить", primary = true, onClick = onGrant)
            }
        }
    }

    /** Общий вид подсказки-баннера внизу экрана: иконка и текст причины. */
    @Composable
    private fun BoxScope.HintBanner(icon: ImageVector, text: String, phone: Boolean) {
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (phone) 72.dp else 24.dp).panelWidth(phone, 520.dp)
                .clip(RoundedCornerShape(16.dp)).background(Surface.copy(alpha = 0.97f))
                .border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
            Text(text, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
    }

    /** Предложение сохранить файлы, скопированные на ПК, и ход сохранения. */
    @Composable
    private fun BoxScope.FileTransferBanner(phone: Boolean) {
        val current = transfer
        val offered = remoteFiles
        if (current == null && offered == null) return
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (phone) 72.dp else 24.dp).panelWidth(phone, 520.dp).clip(RoundedCornerShape(16.dp))
                .background(Surface.copy(alpha = 0.97f)).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            when {
                current is TransferState.Running -> {
                    Text("Сохранение файлов с ПК…", color = TextPrimary, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    if (current.name.isNotEmpty()) Text(current.name, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.height(10.dp))
                    if (current.total > 0) {
                        LinearProgressIndicator(
                            progress = { (current.done.toFloat() / current.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Outline,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Outline)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${formatSize(current.done)} из ${formatSize(current.total)}", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    PanelButton("Отмена", primary = false) { cancelDownload() }
                }
                current is TransferState.Done -> {
                    Text("Сохранено файлов: ${current.files}", color = TextPrimary, fontSize = 15.sp)
                    Text("Папка «Загрузки/${RemoteFileDownloader.FOLDER}» на планшете", color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    PanelButton("Готово", primary = true) { transfer = null; remoteFiles = null }
                }
                current is TransferState.Failed -> {
                    Text("Не удалось сохранить файлы", color = TextPrimary, fontSize = 15.sp)
                    Text(current.message, color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    PanelButton("Закрыть", primary = false) { transfer = null }
                }
                offered != null -> {
                    val count = offered.count { !it.isDirectory }
                    val known = offered.sumOf { it.size.coerceAtLeast(0L) }
                    Text("На ПК скопированы файлы: $count", color = TextPrimary, fontSize = 15.sp)
                    Text(
                        offered.firstOrNull { !it.isDirectory }?.name.orEmpty() + (if (known > 0) " · ${formatSize(known)}" else ""),
                        color = TextSecondary, fontSize = 13.sp, maxLines = 1,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (count > 0) PanelButton("Сохранить на планшет", primary = true) { downloadRemoteFiles() }
                        PanelButton("Скрыть", primary = false) { remoteFiles = null }
                    }
                }
            }
        }
    }

    /**
     * Настройки, которые действуют сразу, без переподключения: сила прокрутки и инерции, скорость мыши (запоминаются
     * для этого ПК), захват жестов и микрофон; здесь же кнопки калибровки тачпада и передачи файлов и папки на ПК. Остальное (масштаб, глубина цвета, диск) применяется при подключении
     * и меняется в карточке ПК. Затемнение под окном перехватывает касания, чтобы они не уходили на ПК.
     */
    @Composable
    private fun BoxScope.SessionSettings(phone: Boolean) {
        var scroll by remember { mutableStateOf(device.scrollPercent.toFloat()) }
        var inertia by remember { mutableStateOf(device.inertiaPercent.toFloat()) }
        var mouse by remember { mutableStateOf(device.mouseSpeedPercent.toFloat()) }
        var wheel by remember { mutableStateOf(device.mouseWheelPercent.toFloat()) }
        fun apply() = applyInputLevels(StrengthLevels.snap(scroll), StrengthLevels.snap(inertia), StrengthLevels.snap(mouse), StrengthLevels.snap(wheel))

        Box(
            Modifier.fillMaxSize().background(Color(0x66000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settingsOpen = false },
        )
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
        Column(
            Modifier.align(Alignment.Center).panelWidth(phone, 520.dp).clip(RoundedCornerShape(20.dp)).background(Surface.copy(alpha = 0.97f))
                .border(1.dp, Outline, RoundedCornerShape(20.dp))
                // Касания внутри окна не должны закрывать его через затемнение.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Настройки сеанса", color = TextPrimary, fontSize = 18.sp)
            StrengthSlider(
                title = "Сила скролла",
                hint = "Прокрутка двумя пальцами на тачпаде и сенсорном экране.",
                value = scroll,
                onChange = { scroll = it; apply() },
            )
            StrengthSlider(
                title = "Сила инерции",
                hint = "Как далеко прокрутка продолжается после отрыва пальцев.",
                value = inertia,
                onChange = { inertia = it; apply() },
            )
            StrengthSlider(
                title = "Скорость курсора мыши",
                hint = "Физическая (Bluetooth) мышь; тачпада чехла и сенсорного экрана не касается.",
                value = mouse,
                onChange = { mouse = it; apply() },
            )
            StrengthSlider(
                title = "Скорость колёсика мыши",
                hint = "Прокрутка колёсиком физической (Bluetooth) мыши; прокрутку пальцами не касается.",
                value = wheel,
                onChange = { wheel = it; apply() },
            )
            if (capturedByTouchpad) {
                SettingSwitch("Жесты тачпада — на ПК", "Выключено: жесты обрабатывает планшет.", captureOn) { toggleCapture() }
            }
            if (device.microphone) {
                SettingSwitch("Микрофон планшета", "Передавать звук с микрофона на ПК.", micOn) { toggleMic() }
            }
            if (device.camera) {
                SettingSwitch("Камера планшета", "Показать камеры планшета программам Windows.", cameraShared) { toggleCamera() }
                val source = cameraSource
                if (cameraShared && source != null) {
                    val cameras = remember(source) { source.catalog() }
                    cameras.forEach { (id, title) ->
                        val shared = id !in cameraOffNow
                        SettingSwitch(title, "Отдавать эту камеру ПК.", shared) { setCameraShared(id, !shared) }
                        if (shared) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Поворот", color = TextSecondary, fontSize = 12.sp)
                                val current = cameraRotationNow[id] ?: 0
                                Device.CAMERA_ROTATIONS.forEach { degrees ->
                                    RotationButton("$degrees°", current == degrees) { setCameraRotation(id, degrees) }
                                }
                            }
                        }
                    }
                    Text(
                        "Поворот добавляется к автоматическому по часовой стрелке и применяется, когда программа на ПК заново открывает камеру.",
                        color = TextSecondary, fontSize = 12.sp,
                    )
                }
            }
            if (capturedByTouchpad || device.fileTransfer) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (capturedByTouchpad) {
                        ToolbarButton(Icons.Outlined.Mouse, "Калибровка") { settingsOpen = false; recalibrate() }
                    }
                    if (device.fileTransfer) {
                        ToolbarButton(Icons.Outlined.UploadFile, "Файлы на ПК") { settingsOpen = false; pickFiles.launch(arrayOf("*/*")) }
                        ToolbarButton(Icons.Outlined.FolderOpen, "Папка на ПК") { settingsOpen = false; pickFolder.launch(null) }
                    }
                }
            }
            Text("Масштаб, глубина цвета и диск применяются при подключении — они в настройках ПК.", color = TextSecondary, fontSize = 12.sp)
            PanelButton("Готово", primary = true) { settingsOpen = false }
        }
    }

    @Composable
    private fun SettingSwitch(title: String, hint: String, checked: Boolean, onToggle: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 15.sp)
                Text(hint, color = TextSecondary, fontSize = 12.sp)
            }
            Switch(
                checked = checked, onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = TextPrimary),
            )
        }
    }

    /** Подтверждение установки агента: на ПК откроется «Выполнить» и Windows спросит разрешение на запуск. */
    @Composable
    private fun BoxScope.AgentConfirm(phone: Boolean) {
        val update = agentVersion != null
        Column(
            Modifier.align(Alignment.Center).panelWidth(phone, 520.dp).clip(RoundedCornerShape(20.dp)).background(Surface.copy(alpha = 0.97f))
                .border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(24.dp),
        ) {
            Text(if (update) "Обновить HiWeb Agent на ПК?" else "Установить HiWeb Agent на ПК?", color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Агент показывает заряд планшета в трее Windows и пересылает уведомления Windows на планшет, " +
                    "пока приложение свёрнуто (кроме уведомлений «Связи с телефоном»).",
                color = TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "На ПК откроется окно «Выполнить» и запустится установщик $bundledAgentVersion с диска планшета. " +
                    "Если Windows спросит, разрешить ли запуск, нажмите «Запустить». Права администратора не нужны; " +
                    "удалить агент можно из меню его значка или в «Приложениях» Windows.",
                color = TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelButton(if (update) "Обновить" else "Установить", primary = true) { agentConfirm = false; installAgent() }
                PanelButton("Отмена", primary = false) { agentConfirm = false }
            }
        }
    }

    /** Без права «Поверх других приложений» Android не даёт открыть сеанс из фона по команде с ПК. */
    @Composable
    private fun BoxScope.OverlayPrompt(phone: Boolean) {
        fun dismiss() {
            overlayPrompt = false
            uiPrefs.edit().putBoolean("overlay_asked", true).apply()
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (phone) 72.dp else 24.dp).panelWidth(phone, 560.dp).clip(RoundedCornerShape(16.dp))
                .background(Surface.copy(alpha = 0.97f)).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            Text("«Переключиться на Планшет» с ПК", color = TextPrimary, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Чтобы пункт в меню HiWeb Agent сам открывал сеанс на планшете, разрешите приложению показ поверх " +
                    "других приложений. Без разрешения придёт уведомление, которое нужно будет нажать.",
                color = TextSecondary, fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelButton("Разрешить", primary = true) {
                    dismiss()
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    )
                }
                PanelButton("Не сейчас", primary = false) { dismiss() }
            }
        }
    }

    /** Заставка, пока планшет не разблокирован: рабочий стол ПК не виден и ввод на ПК не уходит. */
    @Composable
    private fun LockedCover() {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(enabled = true) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Переключение на планшет", color = TextPrimary, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text("Разблокируйте планшет, чтобы открыть ${device.name}", color = TextSecondary, fontSize = 15.sp)
            }
        }
    }

    @Composable
    private fun BoxScope.AgentBanner(text: String, phone: Boolean) {
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = if (phone) 12.dp else 60.dp).panelWidth(phone, 560.dp).clip(RoundedCornerShape(16.dp))
                .background(Surface.copy(alpha = 0.97f)).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (agentInstalling) CircularProgressIndicator(color = Accent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(text, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            PanelButton("Скрыть", primary = false) { agentStatus = null }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f КБ".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes Б"
    }

    /** Подсказка калибровки тачпада: ориентация датчика в чехле-клавиатуре заранее неизвестна. */
    @Composable
    private fun BoxScope.CalibrationPrompt(step: TouchpadCalibrator.Step, phone: Boolean) {
        val (title, text) = when (step) {
            TouchpadCalibrator.Step.RIGHT -> "Калибровка тачпада · шаг 1 из 2" to "Проведите одним пальцем по тачпаду вправо на 2–3 см и отпустите."
            else -> "Калибровка тачпада · шаг 2 из 2" to "Теперь проведите одним пальцем вниз, к себе, на 2–3 см и отпустите."
        }
        Column(
            Modifier.align(Alignment.Center).panelWidth(phone, 480.dp).clip(RoundedCornerShape(20.dp)).background(Surface.copy(alpha = 0.97f))
                .border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Text(text, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Нужна один раз: так приложение узнаёт, как повёрнут тачпад.", color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            PanelButton("Пропустить (тачпад останется за системой)", primary = false) { skipCalibration() }
        }
    }

    /** Подсказка: без службы перехвата Android оставляет себе Win, Alt+Tab и подобные сочетания. */
    @Composable
    private fun KeyCaptureHint(phone: Boolean) {
        Column(
            Modifier.padding(top = if (phone) 12.dp else 44.dp, start = if (phone) 12.dp else 24.dp).panelWidth(phone, 460.dp).clip(RoundedCornerShape(16.dp)).background(Surface.copy(alpha = 0.96f))
                .border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            Text("Сочетания клавиш Win, Alt+Tab и др. пока срабатывают на планшете", color = TextPrimary, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Чтобы они работали на ПК, включите службу «RemoteDesk: передача клавиш» в Специальных возможностях. " +
                    "Она передаёт клавиши только пока открыт удалённый рабочий стол. Если пункт недоступен, откройте " +
                    "Настройки → Приложения → RemoteDesk → ⋮ → «Разрешить ограниченные настройки».",
                color = TextSecondary, fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelButton("Открыть настройки", primary = true) {
                    startActivity(
                        com.hiweb.hiwebremotedesk.data.AppPermissions.settingsIntent(this@RemoteActivity, com.hiweb.hiwebremotedesk.data.AppPermission.KEY_CAPTURE),
                    )
                }
                PanelButton("Не показывать", primary = false) {
                    keyHintDismissed = true
                    getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("key_hint_dismissed", true).apply()
                }
            }
        }
    }

    /**
     * Кнопка панели сеанса. [active] подкрашивает включённый режим (микрофон): по цвету видно,
     * что звук с планшета сейчас уходит на ПК.
     */
    @Composable
    private fun ToolbarButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
        Column(
            Modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                .background(if (active) Accent else SurfaceButton).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(22.dp))
            Text(label, color = if (active) TextPrimary else TextSecondary, fontSize = 11.sp, maxLines = 1)
        }
    }

    /**
     * Ширина плашки: на планшете — заданная (520 dp и т. п.), на телефоне — по ширине экрана с полями,
     * иначе плашка в 520 dp просто вылезает за 384 dp.
     */
    @Composable
    private fun Modifier.panelWidth(phone: Boolean, wide: androidx.compose.ui.unit.Dp): Modifier =
        if (phone) this.fillMaxWidth().padding(horizontal = 12.dp) else this.width(wide)

    @Composable
    private fun PanelButton(text: String, primary: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(if (primary) Accent else SurfaceButton)
                .clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        ) { Text(text, color = TextPrimary, fontSize = 15.sp) }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_POWER_ACTION = "power_action"

        /** Сеанс открыт командой «Переключиться на Планшет» с ПК. */
        const val EXTRA_SWITCH = "switch_from_pc"

        // Скан-коды модификаторов и символы, которые нажимаются с Shift.
        private const val SHIFT_SCAN = 0x2A
        private const val CTRL_SCAN = 0x1D
        private const val ALT_SCAN = 0x38
        private const val WIN_SCAN = 0x5B

        // Скан-коды evdev кнопок мыши: BTN_MIDDLE … BTN_TASK (0x110/0x111 — левая и правая, их не трогаем).
        private const val BTN_MIDDLE_SCAN = 0x112
        private const val BTN_SIDE_SCAN = 0x113
        private const val BTN_EXTRA_SCAN = 0x114
        private const val BTN_FORWARD_SCAN = 0x115
        private const val BTN_BACK_SCAN = 0x116
        private const val BTN_TASK_SCAN = 0x117

        private const val AGENT_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** Пауза после поворота планшета перед сменой размера рабочего стола. */
        private const val ORIENTATION_SETTLE_MS = 400L

        /** Ожидание рабочего стола перед командой питания: не раньше, чем через MIN, пока кадры идут чаще, чем раз в QUIET; не дольше MAX. */
        private const val POWER_MIN_WAIT_MS = 5_000L
        private const val POWER_QUIET_MS = 2_000L
        private const val POWER_MAX_WAIT_MS = 40_000L
        private const val POWER_AFTER_MS = 4_000L

        /** Пауза до первого автоповтора и интервал повторов (≈30 в секунду). */
        private const val REPEAT_DELAY_MS = 600L
        private const val REPEAT_INTERVAL_MS = 33L

        /** Сколько ждать ответа агента после запуска установщика (включая подтверждение запуска на ПК). */
        private const val AGENT_INSTALL_TIMEOUT_MS = 120_000L

        /** Пробуждение: проверка порта, повтор пакета, предел ожидания и пауза после открытия порта. */
        private const val WAKE_PROBE_MS = 1500
        private const val WAKE_RESEND_MS = 10_000L
        private const val WAKE_TIMEOUT_MS = 120_000L
        private const val WAKE_SETTLE_MS = 2_000L
    }
}
