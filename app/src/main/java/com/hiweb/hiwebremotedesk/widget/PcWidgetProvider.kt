package com.hiweb.hiwebremotedesk.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/** Виджет «ПК» (см. [PcWidgets]): система зовёт его при добавлении, раз в 30 мин и после перезагрузки планшета. */
class PcWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        // Сразу — с тем, что известно; затем ещё раз после проверки портов RDP.
        PcWidgets.render(context, widgetIds)
        val pending = goAsync()
        PcWidgets.refresh(context, probe = true) { pending.finish() }
        PcWidgets.watch(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, options: Bundle) {
        PcWidgets.render(context, intArrayOf(widgetId))
    }

    override fun onDeleted(context: Context, widgetIds: IntArray) {
        PcWidgets.forget(context, widgetIds)
    }
}
