package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.PenFrame
import com.hiweb.hiwebremotedesk.rdp.PenPoint
import com.hiweb.hiwebremotedesk.rdp.RdpeiFlags
import com.hiweb.hiwebremotedesk.rdp.TouchFrame
import com.hiweb.hiwebremotedesk.rdp.TouchPoint

/** Куда [NativeTouchEngine] отдаёт накопленные кадры (по одному вызову на событие Android). */
interface NativeTouchOutput {
    fun touch(frames: List<TouchFrame>)
    fun pen(frames: List<PenFrame>)
}

/** Палец в данный момент: идентификатор Android и координаты рабочего стола. */
class NativePointer(val id: Int, val x: Int, val y: Int)

/**
 * Нативный ввод: пальцы и перо уходят на ПК настоящими касаниями (RDPEI), а не мышью — жесты разбирает Windows.
 * Класс только ведёт состояние: сопоставляет идентификаторы пальцев Android с номерами контактов (0…[maxContacts]−1),
 * следит, чтобы у каждого нажатия был отпуск, и собирает кадры. Координаты — пиксели рабочего стола.
 *
 * События копятся в кадры и уходят вместе по [flush] (одно сообщение на событие Android, историю движения можно
 * передать несколькими кадрами). У пера один контакт и своя машина состояний: наведение — `INRANGE`, касание —
 * `INRANGE|INCONTACT`, уход из зоны — `UP`.
 */
class NativeTouchEngine(
    private val out: NativeTouchOutput,
    private val maxContacts: Int = 10,
) {
    private val slots = HashMap<Int, Int>()          // идентификатор Android → номер контакта
    private val last = HashMap<Int, IntArray>()      // номер контакта → последние x, y
    private val touchFrames = ArrayList<TouchFrame>()
    private val penFrames = ArrayList<PenFrame>()

    /** Сколько пальцев сейчас на экране. */
    val fingers: Int get() = slots.size

    private var penInRange = false
    private var penX = 0
    private var penY = 0

    /** Последняя точка пера (для повтора неподвижного пера). */
    private var lastPen: PenPoint? = null

    /** Когда в последний раз уходили кадры пальцев и пера (часы событий, мс). */
    private var lastTouchAt = Long.MIN_VALUE / 2
    private var lastPenAt = Long.MIN_VALUE / 2

    /** Перо в зоне действия экрана (над ним или на нём). */
    val penActive: Boolean get() = penInRange

    private fun freeSlot(): Int? = (0 until maxContacts).firstOrNull { it !in slots.values }

    /** Новый палец. false — все контакты заняты, палец игнорируется до отпускания. */
    fun down(pointerId: Int, x: Int, y: Int, time: Long): Boolean {
        if (pointerId in slots) return true
        val slot = freeSlot() ?: return false
        slots[pointerId] = slot
        last[slot] = intArrayOf(x, y)
        touchFrames += TouchFrame(
            time, listOf(TouchPoint(slot, x, y, RdpeiFlags.DOWN or RdpeiFlags.INRANGE or RdpeiFlags.INCONTACT)),
        )
        return true
    }

    /** Движение: в кадр попадают только пальцы, сдвинувшиеся с прошлого раза. */
    fun move(time: Long, pointers: List<NativePointer>) {
        val moved = ArrayList<TouchPoint>(pointers.size)
        for (p in pointers) {
            val slot = slots[p.id] ?: continue
            val prev = last[slot] ?: continue
            if (prev[0] == p.x && prev[1] == p.y) continue
            prev[0] = p.x
            prev[1] = p.y
            moved += TouchPoint(slot, p.x, p.y, RdpeiFlags.UPDATE or RdpeiFlags.INRANGE or RdpeiFlags.INCONTACT)
        }
        if (moved.isNotEmpty()) touchFrames += TouchFrame(time, moved)
    }

    /** Палец поднят. Пальцы, о которых не знаем (сверх [maxContacts]), игнорируются. */
    fun up(pointerId: Int, x: Int, y: Int, time: Long) {
        val slot = slots.remove(pointerId) ?: return
        last.remove(slot)
        touchFrames += TouchFrame(time, listOf(TouchPoint(slot, x, y, RdpeiFlags.UP)))
    }

    /** Все пальцы и перо отменены (потеря фокуса, `ACTION_CANCEL`, смена режима): Windows получает `UP|CANCELED`. */
    fun cancelAll(time: Long) {
        if (slots.isNotEmpty()) {
            val points = slots.values.sorted().map { slot ->
                val xy = last[slot] ?: intArrayOf(0, 0)
                TouchPoint(slot, xy[0], xy[1], RdpeiFlags.UP or RdpeiFlags.CANCELED)
            }
            touchFrames += TouchFrame(time, points)
            slots.clear()
            last.clear()
        }
        penCancel(time)
        flush()
    }

    // ------------------------------------------------------------------ перо

    private fun penPoint(
        x: Int, y: Int, flags: Int, touching: Boolean, pressure: Float, barrel: Boolean, eraser: Boolean, tiltX: Int, tiltY: Int,
    ): PenPoint {
        var penFlags = 0
        if (barrel) penFlags = penFlags or RdpeiFlags.PEN_BARREL
        if (eraser) penFlags = penFlags or RdpeiFlags.PEN_INVERTED
        if (eraser && touching) penFlags = penFlags or RdpeiFlags.PEN_ERASER
        val p = if (touching) Math.round(pressure.coerceIn(0f, 1f) * 1024f) else 0
        return PenPoint(x, y, flags, penFlags, p, tiltX, tiltY)
    }

    /**
     * Перо в точке: [touching] — касается экрана ([pressure] 0…1), иначе зависло над ним. Первое событие вводит перо
     * в зону (`DOWN|INRANGE`), дальше идут обновления. [tiltX], [tiltY] — наклон в градусах ([PenTilt]).
     */
    fun pen(
        time: Long, x: Int, y: Int, touching: Boolean, pressure: Float, barrel: Boolean, eraser: Boolean,
        tiltX: Int = 0, tiltY: Int = 0,
    ) {
        val contact = if (touching) RdpeiFlags.INCONTACT else 0
        val flags = (if (penInRange) RdpeiFlags.UPDATE else RdpeiFlags.DOWN) or RdpeiFlags.INRANGE or contact
        penInRange = true
        penX = x
        penY = y
        val point = penPoint(x, y, flags, touching, pressure, barrel, eraser, tiltX, tiltY)
        lastPen = point
        penFrames += PenFrame(time, point)
    }

    /** Перо ушло из зоны действия экрана: контакт заканчивается. */
    fun penLeave(time: Long) {
        if (!penInRange) return
        penInRange = false
        penFrames += PenFrame(time, PenPoint(penX, penY, RdpeiFlags.UP, 0, 0))
    }

    /** Перо отменено (потеря фокуса, смена режима): `UP|CANCELED`. */
    fun penCancel(time: Long) {
        if (!penInRange) return
        penInRange = false
        penFrames += PenFrame(time, PenPoint(penX, penY, RdpeiFlags.UP or RdpeiFlags.CANCELED, 0, 0))
    }

    /**
     * Повтор неподвижных контактов: настоящий сенсорный экран шлёт кадры непрерывно, а Android при неподвижном пальце
     * событий почти не даёт, и без кадров Windows ведёт удержание хуже (повтор клавиши сенсорной клавиатуры шёл рывками:
     * 3 символа за 1,5 с против 39 с кадрами каждые 10 мс). Если с прошлого кадра прошло не меньше [minGapMs], пальцы
     * на экране и перо в зоне уходят ещё раз на прежних местах (`UPDATE`). Возвращает true, пока есть что повторять.
     */
    fun keepAlive(time: Long, minGapMs: Long): Boolean {
        if (slots.isNotEmpty() && time - lastTouchAt >= minGapMs) {
            val points = slots.values.sorted().map { slot ->
                val xy = last[slot] ?: intArrayOf(0, 0)
                TouchPoint(slot, xy[0], xy[1], RdpeiFlags.UPDATE or RdpeiFlags.INRANGE or RdpeiFlags.INCONTACT)
            }
            touchFrames += TouchFrame(time, points)
        }
        val p = lastPen
        if (penInRange && p != null && time - lastPenAt >= minGapMs) {
            val again = PenPoint(p.x, p.y, (p.flags and RdpeiFlags.DOWN.inv()) or RdpeiFlags.UPDATE, p.penFlags, p.pressure, p.tiltX, p.tiltY)
            lastPen = again
            penFrames += PenFrame(time, again)
        }
        flush()
        return slots.isNotEmpty() || penInRange
    }

    /** Отдаёт накопленные кадры одним вызовом на пальцы и одним на перо. */
    fun flush() {
        if (touchFrames.isNotEmpty()) {
            val frames = ArrayList(touchFrames)
            touchFrames.clear()
            lastTouchAt = frames.last().timeMs
            out.touch(frames)
        }
        if (penFrames.isNotEmpty()) {
            val frames = ArrayList(penFrames)
            penFrames.clear()
            lastPenAt = frames.last().timeMs
            out.pen(frames)
        }
    }
}

/**
 * Щелчок в нативном режиме: один контакт, отпущенный быстрее [maxMs] и не ушедший дальше порога сдвига. Агенту нужен
 * этот признак, чтобы решить про сенсорную клавиатуру: щелчок мышью он видит сам (ловушка мыши), а касания Windows
 * из сеанса RDP в его ловушку не попадают. Долгое касание (контекстное меню Windows) щелчком не считается.
 */
class NativeTapDetector(private val maxMs: Long) {
    private var downAt = 0L
    private var x0 = 0f
    private var y0 = 0f
    private var armed = false

    fun down(x: Float, y: Float, time: Long) {
        downAt = time
        x0 = x
        y0 = y
        armed = true
    }

    /** Контакт сдвинулся; дальше [slop] — это уже прокрутка или перетаскивание. */
    fun move(x: Float, y: Float, slop: Float) {
        if (armed && Math.hypot((x - x0).toDouble(), (y - y0).toDouble()) > slop) armed = false
    }

    /** Второй палец, отмена, уход пера: жест щелчком не будет. */
    fun cancel() {
        armed = false
    }

    /** Контакт отпущен: true — это был щелчок. */
    fun up(time: Long): Boolean {
        val tap = armed && time - downAt < maxMs
        armed = false
        return tap
    }
}

/**
 * Наклон пера для Windows. Android сообщает угол от перпендикуляра к экрану ([tilt], `AXIS_TILT`, 0…π/2) и сторону, куда
 * перо наклонено ([orientation], `AXIS_ORIENTATION`, −π…π: 0 — верхний конец пера смотрит к верху экрана, по часовой
 * стрелке положительно); Windows ждёт наклон отдельно по осям X и Y в градусах (−90…90). Знак по Y — по расположению осей
 * экрана (Y вниз): перо, склонённое верхним концом вверх, даёт отрицательный наклон. На ПК знаки не проверялись.
 */
object PenTilt {
    /** Пара (tiltX, tiltY) в градусах; перо стоит прямо — (0, 0). */
    fun fromAndroid(tilt: Float, orientation: Float): IntArray {
        if (tilt < 0.01f) return intArrayOf(0, 0)
        val t = Math.tan(tilt.coerceAtMost(1.55f).toDouble())
        val x = Math.toDegrees(Math.atan(t * Math.sin(orientation.toDouble())))
        val y = Math.toDegrees(Math.atan(-t * Math.cos(orientation.toDouble())))
        return intArrayOf(Math.round(x).toInt().coerceIn(-90, 90), Math.round(y).toInt().coerceIn(-90, 90))
    }
}
