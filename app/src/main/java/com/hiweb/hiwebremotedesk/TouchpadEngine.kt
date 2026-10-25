package com.hiweb.hiwebremotedesk

import kotlin.math.abs
import kotlin.math.hypot

/** Куда движок тачпада отправляет результат. Расширяет жесты командами курсора и кнопок. */
interface TouchpadOutput : GestureOutput {
    /** Сдвиг курсора в пикселях удалённого рабочего стола. */
    fun moveCursor(dx: Float, dy: Float)

    /** Кнопка мыши: [TouchpadEngine.BTN_LEFT], [TouchpadEngine.BTN_RIGHT], [TouchpadEngine.BTN_MIDDLE]. */
    fun button(button: Int, down: Boolean)
}

/**
 * Соответствие сырых осей тачпада осям экрана. Тачпад в чехле-клавиатуре установлен боком по отношению к
 * планшету, поэтому ориентация определяется калибровкой (см. [TouchpadCalibrator]).
 */
data class TouchpadAxes(val swap: Boolean = false, val signH: Int = 1, val signV: Int = 1) {
    fun screenDx(rawDx: Float, rawDy: Float): Float = signH * if (swap) rawDy else rawDx
    fun screenDy(rawDx: Float, rawDy: Float): Float = signV * if (swap) rawDx else rawDy
}

/**
 * Обработка сырых касаний тачпада (режим захвата указателя), когда система больше не распознаёт жесты сама:
 *  - один палец — курсор с ускорением, нажатие — левая кнопка;
 *  - нажатие двумя пальцами — правая кнопка, тремя — средняя; физический щелчок тачпада — то же по числу пальцев;
 *  - перетаскивание двумя пальцами, как в macOS: один палец держит левую кнопку (физическое нажатие тачпада
 *    или долгое касание на месте), второй палец водит курсор;
 *  - два пальца — прокрутка в стиле macOS (контент следует за пальцами) или масштаб щипком;
 *  - три или четыре пальца — свайпы (обзор окон, рабочий стол, переключение рабочих столов), см. [TouchpadGestures].
 * Движок не зависит от Android: кадры подаёт [onFrame].
 */
class TouchpadEngine(
    private val out: TouchpadOutput,
    var axes: TouchpadAxes = TouchpadAxes(),
    /** Единиц осей тачпада на миллиметр (resolution из InputDevice). */
    private val unitsPerMm: Float = DEFAULT_UNITS_PER_MM,
) {
    private val gestures = TouchpadGestures(out, swipeThreshold = SWIPE_MM)

    private val last = LinkedHashMap<Int, FloatArray>()
    private var lastTime = 0L
    private var prevButtons = 0
    private var clickButton = -1

    // Состояние текущего касания
    private var downTime = 0L
    private var maxFingers = 0
    private var travelMm = 0f
    private var clicked = false
    private var suppressMove = false
    private var swipeFed = false

    /** Касание уже сработало как прокрутка, масштаб или свайп и тапом считаться не может. */
    private var acted = false

    /** В этом касании уже была прокрутка двумя пальцами: после кратковременного скачка числа пальцев она продолжается. */
    private var scrollSticky = false

    /** HOLD: первый палец долго лежал на месте и второй палец только что лёг — ждём, сдвинется ли он (перетаскивание). */
    private enum class TwoMode { UNDECIDED, SCROLL, PINCH, HOLD }
    private var twoMode = TwoMode.UNDECIDED
    private var twoMoveMm = 0f
    private var twoDistMm = 0f

    /** Левая кнопка зажата движком (перетаскивание «лежащий палец + второй палец»), а не физическим нажатием. */
    private var dragging = false
    private var holdMoveMm = 0f

    /** Сырой сдвиг самого подвижного пальца за кадр: при перетаскивании курсор ведёт он, а не среднее двух пальцев. */
    private var leadDx = 0f
    private var leadDy = 0f

    private val dragActive: Boolean get() = dragging || clickButton == BTN_LEFT

    /**
     * Один кадр: пальцы, лежащие на тачпаде сейчас ([n] штук; поднятый палец в список не входит),
     * и состояние кнопок Android (BUTTON_PRIMARY = 1, SECONDARY = 2, TERTIARY = 4).
     */
    fun onFrame(timeMs: Long, ids: IntArray, xs: FloatArray, ys: FloatArray, n: Int, buttons: Int) {
        handleButtons(buttons, n)
        // Новое касание тачпада останавливает инерцию прокрутки.
        if (n > last.size) gestures.stopFling()

        val wasActive = last.isNotEmpty()
        if (n == 0) {
            if (wasActive) endGesture(timeMs)
            return
        }
        if (!wasActive) startGesture(timeMs)

        val prevCount = last.size
        var rdx = 0f
        var rdy = 0f
        var matched = 0
        var leadMag = -1f
        for (i in 0 until n) {
            val p = last[ids[i]] ?: continue
            val dx = xs[i] - p[0]
            val dy = ys[i] - p[1]
            rdx += dx
            rdy += dy
            matched++
            val mag = hypot(dx, dy)
            if (mag > leadMag) { leadMag = mag; leadDx = dx; leadDy = dy }
        }

        if (wasActive && n != prevCount) {
            onCountChange(timeMs, prevCount, n)
        } else if (wasActive && matched == n && n > 0) {
            rdx /= n
            rdy /= n
            val twoDelta = if (n == 2) distanceChangeMm(ids, xs, ys) else 0f
            onMove(timeMs, rdx / unitsPerMm, rdy / unitsPerMm, n, twoDelta)
        }

        last.clear()
        for (i in 0 until n) last[ids[i]] = floatArrayOf(xs[i], ys[i])
        if (n > maxFingers) maxFingers = n
        lastTime = timeMs
    }

    // ------------------------------------------------------------------ жизненный цикл касания

    private fun startGesture(t: Long) {
        downTime = t
        lastTime = t
        maxFingers = 0
        travelMm = 0f
        clicked = false
        suppressMove = false
        swipeFed = false
        acted = false
        scrollSticky = false
        twoMode = TwoMode.UNDECIDED
        twoMoveMm = 0f
        twoDistMm = 0f
        dragging = false
        holdMoveMm = 0f
    }

    private fun onCountChange(t: Long, from: Int, to: Int) {
        // Выходим из режима предыдущего числа пальцев; до конца касания курсор не двигаем (палец «ползёт» при подъёме).
        if (from >= 3 && to < 3) finishSwipe()
        // Пальцы подняты (остался один или ни одного) — прокрутка продолжается по инерции.
        if (from == 2) finishTwo(if (to < 2) t else null)
        if (from >= 2 && to < from) suppressMove = true
        if (to == 2) {
            // Вернулись к двум пальцам после касания третьим (ладонью) или подъёма пальца: прокрутка продолжается,
            // а не выбирается заново, иначе дрожь пальцев могла бы выглядеть как щипок.
            val holdArmed = from == 1 && maxFingers == 1 && t - downTime >= HOLD_MS && travelMm <= HOLD_TRAVEL_MM
            twoMode = when {
                holdArmed -> TwoMode.HOLD
                scrollSticky -> TwoMode.SCROLL
                else -> TwoMode.UNDECIDED
            }
            holdMoveMm = 0f
            twoMoveMm = 0f
            twoDistMm = 0f
        }
        if (to >= 3 && from < 3) {
            swipeFed = false
            gestures.swipeStart(to)
        }
    }

    private fun endGesture(t: Long) {
        releaseDrag()
        finishTwo(t)
        finishSwipe()
        val isTap = !clicked && !acted && (t - downTime) <= TAP_MS && travelMm <= TAP_TRAVEL_MM
        if (isTap) {
            val button = when {
                maxFingers >= 3 -> BTN_MIDDLE
                maxFingers == 2 -> BTN_RIGHT
                else -> BTN_LEFT
            }
            out.button(button, true)
            out.button(button, false)
        }
        last.clear()
        maxFingers = 0
        gestures.reset() // инерцию прокрутки не трогаем
    }

    /** [releaseAt] — момент подъёма пальцев: тогда прокрутка продолжится по инерции. */
    private fun finishTwo(releaseAt: Long? = null) {
        when (twoMode) {
            TwoMode.PINCH -> gestures.pinchEnd()
            TwoMode.SCROLL -> if (releaseAt != null) gestures.scrollRelease(releaseAt) else gestures.scrollEnd()
            TwoMode.UNDECIDED, TwoMode.HOLD -> Unit
        }
        twoMode = TwoMode.UNDECIDED
    }

    private fun releaseDrag() {
        if (!dragging) return
        dragging = false
        out.button(BTN_LEFT, false)
    }

    private fun finishSwipe() {
        if (swipeFed) gestures.swipeEnd() else gestures.reset()
        swipeFed = false
    }

    /** Сила прокрутки и сила инерции из настроек приложения (1 — по умолчанию). */
    var desktop: DesktopProfile
        get() = gestures.desktop
        set(value) { gestures.desktop = value }

    var scrollStrength: Float
        get() = gestures.scrollStrength
        set(value) { gestures.scrollStrength = value }

    var inertiaStrength: Float
        get() = gestures.inertiaStrength
        set(value) { gestures.inertiaStrength = value }

    /** Идёт инерционная прокрутка: вызывайте [tick] каждый кадр, пока true. */
    val isFlinging: Boolean get() = gestures.isFlinging

    /** Шаг инерции прокрутки; возвращает true, пока она продолжается. */
    fun tick(nowMs: Long): Boolean = gestures.flingStep(nowMs)

    // ------------------------------------------------------------------ движение

    private fun onMove(t: Long, mmX: Float, mmY: Float, n: Int, twoDistDelta: Float) {
        val sdx = axes.screenDx(mmX, mmY)
        val sdy = axes.screenDy(mmX, mmY)
        val moved = hypot(sdx, sdy)
        travelMm += moved
        when {
            n == 1 -> if (!suppressMove || dragActive) moveCursor(t, sdx, sdy)
            n >= 2 && dragActive -> {
                acted = true
                moveCursor(t, axes.screenDx(leadDx / unitsPerMm, leadDy / unitsPerMm), axes.screenDy(leadDx / unitsPerMm, leadDy / unitsPerMm))
            }
            n == 2 && twoMode == TwoMode.HOLD -> holdMove(t)
            n == 2 -> twoFingerMove(t, sdx, sdy, moved, twoDistDelta)
            n >= 3 -> {
                swipeFed = true
                acted = true
                // Свайпы: ось Y обращена «пальцы вверх — положительно».
                gestures.swipe(sdx, -sdy)
            }
        }
    }

    /** Сдвиг курсора на (sdx, sdy) мм с ускорением по скорости. */
    private fun moveCursor(t: Long, sdx: Float, sdy: Float) {
        val moved = hypot(sdx, sdy)
        val dtSec = maxOf(1L, t - lastTime) / 1000f
        val gain = (0.5f + moved / dtSec / ACCEL_MM_PER_S).coerceIn(0.5f, 3.2f)
        out.moveCursor(sdx * PX_PER_MM * gain, sdy * PX_PER_MM * gain)
    }

    /** Первый палец лежит давно, второй лёг: как только второй сдвинулся, кнопка зажимается и он ведёт курсор. */
    private fun holdMove(t: Long) {
        val sdx = axes.screenDx(leadDx / unitsPerMm, leadDy / unitsPerMm)
        val sdy = axes.screenDy(leadDx / unitsPerMm, leadDy / unitsPerMm)
        holdMoveMm += hypot(sdx, sdy)
        if (holdMoveMm < HOLD_COMMIT_MM) return
        dragging = true
        acted = true
        twoMode = TwoMode.UNDECIDED
        out.button(BTN_LEFT, true)
        moveCursor(t, sdx, sdy)
    }

    private fun twoFingerMove(t: Long, sdx: Float, sdy: Float, moved: Float, distDelta: Float) {
        if (twoMode == TwoMode.UNDECIDED) {
            twoMoveMm += moved
            twoDistMm += distDelta
            // Щипок выбирается только при явном сближении или расхождении пальцев, прокрутка — когда
            // пальцы заметно сдвинулись вместе. При посадке пальцы «усаживаются», поэтому ранние
            // мелкие изменения расстояния игнорируются.
            val pinchEvidence = abs(twoDistMm) > PINCH_RATIO * twoMoveMm
            twoMode = when {
                abs(twoDistMm) >= PINCH_COMMIT_MM && pinchEvidence -> TwoMode.PINCH
                twoMoveMm >= SCROLL_COMMIT_MM && !pinchEvidence -> TwoMode.SCROLL
                else -> return
            }
            acted = true
            if (twoMode == TwoMode.SCROLL) scrollSticky = true
            if (twoMode == TwoMode.PINCH) gestures.pinchStart()
            return
        }
        when (twoMode) {
            // Контент следует за пальцами: пальцы вниз — колесо вверх; пальцы вправо — прокрутка влево.
            TwoMode.SCROLL -> gestures.fingerScroll(
                t, sdy / MM_PER_NOTCH_V, -sdx / MM_PER_NOTCH_H, TouchpadGestures.TOUCHPAD_BASE_GAIN,
                MM_PER_NOTCH_H / MM_PER_NOTCH_V,
            )
            TwoMode.PINCH -> if (previousTwoDistMm > 0f && distDelta != 0f) {
                gestures.pinch((previousTwoDistMm + distDelta) / previousTwoDistMm)
            }
            TwoMode.UNDECIDED, TwoMode.HOLD -> Unit
        }
    }

    private var previousTwoDistMm = 0f

    /** Изменение расстояния между двумя пальцами (мм) относительно прошлого кадра; запоминает текущее расстояние. */
    private fun distanceChangeMm(ids: IntArray, xs: FloatArray, ys: FloatArray): Float {
        val order = if (ids[0] <= ids[1]) intArrayOf(0, 1) else intArrayOf(1, 0)
        val a = order[0]
        val b = order[1]
        val now = hypot(xs[a] - xs[b], ys[a] - ys[b]) / unitsPerMm
        val pa = last[ids[a]]
        val pb = last[ids[b]]
        val before = if (pa != null && pb != null) hypot(pa[0] - pb[0], pa[1] - pb[1]) / unitsPerMm else now
        previousTwoDistMm = before
        return now - before
    }

    // ------------------------------------------------------------------ кнопки

    private fun handleButtons(buttons: Int, fingers: Int) {
        val primary = buttons and 1 != 0
        val wasPrimary = prevButtons and 1 != 0
        if (primary && !wasPrimary) {
            // Физический щелчок: число пальцев определяет кнопку, как в macOS.
            clickButton = when {
                fingers >= 3 -> BTN_MIDDLE
                fingers == 2 -> BTN_RIGHT
                else -> BTN_LEFT
            }
            clicked = true
            out.button(clickButton, true)
        } else if (!primary && wasPrimary && clickButton != -1) {
            out.button(clickButton, false)
            clickButton = -1
        }
        edge(buttons, 2, BTN_RIGHT)
        edge(buttons, 4, BTN_MIDDLE)
        prevButtons = buttons
    }

    private fun edge(buttons: Int, mask: Int, button: Int) {
        val now = buttons and mask != 0
        val before = prevButtons and mask != 0
        if (now != before) {
            clicked = clicked || now
            out.button(button, now)
        }
    }

    /** Сброс при потере захвата или фокуса: отпускаем всё, что могло остаться нажатым. */
    fun cancel() {
        releaseDrag()
        finishTwo()
        gestures.cancelAll()
        if (clickButton != -1) out.button(clickButton, false)
        clickButton = -1
        prevButtons = 0
        last.clear()
        maxFingers = 0
    }

    companion object {
        const val BTN_LEFT = 0
        const val BTN_RIGHT = 1
        const val BTN_MIDDLE = 2

        const val DEFAULT_UNITS_PER_MM = 15f

        /** Пикселей рабочего стола на миллиметр движения пальца при средней скорости. */
        const val PX_PER_MM = 7f

        /** Скорость (мм/с), при которой ускорение курсора заметно растёт. */
        const val ACCEL_MM_PER_S = 70f

        /** Мм движения двух пальцев на один щелчок колеса: вертикаль чувствительнее горизонтали. */
        const val MM_PER_NOTCH_V = 4f
        const val MM_PER_NOTCH_H = 8f

        /** Палец считается «лежащим» (для перетаскивания), если он пробыл на месте дольше этого и сдвинулся не больше. */
        private const val HOLD_MS = 300L
        private const val HOLD_TRAVEL_MM = 2f

        /** Сдвиг второго пальца, с которого зажимается кнопка при перетаскивании. */
        private const val HOLD_COMMIT_MM = 1.5f

        /** Сколько сдвинуться двум пальцам вместе, чтобы жест стал прокруткой. */
        private const val SCROLL_COMMIT_MM = 3f

        /** Минимальное изменение расстояния между пальцами для щипка и его превосходство над сдвигом центра. */
        private const val PINCH_COMMIT_MM = 4f
        private const val PINCH_RATIO = 1.2f

        /** Нажатие считается тапом: быстрее 250 мс и без заметного сдвига. */
        private const val TAP_MS = 250L
        private const val TAP_TRAVEL_MM = 2.5f

        /** Минимальный свайп тремя-четырьмя пальцами, мм. */
        const val SWIPE_MM = 20f
    }
}

/**
 * Определяет ориентацию тачпада по двум жестам пользователя: «одним пальцем вправо» и «вниз».
 * Результат — [TouchpadAxes].
 */
class TouchpadCalibrator(private val unitsPerMm: Float = TouchpadEngine.DEFAULT_UNITS_PER_MM) {
    enum class Step { RIGHT, DOWN, DONE }

    var step = Step.RIGHT
        private set

    private var accX = 0f
    private var accY = 0f
    private var waitLift = false
    private var firstAxis = 0
    private var firstSign = 1
    private var axes: TouchpadAxes? = null

    val result: TouchpadAxes? get() = axes

    /** Приращение сырых координат одного пальца (в единицах тачпада). Возвращает true, когда шаг завершён. */
    fun onDelta(rawDx: Float, rawDy: Float): Boolean {
        if (step == Step.DONE || waitLift) return false
        accX += rawDx / unitsPerMm
        accY += rawDy / unitsPerMm
        if (hypot(accX, accY) < TouchpadEngine.SWIPE_MM) return false

        val axis = if (abs(accX) >= abs(accY)) 0 else 1
        val sign = if ((if (axis == 0) accX else accY) >= 0f) 1 else -1
        accX = 0f
        accY = 0f
        when (step) {
            Step.RIGHT -> {
                firstAxis = axis
                firstSign = sign
                step = Step.DOWN
                waitLift = true
            }
            Step.DOWN -> {
                if (axis == firstAxis) {
                    // Второй жест пошёл по той же оси — просим повторить.
                    waitLift = true
                    return false
                }
                axes = TouchpadAxes(swap = firstAxis == 1, signH = firstSign, signV = sign)
                step = Step.DONE
            }
            Step.DONE -> Unit
        }
        return true
    }

    /** Палец поднят: можно начинать следующий шаг. */
    fun onLift() {
        waitLift = false
        accX = 0f
        accY = 0f
    }
}
