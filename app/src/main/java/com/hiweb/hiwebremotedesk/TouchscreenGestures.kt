package com.hiweb.hiwebremotedesk

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Жесты тремя, четырьмя и пятью пальцами на сенсорном экране в духе iPadOS, переведённые в сочетания клавиш ПК:
 *  - три пальца: свайп влево — отменить (Ctrl+Z), вправо — повторить (Windows Ctrl+Y, GNOME Ctrl+Shift+Z);
 *    щипок — копировать (Ctrl+C), разведение — вставить (Ctrl+V), второй щипок подряд — вырезать (Ctrl+X);
 *  - четыре или пять пальцев: свайп влево-вправо — переключение приложений (Alt+Tab / Alt+Shift+Tab),
 *    вверх — «домой» (Windows: рабочий стол Win+D, GNOME: обзор, клавиша Super);
 *  - четыре или пять пальцев щипком — переключатель приложений (Win+Tab / Super).
 * Тачпад этим классом не обрабатывается. Расстояния в пикселях экрана, [density] — пикселей на dp.
 */
class TouchscreenGestures(
    private val out: GestureOutput,
    density: Float = 1f,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var desktop = DesktopProfile.WINDOWS

    private val swipePx = SWIPE_DP * density
    private var fingers = 0
    private var startX = 0f
    private var startY = 0f
    private var startSpread = 0f
    private var curX = 0f
    private var curY = 0f
    private var curSpread = 0f
    private var lastPinchInAt: Long? = null

    /** Касание тремя и более пальцами началось: [spread] — средняя дистанция пальцев от их центра. */
    fun begin(count: Int, x: Float, y: Float, spread: Float) {
        fingers = count
        startX = x; startY = y; startSpread = spread
        curX = x; curY = y; curSpread = spread
    }

    /** Новое число пальцев (до пяти) уточняет жест: больше пальцев — другая группа действий. */
    fun update(count: Int, x: Float, y: Float, spread: Float) {
        if (count > fingers) {
            // Пальцы приходят не одновременно: пока центр не уехал, считаем жест заново с полным числом пальцев.
            fingers = count
            startX = x; startY = y; startSpread = spread
        }
        curX = x; curY = y; curSpread = spread
    }

    /** Пальцы подняты: если смещение или щипок достаточны, выполняется действие; true — жест распознан. */
    fun end(): Boolean {
        val n = fingers
        fingers = 0
        if (n < 3) return false
        val dx = curX - startX
        val dy = curY - startY
        val travel = hypot(dx, dy)
        val spreadChange = curSpread - startSpread
        val pinch = abs(spreadChange) >= PINCH_DP_RATIO * swipePx && abs(spreadChange) > travel
        if (pinch) {
            pinch(n, spreadChange < 0)
            return true
        }
        if (travel < swipePx) return false
        if (abs(dx) >= abs(dy)) horizontalSwipe(n, dx < 0) else if (dy < 0) swipeUp(n)
        return true
    }

    fun cancel() {
        fingers = 0
    }

    private fun pinch(n: Int, inward: Boolean) {
        when {
            n == 3 && inward -> {
                // Второй щипок подряд вырезает: выделение уже скопировано, остаётся его убрать.
                val now = clock()
                val second = lastPinchInAt?.let { now - it <= DOUBLE_PINCH_MS } == true
                lastPinchInAt = if (second) null else now
                combo(KEY_CTRL to false, (if (second) KEY_X else KEY_C) to false)
            }
            n == 3 -> combo(KEY_CTRL to false, KEY_V to false)
            inward -> overview()
        }
    }

    private fun horizontalSwipe(n: Int, toLeft: Boolean) {
        if (n == 3) {
            if (toLeft) combo(KEY_CTRL to false, KEY_Z to false)
            else if (desktop == DesktopProfile.GNOME) combo(KEY_CTRL to false, KEY_SHIFT to false, KEY_Z to false)
            else combo(KEY_CTRL to false, KEY_Y to false)
        } else if (toLeft) {
            combo(KEY_ALT to false, KEY_TAB to false)
        } else {
            combo(KEY_ALT to false, KEY_SHIFT to false, KEY_TAB to false)
        }
    }

    private fun swipeUp(n: Int) {
        if (n == 3) return
        if (desktop == DesktopProfile.GNOME) combo(KEY_WIN to true) else combo(KEY_WIN to true, KEY_D to false)
    }

    private fun overview() {
        if (desktop == DesktopProfile.GNOME) combo(KEY_WIN to true) else combo(KEY_WIN to true, KEY_TAB to false)
    }

    private fun combo(vararg keys: Pair<Int, Boolean>) {
        keys.forEach { (code, ext) -> out.key(code, ext, true) }
        keys.reversed().forEach { (code, ext) -> out.key(code, ext, false) }
    }

    companion object {
        /** Минимальное смещение центра пальцев для свайпа. */
        const val SWIPE_DP = 56f

        /** Изменение средней дистанции пальцев (в долях порога свайпа), с которого движение считается щипком. */
        private const val PINCH_DP_RATIO = 0.6f

        /** Два щипка тремя пальцами в пределах этого времени — вырезать. */
        private const val DOUBLE_PINCH_MS = 1500L

        private const val KEY_CTRL = 0x1D
        private const val KEY_SHIFT = 0x2A
        private const val KEY_ALT = 0x38
        private const val KEY_WIN = 0x5B
        private const val KEY_TAB = 0x0F
        private const val KEY_D = 0x20
        private const val KEY_Z = 0x2C
        private const val KEY_Y = 0x15
        private const val KEY_X = 0x2D
        private const val KEY_C = 0x2E
        private const val KEY_V = 0x2F
    }
}
