package com.amix.tutor.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amix.tutor.auth.AccessInfo
import com.amix.tutor.auth.AccessRepository
import com.amix.tutor.auth.AccessView
import com.amix.tutor.auth.AmixUser
import com.amix.tutor.auth.AuthRepository
import com.amix.tutor.auth.PayStart
import com.amix.tutor.auth.PaySync
import com.amix.tutor.auth.PaymentRepository
import com.amix.tutor.auth.PromoOutcome
import com.amix.tutor.auth.PromoRepository
import com.amix.tutor.auth.SignInOutcome
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
    private val auth: AuthRepository,
    private val promo: PromoRepository,
    private val access: AccessRepository,
    private val payment: PaymentRepository,
    dao: ChatDao
) : ViewModel() {

    init {
        auth.start()
        // при любой смене аккаунта (вход, выход) заново спрашиваем сервер о таймерах и Pro
        viewModelScope.launch { auth.user.collect { access.refresh() } }
    }

    val language: StateFlow<String> = settings.language
    val user: StateFlow<AmixUser?> = auth.user
    val accessInfo: StateFlow<AccessInfo?> = access.info
    val proUntil: StateFlow<Long> = access.proUntil
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val chats: StateFlow<List<ChatEntity>> = dao.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun accessNow(): AccessView = access.stateNow()

    fun hasPendingPayment(): Boolean = payment.hasPending()

    fun onResume() {
        viewModelScope.launch {
            access.refresh()
            if (payment.hasPending()) payment.sync()
        }
    }

    fun setLanguage(lang: String) = settings.setLanguage(lang)

    fun toggleLanguage() = settings.setLanguage(if (language.value == "ru") "en" else "ru")

    suspend fun signInGoogle(activity: Activity): SignInOutcome = auth.signInWithGoogle(activity)

    suspend fun signInEmail(email: String, password: String): SignInOutcome = auth.signInWithEmail(email, password)

    suspend fun createEmail(email: String, password: String): SignInOutcome = auth.createWithEmail(email, password)

    fun signOut() {
        auth.signOut()
        settings.setProUntil(0L)
    }

    suspend fun redeem(code: String): PromoOutcome = promo.redeem(code)

    suspend fun startPayment(plan: String): PayStart = payment.start(plan)

    suspend fun checkPayment(): PaySync = payment.sync()
}
