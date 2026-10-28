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

/** Данные о доступе с сервера плюс моменты, по которым без интернета считается оставшееся время. */
data class AccessInfo(
    val serverNow: Long,
    val guestEndsAt: Long,
    val trialEndsAt: Long,
    val proExpiresAt: Long,
    val isPro: Boolean,
    val signedIn: Boolean,
    val savedElapsed: Long,
    val savedWall: Long
)

enum class AccessKind { LOADING, GUEST_TRIAL, GUEST_EXPIRED, ACCOUNT_TRIAL, ACCOUNT_EXPIRED, PRO }

data class AccessView(val kind: AccessKind, val leftMs: Long)

/**
 * Таймеры считает сервер: гость 24 часа, аккаунт 7 дней, дальше нужен Pro.
 * Без интернета время продолжает идти по монотонным часам телефона (перевод времени не помогает),
 * а после перезагрузки телефона берётся обычное время, но не меньше, чем было.
 */
@Singleton
class AccessRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val auth: AuthRepository,
    private val settings: SettingsStore
) {
    private val _info = MutableStateFlow<AccessInfo?>(load())
    val info: StateFlow<AccessInfo?> = _info.asStateFlow()
    val proUntil: Flow<Long> = info.map { i -> if (i != null && i.signedIn && i.isPro) i.proExpiresAt else 0L }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val signed = auth.isSignedIn()
        val token = if (signed) auth.idToken() else null
        if (signed && token == null) {
            keepOrProvisional(true)
            return@withContext
        }
        try {
            val r = Backend.post("access", token, JSONObject().put("deviceId", deviceId()))
            val j = r.json
            if (r.code == 200 && j != null && j.optString("status") == "ok") {
                val user = j.optJSONObject("user")
                val info = AccessInfo(
                    serverNow = j.getLong("serverNow"),
                    guestEndsAt = j.optLong("guestEndsAt"),
                    trialEndsAt = user?.optLong("trialEndsAt") ?: 0L,
                    proExpiresAt = user?.optLong("proExpiresAt") ?: 0L,
                    isPro = user?.optBoolean("isPro") ?: false,
                    signedIn = signed && user != null,
                    savedElapsed = SystemClock.elapsedRealtime(),
                    savedWall = System.currentTimeMillis()
                )
                _info.value = info
                save(info)
            } else {
                keepOrProvisional(signed)
            }
        } catch (_: IOException) {
            keepOrProvisional(signed)
        }
    }

    fun nowServer(i: AccessInfo): Long {
        val elapsed = SystemClock.elapsedRealtime()
        return if (elapsed >= i.savedElapsed) {
            i.serverNow + (elapsed - i.savedElapsed)
        } else {
            i.serverNow + max(0L, System.currentTimeMillis() - i.savedWall)
        }
    }

    fun stateNow(): AccessView {
        val i = _info.value ?: return AccessView(AccessKind.LOADING, 0L)
        val now = nowServer(i)
        return when {
            i.signedIn && i.isPro && i.proExpiresAt > now -> AccessView(AccessKind.PRO, i.proExpiresAt - now)
            i.signedIn && i.trialEndsAt > now -> AccessView(AccessKind.ACCOUNT_TRIAL, i.trialEndsAt - now)
            i.signedIn -> AccessView(AccessKind.ACCOUNT_EXPIRED, 0L)
            i.guestEndsAt > now -> AccessView(AccessKind.GUEST_TRIAL, i.guestEndsAt - now)
            else -> AccessView(AccessKind.GUEST_EXPIRED, 0L)
        }
    }

    /**
     * Сервер недоступен (нет сети или функция ещё не развёрнута): продолжаем по сохранённым данным.
     * Если данных нет, действует временный локальный период: 24 часа гостю от первого запуска, 7 дней после входа
     * в аккаунт от момента входа. Как только сервер ответит, его данные заменят временные.
     */
    private fun keepOrProvisional(signed: Boolean) {
        val wall = System.currentTimeMillis()
        val day = 24L * 3600_000L
        val cur = _info.value ?: run {
            val start = settings.getRaw(KEY_PROVISIONAL)?.toLongOrNull() ?: wall.also { settings.putRaw(KEY_PROVISIONAL, it.toString()) }
            AccessInfo(
                serverNow = wall, guestEndsAt = start + day, trialEndsAt = 0L, proExpiresAt = 0L,
                isPro = false, signedIn = false, savedElapsed = SystemClock.elapsedRealtime(), savedWall = wall
            )
        }
        _info.value = if (signed) {
            val trial = if (cur.trialEndsAt > 0L) cur.trialEndsAt else {
                (settings.getRaw(KEY_PROV_ACCOUNT)?.toLongOrNull() ?: wall.also { settings.putRaw(KEY_PROV_ACCOUNT, it.toString()) }) + 7L * day
            }
            cur.copy(signedIn = true, trialEndsAt = trial)
        } else {
            cur.copy(signedIn = false)
        }
    }

    private fun deviceId(): String {
        settings.getRaw(KEY_DEVICE)?.let { return it }
        val raw = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) } catch (_: Throwable) { null }
        val base = if (!raw.isNullOrBlank() && raw != "9774d56d682e549c") raw else UUID.randomUUID().toString().replace("-", "")
        val id = "a1-$base"
        settings.putRaw(KEY_DEVICE, id)
        return id
    }

    private fun save(i: AccessInfo) {
        val j = JSONObject()
            .put("serverNow", i.serverNow).put("guestEndsAt", i.guestEndsAt).put("trialEndsAt", i.trialEndsAt)
            .put("proExpiresAt", i.proExpiresAt).put("isPro", i.isPro).put("signedIn", i.signedIn)
            .put("savedElapsed", i.savedElapsed).put("savedWall", i.savedWall)
        settings.putRaw(KEY_CACHE, j.toString())
    }

    private fun load(): AccessInfo? = try {
        val j = JSONObject(settings.getRaw(KEY_CACHE) ?: "")
        AccessInfo(
            j.getLong("serverNow"), j.getLong("guestEndsAt"), j.getLong("trialEndsAt"), j.getLong("proExpiresAt"),
            j.getBoolean("isPro"), j.getBoolean("signedIn"), j.getLong("savedElapsed"), j.getLong("savedWall")
        )
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val KEY_CACHE = "access_cache"
        const val KEY_DEVICE = "device_id"
        const val KEY_PROVISIONAL = "provisional_start"
        const val KEY_PROV_ACCOUNT = "provisional_account_start"
    }
}
