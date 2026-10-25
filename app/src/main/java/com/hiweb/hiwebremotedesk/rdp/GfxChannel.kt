package com.hiweb.hiwebremotedesk.rdp

import android.util.Log

/**
 * Динамический канал графического конвейера «Microsoft::Windows::RDS::Graphics» (MS-RDPEGFX).
 * GNOME Remote Desktop передаёт экран только через него. Поддерживается кодек RemoteFX Progressive
 * (простые тайлы); H.264 отключён в предложенных возможностях, поэтому сервер его не использует.
 */
class GfxChannel(
    private val onDesktopSize: (Int, Int) -> Unit,
    private val onBitmap: (left: Int, top: Int, width: Int, height: Int, argb: IntArray) -> Unit,
) : DvcHandler {
    companion object {
        const val CHANNEL_NAME = "Microsoft::Windows::RDS::Graphics"
        private const val TAG = "RDP"

        private const val CMD_WIRE_TO_SURFACE_1 = 0x0001
        private const val CMD_WIRE_TO_SURFACE_2 = 0x0002
        private const val CMD_DELETE_ENCODING_CONTEXT = 0x0003
        private const val CMD_SOLID_FILL = 0x0004
        private const val CMD_SURFACE_TO_SURFACE = 0x0005
        private const val CMD_SURFACE_TO_CACHE = 0x0006
        private const val CMD_CACHE_TO_SURFACE = 0x0007
        private const val CMD_EVICT_CACHE_ENTRY = 0x0008
        private const val CMD_CREATE_SURFACE = 0x0009
        private const val CMD_DELETE_SURFACE = 0x000A
        private const val CMD_START_FRAME = 0x000B
        private const val CMD_END_FRAME = 0x000C
        private const val CMD_FRAME_ACKNOWLEDGE = 0x000D
        private const val CMD_RESET_GRAPHICS = 0x000E
        private const val CMD_MAP_SURFACE_TO_OUTPUT = 0x000F
        private const val CMD_CAPS_ADVERTISE = 0x0012
        private const val CMD_CAPS_CONFIRM = 0x0013

        private const val CODEC_CAPROGRESSIVE = 0x0009

        private const val CAPVERSION_8 = 0x00080004
        private const val CAPVERSION_81 = 0x00080105
        private const val CAPVERSION_10 = 0x000A0002
        private const val CAPS_FLAG_AVC_DISABLED = 0x20
    }

    private class Surface(val width: Int, val height: Int) {
        var originX = 0
        var originY = 0
        var mapped = false
    }

    private val zgfx = Zgfx()
    private val surfaces = HashMap<Int, Surface>()
    private var write: ((ByteArray) -> Unit)? = null
    private var framesDecoded = 0L
    private var desktopW = 0
    private var desktopH = 0
    private var currentSurface: Surface? = null
    private val unsupported = HashSet<Int>()
    private val progressive = RfxProgressive { x, y, argb ->
        val s = currentSurface ?: return@RfxProgressive
        if (!s.mapped) return@RfxProgressive
        val w = minOf(RfxProgressive.TILE, s.width - x)
        val h = minOf(RfxProgressive.TILE, s.height - y)
        if (w <= 0 || h <= 0) return@RfxProgressive
        val pixels = if (w == RfxProgressive.TILE && h == RfxProgressive.TILE) argb else IntArray(w * h).also { out ->
            for (row in 0 until h) System.arraycopy(argb, row * RfxProgressive.TILE, out, row * w, w)
        }
        onBitmap(s.originX + x, s.originY + y, w, h, pixels)
    }

    override fun onOpen(write: (ByteArray) -> Unit) {
        this.write = write
        // Предлагаем версии 8.0, 8.1 и 10.0 без H.264: сервер ответит RemoteFX Progressive.
        val caps = ByteWriter()
        fun capSet(version: Int, flags: Int) = caps.u32le(version).u32le(4).u32le(flags)
        capSet(CAPVERSION_8, 0)
        capSet(CAPVERSION_81, 0)
        capSet(CAPVERSION_10, CAPS_FLAG_AVC_DISABLED)
        val body = ByteWriter().u16le(3).bytes(caps.toByteArray())
        write(pdu(CMD_CAPS_ADVERTISE, body.toByteArray()))
    }

    override fun onMessage(data: ByteArray) {
        val plain = try {
            zgfx.decompress(data)
        } catch (e: RdpException) {
            Log.w(TAG, "gfx: ${e.message}")
            return
        }
        val r = ByteReader(plain)
        while (r.remaining >= 8) {
            val start = r.pos
            val cmd = r.u16le()
            r.u16le() // flags
            val len = r.u32le().toInt()
            if (len < 8 || start + len > plain.size) {
                Log.w(TAG, "gfx: bad pdu length $len for cmd 0x${cmd.toString(16)}")
                return
            }
            val body = ByteReader(plain, r.pos, start + len)
            try {
                handle(cmd, body)
            } catch (e: Exception) {
                // Ошибка в одной команде не должна рвать сеанс: пропускаем её и ждём следующую.
                Log.w(TAG, "gfx: cmd 0x${cmd.toString(16)} failed", e)
            }
            r.pos = start + len
        }
    }

    private fun handle(cmd: Int, b: ByteReader) {
        when (cmd) {
            CMD_CAPS_CONFIRM -> Log.i(TAG, "gfx: caps confirmed, version=0x${b.u32le().toString(16)}")
            CMD_RESET_GRAPHICS -> {
                val w = b.u32le().toInt()
                val h = b.u32le().toInt()
                Log.i(TAG, "gfx: reset graphics ${w}x$h")
                if (w != desktopW || h != desktopH) {
                    desktopW = w
                    desktopH = h
                    onDesktopSize(w, h)
                }
            }
            CMD_CREATE_SURFACE -> {
                val id = b.u16le()
                val w = b.u16le()
                val h = b.u16le()
                surfaces[id] = Surface(w, h)
                Log.i(TAG, "gfx: create surface $id ${w}x$h")
            }
            CMD_DELETE_SURFACE -> surfaces.remove(b.u16le())
            CMD_MAP_SURFACE_TO_OUTPUT -> {
                val id = b.u16le()
                b.u16le()
                val s = surfaces[id] ?: return
                s.originX = b.u32le().toInt()
                s.originY = b.u32le().toInt()
                s.mapped = true
                Log.i(TAG, "gfx: surface $id mapped at ${s.originX},${s.originY}")
            }
            CMD_START_FRAME -> Unit
            CMD_END_FRAME -> {
                val frameId = b.u32le()
                framesDecoded++
                val ack = ByteWriter().u32le(0).u32le(frameId).u32le(framesDecoded)
                write?.invoke(pdu(CMD_FRAME_ACKNOWLEDGE, ack.toByteArray()))
            }
            CMD_WIRE_TO_SURFACE_1 -> {
                val id = b.u16le()
                val codec = b.u16le()
                b.u8() // формат пикселей
                b.skip(8) // прямоугольник назначения
                val size = b.u32le().toInt()
                decodeToSurface(id, codec, b.bytes(size))
            }
            CMD_WIRE_TO_SURFACE_2 -> {
                val id = b.u16le()
                val codec = b.u16le()
                b.u32le() // контекст кодека
                b.u8()
                val size = b.u32le().toInt()
                decodeToSurface(id, codec, b.bytes(size))
            }
            CMD_DELETE_ENCODING_CONTEXT, CMD_SOLID_FILL, CMD_SURFACE_TO_SURFACE, CMD_SURFACE_TO_CACHE,
            CMD_CACHE_TO_SURFACE, CMD_EVICT_CACHE_ENTRY,
            -> if (unsupported.add(cmd)) Log.i(TAG, "gfx: command 0x${cmd.toString(16)} ignored")
            else -> if (unsupported.add(cmd)) Log.i(TAG, "gfx: unknown command 0x${cmd.toString(16)}")
        }
    }

    private fun decodeToSurface(id: Int, codec: Int, data: ByteArray) {
        val s = surfaces[id] ?: return
        if (codec != CODEC_CAPROGRESSIVE) {
            if (unsupported.add(0x10000 or codec)) Log.w(TAG, "gfx: codec 0x${codec.toString(16)} is not supported")
            return
        }
        currentSurface = s
        progressive.decode(data)
    }

    private fun pdu(cmd: Int, body: ByteArray) = ByteWriter(body.size + 8).u16le(cmd).u16le(0).u32le(body.size + 8).bytes(body).toByteArray()
}
