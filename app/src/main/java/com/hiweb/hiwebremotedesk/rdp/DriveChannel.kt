package com.hiweb.hiwebremotedesk.rdp

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Статический виртуальный канал «rdpdr» (MS-RDPEFS): папка устройства подключается к Windows как диск
 * (\\tsclient\имя). Канал отвечает на запросы файловой системы от сервера, а сами файлы берёт из [storage].
 *
 * Последовательность: сервер объявляет себя → клиент называет себя и отвечает на возможности →
 * после входа пользователя в Windows (PAKID_CORE_USER_LOGGEDON) клиент объявляет диск. До входа диск не
 * объявляется: сервер всё равно забыл бы его при создании сеанса пользователя.
 *
 * Запросы ввода-вывода выполняются через [io] (отдельный поток), чтобы не задерживать чтение сети.
 */
internal class DriveChannel(
    /** Отправляет один целый PDU канала; нарезку на фрагменты делает вызывающий. */
    private val send: (ByteArray) -> Unit,
    /** Папка устройства (диск с идентификатором 1); null — канал без постоянного диска. */
    storage: DriveStorage?,
    driveName: String,
    private val clientName: String = "ANDROID",
    private val io: (Runnable) -> Unit = { it.run() },
    private val log: (String) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) {
    private class Reply(val status: Long, val body: ByteArray)

    /** Объявляемый серверу диск. */
    private class Drive(val id: Int, val deviceName: String, val label: String, val storage: DriveStorage) {
        @Volatile var announced = false
    }

    private class OpenFile(val drive: Drive, var path: List<String>, val isDirectory: Boolean, val deleteOnClose: Boolean) {
        var handle: DriveStorage.Handle? = null
        var handleWritable = false
        var deletePending = false
        var listing: List<DriveStorage.Entry>? = null
        var listIndex = 0
    }

    private val drives = ConcurrentHashMap<Int, Drive>()
    private val stateLock = Any()
    private val reassembly = ByteWriter()
    private val sendLock = Any()
    private val files = ConcurrentHashMap<Int, OpenFile>()
    private var nextFileId = 1

    private var clientMinor = MINOR_RDP6X
    private var clientId = 0L
    private var haveClientId = false
    private var haveCaps = false
    private var loggedOn = false

    init {
        if (storage != null) drives[PRIMARY_ID] = newDrive(PRIMARY_ID, driveName, storage)
    }

    private fun newDrive(id: Int, name: String, storage: DriveStorage): Drive {
        val device = deviceName(name)
        return Drive(id, device, name.ifBlank { device }, storage)
    }

    /**
     * Подключает ещё один диск во время сеанса (например, временный диск с установщиком агента).
     * Объявляется сразу, если обмен возможностями завершён и пользователь вошёл, иначе — как только это случится.
     */
    fun attach(id: Int, name: String, storage: DriveStorage) {
        require(id != PRIMARY_ID) { "id 1 занят папкой устройства" }
        synchronized(stateLock) {
            drives[id] = newDrive(id, name, storage)
            tryAnnounce()
        }
    }

    /** Отключает диск, подключённый [attach]; его открытые файлы закрываются. */
    fun detach(id: Int) {
        synchronized(stateLock) {
            val drive = drives.remove(id) ?: return
            val stale = files.filterValues { it.drive === drive }
            for ((fileId, f) in stale) {
                files.remove(fileId)
                runCatching { f.handle?.close() }
            }
            if (drive.announced) {
                send(header(PAKID_CORE_DEVICELIST_REMOVE).u32le(1).u32le(id).toByteArray())
                log("drive '${drive.deviceName}' removed")
            }
        }
    }

    /** Диск объявлен серверу. */
    fun isAnnounced(id: Int): Boolean = drives[id]?.announced == true

    /** Принимает фрагмент данных канала (заголовок TS_CHANNEL_PDU_HEADER + часть PDU). */
    fun onChannelData(r: ByteReader) {
        if (r.remaining < 8) return
        r.u32le() // общая длина
        val flags = r.u32le().toInt()
        val chunk = r.bytes(r.remaining)
        if (flags and ClipboardChannel.CHANNEL_FLAG_FIRST != 0) reassembly.reset()
        reassembly.bytes(chunk)
        if (flags and ClipboardChannel.CHANNEL_FLAG_LAST == 0) return
        val data = reassembly.toByteArray()
        reassembly.reset()
        try {
            handle(data)
        } catch (e: RdpException) {
            log("bad PDU ignored: ${e.message}")
        }
    }

    /** Закрывает все открытые файлы (сеанс завершён). */
    fun close() {
        val open = files.values.toList()
        files.clear()
        for (f in open) runCatching { f.handle?.close() }
    }

    // ------------------------------------------------------------------ установление связи

    private fun handle(data: ByteArray) {
        synchronized(stateLock) { handleLocked(data) }
    }

    private fun handleLocked(data: ByteArray) {
        val r = ByteReader(data)
        if (r.remaining < 4) return
        val component = r.u16le()
        val packetId = r.u16le()
        if (component != RDPDR_CTYP_CORE) return
        when (packetId) {
            PAKID_CORE_SERVER_ANNOUNCE -> onServerAnnounce(r)
            PAKID_CORE_SERVER_CAPABILITY -> {
                haveCaps = true
                sendCapabilities(r)
                tryAnnounce()
            }
            PAKID_CORE_CLIENTID_CONFIRM -> {
                haveClientId = true
                tryAnnounce()
            }
            PAKID_CORE_USER_LOGGEDON -> {
                loggedOn = true
                tryAnnounce()
            }
            PAKID_CORE_DEVICE_REPLY -> if (r.remaining >= 8) {
                val id = r.u32le()
                val result = r.u32le()
                log("device $id reply 0x${result.toString(16)}")
            }
            PAKID_CORE_DEVICE_IOREQUEST -> {
                val request = data.copyOfRange(4, data.size)
                io(Runnable { processIrp(ByteReader(request)) })
            }
        }
    }

    private fun onServerAnnounce(r: ByteReader) {
        val serverMajor = r.u16le()
        val serverMinor = r.u16le()
        val serverClientId = r.u32le()
        // Сервер может заново объявить канал после входа пользователя: всё объявленное прежде им забыто.
        haveClientId = false
        haveCaps = false
        drives.values.forEach { it.announced = false }
        clientMinor = minOf(MINOR_RDP10X, serverMinor)
        // Эхо идентификатора сервера ломает канал со старыми Windows (до 6.x), поэтому для них выбираем свой.
        clientId = if (serverMajor == 1 && serverMinor < MINOR_RDP6X) {
            var id: Long
            do { id = random.nextInt().toLong() and 0xFFFFFFFFL } while (id == 0L || id == 0xFFFFFFFFL || id == serverClientId)
            id
        } else {
            serverClientId
        }
        log("server announce $serverMajor.$serverMinor, client uses 1.$clientMinor")
        send(header(PAKID_CORE_CLIENTID_CONFIRM).u16le(1).u16le(clientMinor).u32le(clientId).toByteArray())
        val name = (clientName + "\u0000").toByteArray(Charsets.UTF_16LE)
        send(header(PAKID_CORE_CLIENT_NAME).u32le(1).u32le(0).u32le(name.size).bytes(name).toByteArray())
    }

    /** Отвечает общими возможностями и, если сервер поддерживает диски, возможностью дисков. */
    private fun sendCapabilities(r: ByteReader) {
        var serverIo1 = 0L
        var serverHasDrives = false
        if (r.remaining >= 4) {
            val count = r.u16le()
            r.skip(2)
            repeat(count) {
                if (r.remaining < 8) return@repeat
                val type = r.u16le()
                val length = r.u16le()
                r.u32le() // версия
                val body = maxOf(0, length - 8)
                if (r.remaining < body) return@repeat
                if (type == CAP_GENERAL_TYPE && body >= 20) {
                    val caps = ByteReader(r.data, r.pos, r.pos + body)
                    caps.skip(12) // osType, osVersion, версия протокола
                    serverIo1 = caps.u32le()
                }
                if (type == CAP_DRIVE_TYPE) serverHasDrives = true
                r.skip(body)
            }
        }
        val out = header(PAKID_CORE_CLIENT_CAPABILITY).u16le(if (serverHasDrives) 2 else 1).u16le(0)
        out.u16le(CAP_GENERAL_TYPE).u16le(8 + 36).u32le(GENERAL_CAPABILITY_VERSION_02)
        out.u32le(0).u32le(0)                          // osType, osVersion
        out.u16le(1).u16le(clientMinor)                // версия протокола
        out.u32le(IO_CODES and serverIo1).u32le(0)     // ioCode1 (общие с сервером), ioCode2
        out.u32le(EXTENDED_PDU).u32le(0).u32le(0)      // extendedPDU, extraFlags1, extraFlags2
        out.u32le(0)                                   // SpecialTypeDeviceCap
        if (serverHasDrives) out.u16le(CAP_DRIVE_TYPE).u16le(8).u32le(DRIVE_CAPABILITY_VERSION_02)
        send(out.toByteArray())
    }

    /** Объявляет ещё не объявленные диски, когда обмен возможностями завершён и пользователь вошёл в Windows. */
    private fun tryAnnounce() {
        if (!haveClientId || !haveCaps) return
        if (!loggedOn && clientMinor != MINOR_RDP51) return
        for (drive in drives.values.sortedBy { it.id }) {
            if (drive.announced) continue
            drive.announced = true
            val data = (drive.deviceName + "\u0000").toByteArray(Charsets.US_ASCII)
            val dos = drive.deviceName.take(8).toByteArray(Charsets.US_ASCII)
            val out = header(PAKID_CORE_DEVICELIST_ANNOUNCE).u32le(1)
            out.u32le(RDPDR_DTYP_FILESYSTEM).u32le(drive.id).bytes(dos).zeros(8 - dos.size).u32le(data.size).bytes(data)
            send(out.toByteArray())
            log("drive '${drive.deviceName}' announced")
        }
    }

    // ------------------------------------------------------------------ запросы ввода-вывода

    private fun processIrp(r: ByteReader) {
        if (r.remaining < 20) return
        val deviceId = r.u32le()
        val fileId = r.u32le().toInt()
        val completionId = r.u32le()
        val major = r.u32le().toInt()
        val minor = r.u32le().toInt()
        val drive = drives[deviceId.toInt()]
        val reply = try {
            if (drive == null) Reply(NtStatus.NO_SUCH_DEVICE, ByteArray(4)) else execute(drive, major, minor, fileId, r)
        } catch (e: RdpException) {
            Reply(NtStatus.INVALID_PARAMETER, ByteArray(4))
        } catch (e: Exception) {
            log("IRP $major failed: $e")
            Reply(NtStatus.UNSUCCESSFUL, ByteArray(4))
        }
        val out = header(PAKID_CORE_DEVICE_IOCOMPLETION).u32le(deviceId).u32le(completionId).u32le(reply.status).bytes(reply.body)
        synchronized(sendLock) { send(out.toByteArray()) }
    }

    private fun execute(drive: Drive, major: Int, minor: Int, fileId: Int, r: ByteReader): Reply = when (major) {
        IRP_MJ_CREATE -> create(drive, r)
        IRP_MJ_CLOSE -> closeFile(fileId)
        IRP_MJ_READ -> readFile(fileId, r)
        IRP_MJ_WRITE -> writeFile(fileId, r)
        IRP_MJ_QUERY_INFORMATION -> queryInformation(fileId, r)
        IRP_MJ_SET_INFORMATION -> setInformation(fileId, r)
        IRP_MJ_QUERY_VOLUME_INFORMATION -> queryVolume(drive, r)
        IRP_MJ_DIRECTORY_CONTROL ->
            if (minor == IRP_MN_QUERY_DIRECTORY) queryDirectory(fileId, r) else Reply(NtStatus.NOT_SUPPORTED, ByteArray(4))
        IRP_MJ_LOCK_CONTROL, IRP_MJ_DEVICE_CONTROL -> Reply(NtStatus.SUCCESS, ByteArray(4))
        else -> Reply(NtStatus.NOT_SUPPORTED, ByteArray(0))
    }

    /** Выполняет [block]; при ошибке возвращает её код и тело ответа [failure], которое ждёт сервер. */
    private inline fun guarded(failure: ByteArray, block: () -> ByteArray): Reply = try {
        Reply(NtStatus.SUCCESS, block())
    } catch (e: DriveException) {
        Reply(e.status, failure)
    } catch (e: java.io.IOException) {
        Reply(NtStatus.UNSUCCESSFUL, failure)
    } catch (e: SecurityException) {
        Reply(NtStatus.ACCESS_DENIED, failure)
    }

    private fun create(drive: Drive, r: ByteReader): Reply = guarded(ByteWriter().u32le(0).u8(0).toByteArray()) {
        val storage = drive.storage
        val access = r.u32le()
        r.u64le() // начальный размер: Windows сам задаст размер файла
        r.u32le() // атрибуты
        r.u32le() // режим совместного доступа
        val disposition = r.u32le().toInt()
        val options = r.u32le().toInt()
        val path = parsePath(readString(r, r.u32le().toInt()))

        val writeAccess = access and WRITE_ACCESS_MASK != 0L
        val deleteOnClose = options and FILE_DELETE_ON_CLOSE != 0
        val overwrite = disposition == FILE_SUPERSEDE || disposition == FILE_OVERWRITE || disposition == FILE_OVERWRITE_IF
        val existing = storage.stat(path)
        val file: OpenFile
        if (existing != null && existing.isDirectory) {
            if (disposition == FILE_CREATE) throw DriveException(NtStatus.OBJECT_NAME_COLLISION)
            if (options and FILE_NON_DIRECTORY_FILE != 0) throw DriveException(NtStatus.FILE_IS_A_DIRECTORY)
            if (overwrite) throw DriveException(NtStatus.ACCESS_DENIED)
            if (deleteOnClose) requireWritable(storage)
            file = OpenFile(drive, path, true, deleteOnClose)
        } else if (existing != null) {
            if (options and FILE_DIRECTORY_FILE != 0) throw DriveException(NtStatus.NOT_A_DIRECTORY)
            if (disposition == FILE_CREATE) throw DriveException(NtStatus.OBJECT_NAME_COLLISION)
            if (overwrite || writeAccess || deleteOnClose) requireWritable(storage)
            file = OpenFile(drive, path, false, deleteOnClose)
            if (overwrite) storage.open(path, write = true, truncate = true).close()
        } else {
            if (disposition == FILE_OPEN || disposition == FILE_OVERWRITE) throw DriveException(NtStatus.OBJECT_NAME_NOT_FOUND)
            requireWritable(storage)
            val parent = path.dropLast(1)
            if (storage.stat(parent)?.isDirectory != true) throw DriveException(NtStatus.OBJECT_PATH_NOT_FOUND)
            val directory = options and FILE_DIRECTORY_FILE != 0
            if (directory) storage.createDirectory(path) else storage.createFile(path)
            file = OpenFile(drive, path, directory, deleteOnClose)
        }
        val id = nextFileId++
        files[id] = file
        // Значения Information по таблице MS-RDPEFS: для FILE_OPEN_IF — «открыт», для OVERWRITE_IF — «перезаписан», иначе «заменён».
        val information = when (disposition) {
            FILE_OPEN_IF -> INFO_OPENED
            FILE_OVERWRITE_IF -> INFO_OVERWRITTEN
            else -> INFO_SUPERSEDED
        }
        ByteWriter().u32le(id).u8(information).toByteArray()
    }

    private fun closeFile(id: Int): Reply {
        val file = files.remove(id) ?: return Reply(NtStatus.UNSUCCESSFUL, ByteArray(4))
        runCatching { file.handle?.close() }
        file.handle = null
        if (!file.deleteOnClose && !file.deletePending) return Reply(NtStatus.SUCCESS, ByteArray(4))
        return guarded(ByteArray(4)) {
            file.drive.storage.delete(file.path)
            ByteArray(4)
        }
    }

    private fun readFile(id: Int, r: ByteReader): Reply = guarded(ByteArray(4)) {
        val length = r.u32le().toInt()
        val offset = r.u64le()
        val file = openFile(id)
        if (file.isDirectory) throw DriveException(NtStatus.FILE_IS_A_DIRECTORY)
        if (offset < 0 || length < 0) throw DriveException(NtStatus.INVALID_PARAMETER)
        val data = if (length == 0) ByteArray(0) else handleOf(file, write = false).read(offset, minOf(length, MAX_READ))
        ByteWriter(4 + data.size).u32le(data.size).bytes(data).toByteArray()
    }

    private fun writeFile(id: Int, r: ByteReader): Reply = guarded(ByteWriter().u32le(0).u8(0).toByteArray()) {
        val length = r.u32le().toInt()
        val offset = r.u64le()
        r.skip(20)
        val file = openFile(id)
        if (file.isDirectory) throw DriveException(NtStatus.FILE_IS_A_DIRECTORY)
        requireWritable(file.drive.storage)
        if (offset < 0 || length < 0) throw DriveException(NtStatus.INVALID_PARAMETER)
        val data = r.bytes(length)
        handleOf(file, write = true).write(offset, data)
        ByteWriter().u32le(length).u8(0).toByteArray()
    }

    private fun queryInformation(id: Int, r: ByteReader): Reply = guarded(ByteArray(4)) {
        val kind = r.u32le().toInt()
        val file = openFile(id)
        val info = file.drive.storage.stat(file.path) ?: throw DriveException(NtStatus.OBJECT_NAME_NOT_FOUND)
        val size = if (file.isDirectory) 0L else file.handle?.size() ?: info.size
        val time = fileTime(info.modifiedMs)
        val attributes = if (file.isDirectory) ATTR_DIRECTORY else ATTR_NORMAL
        val body = ByteWriter()
        when (kind) {
            FILE_BASIC_INFORMATION -> {
                body.u32le(36).u64le(time).u64le(time).u64le(time).u64le(time).u32le(attributes)
            }
            FILE_STANDARD_INFORMATION -> {
                body.u32le(22).u64le(size).u64le(size).u32le(1).u8(if (file.deletePending) 1 else 0).u8(if (file.isDirectory) 1 else 0)
            }
            FILE_ATTRIBUTE_TAG_INFORMATION -> body.u32le(8).u32le(attributes).u32le(0)
            else -> throw DriveException(NtStatus.UNSUCCESSFUL)
        }
        body.toByteArray()
    }

    private fun setInformation(id: Int, r: ByteReader): Reply {
        val kind = r.u32le().toInt()
        val length = r.u32le().toInt()
        r.skip(24)
        return guarded(ByteWriter().u32le(length).toByteArray()) {
            val file = openFile(id)
            val storage = file.drive.storage
            if (length < 0 || length > r.remaining) throw DriveException(NtStatus.INVALID_PARAMETER)
            when (kind) {
                FILE_BASIC_INFORMATION -> Unit // время и атрибуты хранилище не меняет
                FILE_END_OF_FILE_INFORMATION -> {
                    requireWritable(storage)
                    handleOf(file, write = true).setSize(r.u64le())
                }
                FILE_ALLOCATION_INFORMATION -> {
                    requireWritable(storage)
                    val size = r.u64le()
                    val handle = handleOf(file, write = true)
                    if (size < handle.size()) handle.setSize(size)
                }
                FILE_DISPOSITION_INFORMATION -> {
                    val delete = length == 0 || r.u8() != 0
                    if (delete) {
                        requireWritable(storage)
                        if (file.isDirectory && storage.list(file.path).isNotEmpty()) throw DriveException(NtStatus.DIRECTORY_NOT_EMPTY)
                    }
                    file.deletePending = delete
                }
                FILE_RENAME_INFORMATION -> {
                    requireWritable(storage)
                    val replace = r.u8() != 0
                    r.u8() // корневая папка
                    val target = parsePath(readString(r, r.u32le().toInt()))
                    if (target.isEmpty()) throw DriveException(NtStatus.INVALID_PARAMETER)
                    runCatching { file.handle?.close() }
                    file.handle = null
                    storage.rename(file.path, target, replace)
                    file.path = target
                }
                else -> throw DriveException(NtStatus.UNSUCCESSFUL)
            }
            ByteWriter().u32le(length).toByteArray()
        }
    }

    private fun queryVolume(drive: Drive, r: ByteReader): Reply = guarded(ByteArray(4)) {
        val storage = drive.storage
        val label = drive.label
        val kind = r.u32le().toInt()
        val (total, free) = storage.space()
        val unit = BYTES_PER_SECTOR * SECTORS_PER_UNIT
        val body = ByteWriter()
        when (kind) {
            FILE_FS_VOLUME_INFORMATION -> {
                val name = (label + "\u0000").toByteArray(Charsets.UTF_16LE)
                body.u32le(17 + name.size).u64le(0).u32le(0x52440000 or (label.hashCode() and 0xFFFF)).u32le(name.size).u8(0).bytes(name)
            }
            FILE_FS_SIZE_INFORMATION -> {
                body.u32le(24).u64le(total / unit).u64le(free / unit).u32le(SECTORS_PER_UNIT).u32le(BYTES_PER_SECTOR)
            }
            FILE_FS_DEVICE_INFORMATION -> body.u32le(8).u32le(FILE_DEVICE_DISK).u32le(if (storage.readOnly) FILE_READ_ONLY_DEVICE else 0)
            FILE_FS_ATTRIBUTE_INFORMATION -> {
                val name = "NTFS\u0000".toByteArray(Charsets.UTF_16LE)
                val flags = FILE_CASE_PRESERVED_NAMES or FILE_UNICODE_ON_DISK or if (storage.readOnly) FILE_READ_ONLY_VOLUME else 0
                body.u32le(12 + name.size).u32le(flags).u32le(255).u32le(name.size).bytes(name)
            }
            FILE_FS_FULL_SIZE_INFORMATION -> {
                body.u32le(32).u64le(total / unit).u64le(free / unit).u64le(free / unit).u32le(SECTORS_PER_UNIT).u32le(BYTES_PER_SECTOR)
            }
            else -> throw DriveException(NtStatus.UNSUCCESSFUL)
        }
        body.toByteArray()
    }

    /** Перечисление папки по одному элементу за запрос; первый запрос задаёт шаблон поиска. */
    private fun queryDirectory(id: Int, r: ByteReader): Reply = guarded(ByteWriter().u32le(0).u8(0).toByteArray()) {
        val kind = r.u32le().toInt()
        val initial = r.u8() != 0
        val pathLength = r.u32le().toInt()
        r.skip(23)
        val file = openFile(id)
        val storage = file.drive.storage
        if (!file.isDirectory) throw DriveException(NtStatus.NOT_A_DIRECTORY)
        if (initial) {
            val segments = splitPath(readString(r, pathLength))
            val pattern = segments.lastOrNull() ?: "*"
            val directory = if (segments.isEmpty()) file.path else segments.dropLast(1)
            val self = storage.stat(directory)
            if (self == null || !self.isDirectory) throw DriveException(NtStatus.OBJECT_PATH_NOT_FOUND)
            val entries = ArrayList<DriveStorage.Entry>()
            if (directory.isNotEmpty() && matches(pattern, ".")) entries += DriveStorage.Entry(".", true, 0, self.modifiedMs)
            if (directory.isNotEmpty() && matches(pattern, "..")) entries += DriveStorage.Entry("..", true, 0, self.modifiedMs)
            entries += storage.list(directory).filter { matches(pattern, it.name) }
            file.listing = entries
            file.listIndex = 0
            if (entries.isEmpty()) throw DriveException(NtStatus.NO_SUCH_FILE)
        }
        val listing = file.listing
        if (listing == null || file.listIndex >= listing.size) throw DriveException(NtStatus.NO_MORE_FILES)
        directoryEntry(kind, listing[file.listIndex++])
    }

    private fun directoryEntry(kind: Int, e: DriveStorage.Entry): ByteArray {
        val name = e.name.toByteArray(Charsets.UTF_16LE)
        val time = fileTime(e.modifiedMs)
        val size = if (e.isDirectory) 0L else e.size
        val attributes = if (e.isDirectory) ATTR_DIRECTORY else ATTR_NORMAL
        val w = ByteWriter(100 + name.size)
        fun common() {
            w.u32le(0).u32le(0) // NextEntryOffset, FileIndex
            w.u64le(time).u64le(time).u64le(time).u64le(time)
            w.u64le(size).u64le(size).u32le(attributes).u32le(name.size)
        }
        when (kind) {
            FILE_DIRECTORY_INFORMATION -> { w.u32le(64 + name.size); common() }
            FILE_FULL_DIRECTORY_INFORMATION -> { w.u32le(68 + name.size); common(); w.u32le(0) }
            FILE_BOTH_DIRECTORY_INFORMATION -> { w.u32le(93 + name.size); common(); w.u32le(0).u8(0).zeros(24) }
            FILE_NAMES_INFORMATION -> { w.u32le(12 + name.size).u32le(0).u32le(0).u32le(name.size) }
            else -> throw DriveException(NtStatus.NOT_SUPPORTED)
        }
        return w.bytes(name).toByteArray()
    }

    // ------------------------------------------------------------------ вспомогательное

    private fun openFile(id: Int): OpenFile = files[id] ?: throw DriveException(NtStatus.INVALID_HANDLE)

    private fun requireWritable(storage: DriveStorage) {
        if (storage.readOnly) throw DriveException(NtStatus.ACCESS_DENIED)
    }

    /** Открывает файл при первом обращении к данным; для записи переоткрывает доступный только для чтения. */
    private fun handleOf(file: OpenFile, write: Boolean): DriveStorage.Handle {
        val current = file.handle
        if (current != null && (!write || file.handleWritable)) return current
        runCatching { current?.close() }
        return file.drive.storage.open(file.path, write = write, truncate = false).also {
            file.handle = it
            file.handleWritable = write
        }
    }

    private fun readString(r: ByteReader, byteLength: Int): String {
        if (byteLength < 0 || byteLength > r.remaining) throw RdpException("Некорректная длина пути")
        val raw = String(r.bytes(byteLength), Charsets.UTF_16LE)
        return raw.trimEnd('\u0000')
    }

    private fun splitPath(raw: String): List<String> = raw.split('\\', '/').filter { it.isNotEmpty() && it != "." }

    /** Путь от сервера в список имён; выход за пределы папки и потоки данных NTFS не допускаются. */
    private fun parsePath(raw: String): List<String> {
        val parts = splitPath(raw)
        if (parts.any { it == ".." }) throw DriveException(NtStatus.ACCESS_DENIED)
        if (parts.any { it.contains(':') }) throw DriveException(NtStatus.OBJECT_NAME_NOT_FOUND)
        return parts
    }

    private fun header(packetId: Int) = ByteWriter().u16le(RDPDR_CTYP_CORE).u16le(packetId)

    companion object {
        const val CHANNEL_NAME = "rdpdr"

        private const val RDPDR_CTYP_CORE = 0x4472
        private const val PAKID_CORE_SERVER_ANNOUNCE = 0x496E
        private const val PAKID_CORE_CLIENTID_CONFIRM = 0x4343
        private const val PAKID_CORE_CLIENT_NAME = 0x434E
        private const val PAKID_CORE_DEVICELIST_ANNOUNCE = 0x4441
        private const val PAKID_CORE_DEVICELIST_REMOVE = 0x444D
        private const val PAKID_CORE_DEVICE_REPLY = 0x6472
        private const val PAKID_CORE_DEVICE_IOREQUEST = 0x4952
        private const val PAKID_CORE_DEVICE_IOCOMPLETION = 0x4943
        private const val PAKID_CORE_SERVER_CAPABILITY = 0x5350
        private const val PAKID_CORE_CLIENT_CAPABILITY = 0x4350
        private const val PAKID_CORE_USER_LOGGEDON = 0x554C

        private const val MINOR_RDP51 = 0x0005
        private const val MINOR_RDP6X = 0x000C
        private const val MINOR_RDP10X = 0x000D

        private const val CAP_GENERAL_TYPE = 1
        private const val CAP_DRIVE_TYPE = 4
        private const val GENERAL_CAPABILITY_VERSION_02 = 2
        private const val DRIVE_CAPABILITY_VERSION_02 = 2

        /** Все IRP_MJ_* от CREATE до SET_SECURITY (RDPDR_IRP_MJ_*). */
        private const val IO_CODES = 0x0000FFFFL

        /** RDPDR_DEVICE_REMOVE_PDUS | RDPDR_CLIENT_DISPLAY_NAME_PDU | RDPDR_USER_LOGGEDON_PDU */
        private const val EXTENDED_PDU = 0x7

        private const val RDPDR_DTYP_FILESYSTEM = 0x08
        /** Идентификатор папки устройства; остальные диски подключаются через [attach]. */
        const val PRIMARY_ID = 1

        private const val IRP_MJ_CREATE = 0x00
        private const val IRP_MJ_CLOSE = 0x02
        private const val IRP_MJ_READ = 0x03
        private const val IRP_MJ_WRITE = 0x04
        private const val IRP_MJ_QUERY_INFORMATION = 0x05
        private const val IRP_MJ_SET_INFORMATION = 0x06
        private const val IRP_MJ_QUERY_VOLUME_INFORMATION = 0x0A
        private const val IRP_MJ_DIRECTORY_CONTROL = 0x0C
        private const val IRP_MJ_DEVICE_CONTROL = 0x0E
        private const val IRP_MJ_LOCK_CONTROL = 0x11
        private const val IRP_MN_QUERY_DIRECTORY = 0x01

        private const val FILE_SUPERSEDE = 0
        private const val FILE_OPEN = 1
        private const val FILE_CREATE = 2
        private const val FILE_OPEN_IF = 3
        private const val FILE_OVERWRITE = 4
        private const val FILE_OVERWRITE_IF = 5

        private const val FILE_DIRECTORY_FILE = 0x00000001
        private const val FILE_NON_DIRECTORY_FILE = 0x00000040
        private const val FILE_DELETE_ON_CLOSE = 0x00001000

        /** FILE_WRITE_DATA | FILE_APPEND_DATA | DELETE | GENERIC_WRITE | GENERIC_ALL */
        private const val WRITE_ACCESS_MASK = 0x00000002L or 0x00000004L or 0x00010000L or 0x40000000L or 0x10000000L

        private const val INFO_SUPERSEDED = 0
        private const val INFO_OPENED = 1
        private const val INFO_OVERWRITTEN = 3

        private const val ATTR_DIRECTORY = 0x10
        private const val ATTR_NORMAL = 0x80

        private const val FILE_DIRECTORY_INFORMATION = 1
        private const val FILE_FULL_DIRECTORY_INFORMATION = 2
        private const val FILE_BOTH_DIRECTORY_INFORMATION = 3
        private const val FILE_BASIC_INFORMATION = 4
        private const val FILE_STANDARD_INFORMATION = 5
        private const val FILE_RENAME_INFORMATION = 10
        private const val FILE_NAMES_INFORMATION = 12
        private const val FILE_DISPOSITION_INFORMATION = 13
        private const val FILE_ALLOCATION_INFORMATION = 19
        private const val FILE_END_OF_FILE_INFORMATION = 20
        private const val FILE_ATTRIBUTE_TAG_INFORMATION = 35

        private const val FILE_FS_VOLUME_INFORMATION = 1
        private const val FILE_FS_SIZE_INFORMATION = 3
        private const val FILE_FS_DEVICE_INFORMATION = 4
        private const val FILE_FS_ATTRIBUTE_INFORMATION = 5
        private const val FILE_FS_FULL_SIZE_INFORMATION = 7

        private const val FILE_DEVICE_DISK = 7
        private const val FILE_READ_ONLY_DEVICE = 2
        private const val FILE_CASE_PRESERVED_NAMES = 0x00000002
        private const val FILE_UNICODE_ON_DISK = 0x00000004
        private const val FILE_READ_ONLY_VOLUME = 0x00080000

        private const val BYTES_PER_SECTOR = 512
        private const val SECTORS_PER_UNIT = 8

        /** Верхняя граница одного чтения; Windows читает кусками намного меньше. */
        private const val MAX_READ = 16 * 1024 * 1024

        private const val FILETIME_UNIX_EPOCH = 116_444_736_000_000_000L

        /** Время Windows (100 нс от 1601 года); неизвестное время заменяется текущим. */
        fun fileTime(ms: Long): Long = (if (ms > 0) ms else System.currentTimeMillis()) * 10_000L + FILETIME_UNIX_EPOCH

        /** Имя устройства для сервера: только ASCII без символов, запрещённых в DEVICE_ANNOUNCE. */
        fun deviceName(raw: String): String {
            val clean = raw.map { if (it.code in 0x21..0x7E && it !in ":<>\"/\\|") it else '_' }.joinToString("")
            return if (clean.isEmpty() || clean.all { it == '_' }) "Tablet" else clean
        }

        /** Сопоставление имени с шаблоном Windows: «*», «?», без учёта регистра; «*.*» подходит любому имени. */
        fun matches(pattern: String, name: String): Boolean {
            if (pattern == "*" || pattern == "*.*") return true
            val regex = StringBuilder()
            for (c in pattern) {
                when (c) {
                    '*' -> regex.append(".*")
                    '?' -> regex.append('.')
                    else -> regex.append(Regex.escape(c.toString()))
                }
            }
            return Regex(regex.toString(), RegexOption.IGNORE_CASE).matches(name)
        }
    }
}
