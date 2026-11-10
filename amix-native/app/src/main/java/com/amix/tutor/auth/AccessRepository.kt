package com.amix.tutor.auth

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.amix.tutor.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Статус доступа с сервера плюс моменты, по которым без интернета считается оставшееся время. */
data class AccessInfo(
    val serverNow: Long,
    val trialEndsAt: Long,
    val proExpiresAt: Long,
    val savedElapsed: Long,
    val savedWall: Long
)

enum class AccessKind { LOADING, TRIAL, EXPIRED, PRO }

data class AccessView(val kind: AccessKind, val leftMs: Long)

/**
 * Регистрации нет. Устройство опознаётся по идентификатору (ANDROID_ID, одинаков после переустановки),
 * а сервер хранит его только хэшем и считает 7 дней с первого запуска по своим часам: удаление данных приложения
 * и перевод времени таймер не сбрасывают. Без интернета время идёт по монотонным часам телефона.
 */
@Singleton
class AccessRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsStore
) {
    private val _info = MutableStateFlow<AccessInfo?>(load())
    val info: StateFlow<AccessInfo?> = _info.asStateFlow()
    val proUntil: Flow<Long> = info.map { i -> if (i != null && i.proExpiresAt > nowServer(i)) i.proExpiresAt else 0L }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        try {
            val r = Backend.post("access", JSONObject().put("deviceId", deviceId()))
            val j = r.json
            if (r.code == 200 && j != null && j.optString("status") == "ok") {
                val info = AccessInfo(
                    serverNow = j.getLong("serverNow"),
                    trialEndsAt = j.getLong("trialEndsAt"),
                    proExpiresAt = j.optLong("proExpiresAt"),
                    savedElapsed = SystemClock.elapsedRealtime(),
                    savedWall = System.currentTimeMillis()
                )
                _info.value = info
                save(info)
            } else {
                keepOrProvisional()
            }
        } catch (_: IOException) {
            keepOrProvisional()
        }
    }

    fun nowServer(i: AccessInfo): Long {
        val elapsed = SystemClock.elapsedRealtime()
        return if (elapsed >= i.savedElapsed) {
            i.serverNow + (elapsed - i.savedElapsed)
        } else {
            i.serverNow + max(0L, System.currentTimeMillis() - i.savedWall) // после перезагрузки телефона
        }
    }

    fun stateNow(): AccessView {
        val i = _info.value ?: return AccessView(AccessKind.LOADING, 0L)
        val now = nowServer(i)
        return when {
            i.proExpiresAt > now -> AccessView(AccessKind.PRO, i.proExpiresAt - now)
            i.trialEndsAt > now -> AccessView(AccessKind.TRIAL, i.trialEndsAt - now)
            else -> AccessView(AccessKind.EXPIRED, 0L)
        }
    }

    /** Сервер недоступен: идём по сохранённым данным; при первом запуске без сети временно считаем 7 дней от запуска. */
    private fun keepOrProvisional() {
        if (_info.value != null) return
        val wall = System.currentTimeMillis()
        val start = settings.getRaw(KEY_PROVISIONAL)?.toLongOrNull() ?: wall.also { settings.putRaw(KEY_PROVISIONAL, it.toString()) }
        _info.value = AccessInfo(
            serverNow = wall, trialEndsAt = start + 7L * 24L * 3600_000L, proExpiresAt = 0L,
            savedElapsed = SystemClock.elapsedRealtime(), savedWall = wall
        )
    }

    fun deviceId(): String {
        settings.getRaw(KEY_DEVICE)?.let { return it }
        val raw = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) } catch (_: Throwable) { null }
        val base = if (!raw.isNullOrBlank() && raw != "9774d56d682e549c") raw else UUID.randomUUID().toString().replace("-", "")
        val id = "a1-$base"
        settings.putRaw(KEY_DEVICE, id)
        return id
    }

    private fun save(i: AccessInfo) {
        val j = JSONObject()
            .put("serverNow", i.serverNow).put("trialEndsAt", i.trialEndsAt).put("proExpiresAt", i.proExpiresAt)
            .put("savedElapsed", i.savedElapsed).put("savedWall", i.savedWall)
        settings.putRaw(KEY_CACHE, j.toString())
    }

    private fun load(): AccessInfo? = try {
        val j = JSONObject(settings.getRaw(KEY_CACHE) ?: "")
        AccessInfo(j.getLong("serverNow"), j.getLong("trialEndsAt"), j.getLong("proExpiresAt"), j.getLong("savedElapsed"), j.getLong("savedWall"))
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val KEY_CACHE = "access_cache2"
        const val KEY_DEVICE = "device_id"
        const val KEY_PROVISIONAL = "provisional_start"
    }
}
