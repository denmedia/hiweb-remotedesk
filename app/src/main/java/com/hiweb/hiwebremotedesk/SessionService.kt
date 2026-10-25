package com.hiweb.hiwebremotedesk

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.hiweb.hiwebremotedesk.agent.PcNotifications

/**
 * Служба переднего плана на время сеанса: без неё Android замораживает свёрнутое приложение, соединение
 * с ПК перестаёт читаться и обрывается, а уведомления с ПК не доходят. Сама служба ничего не делает.
 */
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
        val name = intent?.getStringExtra(EXTRA_DEVICE_NAME).orEmpty()
        if (deviceId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        PcNotifications.ensureChannels(this)
        val notification = Notification.Builder(this, PcNotifications.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_pc)
            .setContentTitle("Сеанс с $name активен")
            .setContentText("Нажмите, чтобы вернуться к рабочему столу")
            .setOngoing(true)
            .setContentIntent(PcNotifications.sessionIntent(this, deviceId))
            .build()
        startForeground(PcNotifications.SESSION_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_DEVICE_NAME = "device_name"

        fun start(context: Context, deviceId: String, deviceName: String) {
            val intent = Intent(context, SessionService::class.java)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_DEVICE_NAME, deviceName)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionService::class.java))
        }
    }
}
