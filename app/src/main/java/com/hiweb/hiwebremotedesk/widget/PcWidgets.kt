package com.hiweb.hiwebremotedesk.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.hiweb.hiwebremotedesk.R
import com.hiweb.hiwebremotedesk.agent.AgentHub
import com.hiweb.hiwebremotedesk.agent.AgentPresence
import com.hiweb.hiwebremotedesk.agent.FilesAccess
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.DeviceRepository
import com.hiweb.hiwebremotedesk.data.GuardData
import com.hiweb.hiwebremotedesk.data.GuardStore
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.PcState
import com.hiweb.hiwebremotedesk.data.PcStateStore
import com.hiweb.hiwebremotedesk.data.PcStatus
import com.hiweb.hiwebremotedesk.data.PcStatusRules
import com.hiweb.hiwebremotedesk.data.Probe
import com.hiweb.hiwebremotedesk.data.StatusTone
import com.hiweb.hiwebremotedesk.data.ThumbnailStore
import com.hiweb.hiwebremotedesk.ui.ButtonHint
import com.hiweb.hiwebremotedesk.ui.ButtonHints
import com.hiweb.hiwebremotedesk.ui.CardActions
import com.hiweb.hiwebremotedesk.ui.CardPower
import com.hiweb.hiwebremotedesk.ui.PowerIcon
import com.hiweb.hiwebremotedesk.ui.offlineHint
import com.hiweb.hiwebremotedesk.ui.offlineTitle
import com.hiweb.hiwebremotedesk.ui.statusText
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.DangerRed
import com.hiweb.hiwebremotedesk.ui.theme.TextDisabled
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary
import com.hiweb.hiwebremotedesk.ui.toneColor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Виджет «ПК» на главном экране — карточка одного ПК, как в списке ([com.hiweb.hiwebremotedesk.ui.DeviceCard]): состояние,
 * значки «Файлы» и щит защиты, превью, название и адрес с «Поделиться», «Подключиться»/«Настройки» и кнопки питания
 * (правила кнопок — общие [CardActions]). Нажатие на превью или «Подключиться» сразу открывает сеанс, остальное идёт через
 * [WidgetLaunchActivity]; значок «⇄» — выбор другого ПК ([PcWidgetConfigActivity]). Какой ПК показывает виджет — prefs
 * `widgets`, ключ `w<id виджета>`.
 *
 * Состояние считается теми же правилами, что и на карточке ([PcStatusRules]): сообщение агента, агент на связи и
 * проверка порта RDP. Порт проверяется при обновлении виджета и при изменениях данных (не чаще [PROBE_MIN_INTERVAL_MS]),
 * а не каждые 8 с, как на открытом экране списка, — виджет живёт и при свёрнутом приложении.
 */
object PcWidgets {
    private const val PREFS = "widgets"

    /** Проверка портов не чаще: изменения превью идут каждые 30 с во время сеанса. */
    private const val PROBE_MIN_INTERVAL_MS = 20_000L

    /** Уже этой ширины (dp) подписи кнопок не помещаются: кнопки показываются одними значками. */
    private const val LABELS_MIN_WIDTH_DP = 300f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ports = ConcurrentHashMap<String, Boolean>()
    @Volatile private var probedAt = 0L
    private val watching = AtomicBoolean(false)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceFor(context: Context, widgetId: Int): String? = prefs(context).getString("w$widgetId", null)

    /** Привязывает виджет к ПК и сразу перерисовывает его. */
    fun bind(context: Context, widgetId: Int, deviceId: String) {
        prefs(context).edit().putString("w$widgetId", deviceId).apply()
        render(context, intArrayOf(widgetId))
        refresh(context, probe = true)
    }

    fun forget(context: Context, widgetIds: IntArray) {
        prefs(context).edit().apply { widgetIds.forEach { remove("w$it") } }.apply()
    }

    private fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PcWidgetProvider::class.java))

    /**
     * Пока процесс жив, перерисовывает виджеты при смене списка ПК, превью, сообщений агента, связи с ним и журнала защиты.
     * Вызывать из точек входа (экран списка, сеанс, служба агента, обновление виджета); повторный вызов ничего не делает.
     */
    @OptIn(FlowPreview::class)
    fun watch(context: Context) {
        val app = context.applicationContext
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            combine(
                DeviceRepository.get(app).devices,
                AgentHub.presence,
                PcStateStore.get(app).reports,
                ThumbnailStore.get(app).version,
                GuardStore.get(app).data,
            ) { _, _, _, _, _ -> }
                .debounce(500)
                // Порт тоже перепроверяется (не чаще PROBE_MIN_INTERVAL_MS): иначе ушедший со связи ПК остался бы «онлайн».
                .collect { refresh(app, probe = true) }
        }
    }

    /** Перерисовать все виджеты; [probe] — заодно проверить порты RDP (если давно не проверяли). [done] — по завершении. */
    fun refresh(context: Context, probe: Boolean, done: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try {
                if (probe) withTimeoutOrNull(5_000) { probePorts(app) }
                renderAll(app)
            } finally {
                done()
            }
        }
    }

    private suspend fun probePorts(context: Context) {
        if (System.currentTimeMillis() - probedAt < PROBE_MIN_INTERVAL_MS) return
        val bound = HashSet<String>()
        for (widgetId in widgetIds(context)) deviceFor(context, widgetId)?.let { bound.add(it) }
        val devices = DeviceRepository.get(context).devices.value.filter { it.id in bound }
        if (devices.isEmpty()) return
        coroutineScope {
            devices.map { d -> async { d.id to Probe.isOpen(d.host, d.port, 1500) } }.awaitAll().forEach { (id, open) -> ports[id] = open }
        }
        probedAt = System.currentTimeMillis()
    }

    fun renderAll(context: Context) {
        val ids = widgetIds(context)
        if (ids.isNotEmpty()) render(context, ids)
    }

    fun render(context: Context, widgetIds: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        val repo = DeviceRepository.get(context)
        val reports = PcStateStore.get(context).reports.value
        val agents = AgentHub.presence.value
        val guard = GuardStore.get(context).data.value
        val thumbs = ThumbnailStore.get(context)
        val now = System.currentTimeMillis()
        for (widgetId in widgetIds) {
            val device = deviceFor(context, widgetId)?.let { id -> repo.devices.value.firstOrNull { it.id == id } }
            val views = if (device == null) {
                emptyViews(context, widgetId)
            } else {
                val card = CardData(
                    device = device,
                    status = PcStatusRules.derive(ports[device.id], device.id in agents, reports[device.id], now),
                    agent = agents[device.id],
                    guard = guard[device.id],
                    // Картинку не освобождать вручную: RemoteViews передаёт её системе только в updateAppWidget.
                    thumb = thumbs.loadBitmap(device.id) ?: placeholder(context, device.os),
                )
                // Узкий виджет — кнопки без подписей; лаунчер сам выбирает вариант по размеру.
                RemoteViews(
                    mapOf(
                        SizeF(0f, 0f) to cardViews(context, widgetId, card, labels = false),
                        SizeF(LABELS_MIN_WIDTH_DP, 0f) to cardViews(context, widgetId, card, labels = true),
                    ),
                )
            }
            runCatching { manager.updateAppWidget(widgetId, views) }
        }
    }

    /** Всё, что виджет показывает об одном ПК. */
    private class CardData(val device: Device, val status: PcStatus, val agent: AgentPresence?, val guard: GuardData?, val thumb: Bitmap)

    private fun emptyViews(context: Context, widgetId: Int) = RemoteViews(context.packageName, R.layout.widget_pc).apply {
        setViewVisibility(R.id.widget_card, View.GONE)
        setViewVisibility(R.id.widget_empty, View.VISIBLE)
        setOnClickPendingIntent(R.id.widget_empty, pickIntent(context, widgetId))
    }

    private fun cardViews(context: Context, widgetId: Int, card: CardData, labels: Boolean): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_pc)
        val device = card.device
        val state = card.status.state
        val windows = device.os == OsType.WINDOWS
        fun act(key: String, action: String, hint: ButtonHint? = null) = actionIntent(context, widgetId, device.id, key, action, hint)

        // Строка состояния: точка, текст, система, «Файлы» и щит (только у Windows), выбор ПК.
        val tone = toneColor(state.tone).toArgb()
        v.setColorStateList(R.id.widget_dot, "setImageTintList", ColorStateList.valueOf(tone))
        v.setTextViewText(R.id.widget_status, statusText(card.status))
        v.setTextColor(R.id.widget_status, if (state.tone == StatusTone.UNKNOWN || state.tone == StatusTone.DOWN) TextSecondary.toArgb() else tone)
        v.setImageViewResource(R.id.widget_os, if (device.os == OsType.UBUNTU) R.drawable.ic_widget_ubuntu else R.drawable.ic_widget_windows)
        v.setColorStateList(R.id.widget_os, "setImageTintList", ColorStateList.valueOf(Accent.toArgb()))
        v.setTextViewText(R.id.widget_os_label, device.osLabel)
        v.setViewVisibility(R.id.widget_files, if (windows) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.widget_guard, if (windows) View.VISIBLE else View.GONE)
        if (windows) {
            val files = FilesAccess.of(card.agent)
            val filesReady = files == FilesAccess.READY
            tint(v, R.id.widget_files, if (filesReady) TextSecondary.toArgb() else TextDisabled.toArgb())
            v.setOnClickPendingIntent(
                R.id.widget_files,
                if (filesReady) act("files", WidgetRequests.FILES) else act("files", WidgetRequests.HINT, ButtonHints.files(files)),
            )
            // Щит активен, когда агент на связи или уже присылал журнал; красный со счётчиком, пока есть угрозы.
            val guardKnown = card.guard != null || card.agent != null
            val threats = card.guard?.openThreats?.size ?: 0
            tint(v, R.id.widget_guard_icon, if (guardKnown && threats > 0) DangerRed.toArgb() else if (guardKnown) TextSecondary.toArgb() else TextDisabled.toArgb())
            v.setViewVisibility(R.id.widget_guard_count, if (guardKnown && threats > 0) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.widget_guard_count, threats.coerceAtMost(9).toString())
            v.setOnClickPendingIntent(
                R.id.widget_guard,
                if (guardKnown) act("guard", WidgetRequests.GUARD) else act("guard", WidgetRequests.HINT, ButtonHints.guard()),
            )
        }
        v.setOnClickPendingIntent(R.id.widget_pick, pickIntent(context, widgetId))

        // Превью: последний кадр стола или заглушка в стиле обоев, приглушённые у спящего/выключенного ПК — как на карточке.
        val offline = state.tone == StatusTone.ASLEEP || state.tone == StatusTone.DOWN
        v.setImageViewBitmap(R.id.widget_preview, card.thumb)
        v.setInt(R.id.widget_preview, "setImageAlpha", if (offline) 89 else 255)
        v.setContentDescription(R.id.widget_preview, "Подключиться к ${device.name}")
        if (offline) {
            v.setViewVisibility(R.id.widget_overlay, View.VISIBLE)
            v.setImageViewResource(
                R.id.widget_overlay_icon,
                when (state) {
                    PcState.SLEEP -> R.drawable.ic_widget_sleep
                    PcState.HIBERNATE -> R.drawable.ic_widget_hibernate
                    PcState.SHUTDOWN -> R.drawable.ic_widget_power
                    PcState.RESTARTING -> R.drawable.ic_widget_restart
                    else -> R.drawable.ic_widget_offline
                },
            )
            v.setTextViewText(R.id.widget_overlay_title, offlineTitle(state))
            val hint = offlineHint(device, state)
            v.setTextViewText(R.id.widget_overlay_hint, hint.orEmpty())
            v.setViewVisibility(R.id.widget_overlay_hint, if (hint == null || !labels) View.GONE else View.VISIBLE)
        }
        // Превью нажимается, когда можно подключиться или разбудить; иначе — подсказка, как на карточке.
        val connect = if (!offline || device.canWake) act("connect", WidgetRequests.CONNECT)
        else act("connect", WidgetRequests.HINT, ButtonHints.connect(state))
        v.setOnClickPendingIntent(R.id.widget_preview, connect)
        v.setOnClickPendingIntent(R.id.widget_overlay, connect)

        v.setTextViewText(R.id.widget_name, device.name)
        v.setTextViewText(R.id.widget_host, device.host)
        v.setContentDescription(R.id.widget_share, "Поделиться ПК «${device.name}»")
        v.setOnClickPendingIntent(R.id.widget_share, act("share", WidgetRequests.SHARE))

        // «Подключиться» / «Разбудить» и «Настройки».
        val agentPower = card.agent?.power
        val connectEnabled = CardActions.connectEnabled(device, state, agentPower)
        button(
            v, R.id.widget_btn_connect, R.id.widget_btn_connect_icon, R.id.widget_btn_connect_label, null,
            CardActions.connectLabel(state, agentPower), connectEnabled, primary = true, labels = labels,
            if (connectEnabled) act("btn_connect", WidgetRequests.CONNECT) else act("btn_connect", WidgetRequests.HINT, ButtonHints.connect(state)),
        )
        button(
            v, R.id.widget_btn_settings, R.id.widget_btn_settings_icon, R.id.widget_btn_settings_label, null,
            "Настройки", true, primary = false, labels = labels, act("settings", WidgetRequests.SETTINGS),
        )

        // Кнопки питания: с агентом — четыре режима, без него ещё «Выйти».
        val slots = CardActions.powerSlots(device, state, agentPower)
        POWER_IDS.forEachIndexed { i, ids ->
            val s = slots.getOrNull(i)
            v.setViewVisibility(ids[0], if (s == null) View.GONE else View.VISIBLE)
            if (s == null) return@forEachIndexed
            val action = when (val c = s.command) {
                is CardPower.Agent -> WidgetRequests.AGENT_PREFIX + c.power.name
                is CardPower.Session -> WidgetRequests.POWER_PREFIX + c.action.name
            }
            button(
                v, ids[0], ids[1], ids[2], powerIconRes(s.icon), s.label, s.enabled, primary = false, labels = labels,
                if (s.enabled) act("pw$i", action) else act("pw$i", WidgetRequests.HINT, s.hint),
            )
        }
        return v
    }

    private val POWER_IDS = listOf(
        intArrayOf(R.id.widget_pw0, R.id.widget_pw0_icon, R.id.widget_pw0_label),
        intArrayOf(R.id.widget_pw1, R.id.widget_pw1_icon, R.id.widget_pw1_label),
        intArrayOf(R.id.widget_pw2, R.id.widget_pw2_icon, R.id.widget_pw2_label),
        intArrayOf(R.id.widget_pw3, R.id.widget_pw3_icon, R.id.widget_pw3_label),
        intArrayOf(R.id.widget_pw4, R.id.widget_pw4_icon, R.id.widget_pw4_label),
    )

    private fun powerIconRes(icon: PowerIcon): Int = when (icon) {
        PowerIcon.SHUTDOWN -> R.drawable.ic_widget_power
        PowerIcon.RESTART -> R.drawable.ic_widget_restart
        PowerIcon.SLEEP -> R.drawable.ic_widget_sleep
        PowerIcon.HIBERNATE -> R.drawable.ic_widget_hibernate
        PowerIcon.LOGOUT -> R.drawable.ic_widget_logout
    }

    private fun tint(v: RemoteViews, id: Int, color: Int) = v.setColorStateList(id, "setImageTintList", ColorStateList.valueOf(color))

    /** Кнопка в стиле ActionButton карточки; неактивная остаётся нажимаемой — показывает подсказку. */
    private fun button(
        v: RemoteViews, id: Int, iconId: Int, labelId: Int, icon: Int?, label: String,
        enabled: Boolean, primary: Boolean, labels: Boolean, click: PendingIntent,
    ) {
        v.setInt(id, "setBackgroundResource", if (primary && enabled) R.drawable.widget_btn_primary_bg else R.drawable.widget_btn_bg)
        icon?.let { v.setImageViewResource(iconId, it) }
        tint(v, iconId, if (enabled) TextPrimary.toArgb() else TextDisabled.toArgb())
        v.setTextViewText(labelId, label)
        v.setTextColor(labelId, if (!enabled) TextDisabled.toArgb() else if (primary) TextPrimary.toArgb() else TextSecondary.toArgb())
        v.setViewVisibility(labelId, if (labels) View.VISIBLE else View.GONE)
        v.setViewLayoutHeight(id, if (labels) 60f else 44f, TypedValue.COMPLEX_UNIT_DIP)
        v.setContentDescription(id, label)
        v.setOnClickPendingIntent(id, click)
    }

    /** Заглушка превью, пока нет сохранённого кадра: градиент обоев Windows 11 / Ubuntu и логотип ОС (как PreviewBox). */
    private fun placeholder(context: Context, os: OsType): Bitmap {
        val w = 640
        val h = 400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val colors = if (os == OsType.UBUNTU) intArrayOf(0xFF5E2750.toInt(), 0xFFE95420.toInt(), 0xFF2C001E.toInt())
        else intArrayOf(0xFF0B3C9E.toInt(), 0xFF1E6BF2.toInt(), 0xFF0A1F5C.toInt())
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors, null, Shader.TileMode.CLAMP) })
        val logo = context.getDrawable(if (os == OsType.UBUNTU) R.drawable.ic_widget_ubuntu else R.drawable.ic_widget_windows)!!.mutate()
        val size = h * 22 / 100
        logo.setTint(0xD9FFFFFF.toInt())
        logo.setBounds((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2)
        logo.draw(canvas)
        return bmp
    }

    /**
     * Нажатие в виджете → [WidgetLaunchActivity]. Адрес `hiwebwidget://<виджет>/<кнопка>` делает намерения кнопок различимыми
     * для системы, иначе одинаковые `PendingIntent` разных кнопок слились бы в одно.
     */
    private fun actionIntent(context: Context, widgetId: Int, deviceId: String, key: String, action: String, hint: ButtonHint?): PendingIntent {
        val intent = Intent(context, WidgetLaunchActivity::class.java)
            .setData(Uri.parse("hiwebwidget://$widgetId/$key"))
            .putExtra(WidgetLaunchActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(WidgetLaunchActivity.EXTRA_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (hint != null) {
            intent.putExtra(WidgetLaunchActivity.EXTRA_HINT_TITLE, hint.title).putExtra(WidgetLaunchActivity.EXTRA_HINT_TEXT, hint.text)
        }
        return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun pickIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, PcWidgetConfigActivity::class.java)
            .setData(Uri.parse("hiwebwidget://$widgetId/pick"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
