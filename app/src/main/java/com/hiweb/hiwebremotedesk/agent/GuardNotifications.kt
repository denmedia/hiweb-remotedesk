package com.hiweb.hiwebremotedesk.agent

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.hiweb.hiwebremotedesk.MainActivity
import com.hiweb.hiwebremotedesk.R
import com.hiweb.hiwebremotedesk.data.GuardText
import com.hiweb.hiwebremotedesk.data.GuardThreat

/**
 * Уведомления об угрозах ПК. Кнопки в уведомлении: «Заблокировать подсеть» (если на ПК включён брандмауэр — иначе
 * решение принимается в приложении, где можно включить его) и «Это я». Подтверждение блокировки — запрос Windows
 * на самом ПК, поэтому после нажатия уведомление сообщает, что агент ждёт его.
 */
object GuardNotifications {
    const val EXTRA_DEVICE = "guard_device"
    const val ACTION_BLOCK = "com.hiweb.hiwebremotedesk.GUARD_BLOCK"
    const val ACTION_TRUST = "com.hiweb.hiwebremotedesk.GUARD_TRUST"
    const val EXTRA_THREAT = "guard_threat"
    const val EXTRA_CIDR = "guard_cidr"
    const val EXTRA_IP = "guard_ip"

    private const val BASE_ID = 1000

    // Подсеть → угроза, чьё уведомление сейчас «ждёт подтверждения на ПК»: итог команды заменяет именно его.
    private val waiting = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun idFor(threatId: String): Int = BASE_ID + (threatId.hashCode() and 0x3fffffff)

    private fun openApp(context: Context, deviceId: String, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_DEVICE, deviceId)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(context: Context, name: String, deviceId: String, t: GuardThreat, requestCode: Int): PendingIntent {
        val intent = Intent(context, GuardActionReceiver::class.java).setAction(name)
            .putExtra(EXTRA_DEVICE, deviceId).putExtra(EXTRA_THREAT, t.id).putExtra(EXTRA_CIDR, t.cidr).putExtra(EXTRA_IP, t.ip)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun button(context: Context, title: String, intent: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_pc), title, intent).build()

    fun postThreat(context: Context, deviceId: String, deviceName: String, t: GuardThreat, firewallOn: Boolean) {
        if (!PcNotifications.canPost(context)) return
        PcNotifications.ensureChannels(context)
        val id = idFor(t.id)
        val text = GuardText.threatText(t) + if (firewallOn) "" else "\nБрандмауэр Windows на ПК выключен: блокировка заработает, только если его включить."
        val builder = Notification.Builder(context, PcNotifications.CHANNEL_SECURITY)
            .setSmallIcon(R.drawable.ic_stat_pc)
            .setContentTitle("${GuardText.threatTitle(t)}: $deviceName")
            .setContentText(GuardText.threatText(t))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText(deviceName)
            .setWhen(t.time)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, deviceId, id))
        val review = openApp(context, deviceId, id + 1)
        builder.addAction(
            if (firewallOn) button(context, "Заблокировать ${t.cidr}", action(context, ACTION_BLOCK, deviceId, t, id + 2))
            else button(context, "Решить…", review),
        )
        builder.addAction(button(context, "Это я", action(context, ACTION_TRUST, deviceId, t, id + 3)))
        context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }

    /** Заменяет уведомление угрозы состоянием команды: ждём подтверждения на ПК. */
    fun postWaiting(context: Context, deviceId: String, threatId: String, cidr: String) {
        if (!PcNotifications.canPost(context)) return
        val id = idFor(threatId)
        waiting[cidr] = threatId
        val n = Notification.Builder(context, PcNotifications.CHANNEL_SECURITY)
            .setSmallIcon(R.drawable.ic_stat_pc)
            .setContentTitle("Блокировка $cidr")
            .setContentText("Подтвердите запрос Windows на ПК (права администратора)")
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, deviceId, id))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** Итог команды агента: короткое уведомление, если оно не про отмену самим пользователем. */
    fun postResult(context: Context, deviceId: String, deviceName: String, r: GuardResult) {
        if (r.action != "block") return
        waiting.remove(r.cidr)?.let { cancel(context, it) }
        if (!PcNotifications.canPost(context)) return
        PcNotifications.ensureChannels(context)
        val text = if (r.ok) "Подсеть ${r.cidr} заблокирована" else r.error.ifBlank { "Не удалось заблокировать ${r.cidr}" }
        val n = Notification.Builder(context, PcNotifications.CHANNEL_SECURITY)
            .setSmallIcon(R.drawable.ic_stat_pc)
            .setContentTitle(deviceName.ifBlank { "ПК" })
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, deviceId, idFor("result|" + r.cidr)))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(idFor("result|" + r.cidr), n)
    }

    fun cancel(context: Context, threatId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(idFor(threatId))
    }
}

/** Кнопки в уведомлении об угрозе: команда уходит агенту, дальше — подтверждение на ПК. */
class GuardActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getStringExtra(GuardNotifications.EXTRA_DEVICE) ?: return
        val threat = intent.getStringExtra(GuardNotifications.EXTRA_THREAT) ?: return
        val sent = when (intent.action) {
            GuardNotifications.ACTION_BLOCK -> {
                val cidr = intent.getStringExtra(GuardNotifications.EXTRA_CIDR) ?: return
                GuardHub.block(device, cidr, enableFirewall = false).also { ok -> if (ok) GuardNotifications.postWaiting(context, device, threat, cidr) }
            }
            GuardNotifications.ACTION_TRUST -> {
                val ip = intent.getStringExtra(GuardNotifications.EXTRA_IP) ?: return
                GuardHub.trust(device, ip).also { ok -> if (ok) GuardNotifications.cancel(context, threat) }
            }
            else -> return
        }
        if (!sent) {
            // Агент не на связи: уведомление остаётся, объясняем, почему кнопка не сработала.
            android.widget.Toast.makeText(context, "Агент на ПК сейчас недоступен — попробуйте, когда планшет в одной сети с ПК или в сеансе", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
