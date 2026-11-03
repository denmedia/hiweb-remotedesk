package com.hiweb.hiwebremotedesk.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract

/** Открывает системный файловый менеджер на «диске» ПК (первом из открытых папок); остальные диски — в его боковой панели. */
object PcFilesLauncher {
    /**
     * Возвращает true, если файловый менеджер открылся. Приложение «Файлы», понимающее переход на корень, есть не на всех
     * прошивках: тогда вызывающий подскажет, где искать диск вручную.
     */
    fun open(context: Context, deviceId: String, presence: AgentPresence?): Boolean {
        val share = presence?.files?.takeIf { it.enabled }?.shares?.firstOrNull() ?: return false
        val rootUri = DocumentsContract.buildRootUri(PcDocumentsProvider.authority(context), PcDocId.root(deviceId, share.id).rootId)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
