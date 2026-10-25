package com.hiweb.hiwebremotedesk.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После перезагрузки планшета снова ждём команду «Переключиться на Планшет», если ПК привязаны. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            SwitchListenerService.startIfPaired(context)
        }
    }
}
