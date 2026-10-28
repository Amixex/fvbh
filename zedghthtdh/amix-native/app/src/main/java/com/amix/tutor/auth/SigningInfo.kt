package com.amix.tutor.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Отпечатки подписи этой сборки. Показываются при ошибке входа, чтобы сверить их с Firebase. */
object SigningInfo {
    fun describe(ctx: Context): String = try {
        val pm = ctx.packageManager
        val bytes = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        }
        if (bytes == null) "?" else "SHA-1: ${hex("SHA-1", bytes)}\nSHA-256: ${hex("SHA-256", bytes)}"
    } catch (_: Throwable) {
        "?"
    }

    private fun hex(alg: String, data: ByteArray): String =
        MessageDigest.getInstance(alg).digest(data).joinToString(":") { "%02X".format(it) }
}
