package com.amix.tutor.auth

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class LicenseOutcome {
    data class Ok(val days: Int, val expiresAt: Long, val already: Boolean) : LicenseOutcome()
    object NotFound : LicenseOutcome()
    object Used : LicenseOutcome()
    object Expired : LicenseOutcome()
    object Revoked : LicenseOutcome()
    object BadRequest : LicenseOutcome()
    object RateLimited : LicenseOutcome()
    object NoConnection : LicenseOutcome()
    data class Failed(val detail: String) : LicenseOutcome()
}

/** Активация лицензионного ключа и промокода: проверка только на сервере, нужен интернет. */
@Singleton
class LicenseRepository @Inject constructor(private val access: AccessRepository) {

    suspend fun activateKey(key: String): LicenseOutcome = call("activateKey", JSONObject().put("key", key.trim()))

    suspend fun redeemPromo(code: String): LicenseOutcome = call("promo", JSONObject().put("code", code.trim()))

    private suspend fun call(name: String, body: JSONObject): LicenseOutcome = withContext(Dispatchers.IO) {
        try {
            val r = Backend.post(name, body.put("deviceId", access.deviceId()))
            val j = r.json
            when (j?.optString("status")) {
                "ok" -> {
                    access.refresh()
                    LicenseOutcome.Ok(j.optInt("days", 30), j.optLong("proExpiresAt"), j.optBoolean("already"))
                }
                "not_found" -> LicenseOutcome.NotFound
                "used" -> LicenseOutcome.Used
                "expired" -> LicenseOutcome.Expired
                "revoked" -> LicenseOutcome.Revoked
                "bad_request" -> LicenseOutcome.BadRequest
                "rate_limited" -> LicenseOutcome.RateLimited
                else -> LicenseOutcome.Failed("HTTP ${r.code}")
            }
        } catch (_: IOException) {
            LicenseOutcome.NoConnection
        }
    }
}
