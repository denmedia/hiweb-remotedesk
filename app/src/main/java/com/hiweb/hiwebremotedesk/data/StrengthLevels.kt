package com.hiweb.hiwebremotedesk.data

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * «Сила» прокрутки, инерции, скорость мыши и её колёсика. Задаются отдельно для каждого ПК в процентах от
 * [MIN] до [MAX] с шагом [STEP]; [DEFAULT] — 100%, то, что владелец настроил как нормальное ощущение.
 */
object StrengthLevels {
    const val MIN = 50
    const val MAX = 200
    const val STEP = 10
    const val DEFAULT = 100

    /** Множитель процента: 100% → 1.0. */
    fun factor(percent: Int): Float = percent.coerceIn(MIN, MAX) / 100f

    /** Ближайшее значение, кратное [STEP], в пределах ползунка. */
    fun snap(percent: Float): Int = ((percent / STEP).roundToInt() * STEP).coerceIn(MIN, MAX)

    /**
     * Что на самом деле соответствует «100%» у инерции и у скорости мыши (в единицах исходной шкалы, где 1 —
     * прежние «100%» из первой версии): ползунок показывает проценты от этой опорной величины. Инерция —
     * 0.84 от исходной, мышь — в 2.38 раза быстрее (так выбрал владелец: на прежнем «100%» курсор уходил слишком быстро).
     */
    val INERTIA_BASE: Float = 2.0.pow(-1.0 / 4.0).toFloat()
    val MOUSE_BASE: Float = 2.0.pow(1.25).toFloat()

    /** Множитель, который получает движок: опорная величина на проценты уровня. */
    fun inertiaFactor(percent: Int): Float = INERTIA_BASE * factor(percent)
    fun mouseFactor(percent: Int): Float = MOUSE_BASE * factor(percent)

    /**
     * Версия шкалы в хранилище: 1 и 2 — уровни 1–9 (4 уровня — вдвое; 2 — «100%» у инерции и мыши стали
     * опорными), 3 — опорная скорость мыши вчетверо больше исходной, 4 — проценты с шагом [STEP] и опорная мышь [MOUSE_BASE].
     */
    const val SCALE_VERSION = 4

    /** Множитель уровня 1–9 прежней шкалы (5 — 1.0, каждые 4 уровня — вдвое). */
    private fun levelFactor(level: Int): Float = 2.0.pow((level.coerceIn(1, 9) - 5) / 4.0).toFloat()

    /** Прокрутка пальцами и колёсико: прежний уровень → процент с тем же фактическим значением. */
    fun percentFromLevel(level: Int): Int = snap(levelFactor(level) * 100f)

    /** Инерция: в версии 1 опорной была единица, начиная со 2-й — [INERTIA_BASE]. */
    fun inertiaFromLevel(level: Int, from: Int): Int =
        snap(levelFactor(level) * (if (from < 2) 1f / INERTIA_BASE else 1f) * 100f)

    /** Скорость мыши: опорная величина была 1 (версия 1), 2 (версия 2), 4 (версия 3). */
    fun mouseFromLevel(level: Int, from: Int): Int {
        val base = when {
            from < 2 -> 1f
            from < 3 -> 2f
            else -> 4f
        }
        return snap(levelFactor(level) * base / MOUSE_BASE * 100f)
    }
}
