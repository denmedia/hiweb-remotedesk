package com.hiweb.hiwebremotedesk

import kotlin.math.abs
import kotlin.math.ln

/** Куда жесты тачпада отправляют результат (на удалённый ПК). */
interface GestureOutput {
    /** Колесо мыши. [units] в единицах RDP: 120 — один щелчок; > 0 — вверх/вправо. */
    fun wheel(vertical: Boolean, units: Int)

    /** Нажатие или отпускание клавиши по скан-коду. */
    fun key(code: Int, extended: Boolean, down: Boolean)
}

/** Какой рабочий стол на удалённой стороне: от него зависят сочетания клавиш для свайпов несколькими пальцами. */
enum class DesktopProfile { WINDOWS, GNOME }

/**
 * Жесты тачпада в духе macOS, переведённые в действия удалённого рабочего стола:
 *  - два пальца вверх-вниз и влево-вправо — вертикальная и горизонтальная прокрутка;
 *  - быстрый горизонтальный свайп двумя пальцами — назад или вперёд в браузере (Alt+стрелка): вправо — назад, влево — вперёд;
 *  - щипок двумя пальцами — масштаб (Ctrl + колесо);
 *  - свайп тремя или четырьмя пальцами (Windows / GNOME в Ubuntu):
 *    вверх — обзор окон (Win+Tab / Super), вниз — рабочий стол (Win+D) или сетка приложений (Super+A),
 *    влево-вправо — переключение рабочих столов (Ctrl+Win+стрелка / Ctrl+Alt+стрелка).
 * Нажатия, двойное нажатие и правая кнопка приходят от системы как обычные кнопки мыши.
 */
class TouchpadGestures(
    private val out: GestureOutput,
    private val swipeThreshold: Float = SWIPE_THRESHOLD,
) {
    /** Сочетания клавиш для свайпов несколькими пальцами. */
    var desktop = DesktopProfile.WINDOWS

    private var remV = 0f
    private var remH = 0f

    private var pinchAccum = 0f
    private var pinchArmed = false
    private var ctrlDown = false

    private var swipeX = 0f
    private var swipeY = 0f
    private var swipeFingers = 0

    /** Сила прокрутки пальцами (1 — по умолчанию); задаётся в настройках приложения. */
    var scrollStrength = 1f

    /** Сила инерции прокрутки (1 — по умолчанию); задаётся в настройках приложения. */
    var inertiaStrength = 1f

    /** Базовый коэффициент источника последней прокрутки пальцами (тачпад ослаблен вдвое, сенсорный экран — нет). */
    private var sourceGain = 1f

    private enum class Axis { UNDECIDED, VERTICAL, HORIZONTAL }

    private var axis = Axis.UNDECIDED

    // Сдвиг двух пальцев с начала жеста в вертикальных щелчках: до выбора оси и (по горизонтали) для навигации.
    private var accV = 0f
    private var accH = 0f
    private val pendingTimes = ArrayList<Long>()
    private val pendingV = ArrayList<Float>()

    // Скорость инерции измеряется по движению пальцев, а коэффициенты прокрутки применяются при выводе:
    // так сила прокрутки не влияет на порог запуска инерции, но пропорционально меняет её длину.
    private val fling = ScrollFling { vertical, notches -> scrollNotches(vertical, notches * sourceGain * scrollStrength) }

    /** Идёт инерционная прокрутка: нужно вызывать [flingStep] каждый кадр. */
    val isFlinging: Boolean get() = fling.isActive

    /** Текущая скорость инерции, щелчков в секунду (0 — инерции нет). */
    val flingSpeed: Float get() = fling.speed

    // ------------------------------------------------------------------ прокрутка

    /** Прокрутка в «щелчках» колеса (дробные значения накапливаются). */
    fun scrollNotches(vertical: Boolean, notches: Float) {
        val units = notches * UNITS_PER_NOTCH
        if (vertical) remV = emit(true, remV + units) else remH = emit(false, remH + units)
    }

    /**
     * Прокрутка пальцами: как [scrollNotches], но движение запоминается, чтобы после отрыва пальцев
     * продолжиться по инерции (см. [scrollRelease]). Положительные значения — вверх/вправо.
     */
    fun fingerScroll(timeMs: Long, vNotches: Float, hNotches: Float, gain: Float = 1f, hPerV: Float = 1f) {
        if (axis == Axis.UNDECIDED) {
            // Ось жеста выбирается по первым сдвигам: вертикаль — прокрутка, горизонталь — навигация назад/вперёд.
            accV += vNotches
            accH += hNotches * hPerV
            pendingTimes += timeMs
            pendingV += vNotches
            if (maxOf(abs(accV), abs(accH)) < AXIS_LOCK_NOTCHES) return
            axis = if (abs(accH) > abs(accV) * HORIZONTAL_BIAS) Axis.HORIZONTAL else Axis.VERTICAL
            if (axis == Axis.VERTICAL) {
                // Накопленное до выбора оси отдаётся одним движением, а отсчёты скорости для инерции — как были.
                for (i in pendingTimes.indices) fling.onSample(pendingTimes[i], pendingV[i], 0f)
                sourceGain = gain
                scrollNotches(true, accV * gain * scrollStrength)
            }
            pendingTimes.clear()
            pendingV.clear()
            return
        }
        when (axis) {
            Axis.VERTICAL -> emitScroll(timeMs, vNotches, gain)
            Axis.HORIZONTAL -> accH += hNotches * hPerV
            Axis.UNDECIDED -> Unit
        }
    }

    private fun emitScroll(timeMs: Long, vNotches: Float, gain: Float) {
        sourceGain = gain
        fling.onSample(timeMs, vNotches, 0f)
        if (vNotches != 0f) scrollNotches(true, vNotches * gain * scrollStrength)
    }

    /**
     * Прокрутка одним или двумя пальцами по сенсорному экрану: обе оси сразу, без выбора оси и навигации назад/вперёд,
     * с инерцией. Положительные значения — вверх/вправо.
     */
    fun touchScroll(timeMs: Long, vNotches: Float, hNotches: Float) {
        sourceGain = 1f
        fling.onSample(timeMs, vNotches, hNotches)
        if (vNotches != 0f) scrollNotches(true, vNotches * scrollStrength)
        if (hNotches != 0f) scrollNotches(false, hNotches * scrollStrength)
    }

    /** Два пальца на тачпаде: расстояния в пикселях, как в AXIS_GESTURE_SCROLL_*_DISTANCE. */
    fun scrollDistance(distX: Float, distY: Float, timeMs: Long = 0L) {
        fingerScroll(
            timeMs, distY * SCROLL_Y_SIGN / PIXELS_PER_NOTCH_V, distX * SCROLL_X_SIGN / PIXELS_PER_NOTCH_H,
            TOUCHPAD_BASE_GAIN, PIXELS_PER_NOTCH_H / PIXELS_PER_NOTCH_V,
        )
    }

    /** Пальцы оторваны: прокрутка продолжается по инерции, если они двигались достаточно быстро. */
    fun scrollRelease(timeMs: Long) {
        if (axis == Axis.HORIZONTAL) {
            // Палец вправо — накопленное значение отрицательное (см. знаки прокрутки): это «назад».
            if (abs(accH) >= NAV_NOTCHES) navigate(back = accH < 0)
            fling.reset()
        } else {
            fling.tauScale = inertiaStrength
            fling.release(timeMs)
        }
        scrollEnd()
    }

    private fun navigate(back: Boolean) = combo(KEY_ALT to false, (if (back) KEY_LEFT else KEY_RIGHT) to true)

    /** Шаг инерции; возвращает true, пока прокрутка продолжается. */
    fun flingStep(nowMs: Long): Boolean = fling.step(nowMs)

    /** Новое касание останавливает инерцию. */
    fun stopFling() = fling.stop()

    fun scrollEnd() {
        remV = 0f
        remH = 0f
        axis = Axis.UNDECIDED
        accV = 0f
        accH = 0f
        pendingTimes.clear()
        pendingV.clear()
    }

    /** Отправляет целую часть накопленного значения и возвращает остаток. */
    private fun emit(vertical: Boolean, value: Float): Float {
        var left = value
        while (abs(left) >= 1f) {
            val chunk = left.toInt().coerceIn(-MAX_WHEEL_UNITS, MAX_WHEEL_UNITS)
            out.wheel(vertical, chunk)
            left -= chunk
        }
        return left
    }

    // ------------------------------------------------------------------ щипок

    fun pinchStart() {
        pinchAccum = 0f
        pinchArmed = true
    }

    /** [scale] — множитель относительно предыдущего события (> 1 — пальцы расходятся). */
    fun pinch(scale: Float) {
        if (!pinchArmed || scale <= 0f) return
        pinchAccum += ln(scale)
        // Ctrl нажимается только перед первым шагом масштаба, а первый шаг требует заметного изменения:
        // так случайная дрожь пальцев при прокрутке не превращается в масштаб.
        while (abs(pinchAccum) >= (if (ctrlDown) PINCH_LOG_PER_NOTCH else PINCH_LOG_TO_START)) {
            if (!ctrlDown) {
                ctrlDown = true
                out.key(KEY_CTRL, false, true)
            }
            val dir = if (pinchAccum > 0) 1 else -1
            out.wheel(true, dir * UNITS_PER_NOTCH)
            pinchAccum -= dir * PINCH_LOG_PER_NOTCH
        }
    }

    fun pinchEnd() {
        if (ctrlDown) {
            ctrlDown = false
            out.key(KEY_CTRL, false, false)
        }
        pinchArmed = false
        pinchAccum = 0f
    }

    // ------------------------------------------------------------------ свайпы тремя и четырьмя пальцами

    fun swipeStart(fingers: Int) {
        swipeX = 0f
        swipeY = 0f
        swipeFingers = fingers
    }

    /** Смещения за событие в долях размера тачпада; [y] > 0 — пальцы вверх. */
    fun swipe(x: Float, y: Float) {
        swipeX += x
        swipeY += y
    }

    fun swipeEnd() {
        val ax = abs(swipeX)
        val ay = abs(swipeY)
        val dx = swipeX
        val dy = swipeY
        swipeX = 0f
        swipeY = 0f
        if (maxOf(ax, ay) < swipeThreshold) return
        val gnome = desktop == DesktopProfile.GNOME
        if (ay >= ax) {
            when {
                // В GNOME обзор окон открывается одиночным нажатием Super, а «показать рабочий стол» по умолчанию нет.
                dy > 0 -> if (gnome) combo(KEY_WIN to true) else combo(KEY_WIN to true, KEY_TAB to false)
                else -> if (gnome) combo(KEY_WIN to true, KEY_A to false) else combo(KEY_WIN to true, KEY_D to false)
            }
        } else {
            // Пальцы влево — следующий рабочий стол справа, как в macOS.
            val arrow = if (dx < 0) KEY_RIGHT else KEY_LEFT
            if (gnome) combo(KEY_CTRL to false, KEY_ALT to false, arrow to true)
            else combo(KEY_CTRL to false, KEY_WIN to true, arrow to true)
        }
    }

    private fun combo(vararg keys: Pair<Int, Boolean>) {
        keys.forEach { (code, ext) -> out.key(code, ext, true) }
        keys.reversed().forEach { (code, ext) -> out.key(code, ext, false) }
    }

    /** Конец жеста: сбрасывает накопленное, но не прерывает уже запущенную инерцию. */
    fun reset() {
        pinchEnd()
        scrollEnd()
        swipeX = 0f
        swipeY = 0f
    }

    /** Сброс при потере фокуса или обрыве жеста, останавливает и инерцию. */
    fun cancelAll() {
        reset()
        fling.reset()
    }

    companion object {
        const val UNITS_PER_NOTCH = 120

        /** Базовый коэффициент прокрутки по тачпаду: вдвое слабее прежнего (средний вариант настройки «сила скролла»). */
        const val TOUCHPAD_BASE_GAIN = 0.5f
        private const val MAX_WHEEL_UNITS = 240

        /** Сколько пикселей жеста прокрутки соответствует одному щелчку колеса (по вертикали втрое чувствительнее). */
        const val PIXELS_PER_NOTCH_V = 20f
        const val PIXELS_PER_NOTCH_H = 60f

        /** Знаки осей прокрутки AXIS_GESTURE_SCROLL_*: по вертикали направление инвертировано по результату проверки. */
        const val SCROLL_Y_SIGN = -1f
        const val SCROLL_X_SIGN = 1f

        /** Изменение масштаба на один щелчок колеса (натуральный логарифм, ≈12%). */
        private const val PINCH_LOG_PER_NOTCH = 0.12f

        /** Изменение масштаба (логарифм), с которого щипок начинает действовать (≈22%). */
        private const val PINCH_LOG_TO_START = 0.2f

        /** Минимальный свайп в долях размера тачпада. */
        const val SWIPE_THRESHOLD = 0.15f

        /** Сдвиг (в вертикальных щелчках), с которого выбирается ось жеста двумя пальцами. */
        private const val AXIS_LOCK_NOTCHES = 1f

        /** Горизонталь выбирается, только если она заметно сильнее вертикали (иначе — обычная прокрутка). */
        private const val HORIZONTAL_BIAS = 1.5f

        /** Горизонтальный свайп короче этого (в вертикальных щелчках) не считается навигацией. */
        private const val NAV_NOTCHES = 4f

        private const val KEY_ALT = 0x38
        private const val KEY_CTRL = 0x1D
        private const val KEY_WIN = 0x5B
        private const val KEY_TAB = 0x0F
        private const val KEY_D = 0x20
        private const val KEY_A = 0x1E
        private const val KEY_LEFT = 0x4B
        private const val KEY_RIGHT = 0x4D
    }
}
