package com.amix.tutor.auth

import com.amix.tutor.data.SettingsStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

sealed class PromoOutcome {
    data class Ok(val days: Int, val expiresAt: Long) : PromoOutcome()
    object NotFound : PromoOutcome()
    object Used : PromoOutcome()
    object BadRequest : PromoOutcome()
    object RateLimited : PromoOutcome()
    object NoConnection : PromoOutcome()
    object LoginRequired : PromoOutcome()
    data class Failed(val detail: String) : PromoOutcome()
}

/** Активация промокода: проверка только на сервере (Firebase Function `promo`), нужен интернет и вход. */
@Singleton
class PromoRepository @Inject constructor(
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    private val access: AccessRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun redeem(code: String): PromoOutcome = withContext(Dispatchers.IO) {
        if (!auth.isSignedIn()) return@withContext PromoOutcome.LoginRequired
        // не удалось получить токен у вошедшего пользователя: почти всегда нет сети
        val token = auth.idToken() ?: return@withContext PromoOutcome.NoConnection
        val body = JSONObject().put("code", code.trim()).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (_: Throwable) { null }
                when (json?.optString("status")) {
                    "ok" -> {
                        val expires = json.optLong("proExpiresAt")
                        settings.setProUntil(expires)
                        access.refresh()
                        PromoOutcome.Ok(json.optInt("days", 30), expires)
                    }
                    "not_found" -> PromoOutcome.NotFound
                    "used" -> PromoOutcome.Used
                    "bad_request" -> PromoOutcome.BadRequest
                    "rate_limited" -> PromoOutcome.RateLimited
                    "unauthorized" -> PromoOutcome.LoginRequired
                    else -> PromoOutcome.Failed("HTTP ${resp.code}")
                }
            }
        } catch (_: IOException) {
            PromoOutcome.NoConnection
        }
    }

    private companion object {
        val URL = "https://europe-west1-${FirebaseSetup.PROJECT_ID}.cloudfunctions.net/promo"
    }
}
