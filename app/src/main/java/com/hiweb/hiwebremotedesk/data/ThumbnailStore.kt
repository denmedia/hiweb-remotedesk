package com.hiweb.hiwebremotedesk.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Последний кадр удалённого рабочего стола, показываемый как превью на карточке устройства. */
class ThumbnailStore private constructor(context: Context) {
    private val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun file(id: String) = File(dir, "$id.jpg")

    fun save(id: String, frame: Bitmap) {
        val w = 640
        val h = (frame.height * w.toFloat() / frame.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        file(id).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (scaled !== frame) scaled.recycle()
        _version.value++
    }

    /** Снимок экрана от агента (JPEG, уже уменьшенный агентом). Испорченные данные отбрасываются. */
    fun saveJpeg(id: String, jpeg: ByteArray) {
        val frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        try { save(id, frame) } finally { frame.recycle() }
    }

    fun load(id: String): ImageBitmap? =
        file(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }

    fun delete(id: String) {
        file(id).delete()
        _version.value++
    }

    companion object {
        @Volatile private var instance: ThumbnailStore? = null
        fun get(context: Context): ThumbnailStore =
            instance ?: synchronized(this) { instance ?: ThumbnailStore(context.applicationContext).also { instance = it } }
    }
}
