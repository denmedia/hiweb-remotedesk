package com.hiweb.hiwebremotedesk.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.hiweb.hiwebremotedesk.R
import com.hiweb.hiwebremotedesk.RemoteActivity

/** Уведомления на планшете: пересланные с ПК и постоянное «сеанс активен» фоновой службы. */
object PcNotifications {
    const val CHANNEL_PC = "pc_notifications"
    const val CHANNEL_SESSION = "session"
    const val CHANNEL_SWITCH = "switch_listener"
    const val CHANNEL_SECURITY = "pc_security"
    const val SESSION_NOTIFICATION_ID = 1
    const val SWITCH_NOTIFICATION_ID = 2
    const val SWITCH_REQUEST_ID = 3
    private val RESERVED_IDS = setOf(SESSION_NOTIFICATION_ID, SWITCH_NOTIFICATION_ID, SWITCH_REQUEST_ID)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PC, "Уведомления с ПК", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Уведомления Windows, пока приложение свёрнуто (нужен HiWeb Agent на ПК)"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SECURITY, "Безопасность ПК", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Подбор пароля и входы по RDP с неизвестных адресов (нужен HiWeb Agent на ПК)"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, "Активный сеанс", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Удалённый сеанс продолжается в свёрнутом приложении"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SWITCH, "Переключение с ПК", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Ожидание команды «Переключиться на Планшет» из меню HiWeb Agent"
                setShowBadge(false)
            },
        )
    }

    fun canPost(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Приложение сейчас на экране: уведомления с ПК не нужны, их заменяет сам сеанс. */
    const val BLOCKED_VISIBLE = "visible"

    /** Приложению запрещены уведомления (разрешение не выдано или отключены в настройках Android). */
    const val BLOCKED_PERMISSION = "permission"

    /** Канал «Уведомления с ПК» отключён в настройках Android. */
    const val BLOCKED_CHANNEL = "channel"

    /** Почему уведомление с ПК не будет показано ([BLOCKED_PERMISSION], [BLOCKED_CHANNEL]); null — показать можно. */
    fun blocker(context: Context): String? {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!canPost(context) || !nm.areNotificationsEnabled()) return BLOCKED_PERMISSION
        ensureChannels(context)
        if (nm.getNotificationChannel(CHANNEL_PC)?.importance == NotificationManager.IMPORTANCE_NONE) return BLOCKED_CHANNEL
        return null
    }

    /** Возврат в сеанс: существующий экран сеанса поднимается наверх, а не открывается заново. */
    fun sessionIntent(context: Context, deviceId: String): PendingIntent {
        val intent = Intent(context, RemoteActivity::class.java)
            .putExtra(RemoteActivity.EXTRA_DEVICE_ID, deviceId)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, deviceId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun post(context: Context, deviceId: String, deviceName: String, n: PcNotification) {
        if (!canPost(context)) return
        val title = listOf(n.app, n.title).filter { it.isNotBlank() }.joinToString(": ").ifBlank { deviceName }
        val notification = Notification.Builder(context, CHANNEL_PC)
            .setSmallIcon(R.drawable.ic_stat_pc)
            .setLargeIcon(NotificationIcon.compose(context, n.iconPng))
            .setContentTitle(title)
            .setContentText(n.text)
            .setStyle(Notification.BigTextStyle().bigText(n.text))
            .setSubText(deviceName)
            .setWhen(if (n.timeMs > 0) n.timeMs else System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setGroup("pc_$deviceId")
            .setContentIntent(sessionIntent(context, deviceId))
            .build()
        // id уведомления Windows уникален в пределах ПК; смешиваем с устройством, чтобы ПК не перекрывали друг друга.
        val id = (deviceId.hashCode() * 31 + n.id.hashCode()).let { if (it in RESERVED_IDS) it + 10 else it }
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}
