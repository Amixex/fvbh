package com.amix.tutor.auth

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Запросы к серверным функциям (Firebase, регион europe-west1). Бросает IOException, если нет сети. */
object Backend {
    class Response(val code: Int, val json: JSONObject?)

    private const val PROJECT_ID = "amix-3e8a4"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val base = "https://europe-west1-$PROJECT_ID.cloudfunctions.net/"

    fun post(name: String, body: JSONObject): Response {
        val request = Request.Builder()
            .url(base + name)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return Response(resp.code, try { JSONObject(text) } catch (_: Throwable) { null })
        }
    }
}
