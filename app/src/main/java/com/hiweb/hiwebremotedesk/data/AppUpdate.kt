package com.hiweb.hiwebremotedesk.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Новая версия приложения из GitHub Releases: [version] без префикса «v», APK — файл релиза. */
data class ReleaseInfo(
    val version: String,
    val apkName: String,
    val apkUrl: String,
    val size: Long,
    /** Контрольная сумма SHA-256 (hex, нижний регистр), если GitHub её отдал. */
    val sha256: String?,
)

/** Итог ручной проверки обновления. */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val release: ReleaseInfo) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/**
 * Проверка обновления по последнему релизу GitHub (тег `v<версия>`, APK `HiWebRemoteDesk-<версия>.apk`).
 * Репозиторий публичный, токен не нужен; лимит анонимных запросов (60 в час) для ручной кнопки хватает.
 */
object AppUpdate {
    const val REPO = "denmedia/hiweb-remotedesk"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private val APK_MASK = Regex("""HiWebRemoteDesk-.*\.apk""", RegexOption.IGNORE_CASE)

    /** «v1.10-beta» → [1, 10]: префикс и суффикс после «-» отбрасываются, не числа считаются нулём. */
    fun versionParts(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.trim().toIntOrNull() ?: 0 }

    /** Числовое сравнение по точкам: `1.10 > 1.9`, `1.1 == 1.1.0`. */
    fun compareVersions(a: String, b: String): Int {
        val x = versionParts(a)
        val y = versionParts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Версия из `tag_name` и APK из `assets` (предпочтительно по маске имени); null — тега или APK нет. */
    fun parseRelease(json: String): ReleaseInfo? {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val assets = root.optJSONArray("assets") ?: return null
        var fallback: ReleaseInfo? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
            val info = ReleaseInfo(
                version = tag.trim().removePrefix("v").removePrefix("V"),
                apkName = name,
                apkUrl = url,
                size = a.optLong("size", -1),
                sha256 = a.optString("digest").removePrefix("sha256:").lowercase().takeIf { it.length == 64 },
            )
            if (APK_MASK.matches(name)) return info
            if (fallback == null) fallback = info
        }
        return fallback
    }

    /** Чистое решение «нужно ли обновление» по ответу GitHub; [fetch] подменяется в тестах. */
    fun check(currentVersion: String, fetch: () -> String = ::fetchLatest): UpdateCheck = try {
        val release = parseRelease(fetch())
        when {
            release == null -> UpdateCheck.Failed("В последнем релизе на GitHub нет APK")
            compareVersions(release.version, currentVersion) > 0 -> UpdateCheck.Available(release)
            else -> UpdateCheck.UpToDate
        }
    } catch (e: UpdateHttpException) {
        UpdateCheck.Failed(e.message ?: "Ошибка GitHub")
    } catch (e: IOException) {
        UpdateCheck.Failed("Нет связи с GitHub. Проверьте подключение к интернету")
    } catch (e: org.json.JSONException) {
        UpdateCheck.Failed("GitHub вернул непонятный ответ")
    }

    /** Ошибка ответа GitHub с текстом для пользователя. */
    class UpdateHttpException(message: String) : IOException(message)

    private fun fetchLatest(): String {
        val c = URL(LATEST_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "HiWebRemoteDesk")
            when (val code = c.responseCode) {
                200 -> return c.inputStream.bufferedReader().use { it.readText() }
                403, 429 -> throw UpdateHttpException("GitHub временно ограничил число запросов, попробуйте позже")
                404 -> throw UpdateHttpException("На GitHub пока нет ни одного релиза")
                else -> throw UpdateHttpException("GitHub ответил ошибкой $code")
            }
        } finally {
            c.disconnect()
        }
    }
}
