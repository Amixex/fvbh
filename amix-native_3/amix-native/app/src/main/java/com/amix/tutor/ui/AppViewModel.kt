package com.amix.tutor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amix.tutor.auth.AccessInfo
import com.amix.tutor.auth.AccessRepository
import com.amix.tutor.auth.AccessView
import com.amix.tutor.auth.LicenseOutcome
import com.amix.tutor.auth.LicenseRepository
import com.amix.tutor.data.ChatDao
import com.amix.tutor.data.ChatEntity
import com.amix.tutor.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val access: AccessRepository,
    private val license: LicenseRepository,
    dao: ChatDao
) : ViewModel() {

    init {
        viewModelScope.launch { access.refresh() }
    }

    val language: StateFlow<String> = settings.language
    val accessInfo: StateFlow<AccessInfo?> = access.info
    val proUntil: StateFlow<Long> = access.proUntil.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val chats: StateFlow<List<ChatEntity>> = dao.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun accessNow(): AccessView = access.stateNow()

    fun onResume() {
        viewModelScope.launch { access.refresh() }
    }

    fun setLanguage(lang: String) = settings.setLanguage(lang)

    fun toggleLanguage() = settings.setLanguage(if (language.value == "ru") "en" else "ru")

    suspend fun activateKey(key: String): LicenseOutcome = license.activateKey(key)

    suspend fun redeemPromo(code: String): LicenseOutcome = license.redeemPromo(code)
}
