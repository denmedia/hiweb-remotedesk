package com.hiweb.hiwebremotedesk.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.app.DownloadManager
import com.hiweb.hiwebremotedesk.OpenDownloadsActivity
import com.hiweb.hiwebremotedesk.R
import com.hiweb.hiwebremotedesk.agent.PcNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Что показывает окно «Проверить обновление». */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateState
    data class Downloaded(val release: ReleaseInfo) : UpdateState

    /** [release] не null, если упало именно скачивание: «Повторить» тогда качает заново, а не проверяет. */
    data class Failed(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * Ручное обновление: проверка по GitHub ([AppUpdate]) и скачивание APK в папку Downloads.
 * Установку пользователь делает сам. Работает в собственной области, а не в окне: скачивание
 * продолжается, если окно закрыли, а прогресс виден в уведомлении.
 */
object UpdateManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** Новая проверка; идущее скачивание не прерывает. */
    fun check(context: Context) {
        if (_state.value is UpdateState.Downloading) return
        val version = currentVersion(context)
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = when (val r = AppUpdate.check(version)) {
                UpdateCheck.UpToDate -> UpdateState.UpToDate
                is UpdateCheck.Available -> UpdateState.Available(r.release)
                is UpdateCheck.Failed -> UpdateState.Failed(r.message)
            }
        }
    }

    /** «Повторить» после ошибки: скачивание — заново, проверка — заново. */
    fun retry(context: Context) {
        val failed = _state.value as? UpdateState.Failed
        if (failed?.release != null) download(context, failed.release) else check(context)
    }

    fun download(context: Context, release: ReleaseInfo) {
        if (_state.value is UpdateState.Downloading) return
        val app = context.applicationContext
        _state.value = UpdateState.Downloading(release, 0)
        UpdateNotifications.progress(app, release, 0)
        scope.launch {
            var lastPercent = 0
            try {
                saveToDownloads(app, release) { percent ->
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _state.value = UpdateState.Downloading(release, percent)
                        UpdateNotifications.progress(app, release, percent)
                    }
                }
                _state.value = UpdateState.Downloaded(release)
                UpdateNotifications.done(app, release)
            } catch (e: Exception) {
                UpdateNotifications.cancel(app)
                _state.value = UpdateState.Failed("Не удалось скачать: ${e.message ?: "ошибка сети"}", release)
            }
        }
    }

    /** Качает APK в `Download/` через MediaStore (на Android 13+ разрешений не нужно), проверяет размер и SHA-256. */
    private fun saveToDownloads(context: Context, r: ReleaseInfo, onPercent: (Int) -> Unit) {
        val resolver = context.contentResolver
        // Прежний файл с тем же именем от нашего приложения убираем, чтобы не плодить «(1)».
        runCatching {
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(r.apkName, Environment.DIRECTORY_DOWNLOADS + "%"),
            )
        }
        val uri: Uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, r.apkName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: throw IOException("Файл не удалось создать в папке Downloads")
        try {
            val c = URL(r.apkUrl).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "HiWebRemoteDesk")
                if (c.responseCode != 200) throw IOException("GitHub ответил ошибкой ${c.responseCode}")
                val total = if (r.size > 0) r.size else c.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                c.inputStream.use { input ->
                    resolver.openOutputStream(uri)!!.use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            written += n
                            if (total > 0) onPercent((written * 100 / total).toInt().coerceIn(0, 99))
                        }
                    }
                }
                if (r.size > 0 && written != r.size) throw IOException("Файл скачан не полностью")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (r.sha256 != null && hash != r.sha256) throw IOException("Файл повреждён: не сошлась контрольная сумма")
            } finally {
                c.disconnect()
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}

/** Уведомления о скачивании обновления: прогресс и «готово» (нажатие открывает папку Downloads). */
object UpdateNotifications {
    private const val CHANNEL = "app_update"
    private const val ID = 0x7E01

    private fun nm(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Скачивание новой версии приложения"
                setShowBadge(false)
            },
        )
        return nm
    }

    fun progress(context: Context, r: ReleaseInfo, percent: Int) {
        if (!PcNotifications.canPost(context)) return
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Скачивание версии ${r.version}")
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        nm(context).notify(ID, n)
    }

    fun done(context: Context, r: ReleaseInfo) {
        if (!PcNotifications.canPost(context)) return
        val open = PendingIntent.getActivity(
            context, ID, Intent(context, OpenDownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pc)
            .setContentTitle("Версия ${r.version} скачана")
            .setContentText("Нажмите, чтобы открыть папку Downloads и установить ${r.apkName}")
            .setStyle(Notification.BigTextStyle().bigText("Нажмите, чтобы открыть папку Downloads и установить ${r.apkName}"))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm(context).notify(ID, n)
    }

    fun cancel(context: Context) = nm(context).cancel(ID)
}

/** Открывает папку Downloads в файловом менеджере по умолчанию; если он не понимает адрес папки — список загрузок. */
object DownloadsFolder {
    fun open(context: Context): Boolean {
        val folder = Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                "vnd.android.document/directory",
            )
        val list = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        for (intent in listOf(folder, list)) {
            val ok = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return true
        }
        return false
    }
}
