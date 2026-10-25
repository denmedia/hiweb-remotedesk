package com.hiweb.hiwebremotedesk

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Мост между службой и открытым экраном удалённого сеанса. */
object KeyForwarder {
    /** Обработчик клавиш; задан только пока экран сеанса на переднем плане. Возвращает true, если клавиша ушла на ПК. */
    @Volatile var handler: ((KeyEvent) -> Boolean)? = null

    @Volatile var serviceConnected = false
}

/**
 * Служба специальных возможностей, получающая клавиши раньше системы. Это единственный способ доставить
 * на удалённый ПК сочетания, которые Android обрабатывает сам (клавиша Win, Alt+Tab и др.).
 * Вне экрана удалённого сеанса обработчик не задан, и служба клавиши не трогает.
 */
class KeyCaptureService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        KeyForwarder.serviceConnected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        KeyForwarder.serviceConnected = false
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = KeyForwarder.handler?.invoke(event) ?: false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
