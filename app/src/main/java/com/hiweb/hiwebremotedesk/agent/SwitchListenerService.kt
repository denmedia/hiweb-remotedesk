package com.hiweb.hiwebremotedesk.agent

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.hiweb.hiwebremotedesk.MainActivity
import com.hiweb.hiwebremotedesk.R
import com.hiweb.hiwebremotedesk.RemoteActivity
import com.hiweb.hiwebremotedesk.data.DeviceRepository
import com.hiweb.hiwebremotedesk.data.PcStateStore
import com.hiweb.hiwebremotedesk.data.ThumbnailStore
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Ждёт команду «Переключиться на Планшет» от HiWeb Agent по локальной сети (UDP [AgentPairing.PORT]).
 * Работает, только пока хотя бы один ПК привязан к агенту. Подлинную команду подтверждает и открывает
 * сеанс этого ПК ([SwitchLauncher]).
 */
class SwitchListenerService : Service() {
    @Volatile private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val command = SwitchCommand()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PcNotifications.ensureChannels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, PcNotifications.CHANNEL_SWITCH)
            .setSmallIcon(R.drawable.ic_stat_pc)
            .setContentTitle("Готов к переключению с ПК")
            .setContentText("«Переключиться на Планшет» в меню HiWeb Agent откроет сеанс")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(PcNotifications.SWITCH_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // Без этой блокировки Wi-Fi-адаптер при выключенном экране отбрасывает широковещательные пакеты.
        multicastLock = getSystemService(WifiManager::class.java)?.createMulticastLock("hiweb-switch")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        Thread(::listen, "switch-listener").apply { isDaemon = true }.start()
        // Агенты на ПК сами подключаются сюда по TCP: так список ПК знает, где доступны команды питания.
        val repo = DeviceRepository.get(this)
        val pairing = AgentPairing(this)
        val thumbnails = ThumbnailStore.get(this)
        Thread({
            AgentHub.serve(
                pairing,
                knownDevice = { repo.get(it) != null },
                onScreen = { pc, jpeg -> runCatching { thumbnails.saveJpeg(pc, jpeg) } },
            ) { pc, msg -> PcStateStore.get(this).report(pc, msg) }
        }, "agent-server").apply { isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AgentPairing(this).hasAny()) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { socket?.close() }
        AgentHub.stop()
        runCatching { multicastLock?.release() }
        super.onDestroy()
    }

    private fun listen() {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(AgentPairing.PORT))
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot bind UDP ${AgentPairing.PORT}", e)
            return
        }
        socket = s
        val buffer = ByteArray(2048)
        while (!s.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: Exception) {
                break
            }
            runCatching { handle(s, packet) }.onFailure { Log.w(TAG, "bad switch packet", it) }
        }
    }

    private fun handle(s: DatagramSocket, packet: DatagramPacket) {
        val msg = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
        // «switch» — открыть сеанс ПК; «where» — агент ищет планшет, чтобы подключиться к нему по TCP.
        val type = msg.optString("t")
        val replyType = when (type) { "switch" -> "ack"; "where" -> "here"; else -> return }
        val pc = msg.optString("pc")
        val nonce = msg.optString("nonce")
        val secret = AgentPairing(this).existingSecret(pc) ?: return
        if (DeviceRepository.get(this).get(pc) == null) return
        val result = command.check(pc, msg.optLong("ts"), nonce, msg.optString("mac"), secret, System.currentTimeMillis(), type)
        if (result == SwitchCommand.Result.INVALID) {
            Log.w(TAG, "rejected switch command from ${packet.address}")
            return
        }
        val reply = JSONObject().put("t", replyType).put("nonce", nonce).put("mac", SwitchCommand.replyMac(secret, replyType, nonce))
            .toString().toByteArray()
        s.send(DatagramPacket(reply, reply.size, packet.socketAddress))
        if (type == "switch" && result == SwitchCommand.Result.NEW) {
            Log.i(TAG, "switch to $pc requested by ${packet.address}")
            SwitchLauncher.open(this, pc)
        }
    }

    companion object {
        private const val TAG = "Agent"

        /** Запускает службу, если есть привязанные ПК (после установки агента, запуска приложения, перезагрузки). */
        fun startIfPaired(context: Context) {
            if (!AgentPairing(context).hasAny()) return
            runCatching { context.startForegroundService(Intent(context, SwitchListenerService::class.java)) }
                .onFailure { Log.w(TAG, "switch listener not started", it) }
        }
    }
}

/** Открывает сеанс ПК по команде с ПК, когда приложение в фоне или экран планшета выключен. */
object SwitchLauncher {
    fun open(context: Context, deviceId: String) {
        val intent = Intent(context, RemoteActivity::class.java)
            .putExtra(RemoteActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(RemoteActivity.EXTRA_SWITCH, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        // Из фона Android разрешает открыть окно приложению с правом «Поверх других приложений».
        if (Settings.canDrawOverlays(context)) {
            runCatching { context.startActivity(intent) }.onSuccess { return }
        }
        // Иначе — уведомление: полноэкранное (если разрешено) открывает сеанс само, обычное — по нажатию.
        PcNotifications.ensureChannels(context)
        val pending = PendingIntent.getActivity(context, deviceId.hashCode() + 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val name = DeviceRepository.get(context).get(deviceId)?.name.orEmpty()
        val nm = context.getSystemService(NotificationManager::class.java)
        val builder = Notification.Builder(context, PcNotifications.CHANNEL_PC)
            .setSmallIcon(R.drawable.ic_stat_pc)
            .setContentTitle("Переключиться на планшет")
            .setContentText("$name: нажмите, чтобы открыть сеанс")
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
        if (nm.canUseFullScreenIntent()) builder.setFullScreenIntent(pending, true)
        if (PcNotifications.canPost(context)) nm.notify(PcNotifications.SWITCH_REQUEST_ID, builder.build())
    }
}
