package com.hiweb.hiwebremotedesk.data

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.hiweb.hiwebremotedesk.KeyCaptureService

/**
 * Права и доступы, которые приложение просит у системы. Ни одно не обязательно для запуска: каждое включает свою
 * возможность, поэтому описание говорит, зачем оно нужно.
 */
enum class AppPermission(
    val title: String,
    val description: String,
    val runtime: String?,
    /** Шаги, которые нужно пройти в системных настройках вручную: показываются перед переходом туда. */
    val howTo: String? = null,
) {
    NOTIFICATIONS(
        "Уведомления",
        "Уведомления Windows на планшете, оповещения защиты RDP и значок открытого сеанса в свёрнутом приложении.",
        Manifest.permission.POST_NOTIFICATIONS,
    ),
    MICROPHONE(
        "Микрофон",
        "Передача звука с микрофона планшета в программы Windows (звонки, запись голоса).",
        Manifest.permission.RECORD_AUDIO,
    ),
    CAMERA(
        "Камера",
        "Камеры планшета как веб-камеры в программах Windows (Teams, браузер и др.).",
        Manifest.permission.CAMERA,
    ),
    OVERLAY(
        "Поверх других приложений",
        "Команда «Переключиться на Планшет» из меню HiWeb Agent сразу открывает сеанс, без нажатия на уведомление.",
        null,
    ),
    FULL_SCREEN(
        "Полноэкранные уведомления",
        "Открытие сеанса по команде с ПК, когда экран планшета выключен или заблокирован.",
        null,
    ),
    KEY_CAPTURE(
        "Передача клавиш",
        "Служба специальных возможностей: клавиша Win, Alt+Tab и другие сочетания, а также кнопки мыши уходят на ПК, " +
            "а не обрабатываются Android. Работает только пока открыт сеанс.",
        null,
        "Откроются «Специальные возможности». Дальше: «Установленные приложения» → «RemoteDesk: передача клавиш» → " +
            "включить переключатель и подтвердить.\n\n" +
            "Прямой переход к этому пункту Android сторонним приложениям не разрешает.\n\n" +
            "Если переключатель серый: Настройки → Приложения → RemoteDesk → ⋮ → «Разрешить ограниченные настройки», " +
            "затем повторите.",
    ),
    MOUSE_BUTTONS(
        "Кнопки мыши",
        "Только Samsung. Средняя кнопка, «назад» и «вперёд» по умолчанию выполняют действия самой ОС (сворачивают " +
            "приложение, закрывают сеанс) и поэтому не доходят до Windows как кнопки мыши.",
        null,
        "Приложение не может изменить это само: настройки кнопок мыши Samsung недоступны сторонним приложениям.\n\n" +
            "Откройте Настройки, найдите через поиск «Мышь» и для пунктов «Средняя кнопка», «Дополнительная кнопка 1» и " +
            "«Дополнительная кнопка 2» выберите вариант без системного действия (для средней — обычный щелчок).\n\n" +
            "Это настройка планшета: она действует и вне приложения.",
    ),
}

object AppPermissions {
    /** Служебные аргументы приложения «Настройки» (SettingsActivity.EXTRA_FRAGMENT_ARG_KEY и EXTRA_SHOW_FRAGMENT_ARGUMENTS). */
    private const val SETTINGS_ARG_KEY = ":settings:fragment_args_key"
    private const val SETTINGS_ARGS = ":settings:show_fragment_args"

    /** Права, которые есть на этой версии Android (полноэкранные уведомления — с Android 14) и на этой прошивке (кнопки мыши — Samsung). */
    fun applicable(): List<AppPermission> = AppPermission.entries.filter {
        when (it) {
            AppPermission.FULL_SCREEN -> Build.VERSION.SDK_INT >= 34
            AppPermission.MOUSE_BUTTONS -> Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            else -> true
        }
    }

    fun isGranted(context: Context, p: AppPermission): Boolean = when (p) {
        AppPermission.NOTIFICATIONS, AppPermission.MICROPHONE, AppPermission.CAMERA ->
            context.checkSelfPermission(p.runtime!!) == PackageManager.PERMISSION_GRANTED
        AppPermission.OVERLAY -> Settings.canDrawOverlays(context)
        AppPermission.FULL_SCREEN ->
            Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        AppPermission.MOUSE_BUTTONS -> mouseButtonsFree(context)
        AppPermission.KEY_CAPTURE -> isServiceEnabled(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            context.packageName,
            KeyCaptureService::class.java.name,
        )
    }

    /**
     * Действия ОС у средней и боковых кнопок мыши Samsung выключены (читать эти настройки можно без прав, писать — нельзя).
     * Значения проверены на Tab S9 Ultra: средняя = 3 (обычный щелчок), боковые = 0 (нет действия); по умолчанию у боковых
     * настройки нет вовсе, а у средней стоит значение с системным действием.
     */
    private fun mouseButtonsFree(context: Context): Boolean {
        val resolver = context.contentResolver
        return Settings.System.getString(resolver, "mouse_middle_button_option") == "3" &&
            Settings.System.getString(resolver, "mouse_additional_1_option") == "0" &&
            Settings.System.getString(resolver, "mouse_additional_2_option") == "0"
    }

    fun missing(context: Context): List<AppPermission> = applicable().filter { !isGranted(context, it) }

    /** Экран системных настроек, где пользователь выдаёт это право сам (для обычных прав — если запрос уже не показывается). */
    fun settingsIntent(context: Context, p: AppPermission): Intent {
        val pkg = Uri.parse("package:${context.packageName}")
        return when (p) {
            AppPermission.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            AppPermission.MICROPHONE, AppPermission.CAMERA -> appDetailsIntent(context)
            AppPermission.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            // Страницы «Мышь» у Samsung напрямую не открыть: ведём в главную страницу настроек, там поиск.
            AppPermission.MOUSE_BUTTONS -> Intent(Settings.ACTION_SETTINGS)
            AppPermission.FULL_SCREEN -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            // Страницу самой службы (ACCESSIBILITY_DETAILS_SETTINGS) система открывает только установщикам приложений.
            // Ключ пункта в списке — имя компонента: «чистый» Android прокручивает к нему и подсвечивает; прошивки, которые
            // аргумент не понимают (Samsung), просто открывают общий список.
            AppPermission.KEY_CAPTURE -> {
                val key = ComponentName(context, KeyCaptureService::class.java).flattenToString()
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(SETTINGS_ARG_KEY, key)
                    .putExtra(SETTINGS_ARGS, Bundle().apply { putString(SETTINGS_ARG_KEY, key) })
            }
        }
    }

    /** Сведения о приложении: запасной путь, если нужный экран настроек на этой прошивке не открывается. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /**
     * Включена ли служба: в настройках она записана как `пакет/класс` или `пакет/.Класс`, через двоеточие.
     * Вынесено отдельно, чтобы проверять без Android.
     */
    fun isServiceEnabled(enabledServices: String?, packageName: String, className: String): Boolean =
        enabledServices.orEmpty().split(':').any { entry ->
            val pkg = entry.substringBefore('/', "")
            val cls = entry.substringAfter('/', "")
            pkg == packageName && (if (cls.startsWith(".")) pkg + cls else cls) == className
        }
}
