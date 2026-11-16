package com.amix.tutor.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Язык интерфейса и ответов. Как в веб-версии: ru/uk/be на устройстве дают русский, иначе английский. */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext ctx: Context) {

    private val prefs = ctx.getSharedPreferences("amix_settings", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(readLanguage())
    val language: StateFlow<String> = _language.asStateFlow()

    private val _proUntil = MutableStateFlow(prefs.getLong(KEY_PRO, 0L))
    val proUntil: StateFlow<Long> = _proUntil.asStateFlow()

    /** Срок Pro (мс). Пока хранится локально, подпись сервером и офлайн-проверка будут на этапе «Офлайн-режим». */
    fun setProUntil(ms: Long) {
        prefs.edit().putLong(KEY_PRO, ms).apply()
        _proUntil.value = ms
    }

    fun getRaw(key: String): String? = prefs.getString(key, null)

    fun putRaw(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    private fun readLanguage(): String {
        val saved = prefs.getString(KEY_LANG, null)
        if (saved == "ru" || saved == "en") return saved
        val device = Locale.getDefault().language
        return if (device == "ru" || device == "uk" || device == "be") "ru" else "en"
    }

    fun setLanguage(lang: String) {
        prefs.edit().putString(KEY_LANG, lang).apply()
        _language.value = lang
    }

    private companion object {
        const val KEY_LANG = "lang"
        const val KEY_PRO = "pro_until"
    }
}
