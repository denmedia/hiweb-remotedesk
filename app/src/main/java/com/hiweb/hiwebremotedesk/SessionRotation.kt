package com.hiweb.hiwebremotedesk

import android.content.pm.ActivityInfo

/**
 * Поворот экрана сеанса кнопкой «Повернуть». Поворот дисплея задаётся числами Surface.ROTATION_0…3
 * (0, 1, 2, 3 — против часовой стрелки, если смотреть на устройство): рост числа на 1 поворачивает
 * картинку на экране на 90° по часовой стрелке, убывание — на 90° против.
 */
object SessionRotation {
    /**
     * Поворот дисплея после нажатия кнопки: из альбомной ориентации картинка поворачивается на 90° влево
     * (против часовой), из книжной — обратно в альбомную. Чётность числа меняется на каждый шаг, поэтому
     * результат не зависит от того, какой стороной у планшета «естественная» ориентация.
     */
    fun target(rotation: Int, portrait: Boolean): Int = Math.floorMod(if (portrait) rotation + 1 else rotation - 1, 4)

    /** Сдвиг картинки при смене поворота дисплея: 1 — на 90° по часовой, 3 — на 90° против, 2 — на 180°. */
    fun delta(from: Int, to: Int): Int = Math.floorMod(to - from, 4)

    /**
     * Книжная ли «естественная» ориентация устройства (планшеты Samsung и телефоны — да): при поворотах 0 и 2 окно
     * той же ориентации, что и экран в естественном положении, при 1 и 3 — другой.
     */
    fun naturalPortrait(rotation: Int, portrait: Boolean): Boolean = (rotation % 2 == 0) == portrait

    /** Значение `requestedOrientation`, которое фиксирует нужный поворот дисплея. */
    fun orientationFor(rotation: Int, naturalPortrait: Boolean): Int {
        val r = Math.floorMod(rotation, 4)
        return if (naturalPortrait) when (r) {
            0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            1 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        } else when (r) {
            0 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        }
    }
}

/**
 * Часы анимации смены ориентации (как на iOS): прежняя картинка рабочего стола за [ROTATE_MS] разворачивается
 * к новой ориентации и меняет пропорции, по ходу мутнеет и остаётся мутной, пока Windows не ответит новым
 * разрешением ([ready]); после этого плавно исчезает за [FADE_MS]. Не знает про Android: время приходит
 * параметром, поэтому ход проверяется юнит-тестами.
 */
class RotationClock(private val start: Long, private val fromBlur: Float = 0f) {
    private var readyAt = 0L

    /** Windows ответила, новый рабочий стол готов: картинку можно убирать (не раньше конца поворота). */
    fun ready(now: Long) {
        if (readyAt == 0L) readyAt = now
    }

    val isReady: Boolean get() = readyAt != 0L

    /** Ход поворота и смены пропорций, 0…1 с плавным стартом и финишем. */
    fun progress(now: Long): Float = smooth(((now - start).toFloat() / ROTATE_MS).coerceIn(0f, 1f))

    /** Степень размытия, 0…1: нарастает от прежнего значения и держится до конца. */
    fun blur(now: Long): Float = fromBlur + (1f - fromBlur) * smooth(((now - start).toFloat() / BLUR_MS).coerceIn(0f, 1f))

    /** Непрозрачность прежней картинки: 1, пока ждём Windows, затем плавно до 0. */
    fun alpha(now: Long): Float = 1f - smooth(((now - fadeStart()).toFloat() / FADE_MS).coerceIn(0f, 1f))

    fun finished(now: Long): Boolean = alpha(now) <= 0f

    private fun fadeStart(): Long = if (readyAt != 0L) maxOf(readyAt, start + ROTATE_MS) else start + TIMEOUT_MS

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    companion object {
        const val ROTATE_MS = 450L
        const val BLUR_MS = 700L
        const val FADE_MS = 400L

        /** Если Windows так и не прислала новый рабочий стол, картинка всё равно уходит. */
        const val TIMEOUT_MS = 10_000L
    }
}
