package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.CursorKind
import kotlin.math.hypot

/** Что [StylusEngine] делает с ПК. Координаты — пиксели вида, не рабочего стола. */
interface StylusOutput {
    /** Курсор — в точку (перо над экраном или на нём). */
    fun move(x: Float, y: Float)

    /** Левая кнопка нажата в точке (начало выделения или перетаскивания). */
    fun press(x: Float, y: Float)

    /** Левая кнопка отпущена там, где курсор. */
    fun release()

    /** Касание без движения: левая кнопка нажата (отпускает её [release]); двойной щелчок склеивается по времени и месту. */
    fun tap(x: Float, y: Float, time: Long)

    /** Содержимое сдвинулось вслед за пером на ([dx], [dy]) пикселей, как под пальцем. */
    fun scroll(time: Long, dx: Float, dy: Float)

    /** Перо поднято во время прокрутки: дальше — по инерции. */
    fun scrollRelease(time: Long)

    /** Прокрутка прервана без инерции. */
    fun scrollEnd()
}

/**
 * Перо Samsung (S Pen) как мышь с жестами, «как на планшете вне сеанса»: над экраном курсор идёт за пером; кнопка на
 * пере — правая кнопка мыши (её нажатие отдельно от касания пересылает вид); касание без движения — щелчок.
 *
 * Куда уводит движение пера по экрану, на ПК заранее неизвестно, поэтому решает форма курсора под пером
 * ([cursorKind], её присылает агент): «I» — выделение текста, курсоры размера и перекрестие — перетаскивание,
 * остальное — прокрутка по обеим осям с инерцией. Прокрутка не мешает перетаскиванию значков и окон: перо, придержанное
 * на месте дольше долгого касания ([longPress]), перед движением нажимает левую кнопку — как долгое касание на Android.
 * Решение откладывается до выхода за порог [slop], чтобы форма курсора успела смениться после наведения; левая кнопка
 * при этом нажимается в точке касания, а не там, где перо оказалось к решению.
 */
class StylusEngine(private val out: StylusOutput, private val slop: Float) {
    private enum class Mode { IDLE, PENDING, SCROLL, DRAG, BUTTON, IGNORE }

    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var longFired = false
    private var tapSuppressed = false

    /** Форма курсора Windows на данный момент. */
    var cursorKind: () -> CursorKind = { CursorKind.ARROW }

    /** Перо касается экрана. */
    val touching: Boolean get() = mode != Mode.IDLE

    /**
     * Перо коснулось экрана. [buttonHeld] — кнопка пера уже нажата (правая кнопка мыши; касание тогда только водит
     * курсор), [tapSuppressed] — касание остановило инерцию прокрутки и щелчком не считается.
     */
    fun down(x: Float, y: Float, buttonHeld: Boolean, tapSuppressed: Boolean) {
        finish()
        downX = x
        downY = y
        longFired = false
        this.tapSuppressed = tapSuppressed
        out.move(x, y)
        mode = if (buttonHeld) Mode.BUTTON else Mode.PENDING
    }

    fun move(x: Float, y: Float, time: Long) {
        when (mode) {
            Mode.BUTTON, Mode.DRAG -> out.move(x, y)
            Mode.PENDING -> {
                if (hypot(x - downX, y - downY) < slop) return
                if (longFired || cursorKind() != CursorKind.ARROW) {
                    mode = Mode.DRAG
                    out.press(downX, downY)
                    out.move(x, y)
                } else {
                    mode = Mode.SCROLL
                    out.move(downX, downY)
                    out.scroll(time, x - downX, y - downY)
                    anchorX = x
                    anchorY = y
                }
            }
            Mode.SCROLL -> {
                out.scroll(time, x - anchorX, y - anchorY)
                anchorX = x
                anchorY = y
            }
            Mode.IDLE, Mode.IGNORE -> {}
        }
    }

    /** Перо оторвано от экрана. */
    fun up(time: Long) {
        when (mode) {
            Mode.PENDING -> if (!tapSuppressed) {
                out.tap(downX, downY, time)
                out.release()
            }
            Mode.SCROLL -> out.scrollRelease(time)
            Mode.DRAG -> out.release()
            else -> {}
        }
        mode = Mode.IDLE
    }

    /** Касание отменено системой (ладонь, смена окна). */
    fun cancel() {
        finish()
    }

    /**
     * Кнопка пера нажата или отпущена при касании: нажатие обрывает прокрутку и перетаскивание (дальше перо только водит
     * курсор с правой кнопкой), после отпускания касание до конца ничего не делает.
     */
    fun buttonChanged(held: Boolean) {
        if (mode == Mode.IDLE) return
        if (held && mode != Mode.BUTTON) {
            finish()
            mode = Mode.BUTTON
        } else if (!held && mode == Mode.BUTTON) {
            mode = Mode.IGNORE
        }
    }

    /** Перо не двигалось дольше долгого касания; true — касание ещё не решено и теперь станет перетаскиванием (для отклика). */
    fun longPress(): Boolean {
        if (mode != Mode.PENDING || longFired) return false
        longFired = true
        return true
    }

    private fun finish() {
        when (mode) {
            Mode.SCROLL -> out.scrollEnd()
            Mode.DRAG -> out.release()
            else -> {}
        }
        mode = Mode.IDLE
    }
}
