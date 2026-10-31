package com.hiweb.hiwebremotedesk.rdp

/**
 * Динамический канал графического конвейера «Microsoft::Windows::RDS::Graphics» (MS-RDPEGFX).
 * Сервер рисует в поверхности ([GfxSurface]) кодеками и командами копирования, клиент повторяет это в памяти и в
 * конце каждого кадра отдаёт на экран изменённые области (`onBitmap`). GNOME Remote Desktop передаёт экран только так,
 * Windows — по желанию клиента (`Device.gfx`).
 *
 * Кодеки: без сжатия, RemoteFX Progressive (простые тайлы) и, если на устройстве есть аппаратный декодер ([avc]),
 * H.264 в режиме AVC420 (YUV 4:2:0). С H.264 клиент объявляет только версию 8.1 с флагом AVC420, чтобы сервер не
 * переходил на AVC444, которого здесь нет. HEVC в RDPEGFX не описан, поэтому не поддерживается.
 *
 * [classic] — Windows: версии 10 и выше не предлагаются (там сервер переходит на Progressive с уточнением тайлов).
 * [avcWaitMs] — сколько конец кадра ждёт ответа аппаратного декодера: пока он не ответил, картинка неполная.
 */
class GfxChannel(
    private val onDesktopSize: (Int, Int) -> Unit,
    private val avc: AvcDecoderFactory? = null,
    private val classic: Boolean = false,
    private val avcWaitMs: Long = 250,
    private val log: (String) -> Unit = { android.util.Log.i("RDP", it) },
    /**
     * Вывод области прямо из пикселей поверхности без копирования: [offset] — первая точка, [stride] — шаг строки.
     * Вызывается под блокировкой канала, пиксели действуют только внутри вызова. Без него области копируются в [onBitmap].
     */
    private val onRegion: ((pixels: IntArray, offset: Int, stride: Int, left: Int, top: Int, width: Int, height: Int) -> Unit)? = null,
    private val onBitmap: (left: Int, top: Int, width: Int, height: Int, argb: IntArray) -> Unit,
) : DvcHandler {
    companion object {
        const val CHANNEL_NAME = "Microsoft::Windows::RDS::Graphics"

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

        const val CODEC_UNCOMPRESSED = 0x0000
        const val CODEC_CAVIDEO = 0x0003
        const val CODEC_CLEARCODEC = 0x0008
        const val CODEC_CAPROGRESSIVE = 0x0009
        const val CODEC_PLANAR = 0x000A
        const val CODEC_AVC420 = 0x000B
        const val CODEC_AVC444 = 0x000E
        const val CODEC_AVC444_V2 = 0x000F

        private const val CAPVERSION_8 = 0x00080004
        private const val CAPVERSION_81 = 0x00080105
        private const val CAPVERSION_10 = 0x000A0002
        private const val CAPVERSION_103 = 0x000A0301
        private const val CAPS_FLAG_SMALL_CACHE = 0x02
        private const val CAPS_FLAG_AVC420_ENABLED = 0x10
        private const val CAPS_FLAG_AVC_DISABLED = 0x20
        private const val CAPS_FLAG_AVC_THINCLIENT = 0x40

        /** Размер кадра H.264 — поверхность, округлённая вверх до 16 (макроблок). */
        fun alignedSize(v: Int) = (v + 15) and 15.inv()

        /** Размер ARGB-пикселя в «сыром» виде, байт. */
        private const val RAW_PIXEL = 4
    }

    /** Что нужно знать о кадре H.264, когда декодер вернёт картинку. */
    private class AvcFrame(val surface: GfxSurface, val regions: List<AvcRegion>)

    private class CacheEntry(val width: Int, val height: Int, val pixels: IntArray)

    private val zgfx = Zgfx()
    private val lock = Any()
    private val surfaces = HashMap<Int, GfxSurface>()
    private val cache = HashMap<Int, CacheEntry>()
    private var write: ((ByteArray) -> Unit)? = null
    private var framesDecoded = 0L
    private var desktopW = 0
    private var desktopH = 0
    private val unsupported = HashSet<Int>()
    private val seenCodecs = HashSet<Int>()
    private val avc444Warned = HashSet<Int>()
    private val clear = ClearCodec(log)
    private var avcFailed = false
    private var avcFramesIn = 0L
    private var frameOpen = false

    // Кадры, отданные декодеру H.264 и ещё не вернувшиеся.
    private val avcLock = java.util.concurrent.locks.ReentrantLock()
    private val avcDone = avcLock.newCondition()
    private var avcPending = 0

    override fun onOpen(write: (ByteArray) -> Unit) {
        this.write = write
        val caps = ByteWriter()
        var count = 0
        fun capSet(version: Int, flags: Int) { caps.u32le(version).u32le(4).u32le(flags); count++ }
        // Малый кэш: сервер держит не больше 16 МБ образов, на планшете это ещё поместится в память.
        capSet(CAPVERSION_8, CAPS_FLAG_SMALL_CACHE)
        if (avc != null) {
            capSet(CAPVERSION_81, CAPS_FLAG_SMALL_CACHE or CAPS_FLAG_AVC420_ENABLED)
            // Windows даёт H.264 только начиная с версии 10.3 и только как AVC444; флаг «тонкий клиент» просит его
            // присылать одним основным видом (LC=1), то есть по сути как AVC420 — этого достаточно.
            if (classic) capSet(CAPVERSION_103, CAPS_FLAG_SMALL_CACHE or CAPS_FLAG_AVC_THINCLIENT)
        } else {
            capSet(CAPVERSION_81, CAPS_FLAG_SMALL_CACHE)
            // Версия 10 с отключённым AVC: сервер (GNOME) ответит RemoteFX Progressive.
            if (!classic) capSet(CAPVERSION_10, CAPS_FLAG_SMALL_CACHE or CAPS_FLAG_AVC_DISABLED)
        }
        val body = ByteWriter().u16le(count).bytes(caps.toByteArray())
        write(pdu(CMD_CAPS_ADVERTISE, body.toByteArray()))
    }

    override fun onMessage(data: ByteArray) {
        val plain = try {
            zgfx.decompress(data)
        } catch (e: RdpException) {
            log("gfx: ${e.message}")
            return
        }
        val r = ByteReader(plain)
        while (r.remaining >= 8) {
            val start = r.pos
            val cmd = r.u16le()
            r.u16le() // flags
            val len = r.u32le().toInt()
            if (len < 8 || start + len > plain.size) {
                log("gfx: bad pdu length $len for cmd 0x${cmd.toString(16)}")
                return
            }
            val body = ByteReader(plain, r.pos, start + len)
            try {
                if (cmd == CMD_END_FRAME) endFrame(body.u32le()) else synchronized(lock) { handle(cmd, body) }
            } catch (e: Exception) {
                // Ошибка в одной команде не должна рвать сеанс: пропускаем её и ждём следующую.
                log("gfx: cmd 0x${cmd.toString(16)} failed: $e")
            }
            r.pos = start + len
        }
    }

    private fun handle(cmd: Int, b: ByteReader) {
        when (cmd) {
            CMD_CAPS_CONFIRM -> {
                val version = b.u32le()
                b.u32le() // длина набора
                log("gfx: caps confirmed, version=0x${version.toString(16)}, flags=0x${b.u32le().toString(16)}")
            }
            CMD_RESET_GRAPHICS -> {
                val w = b.u32le().toInt()
                val h = b.u32le().toInt()
                log("gfx: reset graphics ${w}x$h")
                // Сброс отменяет всё нарисованное: поверхности и кэш создаются заново.
                surfaces.values.forEach { releaseAvc(it) }
                surfaces.clear()
                cache.clear()
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
                surfaces.remove(id)?.let { releaseAvc(it) }
                surfaces[id] = GfxSurface(id, w, h)
                log("gfx: create surface $id ${w}x$h")
            }
            CMD_DELETE_SURFACE -> surfaces.remove(b.u16le())?.let { releaseAvc(it) }
            CMD_MAP_SURFACE_TO_OUTPUT -> {
                val id = b.u16le()
                b.u16le()
                val s = surfaces[id] ?: return
                s.originX = b.u32le().toInt()
                s.originY = b.u32le().toInt()
                // Нарисованное до монтирования не пропадает: изменённые области копятся и уйдут на экран в конце кадра.
                s.mapped = true
                log("gfx: surface $id mapped at ${s.originX},${s.originY}")
            }
            CMD_START_FRAME -> frameOpen = true
            CMD_WIRE_TO_SURFACE_1 -> {
                val id = b.u16le()
                val codec = b.u16le()
                b.u8() // формат пикселей
                val rect = rect16(b)
                val size = b.u32le().toInt()
                decodeToSurface(id, codec, rect, b.bytes(size))
            }
            CMD_WIRE_TO_SURFACE_2 -> {
                val id = b.u16le()
                val codec = b.u16le()
                b.u32le() // контекст кодека
                b.u8()
                val size = b.u32le().toInt()
                decodeToSurface(id, codec, null, b.bytes(size))
            }
            CMD_SOLID_FILL -> {
                val s = surfaces[b.u16le()]
                val blue = b.u8()
                val green = b.u8()
                val red = b.u8()
                b.u8()
                val color = (red shl 16) or (green shl 8) or blue
                repeat(b.u16le()) {
                    val r = rect16(b)
                    s?.fill(r.left, r.top, r.right, r.bottom, color)
                }
            }
            CMD_SURFACE_TO_SURFACE -> {
                val src = surfaces[b.u16le()]
                val dst = surfaces[b.u16le()]
                val r = rect16(b)
                val n = b.u16le()
                val piece = src?.copyOut(r.left, r.top, r.right, r.bottom)
                repeat(n) {
                    val x = b.u16le()
                    val y = b.u16le()
                    if (piece != null && dst != null) dst.blit(piece.second, piece.first.width, 0, 0, piece.first.width, piece.first.height, x + piece.first.left - r.left, y + piece.first.top - r.top)
                }
            }
            CMD_SURFACE_TO_CACHE -> {
                val src = surfaces[b.u16le()]
                b.u64le() // ключ кэша для постоянного хранения — не используется
                val slot = b.u16le()
                val r = rect16(b)
                val piece = src?.copyOut(r.left, r.top, r.right, r.bottom)
                if (piece != null) cache[slot] = CacheEntry(piece.first.width, piece.first.height, piece.second)
            }
            CMD_CACHE_TO_SURFACE -> {
                val entry = cache[b.u16le()]
                val dst = surfaces[b.u16le()]
                repeat(b.u16le()) {
                    val x = b.u16le()
                    val y = b.u16le()
                    if (entry != null && dst != null) dst.blit(entry.pixels, entry.width, 0, 0, entry.width, entry.height, x, y)
                }
            }
            CMD_EVICT_CACHE_ENTRY -> cache.remove(b.u16le())
            CMD_DELETE_ENCODING_CONTEXT -> Unit
            else -> if (unsupported.add(cmd)) log("gfx: command 0x${cmd.toString(16)} ignored")
        }
    }

    private fun rect16(b: ByteReader): AvcRegion {
        val left = b.u16le()
        val top = b.u16le()
        val right = b.u16le()
        val bottom = b.u16le()
        return AvcRegion(left, top, right, bottom)
    }

    /** Конец кадра: ждёт аппаратный декодер, отдаёт изменённое на экран и подтверждает кадр серверу. */
    private fun endFrame(frameId: Long) {
        val t0 = System.nanoTime()
        awaitAvc()
        val t1 = System.nanoTime()
        synchronized(lock) {
            frameOpen = false
            flush()
            framesDecoded++
            val ack = ByteWriter().u32le(0).u32le(frameId).u32le(framesDecoded)
            write?.invoke(pdu(CMD_FRAME_ACKNOWLEDGE, ack.toByteArray()))
        }
        stats.frame(t1 - t0, System.nanoTime() - t1)
    }

    /** Раз в 5 с пишет в журнал, где проводит время конец кадра: ожидание декодера H.264 и вывод на экран. */
    private inner class Stats {
        private var frames = 0
        private var waitSum = 0L
        private var waitMax = 0L
        private var flushSum = 0L
        private var flushMax = 0L
        private var convertSum = 0L
        private var convertMax = 0L
        private var since = System.nanoTime()

        fun convert(ns: Long) { synchronized(this) { convertSum += ns; if (ns > convertMax) convertMax = ns } }

        fun frame(waitNs: Long, flushNs: Long) = synchronized(this) {
            frames++
            waitSum += waitNs; if (waitNs > waitMax) waitMax = waitNs
            flushSum += flushNs; if (flushNs > flushMax) flushMax = flushNs
            val now = System.nanoTime()
            if (now - since >= 5_000_000_000L) {
                val secs = (now - since) / 1_000_000_000.0
                log(
                    "gfx: за %.0f с кадров %d (%.1f/с); ожидание декодера %d/%d мс, перевод в ARGB %d мс (до %d), вывод %d/%d мс (среднее/наибольшее)"
                        .format(secs, frames, frames / secs, waitSum / frames / 1_000_000, waitMax / 1_000_000, convertSum / frames / 1_000_000, convertMax / 1_000_000, flushSum / frames / 1_000_000, flushMax / 1_000_000),
                )
                frames = 0; waitSum = 0; waitMax = 0; flushSum = 0; flushMax = 0; convertSum = 0; convertMax = 0; since = now
            }
        }
    }

    private val stats = Stats()

    /** Отправляет изменённые области смонтированных поверхностей на экран (под [lock]). */
    private fun flush() {
        for (s in surfaces.values) {
            if (!s.mapped) continue
            for (r in s.takeDirty()) {
                val c = s.clip(r.left, r.top, r.right, r.bottom) ?: continue
                if (onRegion != null) {
                    onRegion.invoke(s.pixels, c.top * s.width + c.left, s.width, s.originX + c.left, s.originY + c.top, c.width, c.height)
                } else {
                    val (clipped, pixels) = s.copyOut(c.left, c.top, c.right, c.bottom) ?: continue
                    onBitmap(s.originX + clipped.left, s.originY + clipped.top, clipped.width, clipped.height, pixels)
                }
            }
        }
    }

    private fun awaitAvc() {
        if (avcWaitMs <= 0) return
        var left = avcWaitMs * 1_000_000
        avcLock.lock()
        try {
            while (avcPending > 0) {
                if (left <= 0) {
                    log("gfx: декодер H.264 не ответил за $avcWaitMs мс (кадров в очереди $avcPending)")
                    avcPending = 0
                    break
                }
                left = avcDone.awaitNanos(left)
            }
        } finally {
            avcLock.unlock()
        }
    }

    private fun decodeToSurface(id: Int, codec: Int, rect: AvcRegion?, data: ByteArray) {
        val s = surfaces[id] ?: return
        if (seenCodecs.add(codec)) log("gfx: первый блок кодека 0x${codec.toString(16)}, ${data.size} Б")
        when (codec) {
            CODEC_CAPROGRESSIVE -> {
                val decoder = s.progressive ?: RfxProgressive { tile, srcX, srcY, w, h, dx, dy ->
                    s.blit(tile, RfxProgressive.TILE, srcX, srcY, w, h, dx, dy)
                }.also { s.progressive = it }
                decoder.decode(data)
            }
            CODEC_AVC420 -> if (avc != null) decodeAvc420(s, Avc420Stream.parse(data)) else logUnsupported(codec)
            CODEC_AVC444, CODEC_AVC444_V2 -> if (avc != null) decodeAvc444(s, data) else logUnsupported(codec)
            CODEC_UNCOMPRESSED -> if (rect != null) decodeUncompressed(s, rect, data) else logUnsupported(codec)
            CODEC_CLEARCODEC -> if (rect != null) decodeClear(s, rect, data) else logUnsupported(codec)
            else -> logUnsupported(codec)
        }
    }

    /** Несжатые пиксели B,G,R,X построчно, без выравнивания строк. */
    private fun decodeUncompressed(s: GfxSurface, rect: AvcRegion, data: ByteArray) {
        val w = rect.width
        val h = rect.height
        if (w <= 0 || h <= 0 || data.size < w * h * RAW_PIXEL) throw RdpException("gfx: несжатый блок ${w}x$h в ${data.size} Б")
        val out = IntArray(w * h)
        var p = 0
        for (i in out.indices) {
            out[i] = (0xFF shl 24) or ((data[p + 2].toInt() and 0xFF) shl 16) or ((data[p + 1].toInt() and 0xFF) shl 8) or (data[p].toInt() and 0xFF)
            p += RAW_PIXEL
        }
        s.blit(out, w, 0, 0, w, h, rect.left, rect.top)
    }

    /** ClearCodec дорисовывает поверх того, что уже есть на поверхности, поэтому берёт область как основу. */
    private fun decodeClear(s: GfxSurface, rect: AvcRegion, data: ByteArray) {
        val w = rect.width
        val h = rect.height
        if (w <= 0 || h <= 0) return
        val canvas = IntArray(w * h)
        java.util.Arrays.fill(canvas, GfxSurface.OPAQUE_BLACK)
        s.copyOut(rect.left, rect.top, rect.right, rect.bottom)?.let { (visible, pixels) ->
            for (row in 0 until visible.height) {
                System.arraycopy(pixels, row * visible.width, canvas, (visible.top - rect.top + row) * w + (visible.left - rect.left), visible.width)
            }
        }
        clear.decode(data, w, h, canvas)
        s.blit(canvas, w, 0, 0, w, h, rect.left, rect.top)
    }

    private fun logUnsupported(codec: Int) {
        if (unsupported.add(0x10000 or codec)) log("gfx: codec 0x${codec.toString(16)} is not supported")
    }

    /**
     * AVC444: основной вид (яркость и половина цветности) и дополнительный (остаток цветности). Дополнительный вид
     * не используется: при флаге «тонкий клиент» Windows его не присылает (LC=1), а картинка из основного вида — обычный
     * YUV 4:2:0. Если он всё же пришёл (LC=0 — после основного, LC=2 — вместо него), он пропускается, цвет остаётся 4:2:0.
     */
    private fun decodeAvc444(s: GfxSurface, data: ByteArray) {
        val stream = Avc444Stream.parse(data)
        if (stream.luma == null || stream.hasChroma) {
            if (avc444Warned.add(stream.lc)) log("gfx: AVC444 с дополнительным видом (LC=${stream.lc}) — цветность упрощена до 4:2:0")
        }
        stream.luma?.let { decodeAvc420(s, it) }
    }

    private fun decodeAvc420(s: GfxSurface, stream: Avc420Stream) {
        if (avcFailed) return
        var decoder = s.avc
        if (decoder == null) {
            decoder = avc!!.create(alignedSize(s.width), alignedSize(s.height), ::onAvcFrame)
            if (decoder == null) {
                // Повторные попытки на каждом кадре только замедлят сеанс: дальше этот канал AVC не декодирует.
                avcFailed = true
                log("gfx: аппаратный декодер H.264 не создан, кадры AVC420 пропускаются")
                return
            }
            s.avc = decoder
        }
        if (avcFramesIn++ == 0L) log("gfx: первый кадр AVC420, ${stream.regions.size} областей, ${stream.bitstream.size} Б")
        avcLock.lock()
        try { avcPending++ } finally { avcLock.unlock() }
        if (!decoder.decode(stream.bitstream, AvcFrame(s, stream.regions))) avcReturned()
    }

    /** Кадр H.264 готов (поток декодера): переводит изменённые области в ARGB прямо в поверхность. */
    private fun onAvcFrame(frame: YuvPlanes, tag: Any) {
        val f = tag as AvcFrame
        val started = System.nanoTime()
        synchronized(lock) {
            val s = f.surface
            for (region in f.regions) {
                val right = minOf(region.right, s.width, frame.width)
                val bottom = minOf(region.bottom, s.height, frame.height)
                if (region.left < 0 || region.top < 0 || region.left >= right || region.top >= bottom) continue
                YuvConverter.toArgbInto(frame, region.left, region.top, right, bottom, s.pixels, region.top * s.width + region.left, s.width)
                s.markDirty(region.left, region.top, right, bottom)
            }
            // Ответ пришёл после конца кадра (или кадров не было): на экран сразу.
            if (!frameOpen) flush()
        }
        stats.convert(System.nanoTime() - started)
        avcReturned()
    }

    private fun avcReturned() {
        avcLock.lock()
        try {
            if (avcPending > 0) avcPending--
            avcDone.signalAll()
        } finally {
            avcLock.unlock()
        }
    }

    private fun releaseAvc(s: GfxSurface) {
        s.avc?.release()
        s.avc = null
    }

    override fun onClose() {
        synchronized(lock) {
            surfaces.values.forEach { releaseAvc(it) }
            surfaces.clear()
            cache.clear()
        }
    }

    private fun pdu(cmd: Int, body: ByteArray) = ByteWriter(body.size + 8).u16le(cmd).u16le(0).u32le(body.size + 8).bytes(body).toByteArray()
}
