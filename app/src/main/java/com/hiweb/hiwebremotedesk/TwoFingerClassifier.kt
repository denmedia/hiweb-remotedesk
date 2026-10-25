package com.hiweb.hiwebremotedesk

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Различает два вида жеста двумя пальцами по сенсорному экрану, пока первый палец уже держит левую кнопку:
 *  - [Kind.DRAG] — первый палец неподвижен, двигается только второй (перетаскивание: второй палец ведёт курсор);
 *  - [Kind.SCROLL] — первый палец тоже сдвинулся (прокрутка двумя пальцами);
 *  - [Kind.PINCH] — оба пальца сдвинулись, а расстояние между ними изменилось сильнее, чем сместился их центр (масштаб).
 */
class TwoFingerClassifier(private val slop: Float) {
    enum class Kind { DRAG, SCROLL, PINCH }

    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 0f
    private var y2 = 0f
    private var dist0 = 0f

    /** Запоминает положения обоих пальцев в момент касания второго. */
    fun begin(firstX: Float, firstY: Float, secondX: Float, secondY: Float) {
        x1 = firstX; y1 = firstY
        x2 = secondX; y2 = secondY
        dist0 = hypot(secondX - firstX, secondY - firstY)
    }

    /** Текущие положения пальцев; null — пока рано решать (оба в пределах порога дрожания). */
    fun update(firstX: Float, firstY: Float, secondX: Float, secondY: Float): Kind? {
        val d1 = hypot(firstX - x1, firstY - y1)
        val d2 = hypot(secondX - x2, secondY - y2)
        val spreadChange = abs(hypot(secondX - firstX, secondY - firstY) - dist0)
        val centerShift = hypot((firstX + secondX - x1 - x2) / 2f, (firstY + secondY - y1 - y2) / 2f)
        return when {
            d1 >= slop && spreadChange >= PINCH_SLOPS * slop && spreadChange > PINCH_RATIO * centerShift -> Kind.PINCH
            d1 >= slop -> Kind.SCROLL
            d2 >= slop && d1 * HOLD_RATIO <= d2 -> Kind.DRAG
            else -> null
        }
    }

    private companion object {
        /** Во сколько раз второй палец должен сместиться дальше первого, чтобы жест считался перетаскиванием. */
        const val HOLD_RATIO = 2.5f

        /** Расстояние между пальцами должно измениться не меньше чем на столько порогов дрожания... */
        const val PINCH_SLOPS = 1.5f

        /** ...и минимум во столько раз сильнее, чем сместился центр пальцев (иначе это прокрутка). */
        const val PINCH_RATIO = 1.5f
    }
}
