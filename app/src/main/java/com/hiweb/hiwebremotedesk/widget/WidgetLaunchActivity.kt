package com.hiweb.hiwebremotedesk.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.hiweb.hiwebremotedesk.MainActivity
import com.hiweb.hiwebremotedesk.RemoteActivity
import com.hiweb.hiwebremotedesk.agent.AgentHub
import com.hiweb.hiwebremotedesk.agent.FilesAccess
import com.hiweb.hiwebremotedesk.agent.PcFilesLauncher
import com.hiweb.hiwebremotedesk.data.DeviceRepository
import com.hiweb.hiwebremotedesk.data.GuardNavigation
import com.hiweb.hiwebremotedesk.ui.ButtonHint
import com.hiweb.hiwebremotedesk.ui.ButtonHints

/**
 * Невидимая «прокладка» нажатий в виджете: решает в момент нажатия, а не при отрисовке виджета.
 * Подключение открывает сеанс сразу (уже идущий сеанс этого ПК поднимается, а не открывается второй), «Файлы» — файловый
 * менеджер; остальное (питание с подтверждением, настройки, «Поделиться», защита, подсказки) делает экран списка ПК
 * через [WidgetRequests]. ПК, удалённый из приложения, открывает список ПК.
 */
class WidgetLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle()
        finish()
    }

    private fun handle() {
        val id = intent.getStringExtra(EXTRA_DEVICE_ID)
        val device = id?.let { DeviceRepository.get(this).get(it) }
        if (device == null) return openMain()
        when (val action = intent.getStringExtra(EXTRA_ACTION) ?: WidgetRequests.CONNECT) {
            WidgetRequests.CONNECT ->
                // Без сохранённого пароля при NLA пароль спрашивает экран списка, как при нажатии на карточке.
                if (device.hasCredentials || !device.useNla) openSession(device.id) else forward(WidgetRequest(device.id, action))
            WidgetRequests.FILES -> {
                val presence = AgentHub.presence.value[device.id]
                val access = FilesAccess.of(presence)
                when {
                    access != FilesAccess.READY -> forward(WidgetRequest(device.id, WidgetRequests.HINT, ButtonHints.files(access)))
                    !PcFilesLauncher.open(this, device.id, presence) ->
                        forward(WidgetRequest(device.id, WidgetRequests.HINT, ButtonHints.filesManual(device.name)))
                }
            }
            WidgetRequests.GUARD -> {
                GuardNavigation.open.value = device.id
                openMain()
            }
            else -> {
                val title = intent.getStringExtra(EXTRA_HINT_TITLE)
                val hint = title?.let { ButtonHint(it, intent.getStringExtra(EXTRA_HINT_TEXT).orEmpty()) }
                forward(WidgetRequest(device.id, action, hint))
            }
        }
    }

    private fun openSession(id: String) {
        val intent = Intent(this, RemoteActivity::class.java)
            .putExtra(RemoteActivity.EXTRA_DEVICE_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Сеанс с этим ПК уже открыт: второй экран сеанса открыл бы второе подключение.
        if (RemoteActivity.openDeviceId == id) intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    private fun forward(request: WidgetRequest) {
        WidgetRequests.pending.value = request
        openMain()
    }

    /** Список ПК поднимается без очистки задачи: CLEAR_TOP закрыл бы открытый поверх него сеанс. */
    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_ACTION = "widget_action"
        const val EXTRA_HINT_TITLE = "hint_title"
        const val EXTRA_HINT_TEXT = "hint_text"
    }
}
