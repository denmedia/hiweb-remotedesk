package com.hiweb.hiwebremotedesk.data

/** Поддерживаемые ОС удалённых устройств. */
enum class OsType(val title: String, val defaultPort: Int) {
    WINDOWS("Windows", 3389),

    /** Ubuntu подключается через штатный GNOME Remote Desktop (общий экран пользователя, порт 3390). */
    UBUNTU("Ubuntu", 3390),
}

data class Device(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 3389,
    val os: OsType = OsType.WINDOWS,
    /** Допускается формат ДОМЕН\пользователь. */
    val username: String = "",
    val password: String = "",
    /** SHA-256 сертификата, закреплённый при первом подключении. */
    val certSha256: String? = null,
    /** Проверка подлинности на уровне сети (NLA). Без неё вход выполняется на экране Windows. */
    val useNla: Boolean = true,
    /** Масштаб интерфейса Windows в процентах (100–300), применяется при подключении. */
    val scalePercent: Int = 100,
    /** Сила прокрутки пальцами и сила её инерции, % (50–200 с шагом 10, 100 — по умолчанию), отдельно для каждого ПК. */
    val scrollPercent: Int = StrengthLevels.DEFAULT,
    val inertiaPercent: Int = StrengthLevels.DEFAULT,
    /**
     * Скорость перемещения физической (Bluetooth) мыши, % (50–200 с шагом 10, 100 — по умолчанию).
     * Тачпад чехла идёт своим путём со своим ускорением и этой настройкой не затрагивается.
     */
    val mouseSpeedPercent: Int = StrengthLevels.DEFAULT,
    /** Скорость прокрутки колёсиком физической мыши, % (50–200 с шагом 10; 100 — один щелчок колеса как у Windows). */
    val mouseWheelPercent: Int = StrengthLevels.DEFAULT,
    /** Обмен файлами через буфер обмена: копирование на ПК (Ctrl+C) и с планшета с вставкой (Ctrl+V). */
    val fileTransfer: Boolean = true,
    /**
     * H.264 с аппаратным декодированием (RDPEGFX AVC420, у Windows — основной вид AVC444): сервер сжимает экран
     * видеокодеком, планшет разжимает его видеочипом. Действует только в сеансе с графическим конвейером (Ubuntu или
     * Windows с [windowsGfx]); если сервер или устройство не умеют H.264, сеанс идёт на RemoteFX как раньше.
     */
    val hardwareVideo: Boolean = true,
    /**
     * Графический конвейер RDPEGFX для Windows (вместо классических битмап-обновлений): нужен для H.264, меняет размер
     * стола без переподключения. Для Ubuntu конвейер включён всегда. По умолчанию выключен: проверен меньше классического пути.
     */
    val windowsGfx: Boolean = false,
    /** Микрофон планшета доступен Windows в сеансе (канал audin, устройство «Микрофон … (перенаправлено)»). */
    val microphone: Boolean = false,
    /** Камера планшета доступна программам Windows (канал rdpecam). */
    val camera: Boolean = false,
    /** Идентификаторы камер планшета (`CameraSource.catalog`), которые НЕ показываются Windows; остальные — показываются. */
    val cameraOff: Set<String> = emptySet(),
    /** Дополнительный поворот кадра камеры по часовой стрелке (0/90/180/270°) поверх автоматического; ключ — идентификатор камеры. */
    val cameraRotation: Map<String, Int> = emptyMap(),
    /** Общий диск: папка планшета подключается к Windows как сетевой диск \\tsclient\имя. */
    val driveEnabled: Boolean = false,
    /** Адрес выбранной папки (дерево документов Android); без него диск не подключается. */
    val driveUri: String? = null,
    /** Имя диска в Windows; допускаются латинские буквы, цифры и знаки без пробелов. */
    val driveName: String = DEFAULT_DRIVE_NAME,
    val driveReadOnly: Boolean = false,
    /** Глубина цвета сеанса, бит на пиксель: одно из [COLOR_DEPTHS]. */
    val colorDepth: Int = DEFAULT_COLOR_DEPTH,
    /** MAC-адрес проводной сетевой карты для пробуждения по сети; пусто — не будить. Агент заполняет сам. */
    val wakeMac: String = "",
) {
    val hasCredentials: Boolean get() = username.isNotBlank() && password.isNotEmpty()

    val canWake: Boolean get() = WakeOnLan.parseMac(wakeMac) != null

    /** Подпись ОС для списков. */
    val osLabel: String get() = os.title

    companion object {
        val COLOR_DEPTHS = listOf(16, 24, 32)
        const val DEFAULT_COLOR_DEPTH = 16
        const val DEFAULT_DRIVE_NAME = "Tablet"

        /** Допустимые дополнительные повороты кадра камеры, градусы по часовой стрелке. */
        val CAMERA_ROTATIONS = listOf(0, 90, 180, 270)
    }
}
