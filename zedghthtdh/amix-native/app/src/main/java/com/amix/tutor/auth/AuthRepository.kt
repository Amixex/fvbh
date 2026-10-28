package com.amix.tutor.auth

import android.app.Activity
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.OAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

data class AmixUser(val uid: String, val name: String?, val email: String?, val photoUrl: String?)

enum class AuthErrorKind { CERT_HASH, NETWORK, CANCELLED, WRONG_CREDENTIALS, EMAIL_IN_USE, WEAK_PASSWORD, INVALID_EMAIL, OTHER }

sealed class SignInOutcome {
    object Success : SignInOutcome()
    data class Failure(val kind: AuthErrorKind, val detail: String = "") : SignInOutcome()
}

/**
 * Вход через Firebase Auth. Google открывается в браузере (Custom Tab), поэтому не требует
 * Google Play Services; запасной вход по почте и паролю подходит для любых устройств.
 */
@Singleton
class AuthRepository @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val auth: FirebaseAuth by lazy {
        FirebaseSetup.init(ctx)
        FirebaseAuth.getInstance()
    }
    private val _user = MutableStateFlow<AmixUser?>(null)
    val user: StateFlow<AmixUser?> = _user.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        try {
            auth.addAuthStateListener { _user.value = it.currentUser?.toAmix() }
        } catch (e: Throwable) {
            com.amix.tutor.CrashReporter.reportNonFatal(ctx, e, "AuthRepository.start")
        }
    }

    fun isSignedIn(): Boolean = _user.value != null

    suspend fun signInWithGoogle(activity: Activity): SignInOutcome = guarded {
        val provider = OAuthProvider.newBuilder("google.com")
            .addCustomParameter("prompt", "select_account")
            .setScopes(listOf("email", "profile"))
            .build()
        val pending = auth.pendingAuthResult
        if (pending != null) pending.awaitTask() else auth.startActivityForSignInWithProvider(activity, provider).awaitTask()
    }

    suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
        guarded { auth.signInWithEmailAndPassword(email.trim(), password).awaitTask() }

    suspend fun createWithEmail(email: String, password: String): SignInOutcome =
        guarded { auth.createUserWithEmailAndPassword(email.trim(), password).awaitTask() }

    fun signOut() {
        try {
            auth.signOut()
        } catch (_: Throwable) {
        }
    }

    suspend fun idToken(): String? = try {
        auth.currentUser?.getIdToken(false)?.awaitTask()?.token
    } catch (_: Throwable) {
        null
    }

    private suspend fun guarded(block: suspend () -> Any?): SignInOutcome = try {
        block()
        SignInOutcome.Success
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val kind = kindOf(e)
        SignInOutcome.Failure(kind, if (kind == AuthErrorKind.CERT_HASH) SigningInfo.describe(ctx) else detailOf(e))
    }

    private fun kindOf(e: Throwable): AuthErrorKind {
        val msg = e.message.orEmpty()
        val code = (e as? FirebaseAuthException)?.errorCode.orEmpty()
        return when {
            code == "ERROR_INVALID_CERT_HASH" -> AuthErrorKind.CERT_HASH
            e is FirebaseNetworkException || e is IOException -> AuthErrorKind.NETWORK
            e is FirebaseAuthWeakPasswordException -> AuthErrorKind.WEAK_PASSWORD
            e is FirebaseAuthUserCollisionException -> AuthErrorKind.EMAIL_IN_USE
            code == "ERROR_INVALID_EMAIL" -> AuthErrorKind.INVALID_EMAIL
            e is FirebaseAuthInvalidUserException || e is FirebaseAuthInvalidCredentialsException ->
                AuthErrorKind.WRONG_CREDENTIALS
            code == "ERROR_WEB_CONTEXT_CANCELED" || msg.contains("cancel", ignoreCase = true) -> AuthErrorKind.CANCELLED
            else -> AuthErrorKind.OTHER
        }
    }

    private fun detailOf(e: Throwable): String {
        val code = (e as? FirebaseAuthException)?.errorCode
        return (e.javaClass.simpleName + (if (code != null) " [$code]" else "") + ": " + e.message.orEmpty()).take(300)
    }

    private fun FirebaseUser.toAmix() = AmixUser(uid, displayName, email, photoUrl?.toString())

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
