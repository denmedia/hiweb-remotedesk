package com.hiweb.hiwebremotedesk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import com.hiweb.hiwebremotedesk.rdp.CursorImage
import com.hiweb.hiwebremotedesk.rdp.RdpClient
import com.hiweb.hiwebremotedesk.rdp.RemoteCursor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Показывает кадр удалённого рабочего стола (вписан в экран) и превращает мышь, трекпад и касания
 * в события указателя RDP. Клавиатуру обрабатывает Activity, виртуальная клавиатура не используется.
 */
class RemoteView(context: Context) : View(context) {
    var client: RdpClient? = null

    private val lock = Any()
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private var lastButtons = 0
    private var lastX = -1
    private var lastY = -1

    // Жесты пальцем
    private enum class TouchMode { IDLE, SINGLE, PAN, PENDING, DRAG, SCROLL, PINCH, MULTI }

    private var touchMode = TouchMode.IDLE

    /** Левая кнопка удалённой мыши зажата пальцем (нажатие откладывается до сдвига пальца или отпускания). */
    private var fingerDown = false

    /** Куда палец коснулся экрана; одиночное касание превращается в щелчок именно здесь. */
    private var downX = 0f
    private var downY = 0f

    /** Долгое касание сработало: отпускание без движения — правая кнопка, движение — перетаскивание левой. */
    private var longFired = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPress = Runnable { fireLongPress() }

    /**
     * Где на рабочем столе ПК открыта сенсорная клавиатура Windows (сообщает HiWeb Agent), null — скрыта.
     * Касания, начатые в ней, — набор текста ([keyTouch]), а не жесты.
     */
    var keyboardPane: android.graphics.Rect? = null
    private val keyRepeat = Runnable { fireKeyRepeat() }

    /** Текущая серия касаний началась на сенсорной клавиатуре ПК. */
    private var keyMode = false

    /** Палец, который сейчас держит клавишу (левая кнопка нажата в его точке), или -1. */
    private var keyFinger = -1
    private var keyX = 0f
    private var keyY = 0f

    /** Сколько повторных нажатий клавиши уже отправлено за это удержание (0 — повтора не было). */
    private var keyRepeats = 0
    private var pinchDist = 0f
    private var primaryId = -1
    private var secondId = -1

    /** Перетаскивание уже началось, а второй палец был поднят: следующий второй палец продолжит его без разбора жеста. */
    private var regrip = false
    private var scrollAnchor = 0f
    private var scrollAnchorX = 0f

    /** Касание, которое остановило инерцию прокрутки, щелчком не считается (как в iOS). */
    private var tapSuppressed = false

    /** Предыдущий тап (время отпускания и точка), чтобы второй тап лёг в ту же точку и стал двойным щелчком. */
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** Положение курсора в координатах вида: у первого пальца, затем сдвигается вторым при перетаскивании. */
    private var touchX = 0f
    private var touchY = 0f
    private var secondX = 0f
    private var secondY = 0f
    private var secondDownAt = 0L
    private val twoFinger = TwoFingerClassifier(ViewConfiguration.get(context).scaledTouchSlop.toFloat())

    // Положение курсора на удалённом рабочем столе (пиксели рабочего стола)
    private var cursorX = 0f
    private var cursorY = 0f

    init {
        // Фокус нужен, чтобы запросить захват указателя (тачпад обрабатывает приложение, а не система).
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        setBackgroundColor(Color.BLACK)
    }

    // Смена ориентации: прежняя картинка стола поворачивается, мутнеет и исчезает, когда Windows пришлёт новый размер.
    private class Transition(
        val picture: Bitmap,
        var clock: RotationClock,
        var fromAngle: Float,
        var fromScaleX: Float,
        var fromScaleY: Float,
    )

    private var transition: Transition? = null

    /** Прежняя картинка: ею заливается новый рабочий стол, пока Windows не нарисовала его целиком. */
    @Volatile private var prefill: Bitmap? = null
    private val prefillPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Идущая смена ориентации уже перешла на новый рабочий стол: до этого живой кадр старого размера не рисуется. */
    @Volatile private var desktopFresh = true

    /** Идёт ожидание ответа Windows (картинка размыта, нужен лоадер); вызывается из UI-потока. */
    var onRotationWait: ((Boolean) -> Unit)? = null

    // Сколько площади нового стола Windows уже перерисовала (под [lock]); -1 — не ждём.
    private var awaitCovered = -1L
    private var awaitArea = 0L
    private var awaitFirst = false

    // Размер сменился внутри сеанса: кадры не замолкают, поэтому ждём лишь короткое время после покрытия стола.
    private var liveResize = false
    private var settledAt = 0L
    private val snapshotNode = RenderNode("rotation")
    private val desktopReady: Runnable = Runnable {
        synchronized(lock) { awaitCovered = -1; settledAt = 0 }
        removeCallbacks(desktopQuiet)
        transition?.clock?.ready(SystemClock.uptimeMillis())
        onRotationWait?.invoke(false)
        invalidate()
    }

    // Кадры нового стола перестали приходить: после экрана входа и «Добро пожаловать» Windows присылает сам стол.
    private val desktopQuiet: Runnable = Runnable { desktopReady.run() }

    fun initDesktop(width: Int, height: Int, live: Boolean = false) {
        synchronized(lock) {
            if (bitmap?.width != width || bitmap?.height != height) {
                val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap = created
                cursorX = width / 2f
                cursorY = height / 2f
                prefill?.let { old ->
                    Canvas(created).drawBitmap(old, null, Rect(0, 0, width, height), prefillPaint)
                    awaitCovered = 0
                    awaitArea = width.toLong() * height
                    awaitFirst = false
                    settledAt = 0
                    liveResize = live
                    desktopFresh = true
                }
            }
        }
        post { computeLayout(); invalidate() }
    }

    /** Вызывается из потока RDP. */
    fun applyBitmap(left: Int, top: Int, w: Int, h: Int, argb: IntArray, offset: Int = 0, stride: Int = w) {
        var settled = false
        var first = false
        var quietMs = FRAMES_QUIET_MS
        var capMs = FRAMES_CAP_MS
        synchronized(lock) {
            val b = bitmap ?: return
            if (left < 0 || top < 0 || left + w > b.width || top + h > b.height) return
            b.setPixels(argb, offset, stride, left, top, w, h)
            if (awaitCovered >= 0) {
                awaitCovered += w.toLong() * h
                if (!awaitFirst) {
                    awaitFirst = true
                    first = true
                }
                settled = awaitCovered * 2 >= awaitArea
                if (liveResize) {
                    capMs = LIVE_CAP_MS
                    if (settled) {
                        val now = SystemClock.uptimeMillis()
                        if (settledAt == 0L) settledAt = now
                        // Кадры идут и после смены размера (экран живой): откладываем уход картинки лишь до LIVE_SETTLE_MAX_MS.
                        quietMs = minOf(LIVE_QUIET_MS, maxOf(0L, settledAt + LIVE_SETTLE_MAX_MS - now))
                    }
                }
            }
        }
        // Картинка уходит, когда Windows перерисовала половину стола и кадры затихли: анимация экрана входа или
        // «Добро пожаловать» их не прерывает. Не дольше FRAMES_CAP_MS после первого кадра (на столе может идти видео).
        if (first) postDelayed(desktopReady, capMs)
        if (settled) {
            removeCallbacks(desktopQuiet)
            postDelayed(desktopQuiet, quietMs)
        }
        postInvalidateOnAnimation()
    }

    /**
     * Начало смены ориентации (из UI-потока, до перекладки под новый размер): запоминает картинку стола и
     * поворот дисплея [delta] (1 — на 90° по часовой, 3 — против, см. [SessionRotation.delta]). Картинка стартует
     * там, где пользователь её видел, и за время анимации становится вровень с новым окном.
     */
    fun beginRotation(delta: Int) {
        if (delta == 0 || width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        removeCallbacks(desktopReady)
        // Три четверти оборота по часовой — это четверть против: картинка поворачивается кратчайшим путём.
        val turns = if (delta == 3) -1 else delta
        val current = transition
        if (current != null) {
            // Поворот поверх идущего: продолжаем с того, что сейчас на экране.
            val p = current.clock.progress(now)
            val b = current.picture
            current.fromAngle = current.fromAngle * (1 - p) - turns * 90f
            current.fromScaleX += (width / b.width.toFloat() - current.fromScaleX) * p
            current.fromScaleY += (height / b.height.toFloat() - current.fromScaleY) * p
            current.clock = RotationClock(now, current.clock.blur(now))
        } else {
            val picture = synchronized(lock) { bitmap?.copy(Bitmap.Config.ARGB_8888, false) } ?: return
            transition = Transition(picture, RotationClock(now), -turns * 90f, scale, scale)
            prefill = picture
        }
        desktopFresh = false
        synchronized(lock) { awaitCovered = -1 }
        removeCallbacks(desktopQuiet)
        onRotationWait?.invoke(true)
        invalidate()
    }

    /** Новый размер стола не понадобился (поворот отменён или размер тот же): прежняя картинка просто исчезает. */
    fun finishRotation() {
        desktopFresh = true
        removeCallbacks(desktopReady)
        post(desktopReady)
    }

    private fun endTransition() {
        removeCallbacks(desktopReady)
        removeCallbacks(desktopQuiet)
        onRotationWait?.invoke(false)
        transition = null
        prefill = null
        desktopFresh = true
    }

    private fun drawTransition(canvas: Canvas, tr: Transition, now: Long) {
        val b = tr.picture
        val p = tr.clock.progress(now)
        val blur = tr.clock.blur(now)
        val sx = tr.fromScaleX + (width / b.width.toFloat() - tr.fromScaleX) * p
        val sy = tr.fromScaleY + (height / b.height.toFloat() - tr.fromScaleY) * p
        if (!canvas.isHardwareAccelerated) {
            canvas.save()
            canvas.translate(width / 2f, height / 2f)
            canvas.rotate(tr.fromAngle * (1 - p))
            canvas.scale(sx, sy)
            canvas.translate(-b.width / 2f, -b.height / 2f)
            paint.alpha = (tr.clock.alpha(now) * 255).toInt()
            canvas.drawBitmap(b, 0f, 0f, paint)
            paint.alpha = 255
            canvas.restore()
            return
        }
        snapshotNode.setPosition(0, 0, width, height)
        val rc = snapshotNode.beginRecording(width, height)
        rc.translate(width / 2f, height / 2f)
        rc.rotate(tr.fromAngle * (1 - p))
        rc.scale(sx, sy)
        rc.translate(-b.width / 2f, -b.height / 2f)
        rc.drawBitmap(b, 0f, 0f, paint)
        snapshotNode.endRecording()
        val radius = BLUR_RADIUS_DP * resources.displayMetrics.density * blur
        snapshotNode.setRenderEffect(if (radius < 0.5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        snapshotNode.setAlpha(tr.clock.alpha(now))
        canvas.drawRenderNode(snapshotNode)
    }

    fun snapshot(): Bitmap? = synchronized(lock) { bitmap?.copy(Bitmap.Config.ARGB_8888, false) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    private fun computeLayout() {
        val b = synchronized(lock) { bitmap } ?: return
        if (width == 0 || height == 0) return
        scale = min(width / b.width.toFloat(), height / b.height.toFloat())
        offX = (width - b.width * scale) / 2f
        offY = (height - b.height * scale) / 2f
        if (remoteCursor != null) updatePointerIcon() // размер значка зависит от масштаба
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val tr = transition
        val now = SystemClock.uptimeMillis()
        // Новый стол (экран входа Windows, «Добро пожаловать») не должен просвечивать из-за вращающейся картинки:
        // он показывается только под исчезающей.
        if (tr == null || (desktopFresh && tr.clock.alpha(now) < 1f)) {
            synchronized(lock) {
                val b = bitmap ?: return
                canvas.save()
                canvas.translate(offX, offY)
                canvas.scale(scale, scale)
                canvas.drawBitmap(b, 0f, 0f, paint)
                canvas.restore()
            }
        }
        if (tr != null) {
            if (tr.clock.finished(now)) {
                endTransition()
                invalidate()
            } else {
                drawTransition(canvas, tr, now)
                postInvalidateOnAnimation()
            }
            return
        }
        if (captured && (!fingerInput || fingerCursorShown())) drawCursor(canvas)
    }

    /** Последним вводом был палец на экране, а не тачпад или мышь: курсор не рисуется, пока палец не в режиме перетаскивания. */
    private var fingerInput = false
    private var fingerCursorWasShown = false

    /**
     * Пальцем курсор нужен, только когда он что-то делает: долгое касание (до отпускания — правая кнопка, дальше —
     * перетаскивание с левой) и перетаскивание вторым пальцем. Обычный тап, прокрутка и жесты курсора не показывают.
     */
    private fun fingerCursorShown(): Boolean = when (touchMode) {
        TouchMode.SINGLE -> longFired || (regrip && fingerDown)
        TouchMode.DRAG -> true
        else -> false
    }

    /** Перерисовка, когда курсор под пальцем появился, пропал или двигается. */
    private fun refreshFingerCursor() {
        val shown = fingerCursorShown()
        if (shown || shown != fingerCursorWasShown) postInvalidateOnAnimation()
        fingerCursorWasShown = shown
    }

    /** Ввод тачпадом или мышью возвращает курсор. */
    private fun pointerInput() {
        if (!fingerInput) return
        fingerInput = false
        postInvalidateOnAnimation()
    }

    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val cursorPath = Path()

    private class CursorArt(val image: CursorImage, val color: Bitmap, val invert: Bitmap?)

    /** Последний курсор, присланный Windows; null — сервер ещё ничего не присылал (рисуем стрелку). */
    @Volatile private var remoteCursor: RemoteCursor? = null
    @Volatile private var cursorArt: CursorArt? = null
    private val invertPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DIFFERENCE }

    // Windows по RDP присылает только «стрелку» (PTR_DEFAULT) при любой смене курсора; пока агент на ПК сообщает
    // настоящую форму, эта стрелка её не перекрывает.
    @Volatile private var agentCursor = false

    /** Курсор от сервера RDP. Вызывается из потока RDP. */
    fun setRemoteCursor(cursor: RemoteCursor) {
        if (agentCursor && cursor === RemoteCursor.Default) return
        applyCursor(cursor)
    }

    /** Курсор от агента на ПК; null — агент пропал, возвращаемся к серверному. Вызывается из любого потока. */
    fun setAgentCursor(cursor: RemoteCursor?) {
        agentCursor = cursor != null
        applyCursor(cursor ?: RemoteCursor.Default)
    }

    private fun applyCursor(cursor: RemoteCursor) {
        cursorArt = (cursor as? CursorImage)?.let { img ->
            val color = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
            color.setPixels(img.argb, 0, img.width, 0, 0, img.width, img.height)
            val invert = img.invert?.let { mask ->
                // Белые пиксели с режимом DIFFERENCE инвертируют фон под курсором, как на ПК.
                Bitmap.createBitmap(IntArray(mask.size) { if (mask[it]) Color.WHITE else 0 }, img.width, img.height, Bitmap.Config.ARGB_8888)
            }
            CursorArt(img, color, invert)
        }
        remoteCursor = cursor
        post { updatePointerIcon(); invalidate() }
    }

    /** Курсор для обычной мыши, когда указатель не захвачен: системный курсор заменяется курсором Windows. */
    private fun updatePointerIcon() {
        val arrow = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW)
        pointerIcon = when (remoteCursor) {
            null -> null
            RemoteCursor.Default -> arrow
            RemoteCursor.Hidden -> PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
            else -> cursorArt?.let { iconFor(it.image) } ?: arrow
        }
    }

    private fun iconFor(img: CursorImage): PointerIcon {
        val px = img.argb.copyOf()
        img.invert?.let { mask ->
            // Системный курсор не умеет инвертировать фон: рисуем чёрным с белой каймой, видимым на любом фоне.
            val halo = BooleanArray(px.size)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                if (!mask[y * img.width + x]) continue
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until img.width && ny in 0 until img.height) halo[ny * img.width + nx] = true
                }
            }
            for (i in px.indices) if (halo[i] && px[i] ushr 24 == 0) px[i] = Color.WHITE
            for (i in px.indices) if (mask[i]) px[i] = Color.BLACK
        }
        val bmp = Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
        val k = scale.coerceAtLeast(1f)
        val scaled = Bitmap.createScaledBitmap(bmp, (img.width * k).roundToInt(), (img.height * k).roundToInt(), true)
        return PointerIcon.create(
            scaled,
            (img.hotX * k).coerceIn(0f, scaled.width - 1f),
            (img.hotY * k).coerceIn(0f, scaled.height - 1f),
        )
    }

    /** При захвате указателя системный курсор скрыт, поэтому рисуем курсор Windows (или стрелку, пока он неизвестен). */
    private fun drawCursor(canvas: Canvas) {
        val c = remoteCursor
        if (c === RemoteCursor.Hidden) return
        val art = cursorArt
        if (c is CursorImage && art != null) {
            canvas.save()
            canvas.translate(offX + cursorX * scale, offY + cursorY * scale)
            canvas.scale(scale, scale)
            canvas.drawBitmap(art.color, -art.image.hotX.toFloat(), -art.image.hotY.toFloat(), paint)
            art.invert?.let { canvas.drawBitmap(it, -art.image.hotX.toFloat(), -art.image.hotY.toFloat(), invertPaint) }
            canvas.restore()
            return
        }
        drawArrow(canvas)
    }

    private fun drawArrow(canvas: Canvas) {
        val k = resources.displayMetrics.density * 1.15f
        cursorPath.reset()
        cursorPath.moveTo(0f, 0f)
        cursorPath.lineTo(0f, 17f * k)
        cursorPath.lineTo(4.5f * k, 13.2f * k)
        cursorPath.lineTo(7.5f * k, 20f * k)
        cursorPath.lineTo(10.5f * k, 18.7f * k)
        cursorPath.lineTo(7.6f * k, 12.2f * k)
        cursorPath.lineTo(12.5f * k, 12.2f * k)
        cursorPath.close()
        canvas.save()
        canvas.translate(offX + cursorX * scale, offY + cursorY * scale)
        canvas.drawPath(cursorPath, cursorFill)
        canvas.drawPath(cursorPath, cursorStroke)
        canvas.restore()
    }

    // ------------------------------------------------------------------ ввод

    private fun remoteX(x: Float) = ((x - offX) / scale).roundToInt()
    private fun remoteY(y: Float) = ((y - offY) / scale).roundToInt()

    private fun move(x: Float, y: Float) {
        val rx = remoteX(x)
        val ry = remoteY(y)
        if (rx == lastX && ry == lastY) return
        lastX = rx; lastY = ry
        cursorX = rx.toFloat(); cursorY = ry.toFloat()
        client?.sendMouse(RdpClient.PTR_MOVE, rx, ry)
    }

    private fun syncButtons(e: MotionEvent) = applyButtons(e.buttonState, remoteX(e.x), remoteY(e.y))

    private fun applyButtons(state: Int, rx: Int, ry: Int) {
        val changed = state xor lastButtons
        if (changed == 0) return
        // Дополнительные кнопки в журнале: видно, дошло ли нажатие до приложения или его съела система.
        if (changed and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_BACK or MotionEvent.BUTTON_FORWARD) != 0) {
            Log.i("Input", "mouse buttons state=$state changed=$changed")
        }
        for (mask in MOUSE_BUTTONS) {
            if (changed and mask != 0) sendButton(mask, state and mask != 0, rx, ry)
        }
        lastButtons = state
        lastX = rx; lastY = ry
    }

    /** Кнопка мыши на ПК: левая, правая и средняя — обычными флагами, «назад»/«вперёд» — боковыми (XBUTTON). */
    private fun sendButton(androidMask: Int, down: Boolean, x: Int, y: Int) {
        val c = client ?: return
        val d = if (down) RdpClient.PTR_DOWN else 0
        when (androidMask) {
            MotionEvent.BUTTON_PRIMARY -> c.sendMouse(RdpClient.PTR_LEFT or d, x, y)
            MotionEvent.BUTTON_SECONDARY -> c.sendMouse(RdpClient.PTR_RIGHT or d, x, y)
            MotionEvent.BUTTON_TERTIARY -> c.sendMouse(RdpClient.PTR_MIDDLE or d, x, y)
            MotionEvent.BUTTON_BACK -> c.sendMouseX(RdpClient.PTRX_BACK, down, x, y)
            MotionEvent.BUTTON_FORWARD -> c.sendMouseX(RdpClient.PTRX_FORWARD, down, x, y)
        }
    }

    /**
     * Кнопка мыши, пришедшая клавишей: Samsung превращает среднюю, «назад» и «вперёд» в Home/Back/Forward по настройкам
     * ОС. [androidMask] — `MotionEvent.BUTTON_TERTIARY/BACK/FORWARD`. Бит в [lastButtons] общий с обычным путём, поэтому
     * если кнопка придёт ещё и событием мыши, на ПК она нажмётся и отпустится один раз.
     */
    fun mouseButtonKey(androidMask: Int, down: Boolean) {
        val held = lastButtons and androidMask != 0
        if (down == held) return
        lastButtons = if (down) lastButtons or androidMask else lastButtons and androidMask.inv()
        sendButton(androidMask, down, if (lastX >= 0) lastX else cursorX.roundToInt(), if (lastY >= 0) lastY else cursorY.roundToInt())
    }

    /** Колесо в точке, где сейчас курсор; [units] в единицах RDP (120 — щелчок), > 0 — вверх/вправо. */
    private fun sendWheel(vertical: Boolean, units: Int) {
        val rot = units.coerceIn(-255, 255)
        if (rot == 0) return
        val b = synchronized(lock) { bitmap }
        val x = if (lastX >= 0) lastX else (b?.width ?: 0) / 2
        val y = if (lastY >= 0) lastY else (b?.height ?: 0) / 2
        val flag = if (vertical) RdpClient.PTR_WHEEL else RdpClient.PTR_HWHEEL
        client?.sendMouse(flag or (rot and 0x1FF), x, y)
    }

    /** Жесты тачпада (прокрутка, щипок, свайпы несколькими пальцами) переводятся в действия Windows. */
    private val gestureOutput = object : GestureOutput {
        override fun wheel(vertical: Boolean, units: Int) = sendWheel(vertical, units)
        override fun key(code: Int, extended: Boolean, down: Boolean) {
            client?.sendKey(code, extended, down)
        }
    }
    private val gestures = TouchpadGestures(gestureOutput)

    /** Жесты тремя-пятью пальцами на сенсорном экране в духе iPadOS (отмена, копирование, переключение приложений). */
    private val touchGestures = TouchscreenGestures(gestureOutput, resources.displayMetrics.density)

    // ------------------------------------------------------------------ захват тачпада

    private val touchpadOutput = object : TouchpadOutput {
        override fun moveCursor(dx: Float, dy: Float) = moveCursorBy(dx, dy)

        override fun button(button: Int, down: Boolean) {
            val flag = when (button) {
                TouchpadEngine.BTN_RIGHT -> RdpClient.PTR_RIGHT
                TouchpadEngine.BTN_MIDDLE -> RdpClient.PTR_MIDDLE
                else -> RdpClient.PTR_LEFT
            }
            client?.sendMouse(flag or (if (down) RdpClient.PTR_DOWN else 0), cursorX.roundToInt(), cursorY.roundToInt())
        }

        override fun wheel(vertical: Boolean, units: Int) = sendWheel(vertical, units)

        override fun key(code: Int, extended: Boolean, down: Boolean) {
            client?.sendKey(code, extended, down)
        }
    }

    private var engine = TouchpadEngine(touchpadOutput)
    private var captured = false
    private var padUnitsKnown = false
    private var scrollStrength = 1f
    private var inertiaStrength = 1f

    /** Сила прокрутки и инерции из настроек приложения (1 — по умолчанию); действует на все виды прокрутки пальцами. */
    fun setScrollSettings(scroll: Float, inertia: Float) {
        scrollStrength = scroll
        inertiaStrength = inertia
        gestures.scrollStrength = scroll
        gestures.inertiaStrength = inertia
        engine.scrollStrength = scroll
        engine.inertiaStrength = inertia
    }

    private var desktop = DesktopProfile.WINDOWS

    /** Сочетания клавиш для свайпов тремя и четырьмя пальцами зависят от ОС удалённого ПК. */
    fun setDesktopProfile(profile: DesktopProfile) {
        desktop = profile
        gestures.desktop = profile
        touchGestures.desktop = profile
        engine.desktop = profile
    }
    private var padUnits = TouchpadEngine.DEFAULT_UNITS_PER_MM

    /**
     * Скорость курсора физической (Bluetooth) мыши из настроек ПК (1 — по умолчанию, 2 — вдвое быстрее).
     * Тачпада чехла не касается: у него своё ускорение в [TouchpadEngine].
     */
    private var mouseSpeed = 1f

    /** Скорость перемещения курсора мышью; применяется сразу, без переподключения. */
    fun setMouseSpeed(speed: Float) {
        mouseSpeed = speed
    }

    private var mouseWheelSpeed = 1f

    /** Скорость прокрутки колёсиком физической мыши (1 — щелчок как в Windows); пальцев и тачпада не касается. */
    fun setMouseWheelSpeed(speed: Float) {
        mouseWheelSpeed = speed
    }

    /** Колесо физической мыши: ось колеса в щелчках, масштабируется настройкой скорости колёсика. */
    private fun mouseWheel(e: MotionEvent) {
        gestures.scrollNotches(true, e.getAxisValue(MotionEvent.AXIS_VSCROLL) * mouseWheelSpeed)
        gestures.scrollNotches(false, e.getAxisValue(MotionEvent.AXIS_HSCROLL) * mouseWheelSpeed)
    }

    /**
     * Захват указателя: Android отдаёт приложению сырые касания тачпада и сам не обрабатывает на нём жесты
     * (домой, недавние и т. д.), поэтому жесты тремя-четырьмя пальцами доходят до удалённого рабочего стола.
     */
    var captureEnabled = false
        set(value) {
            field = value
            applyCapture()
        }

    /** Соответствие осей тачпада осям экрана (результат калибровки). */
    var touchpadAxes = TouchpadAxes()
        set(value) {
            field = value
            engine.axes = value
        }

    /** Шаг калибровки изменился (null — калибровка завершена или отменена); вызывается в главном потоке. */
    var onCalibrationStep: ((TouchpadCalibrator.Step?) -> Unit)? = null

    /** Калибровка завершена. */
    var onCalibrated: ((TouchpadAxes) -> Unit)? = null

    private var calibrator: TouchpadCalibrator? = null
    private var calibId = -1
    private var calibX = 0f
    private var calibY = 0f

    private val padIds = IntArray(MAX_FINGERS)
    private val padXs = FloatArray(MAX_FINGERS)
    private val padYs = FloatArray(MAX_FINGERS)

    fun startCalibration() {
        calibrator = TouchpadCalibrator(padUnits)
        calibId = -1
        onCalibrationStep?.invoke(TouchpadCalibrator.Step.RIGHT)
    }

    fun cancelCalibration() {
        if (calibrator != null) {
            calibrator = null
            onCalibrationStep?.invoke(null)
        }
    }

    private fun applyCapture() {
        if (captureEnabled && hasWindowFocus() && client != null) {
            requestFocus()
            requestPointerCapture()
        } else if (hasPointerCapture()) {
            releasePointerCapture()
        }
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        captured = hasCapture
        if (!hasCapture) engine.cancel()
        postInvalidateOnAnimation()
    }

    override fun onCapturedPointerEvent(e: MotionEvent): Boolean = when {
        e.isFromSource(InputDevice.SOURCE_TOUCHPAD) -> { handleTouchpad(e); true }
        e.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) -> { handleRelativeMouse(e); true }
        else -> false
    }

    private fun moveCursorBy(dx: Float, dy: Float) {
        val b = synchronized(lock) { bitmap } ?: return
        cursorX = (cursorX + dx).coerceIn(0f, b.width - 1f)
        cursorY = (cursorY + dy).coerceIn(0f, b.height - 1f)
        postInvalidateOnAnimation()
        val rx = cursorX.roundToInt()
        val ry = cursorY.roundToInt()
        if (rx == lastX && ry == lastY) return
        lastX = rx
        lastY = ry
        client?.sendMouse(RdpClient.PTR_MOVE, rx, ry)
    }

    private fun learnPadUnits(e: MotionEvent) {
        padUnitsKnown = true
        val res = e.device?.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)?.resolution ?: 0f
        if (res > 0f && res != padUnits) {
            padUnits = res
            engine = TouchpadEngine(touchpadOutput, touchpadAxes, res)
            engine.scrollStrength = scrollStrength
            engine.inertiaStrength = inertiaStrength
            engine.desktop = desktop
        }
        Log.i("Touchpad", "capture: units/mm=$padUnits rotation=${display?.rotation}")
    }

    private fun handleTouchpad(e: MotionEvent) {
        pointerInput()
        if (!padUnitsKnown) learnPadUnits(e)
        val action = e.actionMasked
        var n = 0
        for (i in 0 until minOf(e.pointerCount, MAX_FINGERS)) {
            val lifting = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
                (action == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex)
            if (lifting) continue
            padIds[n] = e.getPointerId(i)
            padXs[n] = e.getAxisValue(MotionEvent.AXIS_X, i)
            padYs[n] = e.getAxisValue(MotionEvent.AXIS_Y, i)
            n++
        }
        calibrator?.let { calibrate(it, n); return }
        engine.onFrame(e.eventTime, padIds, padXs, padYs, n, e.buttonState)
        ensureFling()
    }

    private fun calibrate(cal: TouchpadCalibrator, n: Int) {
        if (n != 1) {
            calibId = -1
            if (n == 0) cal.onLift()
            return
        }
        if (calibId == padIds[0] && cal.onDelta(padXs[0] - calibX, padYs[0] - calibY)) {
            if (cal.step == TouchpadCalibrator.Step.DONE) {
                val axes = cal.result!!
                touchpadAxes = axes
                calibrator = null
                onCalibrated?.invoke(axes)
                onCalibrationStep?.invoke(null)
            } else {
                onCalibrationStep?.invoke(cal.step)
            }
        }
        calibId = padIds[0]
        calibX = padXs[0]
        calibY = padYs[0]
    }

    /** Мышь в режиме захвата присылает относительные смещения в пикселях экрана. */
    private fun handleRelativeMouse(e: MotionEvent) {
        pointerInput()
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE ->
                moveCursorBy(
                    e.getAxisValue(MotionEvent.AXIS_RELATIVE_X) / scale * mouseSpeed,
                    e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) / scale * mouseSpeed,
                )
            MotionEvent.ACTION_SCROLL -> mouseWheel(e)
        }
        applyButtons(e.buttonState, cursorX.roundToInt(), cursorY.roundToInt())
    }

    // ------------------------------------------------------------------ инерция прокрутки

    private var flingRunning = false
    private val flingFrame = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val a = gestures.flingStep(now)
            val b = engine.tick(now)
            if (a || b) postOnAnimation(this) else flingRunning = false
        }
    }

    /** Запускает покадровую инерцию, если она началась (сенсорный экран, тачпад в обычном режиме или в захвате). */
    private fun ensureFling() {
        if (!flingRunning && (gestures.isFlinging || engine.isFlinging)) {
            flingRunning = true
            postOnAnimation(flingFrame)
        }
    }

    private var gestureLogCounter = 0

    private companion object {
        const val MAX_FINGERS = 8

        /** Размытие прежней картинки при смене ориентации (радиус в dp при полной степени). */
        const val BLUR_RADIUS_DP = 26f

        /** Сколько кадры нового стола должны молчать, прежде чем убирать прежнюю картинку, и самый долгий срок ожидания после первого кадра. */
        const val FRAMES_QUIET_MS = 500L
        const val FRAMES_CAP_MS = 5_000L

        /** Смена размера внутри сеанса: затишье, самый долгий срок после покрытия стола и после первого кадра. */
        const val LIVE_QUIET_MS = 120L
        const val LIVE_SETTLE_MAX_MS = 400L
        const val LIVE_CAP_MS = 1_500L

        /** Кнопки мыши Android, которые пересылаются на ПК. */
        val MOUSE_BUTTONS = intArrayOf(
            MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY,
            MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD,
        )

        /** Через сколько палец, не сдвигаясь, даёт правую кнопку (долгое касание, как в iPadOS). */
        const val LONG_PRESS_MS = 500L

        /** Удержание клавиши сенсорной клавиатуры ПК: первый повтор через столько, дальше — с периодом KEY_REPEAT_MS. */
        const val KEY_REPEAT_DELAY_MS = 450L
        const val KEY_REPEAT_MS = 60L

        /** Два тапа не дальше друг от друга и не позже этого интервала — двойной щелчок в одной точке. */
        const val DOUBLE_TAP_MS = 500L
        // Не больше половины клавиши экранной клавиатуры: иначе быстрый тап по соседней клавише попадает в предыдущую.
        const val DOUBLE_TAP_DP = 24f

        /** Скорость инерции (щелчков в секунду), выше которой касание только останавливает прокрутку, без щелчка. */
        const val FLING_TAP_GUARD = 4f

        /** Пикселей экрана на один щелчок колеса при прокрутке двумя пальцами по сенсорному экрану. */
        const val TOUCH_PX_PER_NOTCH = 80f

        /** MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE (в открытом SDK пока не опубликована). */
        const val CLASSIFICATION_MULTI_FINGER_SWIPE = 4
    }

    /**
     * Android распознаёт жесты тачпада сам и отдаёт их как события с классификацией (а не как колесо мыши):
     * прокрутка двумя пальцами, щипок и свайпы тремя и более пальцами. Возвращает true, если событие обработано.
     */
    private fun handleGesture(e: MotionEvent): Boolean {
        val cls = e.classification
        if (cls != MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE &&
            cls != MotionEvent.CLASSIFICATION_PINCH &&
            cls != CLASSIFICATION_MULTI_FINGER_SWIPE
        ) return false
        if (Build.VERSION.SDK_INT < 34) return true
        val action = e.actionMasked
        val end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val move = action == MotionEvent.ACTION_MOVE
        when (cls) {
            MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE -> when {
                move -> gestures.scrollDistance(
                    e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE),
                    e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE),
                    e.eventTime,
                )
                action == MotionEvent.ACTION_UP -> { gestures.scrollRelease(e.eventTime); ensureFling() }
                end -> gestures.scrollEnd()
            }
            MotionEvent.CLASSIFICATION_PINCH -> when {
                action == MotionEvent.ACTION_DOWN -> gestures.pinchStart()
                move -> gestures.pinch(e.getAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR))
                end -> gestures.pinchEnd()
            }
            CLASSIFICATION_MULTI_FINGER_SWIPE -> when {
                action == MotionEvent.ACTION_DOWN -> gestures.swipeStart(e.pointerCount)
                move -> gestures.swipe(
                    e.getAxisValue(MotionEvent.AXIS_GESTURE_X_OFFSET),
                    e.getAxisValue(MotionEvent.AXIS_GESTURE_Y_OFFSET),
                )
                action == MotionEvent.ACTION_UP -> gestures.swipeEnd()
                action == MotionEvent.ACTION_CANCEL -> gestures.cancelAll()
            }
        }
        // Временная диагностика: помогает сверить знаки осей с реальным тачпадом.
        if (!move || gestureLogCounter++ % 8 == 0) {
            Log.d(
                "Touchpad",
                "cls=$cls action=$action ptrs=${e.pointerCount} scrollX=${e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE)} " +
                    "scrollY=${e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE)} " +
                    "pinch=${e.getAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR)} " +
                    "offX=${e.getAxisValue(MotionEvent.AXIS_GESTURE_X_OFFSET)} offY=${e.getAxisValue(MotionEvent.AXIS_GESTURE_Y_OFFSET)}",
            )
        }
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            gestures.cancelAll()
            engine.cancel()
        }
        applyCapture()
    }

    override fun onDetachedFromWindow() {
        dropLongPress()
        removeCallbacks(keyRepeat)
        touchGestures.cancel()
        gestures.cancelAll()
        engine.cancel()
        super.onDetachedFromWindow()
    }

    private fun isMouse(e: MotionEvent) =
        e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE || e.isFromSource(InputDevice.SOURCE_MOUSE) || e.isFromSource(InputDevice.SOURCE_TOUCHPAD)

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (handleGesture(e)) return true
        if (!isMouse(e)) return super.onGenericMotionEvent(e)
        pointerInput()
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> { move(e.x, e.y); syncButtons(e) }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> syncButtons(e)
            MotionEvent.ACTION_SCROLL -> {
                move(e.x, e.y)
                mouseWheel(e)
            }
            else -> return super.onGenericMotionEvent(e)
        }
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (handleGesture(e)) return true
        if (isMouse(e)) {
            pointerInput()
            move(e.x, e.y)
            syncButtons(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                // Гарантируем отпускание кнопок, даже если buttonState пришёл нулевым.
                lastButtons = 0
            }
            return true
        }
        fingerInput = true
        fingerTouch(e)
        refreshFingerCursor()
        return true
    }

    /**
     * Сенсорный экран как на iOS: касание — щелчок, движение одного пальца — прокрутка по обеим осям с инерцией;
     * долгое касание: отпустить — правая кнопка (контекстное меню), повести — перетаскивание элемента с левой кнопкой.
     * Два пальца: прокрутка, щипок — масштаб (Ctrl + колесо), а при неподвижном первом пальце второй ведёт курсор
     * (первый держит кнопку). Три, четыре и пять пальцев — свайпы и щипки
     * ([TouchscreenGestures]). Левая кнопка нажимается только когда становится ясно, что это перетаскивание, поэтому
     * прокрутка, щипок и долгое касание не оставляют случайных щелчков.
     */
    private fun fingerTouch(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val pane = keyboardPane
            keyMode = pane != null && pane.contains(remoteX(e.x), remoteY(e.y))
        }
        if (keyMode) {
            keyTouch(e)
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Касание останавливает инерцию; щелчком оно не считается, только пока экран заметно прокручивается,
                // иначе тап по кнопке сразу после прокрутки теряется на затухающем «хвосте» инерции.
                tapSuppressed = gestures.flingSpeed >= FLING_TAP_GUARD
                gestures.stopFling()
                dropLongPress()
                primaryId = e.getPointerId(0)
                secondId = -1
                regrip = false
                longFired = false
                touchMode = TouchMode.SINGLE
                touchX = e.x
                touchY = e.y
                downX = e.x
                downY = e.y
                fingerDown = false
                postDelayed(longPress, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gestures.stopFling()
                if (e.pointerCount >= 3) {
                    startMulti(e)
                } else if (e.pointerCount == 2) {
                    dropLongPress()
                    secondId = e.getPointerId(e.actionIndex)
                    val i1 = e.findPointerIndex(primaryId)
                    val i2 = e.findPointerIndex(secondId)
                    secondX = e.getX(i2)
                    secondY = e.getY(i2)
                    secondDownAt = e.eventTime
                    scrollAnchor = (e.getY(0) + e.getY(1)) / 2f
                    scrollAnchorX = (e.getX(0) + e.getX(1)) / 2f
                    if (touchMode != TouchMode.SINGLE || i1 < 0 || longFired) {
                        if (touchMode == TouchMode.PAN) gestures.scrollEnd()
                        releaseLeft()
                        move(scrollAnchorX, scrollAnchor)
                        touchMode = TouchMode.SCROLL
                    } else if (regrip) {
                        touchMode = TouchMode.DRAG
                    } else {
                        if (!fingerDown) {
                            touchX = e.getX(i1)
                            touchY = e.getY(i1)
                        }
                        twoFinger.begin(e.getX(i1), e.getY(i1), secondX, secondY)
                        touchMode = TouchMode.PENDING
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                when (touchMode) {
                    TouchMode.SCROLL -> {
                        // Один из двух пальцев поднят: прокрутка завершена, дальше — по инерции.
                        touchMode = TouchMode.IDLE
                        gestures.scrollRelease(e.eventTime)
                        ensureFling()
                    }
                    TouchMode.PINCH -> {
                        gestures.pinchEnd()
                        touchMode = TouchMode.IDLE
                    }
                    TouchMode.MULTI -> {
                        touchGestures.end()
                        touchMode = TouchMode.IDLE
                    }
                    TouchMode.PENDING, TouchMode.DRAG -> if (touchMode == TouchMode.PENDING && isRolloverTap(e)) {
                        rolloverTap(e, id)
                    } else if (id == primaryId) {
                        // Первый палец поднят: кнопка отпускается там, где сейчас курсор.
                        releaseLeft()
                        touchMode = TouchMode.IDLE
                    } else {
                        regrip = touchMode == TouchMode.DRAG
                        touchMode = TouchMode.SINGLE
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchMode == TouchMode.PENDING) classifyTwoFingers(e)
                when (touchMode) {
                    TouchMode.SINGLE -> singleFingerMove(e)
                    TouchMode.DRAG -> dragWithSecondFinger(e)
                    TouchMode.SCROLL -> if (e.pointerCount >= 2) {
                        // Контент следует за пальцами; запоминаем движение, чтобы после отрыва прокрутка шла по инерции.
                        val x = (e.getX(0) + e.getX(1)) / 2f
                        val y = (e.getY(0) + e.getY(1)) / 2f
                        gestures.touchScroll(
                            e.eventTime, (y - scrollAnchor) / TOUCH_PX_PER_NOTCH, -(x - scrollAnchorX) / TOUCH_PX_PER_NOTCH,
                        )
                        scrollAnchor = y
                        scrollAnchorX = x
                    }
                    TouchMode.PAN -> {
                        gestures.touchScroll(
                            e.eventTime, (e.y - scrollAnchor) / TOUCH_PX_PER_NOTCH, -(e.x - scrollAnchorX) / TOUCH_PX_PER_NOTCH,
                        )
                        scrollAnchor = e.y
                        scrollAnchorX = e.x
                    }
                    TouchMode.PINCH -> if (e.pointerCount >= 2) {
                        val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        if (pinchDist > 0f && d > 0f) gestures.pinch(d / pinchDist)
                        pinchDist = d
                    }
                    TouchMode.MULTI -> {
                        val m = multiMetrics(e)
                        touchGestures.update(e.pointerCount, m[0], m[1], m[2])
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dropLongPress()
                val up = e.actionMasked == MotionEvent.ACTION_UP
                if (up && touchMode == TouchMode.SINGLE && !fingerDown && !regrip && !tapSuppressed) {
                    // Простое касание — щелчок на месте касания, долгое без движения — правая кнопка.
                    if (longFired) rightClick(downX, downY) else tap(downX, downY, e.eventTime)
                }
                releaseLeft()
                when (touchMode) {
                    TouchMode.SCROLL, TouchMode.PAN -> if (up) {
                        gestures.scrollRelease(e.eventTime)
                        ensureFling()
                    } else {
                        gestures.scrollEnd()
                    }
                    TouchMode.PINCH -> gestures.pinchEnd()
                    TouchMode.MULTI -> if (up) touchGestures.end() else touchGestures.cancel()
                    else -> {}
                }
                touchMode = TouchMode.IDLE
                regrip = false
            }
        }
    }

    /** Палец сдвинулся дальше порога дрожания: до долгого касания — прокрутка, после него — перетаскивание. */
    private fun singleFingerMove(e: MotionEvent) {
        if (regrip) return
        if (!fingerDown) {
            if (hypot(e.x - downX, e.y - downY) < touchSlop) return
            dropLongPress()
            if (!longFired) {
                // Быстрое движение — прокрутка по обеим осям (контент следует за пальцем), как на iOS.
                touchMode = TouchMode.PAN
                // Колесо уходит окну под курсором, поэтому курсор сначала ставится под палец — без предварительного тапа.
                move(downX, downY)
                gestures.touchScroll(
                    e.eventTime, (e.y - downY) / TOUCH_PX_PER_NOTCH, -(e.x - downX) / TOUCH_PX_PER_NOTCH,
                )
                scrollAnchor = e.y
                scrollAnchorX = e.x
                return
            }
            // Долгое касание, затем движение — перетаскивание элемента с левой кнопкой с места касания.
            pressLeft(downX, downY)
        }
        touchX = e.x
        touchY = e.y
        move(e.x, e.y)
    }

    /**
     * Щелчок по тапу. Windows засчитывает двойной щелчок, только если обе точки почти совпадают (около 4 пикселей
     * ПК), а палец при втором тапе всегда попадает чуть в другое место, поэтому быстрый второй тап рядом с первым
     * ставится ровно в точку первого.
     */
    private fun tap(x: Float, y: Float, time: Long) {
        val second = lastTapAt != 0L && time - lastTapAt <= DOUBLE_TAP_MS &&
            hypot(x - lastTapX, y - lastTapY) <= DOUBLE_TAP_DP * resources.displayMetrics.density
        val px = if (second) lastTapX else x
        val py = if (second) lastTapY else y
        // После двойного щелчка следующий тап начинает новую пару.
        lastTapAt = if (second) 0L else time
        lastTapX = px
        lastTapY = py
        pressLeft(px, py)
    }

    /**
     * Быстрый набор на экранной клавиатуре: следующий палец касается экрана раньше, чем поднят предыдущий. Если
     * пальцы не сдвигались (жест двумя пальцами не распознан) и касание короткое, это два отдельных тапа.
     */
    private fun isRolloverTap(e: MotionEvent): Boolean =
        !fingerDown && !regrip && !longFired && !tapSuppressed && e.eventTime - e.downTime < LONG_PRESS_MS

    private fun rolloverTap(e: MotionEvent, liftedId: Int) {
        tap(downX, downY, e.eventTime)
        releaseLeft()
        if (liftedId == primaryId) {
            // Оставшийся палец — новое одиночное касание со своей точки: его отрыв даст следующий щелчок.
            primaryId = secondId
            secondId = -1
            downX = secondX
            downY = secondY
            touchX = secondX
            touchY = secondY
            touchMode = TouchMode.SINGLE
            postDelayed(longPress, (LONG_PRESS_MS - (e.eventTime - secondDownAt)).coerceAtLeast(0L))
        } else {
            // Второй палец поднят раньше первого: щелчки идут в порядке касаний, оставшийся палец уже отработан.
            tap(secondX, secondY, e.eventTime)
            releaseLeft()
            touchMode = TouchMode.IDLE
        }
    }

    private fun pressLeft(x: Float, y: Float) {
        touchX = x
        touchY = y
        fingerDown = true
        move(x, y)
        client?.sendMouse(RdpClient.PTR_LEFT or RdpClient.PTR_DOWN, remoteX(x), remoteY(y))
    }

    private fun dropLongPress() {
        removeCallbacks(longPress)
    }

    /**
     * Набор на сенсорной клавиатуре ПК. Каждый палец — отдельное нажатие: кнопка мыши нажимается, когда палец
     * коснулся, и отпускается, когда он поднят (клавиша срабатывает при отпускании, как у самой клавиатуры). При
     * быстром наборе следующий палец касается раньше, чем поднят предыдущий: предыдущая клавиша тогда отпускается
     * сразу. Жесты, прокрутка и двойной щелчок здесь не распознаются — иначе часть касаний терялась бы. Удержание
     * одного пальца — повтор клавиши.
     */
    private fun keyTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) gestures.stopFling()
                releaseKey()
                val i = e.actionIndex
                keyFinger = e.getPointerId(i)
                keyX = e.getX(i)
                keyY = e.getY(i)
                keyRepeats = 0
                pressLeft(keyX, keyY)
                postDelayed(keyRepeat, KEY_REPEAT_DELAY_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                // Палец съехал с удерживаемой клавиши: повтор прекращается; курсор за пальцем не идёт.
                val i = e.findPointerIndex(keyFinger)
                if (i >= 0 && hypot(e.getX(i) - keyX, e.getY(i) - keyY) >= touchSlop) removeCallbacks(keyRepeat)
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == keyFinger) releaseKey()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                releaseKey()
                keyMode = false
            }
        }
    }

    private fun releaseKey() {
        removeCallbacks(keyRepeat)
        if (keyFinger < 0) return
        keyFinger = -1
        releaseLeft()
    }

    /** Клавиша удерживается: отпускание (первый символ), затем щелчки каждые [KEY_REPEAT_MS], пока палец на ней. */
    private fun fireKeyRepeat() {
        if (keyFinger < 0) return
        if (keyRepeats == 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (fingerDown) {
            releaseLeft()
        } else {
            pressLeft(keyX, keyY)
            releaseLeft()
        }
        keyRepeats++
        postDelayed(keyRepeat, KEY_REPEAT_MS)
    }

    /**
     * Палец не двигался и не отпускался: как долгое касание в iOS. Если отпустить без движения — контекстное меню
     * (правая кнопка), если повести — перетаскивание элемента.
     */
    private fun fireLongPress() {
        if (touchMode != TouchMode.SINGLE || fingerDown || regrip || longFired || tapSuppressed) return
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        move(downX, downY)
        refreshFingerCursor()
    }

    private fun rightClick(x: Float, y: Float) {
        move(x, y)
        client?.sendMouse(RdpClient.PTR_RIGHT or RdpClient.PTR_DOWN, remoteX(x), remoteY(y))
        client?.sendMouse(RdpClient.PTR_RIGHT, remoteX(x), remoteY(y))
    }

    /** Центр пальцев и их средняя дистанция от центра. */
    private fun multiMetrics(e: MotionEvent): FloatArray {
        val n = e.pointerCount
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) { cx += e.getX(i); cy += e.getY(i) }
        cx /= n; cy /= n
        var spread = 0f
        for (i in 0 until n) spread += hypot(e.getX(i) - cx, e.getY(i) - cy)
        return floatArrayOf(cx, cy, spread / n)
    }

    /** Третий и следующие пальцы: текущие действия одним-двумя пальцами прерываются, начинается жест несколькими пальцами. */
    private fun startMulti(e: MotionEvent) {
        dropLongPress()
        val m = multiMetrics(e)
        if (touchMode == TouchMode.MULTI) {
            touchGestures.update(e.pointerCount, m[0], m[1], m[2])
            return
        }
        releaseLeft()
        when (touchMode) {
            TouchMode.SCROLL -> gestures.scrollEnd()
            TouchMode.PINCH -> gestures.pinchEnd()
            else -> {}
        }
        touchMode = TouchMode.MULTI
        regrip = false
        touchGestures.begin(e.pointerCount, m[0], m[1], m[2])
    }

    private fun releaseLeft() {
        if (!fingerDown) return
        fingerDown = false
        client?.sendMouse(RdpClient.PTR_LEFT, remoteX(touchX), remoteY(touchY))
    }

    /** Пока решение не принято, левая кнопка не нажата: жест двумя пальцами не оставит случайного щелчка. */
    private fun classifyTwoFingers(e: MotionEvent) {
        val i1 = e.findPointerIndex(primaryId)
        val i2 = e.findPointerIndex(secondId)
        if (i1 < 0 || i2 < 0) return
        when (twoFinger.update(e.getX(i1), e.getY(i1), e.getX(i2), e.getY(i2))) {
            TwoFingerClassifier.Kind.DRAG -> {
                if (!fingerDown) pressLeft(touchX, touchY)
                touchMode = TouchMode.DRAG
            }
            TwoFingerClassifier.Kind.SCROLL -> {
                releaseLeft()
                move((e.getX(i1) + e.getX(i2)) / 2f, (e.getY(i1) + e.getY(i2)) / 2f)
                touchMode = TouchMode.SCROLL
            }
            TwoFingerClassifier.Kind.PINCH -> {
                releaseLeft()
                move((e.getX(i1) + e.getX(i2)) / 2f, (e.getY(i1) + e.getY(i2)) / 2f)
                pinchDist = hypot(e.getX(i1) - e.getX(i2), e.getY(i1) - e.getY(i2))
                gestures.pinchStart()
                touchMode = TouchMode.PINCH
            }
            null -> {}
        }
    }

    /** Курсор сдвигается на смещение второго пальца, первый палец держит кнопку мыши. */
    private fun dragWithSecondFinger(e: MotionEvent) {
        val i2 = e.findPointerIndex(secondId)
        if (i2 < 0) return
        val x = e.getX(i2)
        val y = e.getY(i2)
        val b = synchronized(lock) { bitmap } ?: return
        touchX = (touchX + x - secondX).coerceIn(offX, offX + b.width * scale)
        touchY = (touchY + y - secondY).coerceIn(offY, offY + b.height * scale)
        secondX = x
        secondY = y
        move(touchX, touchY)
    }
}
