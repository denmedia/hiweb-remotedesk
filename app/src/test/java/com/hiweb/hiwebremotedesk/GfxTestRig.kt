package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.AvcDecoder
import com.hiweb.hiwebremotedesk.rdp.AvcDecoderFactory
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.GfxChannel
import com.hiweb.hiwebremotedesk.rdp.YuvPlanes

/** Подставной декодер H.264: запоминает поданные кадры, ответ тесты отдают сами через [onFrame]. */
class FakeDecoder(val onFrame: (YuvPlanes, Any) -> Unit, val width: Int, val height: Int) : AvcDecoder {
    val inputs = ArrayList<Pair<ByteArray, Any>>()
    var released = false
    override fun decode(bitstream: ByteArray, tag: Any): Boolean { inputs += bitstream to tag; return true }
    override fun release() { released = true }
}

class TestBitmap(val left: Int, val top: Int, val w: Int, val h: Int, val argb: IntArray)

/**
 * Графический канал с записью всего, что он отправил и нарисовал, и построителями команд RDPEGFX.
 * Сообщения подаются без сжатия ZGFX; ожидание ответа декодера H.264 в конце кадра выключено.
 */
class Rig(avcEnabled: Boolean = true, createFails: Boolean = false, classic: Boolean = false, useRegions: Boolean = false) {
    val sent = ArrayList<ByteArray>()
    val bitmaps = ArrayList<TestBitmap>()
    val decoders = ArrayList<FakeDecoder>()
    val sizes = ArrayList<Pair<Int, Int>>()
    /** Области, выведенные без копирования: (смещение, шаг строки, левый, верхний, ширина, высота) и первое слово пикселей. */
    class Region(val offset: Int, val stride: Int, val left: Int, val top: Int, val w: Int, val h: Int, val first: Int)
    val regions = ArrayList<Region>()
    var creates = 0
    val channel = GfxChannel(
        onDesktopSize = { w, h -> sizes += w to h },
        log = {},
        classic = classic,
        avcWaitMs = 0,
        avc = if (!avcEnabled) null else AvcDecoderFactory { w, h, onFrame ->
            creates++
            if (createFails) null else FakeDecoder(onFrame, w, h).also { decoders += it }
        },
        onRegion = if (!useRegions) null else { argb, offset, stride, l, t, w, h -> regions += Region(offset, stride, l, t, w, h, argb[offset]) },
        onBitmap = { l, t, w, h, argb -> bitmaps += TestBitmap(l, t, w, h, argb) },
    )

    init { channel.onOpen { sent += it } }

    private fun pdu(cmd: Int, body: ByteArray) = ByteWriter().u16le(cmd).u16le(0).u32le(body.size + 8).bytes(body).toByteArray()

    /** Сообщение канала без сжатия ZGFX: дескриптор одного сегмента, флаги «RDP8, не сжато», затем данные. */
    fun feed(vararg pdus: ByteArray) {
        val all = ByteWriter()
        pdus.forEach { all.bytes(it) }
        channel.onMessage(byteArrayOf(0xE0.toByte(), 0x04) + all.toByteArray())
    }

    fun resetGraphics(w: Int, h: Int) = pdu(0x000E, ByteWriter().u32le(w).u32le(h).u32le(0).zeros(332).toByteArray())
    fun createSurface(id: Int, w: Int, h: Int) = pdu(0x0009, ByteWriter().u16le(id).u16le(w).u16le(h).u8(0x20).toByteArray())
    fun mapSurface(id: Int, x: Int, y: Int) = pdu(0x000F, ByteWriter().u16le(id).u16le(0).u32le(x).u32le(y).toByteArray())
    fun deleteSurface(id: Int) = pdu(0x000A, ByteWriter().u16le(id).toByteArray())
    fun startFrame(id: Int) = pdu(0x000B, ByteWriter().u32le(0).u32le(id).toByteArray())
    fun endFrame(id: Int) = pdu(0x000C, ByteWriter().u32le(id).toByteArray())

    fun wire1(id: Int, codec: Int, data: ByteArray, left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 100) = pdu(
        0x0001,
        ByteWriter().u16le(id).u16le(codec).u8(0x20).u16le(left).u16le(top).u16le(right).u16le(bottom).u32le(data.size).bytes(data).toByteArray(),
    )

    fun wire2(id: Int, codec: Int, data: ByteArray) = pdu(
        0x0002,
        ByteWriter().u16le(id).u16le(codec).u32le(1).u8(0x20).u32le(data.size).bytes(data).toByteArray(),
    )

    /** Цвет в виде 0xRRGGBB. */
    fun solidFill(id: Int, rgb: Int, vararg rects: IntArray): ByteArray {
        val w = ByteWriter().u16le(id).u8(rgb and 0xFF).u8((rgb shr 8) and 0xFF).u8((rgb shr 16) and 0xFF).u8(0xFF).u16le(rects.size)
        rects.forEach { r -> r.forEach { w.u16le(it) } }
        return pdu(0x0004, w.toByteArray())
    }

    fun surfaceToSurface(src: Int, dst: Int, rect: IntArray, vararg points: IntArray): ByteArray {
        val w = ByteWriter().u16le(src).u16le(dst)
        rect.forEach { w.u16le(it) }
        w.u16le(points.size)
        points.forEach { w.u16le(it[0]).u16le(it[1]) }
        return pdu(0x0005, w.toByteArray())
    }

    fun surfaceToCache(src: Int, slot: Int, rect: IntArray): ByteArray {
        val w = ByteWriter().u16le(src).u64le(0x1122334455667788L).u16le(slot)
        rect.forEach { w.u16le(it) }
        return pdu(0x0006, w.toByteArray())
    }

    fun cacheToSurface(slot: Int, dst: Int, vararg points: IntArray): ByteArray {
        val w = ByteWriter().u16le(slot).u16le(dst).u16le(points.size)
        points.forEach { w.u16le(it[0]).u16le(it[1]) }
        return pdu(0x0007, w.toByteArray())
    }

    fun evict(slot: Int) = pdu(0x0008, ByteWriter().u16le(slot).toByteArray())
}
