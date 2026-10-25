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
    /** Сила прокрутки пальцами и сила её инерции (уровни 1–9, 5 — по умолчанию), отдельно для каждого ПК. */
    val scrollLevel: Int = StrengthLevels.DEFAULT,
    val inertiaLevel: Int = StrengthLevels.DEFAULT,
    /** Обмен файлами через буфер обмена: копирование на ПК (Ctrl+C) и с планшета с вставкой (Ctrl+V). */
    val fileTransfer: Boolean = true,
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
    }
}
