package com.hiweb.hiwebremotedesk

import kotlin.math.exp
import kotlin.math.hypot

/**
 * Инерция прокрутки (как на тачпаде macOS): по скорости пальцев в момент отрыва прокрутка продолжается и плавно
 * затухает. Не зависит от Android: время передаёт вызывающий (миллисекунды), шаги инерции выполняет [step].
 *
 * Скорости измеряются в «щелчках» колеса в секунду; результат отдаётся в [emit] дробными щелчками.
 */
class ScrollFling(private val emit: (vertical: Boolean, notches: Float) -> Unit) {
    private class Sample(val t: Long, val v: Float, val h: Float)

    private val samples = ArrayDeque<Sample>()
    private var velV = 0f
    private var velH = 0f
    private var lastStep = 0L

    var isActive = false
        private set

    /** Текущая скорость инерции, щелчков в секунду (0 — инерции нет). */
    val speed: Float get() = if (isActive) hypot(velV, velH) else 0f

    /** Сила инерции: во сколько раз дольше и дальше продолжается прокрутка (1 — по умолчанию). */
    var tauScale = 1f

    /** Прокрутка пальцами: сдвиг [v] и [h] (щелчки) в момент [t]. Новое касание останавливает инерцию. */
    fun onSample(t: Long, v: Float, h: Float) {
        isActive = false
        while (samples.isNotEmpty() && t - samples.first().t > WINDOW_MS) samples.removeFirst()
        samples.addLast(Sample(t, v, h))
    }

    /** Пальцы оторваны в момент [t]: запускает инерцию, если они двигались достаточно быстро. */
    fun release(t: Long) {
        val last = samples.lastOrNull()
        // Если пальцы остановились до отрыва, инерции нет.
        if (last != null && t - last.t <= STALE_MS) {
            val first = samples.first()
            val span = maxOf(last.t - first.t + FRAME_MS, MIN_SPAN_MS) / 1000f
            val v = (samples.sumOf { it.v.toDouble() }.toFloat() / span).coerceIn(-MAX_SPEED, MAX_SPEED)
            val h = (samples.sumOf { it.h.toDouble() }.toFloat() / span).coerceIn(-MAX_SPEED, MAX_SPEED)
            if (hypot(v, h) >= START_SPEED) {
                velV = v
                velH = h
                lastStep = t
                isActive = true
            }
        }
        samples.clear()
    }

    /** Шаг инерции в момент [nowMs]. Возвращает true, пока прокрутка продолжается. */
    fun step(nowMs: Long): Boolean {
        if (!isActive) return false
        val dt = ((nowMs - lastStep) / 1000f).coerceIn(0f, MAX_STEP_SEC)
        if (dt <= 0f) return true
        // Скорость затухает экспоненциально; пройденный путь за шаг — точный интеграл, поэтому не зависит от частоты кадров.
        val tau = TAU_SEC * tauScale.coerceAtLeast(0.05f)
        val decay = exp(-dt / tau)
        val distance = tau * (1f - decay)
        if (velV != 0f) emit(true, velV * distance)
        if (velH != 0f) emit(false, velH * distance)
        velV *= decay
        velH *= decay
        lastStep = nowMs
        if (hypot(velV, velH) < STOP_SPEED) isActive = false
        return isActive
    }

    /** Останавливает инерцию (новое касание, потеря фокуса). */
    fun stop() {
        isActive = false
    }

    fun reset() {
        isActive = false
        samples.clear()
    }

    companion object {
        /** Окно, по которому оценивается скорость. */
        private const val WINDOW_MS = 100L

        /** Максимальная пауза между последним движением и отрывом, при которой инерция ещё возможна. */
        private const val STALE_MS = 60L

        /** Типичный интервал между кадрами ввода: учитывается для самого первого отсчёта окна. */
        private const val FRAME_MS = 8L
        private const val MIN_SPAN_MS = 24L

        /** Минимальная скорость для запуска инерции, щелчков в секунду. */
        private const val START_SPEED = 6f
        private const val STOP_SPEED = 0.5f
        private const val MAX_SPEED = 60f

        /** Постоянная затухания: за это время скорость падает в e раз (путь инерции = скорость × TAU). */
        const val TAU_SEC = 0.5f
        private const val MAX_STEP_SEC = 0.25f
    }
}
