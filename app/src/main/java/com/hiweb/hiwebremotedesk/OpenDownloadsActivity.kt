package com.hiweb.hiwebremotedesk

import android.app.Activity
import android.os.Bundle
import com.hiweb.hiwebremotedesk.data.DownloadsFolder

/**
 * Невидимая «прокладка» для уведомления «Версия скачана»: открывает папку Downloads в файловом менеджере
 * и сразу закрывается. Через неё, а не напрямую из `PendingIntent`, чтобы при отказе файлового менеджера
 * принять адрес папки можно было попробовать запасной вариант ([DownloadsFolder.open]).
 */
class OpenDownloadsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadsFolder.open(this)
        finish()
    }
}
