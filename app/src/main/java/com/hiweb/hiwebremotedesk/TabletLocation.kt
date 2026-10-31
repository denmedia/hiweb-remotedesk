package com.hiweb.hiwebremotedesk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.hiweb.hiwebremotedesk.rdp.LocationChannel
import com.hiweb.hiwebremotedesk.rdp.LocationFix
import com.hiweb.hiwebremotedesk.rdp.LocationSource

/** Описание определения для выбора лучшего из нескольких датчиков: время (мс по часам устройства), погрешность (м), датчик. */
internal class LocationSample(val timeMs: Long, val accuracy: Float, val provider: String)

/**
 * Какое из двух определений оставить. Датчики отвечают вразнобой: GPS точнее, но молчит в помещении; сетевой отвечает
 * всегда, но грубее. Новое определение лучше, если текущего нет, оно заметно свежее, точнее или свежее и не заметно хуже
 * (классическая схема из руководства Android по определению местоположения).
 */
internal object LocationPick {
    /** Старше этого определение считается устаревшим: любое новое заменит его. */
    const val STALE_MS = 2 * 60_000L

    /** Насколько новое может быть грубее и всё равно победить, если оно свежее. */
    const val WORSE_OK_M = 200f

    fun better(current: LocationSample?, new: LocationSample): Boolean {
        if (current == null) return true
        val dt = new.timeMs - current.timeMs
        if (dt > STALE_MS) return true
        if (dt < -STALE_MS) return false
        val dAcc = new.accuracy - current.accuracy
        return when {
            dAcc < 0 -> true
            dt > 0 && dAcc == 0f -> true
            dt > 0 && dAcc <= WORSE_OK_M && new.provider == current.provider -> true
            else -> false
        }
    }
}

/**
 * Координаты планшета для канала location: обычный [LocationManager] (без Google Play Services). Слушаются все включённые
 * датчики — GPS, сетевой и fused, лучшее определение по [LocationPick] уходит в канал. Отсчёты приходят в отдельном потоке,
 * чтобы запись в сеть не шла из потока интерфейса, и повторяются раз в [KEEPALIVE_MS], пока планшет стоит на месте: в пакете
 * нет времени определения, а сетевой датчик при неподвижности молчит долго.
 *
 * Работает и в свёрнутом приложении, пока идёт [SessionService] с типом «геолокация» (выбирает `RemoteActivity`).
 */
class TabletLocation(private val context: Context) : LocationSource {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listeners: List<LocationListener> = emptyList()

    // Все поля ниже трогает только поток [thread]; снаружи — через handler.post.
    private var best: Location? = null
    private var bestSample: LocationSample? = null
    @Volatile private var feed: ((LocationFix) -> Unit)? = null

    private val keepalive = object : Runnable {
        override fun run() {
            best?.let { deliver(it) }
            handler?.postDelayed(this, KEEPALIVE_MS)
        }
    }

    /** Есть ли у приложения доступ к местоположению (точный или примерный). */
    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // доступ проверяется в hasPermission(); SecurityException отдельных датчиков перехватывается
    override fun start(feed: (LocationFix) -> Unit): Boolean {
        stop()
        val lm = manager ?: return false
        if (!hasPermission()) return false
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
            .filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER || it == LocationManager.FUSED_PROVIDER }
        if (providers.isEmpty()) {
            Log.i(TAG, "location: нет включённых датчиков местоположения")
            return false
        }
        val t = HandlerThread("rdp-location").apply { start() }
        val h = Handler(t.looper)
        this.feed = feed
        thread = t
        handler = h
        val started = ArrayList<LocationListener>()
        for (provider in providers) {
            val listener = LocationListener { loc -> consider(loc) }
            try {
                lm.requestLocationUpdates(provider, UPDATE_MS, 0f, listener, t.looper)
                started += listener
                // Последнее известное определение не старше 10 минут даёт первую точку, не дожидаясь датчика.
                lm.getLastKnownLocation(provider)?.takeIf { System.currentTimeMillis() - it.time < LAST_KNOWN_MAX_MS }?.let { h.post { consider(it) } }
            } catch (e: SecurityException) {
                Log.i(TAG, "location: датчик $provider недоступен: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.i(TAG, "location: датчик $provider не поддерживается: ${e.message}")
            }
        }
        listeners = started
        if (started.isEmpty()) {
            stop()
            return false
        }
        Log.i(TAG, "location: слежение запущено, датчики: ${providers.joinToString()}")
        h.postDelayed(keepalive, KEEPALIVE_MS)
        return true
    }

    private fun consider(loc: Location) {
        val sample = LocationSample(loc.time, if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE, loc.provider.orEmpty())
        if (!LocationPick.better(bestSample, sample)) return
        best = loc
        bestSample = sample
        deliver(loc)
    }

    private fun deliver(loc: Location) {
        val sink = feed ?: return
        runCatching { sink(toFix(loc)) }
    }

    /**
     * Останавливает слежение. Не ждёт поток отсчётов: метод зовут из канала под его замком, а поток в это время может
     * ждать тот же замок в [LocationChannel]; после остановки [feed] обнуляется, так что лишний отсчёт никуда не уйдёт.
     */
    override fun stop() {
        feed = null
        val lm = manager
        listeners.forEach { l -> runCatching { lm?.removeUpdates(l) } }
        listeners = emptyList()
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        best = null
        bestSample = null
    }

    internal companion object {
        private const val TAG = "RDP"

        /** Как часто просим датчики об определении. */
        const val UPDATE_MS = 5_000L

        /** Как часто повторяем последнее определение, пока новых нет. */
        const val KEEPALIVE_MS = 30_000L
        private const val LAST_KNOWN_MAX_MS = 10 * 60_000L

        fun toFix(loc: Location): LocationFix = LocationFix(
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitude = if (loc.hasAltitude()) Math.round(loc.altitude).toInt() else 0,
            speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
            heading = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0,
            accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else LocationFix.DEFAULT_ACCURACY,
            source = if (loc.provider == LocationManager.GPS_PROVIDER) LocationChannel.SOURCE_GNSS else LocationChannel.SOURCE_WIFI,
        )
    }
}
