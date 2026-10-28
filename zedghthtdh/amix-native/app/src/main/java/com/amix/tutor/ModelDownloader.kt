package com.amix.tutor

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Хранилище модели.
 *
 * Основной путь: модель ВШИТА в APK (app/src/main/assets/amix-model.task, кладётся при сборке).
 * MediaPipe умеет читать модель только из обычного файла, поэтому при первом запуске
 * она один раз копируется во внутреннюю память приложения. Дальше интернет не нужен.
 *
 * Запасной путь: если в APK модели нет (сборка без неё), её можно скачать один раз.
 */
object ModelDownloader {

    // Запасной вариант: модель Qwen2.5 1.5B Instruct, формат MediaPipe .task
    const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

    const val MODEL_FILE = "amix-model.task"

    /** Имя файла модели внутри APK. */
    const val ASSET_NAME = "amix-model.task"

    private const val MIN_VALID_BYTES = 100L * 1024 * 1024
    private const val SPACE_RESERVE = 64L * 1024 * 1024

    /** Не хватает свободного места во внутренней памяти. */
    class NoSpaceException(val missingBytes: Long) : IOException("NO_SPACE")

    fun modelFile(ctx: Context): File = File(ctx.filesDir, MODEL_FILE)

    /** Есть ли модель внутри APK. */
    fun hasBundledModel(ctx: Context): Boolean =
        try {
            ctx.assets.list("")?.contains(ASSET_NAME) == true
        } catch (_: IOException) {
            false
        }

    /** Размер вшитой модели в байтах или -1, если узнать не удалось. */
    fun bundledSize(ctx: Context): Long =
        try {
            ctx.assets.openFd(ASSET_NAME).use { it.length }
        } catch (_: Throwable) {
            -1L
        }

    /** Готов ли файл модели во внутренней памяти (целый и нужного размера). */
    fun isDownloaded(ctx: Context): Boolean {
        val f = modelFile(ctx)
        if (!f.exists()) return false
        val bundled = bundledSize(ctx)
        return if (bundled > 0) f.length() == bundled else f.length() > MIN_VALID_BYTES
    }

    /** Копирует вшитую модель во внутреннюю память. Делается один раз после установки. */
    suspend fun copyFromAssets(
        ctx: Context,
        onProgress: (copied: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val total = bundledSize(ctx)
        val target = modelFile(ctx)
        val tmp = File(ctx.filesDir, "$MODEL_FILE.part")
        if (tmp.exists()) tmp.delete()
        // Старый или битый файл модели не нужен: удаляем, чтобы освободить место
        if (target.exists() && !isDownloaded(ctx)) target.delete()

        if (total > 0) {
            val free = ctx.filesDir.usableSpace
            if (free < total + SPACE_RESERVE) throw NoSpaceException(total + SPACE_RESERVE - free)
        }

        ctx.assets.open(ASSET_NAME, AssetManager.ACCESS_STREAMING).use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1024 * 1024)
                var sum = 0L
                var lastReported = 0L
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    sum += read
                    if (sum - lastReported >= 8L * 1024 * 1024) {
                        onProgress(sum, total)
                        lastReported = sum
                    }
                }
                out.flush()
                out.fd.sync()
            }
        }
        onProgress(total, total)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Запасной путь: скачать модель из интернета (если в APK её нет). */
    suspend fun download(
        ctx: Context,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val target = modelFile(ctx)
        val tmp = File(ctx.filesDir, "$MODEL_FILE.part")
        if (tmp.exists()) tmp.delete()

        val req = Request.Builder().url(MODEL_URL).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var read: Int
                    var sum = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        sum += read
                        onProgress(sum, total)
                    }
                }
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
