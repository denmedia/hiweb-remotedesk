package com.hiweb.hiwebremotedesk

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InstallDesktop
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.hiweb.hiwebremotedesk.ui.theme.Accent
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

    // HiWeb Agent на ПК (только Windows): заряд планшета в трее ПК и уведомления Windows в свёрнутом приложении.
    private var agent: AgentLink? = null
    private var agentVersion by mutableStateOf<String?>(null)
    private val bundledAgentVersion by lazy { AgentInstaller.bundledVersion(this) }
    private var agentConfirm by mutableStateOf(false)
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
                if (!visible) PcNotifications.post(this@RemoteActivity, device.id, device.name, n)
            }

            override fun onAgentClosed() {
                runOnUiThread {
                    agentVersion = null
                    remoteView.keyboardPane = null
                }
            }

            override fun onKeyboardPane(rect: android.graphics.Rect?) {
                runOnUiThread { remoteView.keyboardPane = rect }
            }

            override fun onAgentActivate() {
                runOnUiThread { if (!visible) SwitchLauncher.open(this@RemoteActivity, device.id) }
            }

            override fun onAgentStatus(msg: org.json.JSONObject) {
                PcStateStore.get(this@RemoteActivity).report(device.id, msg)
            }
        }).also { link ->
            registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.let { sendBattery(it, link) }
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
        handler.removeCallbacks(applyOrientation)
        handler.postDelayed(applyOrientation, ORIENTATION_SETTLE_MS)
    }

    /** Устаревший агент на ПК: окно с предложением обновить — не чаще раза в сутки для каждого ПК. */
    private fun offerAgentUpdate(installed: String) {
        if (!visible || agentInstalling || !AgentInstaller.isOutdated(installed, bundledAgentVersion)) return
        val key = "agent_update_asked_" + device.id
        val now = System.currentTimeMillis()
        if (now - uiPrefs.getLong(key, 0) < AGENT_UPDATE_INTERVAL_MS) return
        uiPrefs.edit().putLong(key, now).apply()
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
            finish()
            return
        }
        device = found
        powerAction = intent.getStringExtra(EXTRA_POWER_ACTION)?.let { name -> PowerAction.entries.firstOrNull { it.name == name } }
        keyHintDismissed = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("key_hint_dismissed", false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        // Рисуем под вырезом камеры, но элементы управления и сам рабочий стол смещаем по его границе (см. RemoteScreen).
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        remoteView = RemoteView(this)
        // Сила прокрутки и инерции задаётся в настройках этого ПК
        remoteView.setScrollSettings(StrengthLevels.factor(device.scrollLevel), StrengthLevels.factor(device.inertiaLevel))
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
    private val applyOrientation = Runnable {
        val c = client ?: return@Runnable
        if (state != SessionState.Connected) return@Runnable
        val size = desktopSize()
        if (size == requestedSize) return@Runnable
        val (pw, ph) = physicalMm()
        if (c.resizeDesktop(size.first, size.second, pw, ph)) {
            requestedSize = size
            return@Runnable
        }
        if (powerAction != null) return@Runnable
        // Windows без графического конвейера по DisplayControl меняет размер у себя, но не присылает
        // клиенту новый размер (проверено: касания мимо, окна не разворачиваются). Поэтому переподключаемся
        // с новым размером: сеанс Windows сохраняется, подключение занимает доли секунды.
        android.util.Log.i("RDP", "reconnecting for desktop ${size.first}x${size.second}")
        connect()
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

    private fun connect() {
        val seq = ++sessionSeq
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
            gfx = device.os == OsType.UBUNTU,
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
                    state = SessionState.Connected
                    pushClipboard()
                    updateCapture()
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
                    applyOrientation.run()
                    if (powerAction == null) {
                        SessionService.start(this@RemoteActivity, device.id, device.name)
                        askNotificationPermission()
                    }
                }
            }

            override fun onDesktopSize(width: Int, height: Int) {
                if (seq != sessionSeq) return
                desktopWidth = width
                remoteView.initDesktop(width, height)
            }

            override fun onBitmap(left: Int, top: Int, width: Int, height: Int, argb: IntArray) {
                if (seq != sessionSeq) return
                lastBitmapAt = SystemClock.uptimeMillis()
                remoteView.applyBitmap(left, top, width, height, argb)
            }

            override fun onCursor(cursor: RemoteCursor) {
                if (seq == sessionSeq) remoteView.setRemoteCursor(cursor)
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
                        if (!isFinishing) finish()
                    } else {
                        state = SessionState.Failed(error)
                        updateCapture()
                    }
                }
            }
        }, AudioPlayer(), createDriveStorage(),
            dynamicChannels = link?.let { mapOf(AgentLink.CHANNEL_NAME to it) }.orEmpty(),
            attachableDrives = device.os == OsType.WINDOWS,
        )
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
                runOnUiThread { if (!isFinishing) finish() }
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

    override fun dispatchKeyEvent(e: KeyEvent): Boolean = forwardKey(e) || super.dispatchKeyEvent(e)

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

    private fun tapWinKey() {
        val c = client ?: return
        c.sendKey(0x5B, true, true); c.sendKey(0x5B, true, false)
    }

    /** Захват тачпада включён, пока сеанс открыт и окно на переднем плане; при первом запуске — калибровка. */
    private fun updateCapture() {
        if (!::remoteView.isInitialized) return
        val active = captureOn && resumed && state == SessionState.Connected && !lockedCover
        remoteView.captureEnabled = active
        if (!active) remoteView.cancelCalibration()
        else if (!touchpadCalibrated && calibStep == null) remoteView.startCalibration()
    }

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
        finish()
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
        KeyForwarder.handler = { e -> forwardKey(e) }
        resumed = true
        if (lockedCover && !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) unlockCover()
        updateCapture()
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
        }
        cancelDownload()
        handler.removeCallbacks(periodicThumbnail)
        handler.removeCallbacks(applyOrientation)
        client?.close()
        super.onDestroy()
    }

    @Composable
    private fun RemoteScreen() {
        var toolbar by remember { mutableStateOf(false) }
        LaunchedEffect(toolbar) {
            if (toolbar) {
                kotlinx.coroutines.delay(5000)
                toolbar = false
            }
        }
        BackHandler { disconnect() }

        Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout)) {
            AndroidView(factory = { remoteView }, modifier = Modifier.fillMaxSize())

            if (state == SessionState.Connected) {
                Box(Modifier.align(Alignment.TopCenter)) {
                    if (toolbar) {
                        Row(
                            Modifier.padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Surface.copy(alpha = 0.95f))
                                .border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ToolbarButton(Icons.Outlined.Close, "Отключиться") { disconnect() }
                            ToolbarButton(Icons.Outlined.Lock, "Ctrl+Alt+Del") { sendCtrlAltEnd(); toolbar = false }
                            ToolbarButton(Icons.Outlined.Window, "Win") { tapWinKey(); toolbar = false }
                            ToolbarButton(Icons.Outlined.TouchApp, if (captureOn) "Жесты: ПК" else "Жесты: планшет") { toggleCapture(); toolbar = false }
                            ToolbarButton(Icons.Outlined.Mouse, "Калибровка") { recalibrate(); toolbar = false }
                            if (device.fileTransfer) {
                                ToolbarButton(Icons.Outlined.UploadFile, "Файлы на ПК") { pickFiles.launch(arrayOf("*/*")); toolbar = false }
                                ToolbarButton(Icons.Outlined.FolderOpen, "Папка на ПК") { pickFolder.launch(null); toolbar = false }
                            }
                            if (device.os == OsType.WINDOWS && powerAction == null) {
                                val installed = agentVersion
                                val label = when {
                                    installed == null -> "Установить агент"
                                    AgentInstaller.isOutdated(installed, bundledAgentVersion) -> "Обновить агент"
                                    else -> null
                                }
                                if (label != null) ToolbarButton(Icons.Outlined.InstallDesktop, label) { agentConfirm = true; toolbar = false }
                            }
                        }
                    } else {
                        Box(
                            Modifier.padding(top = 6.dp).clip(RoundedCornerShape(16.dp)).background(Surface.copy(alpha = 0.92f))
                                .border(1.dp, Outline, RoundedCornerShape(16.dp)).clickable { toolbar = true }
                                .padding(horizontal = 30.dp, vertical = 6.dp),
                        ) { Icon(Icons.Outlined.ExpandMore, "Меню сеанса", tint = Color.White, modifier = Modifier.size(28.dp)) }
                    }
                }
            }

            if (state == SessionState.Connected) powerStatus?.let { PowerBanner(it) }
            if (state == SessionState.Connected) FileTransferBanner()
            if (state == SessionState.Connected && agentConfirm) AgentConfirm()
            if (state == SessionState.Connected && !agentConfirm) agentStatus?.let { AgentBanner(it) }
            if (state == SessionState.Connected && overlayPrompt && !agentConfirm) OverlayPrompt()
            if (lockedCover) LockedCover()
            if (state == SessionState.Connected && !keyCaptureActive && !keyHintDismissed) KeyCaptureHint()
            calibStep?.let { CalibrationPrompt(it) }

            when (val s = state) {
                SessionState.Connected -> Unit
                SessionState.Connecting -> Box(Modifier.fillMaxSize().background(Color(0xCC081326)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(16.dp))
                        val waking = wakeStatus
                        Text(waking ?: "Подключение к ${device.name}…", color = TextPrimary, fontSize = 18.sp)
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
                        Modifier.width(420.dp).clip(RoundedCornerShape(20.dp)).background(Surface).border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Не удалось подключиться", color = TextPrimary, fontSize = 20.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(s.message, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PanelButton("Назад", primary = false) { finish() }
                            PanelButton("Повторить", primary = true) { connectWaking() }
                        }
                    }
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

    /** Предложение сохранить файлы, скопированные на ПК, и ход сохранения. */
    @Composable
    private fun BoxScope.FileTransferBanner() {
        val current = transfer
        val offered = remoteFiles
        if (current == null && offered == null) return
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).width(520.dp).clip(RoundedCornerShape(16.dp))
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

    /** Подтверждение установки агента: на ПК откроется «Выполнить» и Windows спросит разрешение на запуск. */
    @Composable
    private fun BoxScope.AgentConfirm() {
        val update = agentVersion != null
        Column(
            Modifier.align(Alignment.Center).width(520.dp).clip(RoundedCornerShape(20.dp)).background(Surface.copy(alpha = 0.97f))
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
    private fun BoxScope.OverlayPrompt() {
        fun dismiss() {
            overlayPrompt = false
            uiPrefs.edit().putBoolean("overlay_asked", true).apply()
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).width(560.dp).clip(RoundedCornerShape(16.dp))
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
    private fun BoxScope.AgentBanner(text: String) {
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 60.dp).width(560.dp).clip(RoundedCornerShape(16.dp))
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
    private fun BoxScope.CalibrationPrompt(step: TouchpadCalibrator.Step) {
        val (title, text) = when (step) {
            TouchpadCalibrator.Step.RIGHT -> "Калибровка тачпада · шаг 1 из 2" to "Проведите одним пальцем по тачпаду вправо на 2–3 см и отпустите."
            else -> "Калибровка тачпада · шаг 2 из 2" to "Теперь проведите одним пальцем вниз, к себе, на 2–3 см и отпустите."
        }
        Column(
            Modifier.align(Alignment.Center).width(480.dp).clip(RoundedCornerShape(20.dp)).background(Surface.copy(alpha = 0.97f))
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
    private fun KeyCaptureHint() {
        Column(
            Modifier.padding(top = 44.dp, start = 24.dp).width(460.dp).clip(RoundedCornerShape(16.dp)).background(Surface.copy(alpha = 0.96f))
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
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                PanelButton("Не показывать", primary = false) {
                    keyHintDismissed = true
                    getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("key_hint_dismissed", true).apply()
                }
            }
        }
    }

    @Composable
    private fun ToolbarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(SurfaceButton).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(22.dp))
            Text(label, color = TextSecondary, fontSize = 11.sp)
        }
    }

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
