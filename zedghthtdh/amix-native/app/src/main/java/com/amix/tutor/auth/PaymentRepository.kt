package com.amix.tutor.auth

import com.amix.tutor.data.SettingsStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class PayStart {
    data class Url(val url: String) : PayStart()
    object LoginRequired : PayStart()
    object NoConnection : PayStart()
    data class Failed(val detail: String) : PayStart()
}

sealed class PaySync {
    data class Granted(val until: Long) : PaySync()
    object Pending : PaySync()
    object None : PaySync()
    object NoConnection : PaySync()
}

/** Оплата Pro через ЮKassa. Цена и срок определяются на сервере по тарифу (m1, m6, m12). */
@Singleton
class PaymentRepository @Inject constructor(
    private val auth: AuthRepository,
    private val access: AccessRepository,
    private val settings: SettingsStore
) {
    fun hasPending(): Boolean = settings.getRaw(KEY_PENDING) == "1"

    suspend fun start(plan: String): PayStart = withContext(Dispatchers.IO) {
        if (!auth.isSignedIn()) return@withContext PayStart.LoginRequired
        val token = auth.idToken() ?: return@withContext PayStart.NoConnection
        try {
            val r = Backend.post("payCreate", token, JSONObject().put("plan", plan))
            val j = r.json
            if (j != null && r.code == 200 && j.optString("status") == "ok" && j.optString("url").isNotBlank()) {
                settings.putRaw(KEY_PENDING, "1")
                PayStart.Url(j.getString("url"))
            } else {
                PayStart.Failed("HTTP ${r.code} ${j?.optString("status").orEmpty()}")
            }
        } catch (_: IOException) {
            PayStart.NoConnection
        }
    }

    /** Проверка оплаты после возврата в приложение. */
    suspend fun sync(): PaySync = withContext(Dispatchers.IO) {
        if (!auth.isSignedIn()) return@withContext PaySync.None
        val token = auth.idToken() ?: return@withContext PaySync.NoConnection
        try {
            val r = Backend.post("paySync", token, JSONObject())
            val j = r.json
            when (j?.optString("status")) {
                "ok" -> {
                    settings.putRaw(KEY_PENDING, null)
                    access.refresh()
                    PaySync.Granted(j.optLong("proExpiresAt"))
                }
                "pending" -> PaySync.Pending
                else -> {
                    settings.putRaw(KEY_PENDING, null)
                    PaySync.None
                }
            }
        } catch (_: IOException) {
            PaySync.NoConnection
        }
    }

    private companion object {
        const val KEY_PENDING = "pay_pending"
    }
}
