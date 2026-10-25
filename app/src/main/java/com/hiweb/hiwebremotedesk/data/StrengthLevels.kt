package com.hiweb.hiwebremotedesk.data

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Уровни «силы» прокрутки и инерции. Задаются отдельно для каждого ПК. Уровень от [MIN] до [MAX];
 * средний [DEFAULT] — значение по умолчанию (100%), крайние — вдвое слабее и вдвое сильнее.
 */
object StrengthLevels {
    const val MIN = 1
    const val MAX = 9
    const val DEFAULT = 5

    /** Множитель уровня: каждые 4 шага — вдвое (уровень 1 → 0.5, 5 → 1.0, 9 → 2.0). */
    fun factor(level: Int): Float = 2.0.pow((level.coerceIn(MIN, MAX) - DEFAULT) / 4.0).toFloat()

    fun percent(level: Int): Int = (factor(level) * 100f).roundToInt()
}
