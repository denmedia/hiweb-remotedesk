package com.hiweb.hiwebremotedesk.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.hiweb.hiwebremotedesk.R

/**
 * Большая иконка уведомления с ПК: иконка приложения Windows, а в её правом нижнем углу — значок RemoteDesk.
 * Всё собирается в памяти из PNG, присланного агентом; на диск ничего не пишется. Без иконки приложения
 * (системные уведомления Windows) остаётся один значок RemoteDesk.
 */
object NotificationIcon {
    private const val SIZE = 192
    private const val BADGE = 80
    private const val RING = 6

    fun compose(context: Context, windowsPng: ByteArray?): Bitmap {
        val app = windowsPng?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (app == null) return launcher(context, SIZE)

        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(app, null, Rect(0, 0, SIZE, SIZE), paint)
        app.recycle()

        // Значок на белой подложке-кольце, чтобы он не сливался с иконкой приложения.
        val badge = launcher(context, BADGE)
        val cx = SIZE - BADGE / 2f
        val cy = SIZE - BADGE / 2f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, BADGE / 2f + RING, paint)
        val clip = Path().apply { addCircle(cx, cy, BADGE / 2f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(badge, SIZE - BADGE.toFloat(), SIZE - BADGE.toFloat(), paint)
        canvas.restore()
        badge.recycle()
        return out
    }

    /** Значок приложения (адаптивная иконка запуска), нарисованный в квадрат [size]. */
    private fun launcher(context: Context, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        context.getDrawable(R.mipmap.ic_launcher)?.let {
            it.setBounds(0, 0, size, size)
            it.draw(Canvas(bitmap))
        }
        return bitmap
    }
}
