package com.amix.tutor.auth

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Запросы к серверу на Supabase: вызов SQL-функций через /rest/v1/rpc/<функция>.
 * Адрес проекта и публичный (publishable) ключ берутся из res/values/config.xml.
 * Бросает IOException, если нет сети или сервер не настроен.
 */
object Backend {
    class Response(val code: Int, val json: JSONObject?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var baseUrl = ""
    @Volatile private var apiKey = ""

    fun configure(url: String, key: String) {
        baseUrl = url.trim().trimEnd('/')
        apiKey = key.trim()
    }

    fun isConfigured(): Boolean = baseUrl.startsWith("https://") && apiKey.isNotBlank() && !apiKey.startsWith("PASTE")

    fun post(function: String, body: JSONObject): Response {
        if (!isConfigured()) throw IOException("server is not configured")
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/rpc/$function")
            .header("apikey", apiKey)
            .apply { if (apiKey.startsWith("eyJ")) header("Authorization", "Bearer $apiKey") } // старые ключи anon (JWT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return Response(resp.code, try { JSONObject(text) } catch (_: Throwable) { null })
        }
    }
}
