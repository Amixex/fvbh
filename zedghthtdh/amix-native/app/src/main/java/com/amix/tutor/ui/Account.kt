package com.amix.tutor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.amix.tutor.R
import com.amix.tutor.auth.AmixUser
import com.amix.tutor.auth.AuthErrorKind
import com.amix.tutor.auth.SignInOutcome
import com.amix.tutor.ui.theme.AmixBg
import com.amix.tutor.ui.theme.AmixLine
import com.amix.tutor.ui.theme.AmixMuted
import com.amix.tutor.ui.theme.AmixPanel
import com.amix.tutor.ui.theme.AmixPanel2
import com.amix.tutor.ui.theme.AmixText
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Аватарка из фото аккаунта (Coil кэширует на диск, поэтому работает офлайн). Без фото: круг с первой буквой. */
@Composable
fun AmixAvatar(user: AmixUser, size: Dp) {
    val letter = (user.name?.firstOrNull() ?: user.email?.firstOrNull() ?: '?').uppercaseChar().toString()
    Box(
        Modifier.size(size).clip(CircleShape).background(AmixPanel2),
        contentAlignment = Alignment.Center
    ) {
        Text(letter, fontSize = (size.value * .42f).sp, fontWeight = FontWeight.Bold)
        if (!user.photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = user.photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        }
    }
}

/** Кнопка справа вверху: аватарка аккаунта, а без входа значок как раньше. Размер и рамка прежние (44 dp). */
@Composable
fun AccountButton(user: AmixUser?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(AmixPanel2)
            .border(1.dp, AmixLine, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (user != null) AmixAvatar(user, 44.dp) else Text("☺", fontSize = 15.sp)
    }
}

@Composable
private fun authErrorText(f: SignInOutcome.Failure): String = when (f.kind) {
    AuthErrorKind.CERT_HASH -> stringResource(R.string.auth_err_cert, f.detail)
    AuthErrorKind.NETWORK -> stringResource(R.string.auth_err_network)
    AuthErrorKind.CANCELLED -> stringResource(R.string.auth_err_cancelled)
    AuthErrorKind.WRONG_CREDENTIALS -> stringResource(R.string.auth_err_wrong)
    AuthErrorKind.EMAIL_IN_USE -> stringResource(R.string.auth_err_exists)
    AuthErrorKind.WEAK_PASSWORD -> stringResource(R.string.auth_err_weak)
    AuthErrorKind.INVALID_EMAIL -> stringResource(R.string.auth_err_email)
    AuthErrorKind.OTHER -> stringResource(R.string.auth_err_other, f.detail)
}

@Composable
fun AccountDialog(
    user: AmixUser?,
    proUntil: Long,
    onDismiss: () -> Unit,
    onGoogle: suspend () -> SignInOutcome,
    onEmailIn: suspend (String, String) -> SignInOutcome,
    onEmailNew: suspend (String, String) -> SignInOutcome,
    onSignOut: () -> Unit,
    blocking: Boolean = false
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<SignInOutcome.Failure?>(null) }
    var emptyFields by remember { mutableStateOf(false) }

    fun run(action: suspend () -> SignInOutcome) {
        scope.launch {
            busy = true
            failure = null
            emptyFields = false
            val result = action()
            busy = false
            if (result is SignInOutcome.Failure) failure = result else onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy && !blocking) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !blocking, dismissOnClickOutside = !blocking),
        containerColor = AmixPanel,
        title = { Text(stringResource(if (blocking) R.string.gate_auth_title else R.string.auth_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (user != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AmixAvatar(user, 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(user.name ?: user.email.orEmpty(), fontWeight = FontWeight.SemiBold)
                            if (!user.name.isNullOrBlank() && !user.email.isNullOrBlank()) {
                                Text(user.email, color = AmixMuted, fontSize = 13.sp)
                            }
                        }
                    }
                    if (proUntil > System.currentTimeMillis()) {
                        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(proUntil))
                        Text(stringResource(R.string.pro_active_until, date), color = AmixMuted, fontSize = 13.sp)
                    }
                } else {
                    if (blocking) Text(stringResource(R.string.gate_auth_text), color = AmixMuted, fontSize = 13.sp)
                    Button(
                        onClick = { run { onGoogle() } },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AmixBg)
                    ) { Text(stringResource(R.string.auth_google), fontWeight = FontWeight.SemiBold) }

                    Text(
                        stringResource(R.string.auth_or),
                        color = AmixMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AmixText, unfocusedTextColor = AmixText, cursorColor = AmixText,
                        focusedBorderColor = Color(0x33FFFFFF), unfocusedBorderColor = AmixLine
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.auth_email_hint), color = AmixMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = colors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.auth_password_hint), color = AmixMuted) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = colors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (email.isBlank() || password.isEmpty()) emptyFields = true
                                else run { onEmailIn(email, password) }
                            },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AmixBg)
                        ) { Text(stringResource(R.string.auth_sign_in), fontWeight = FontWeight.SemiBold) }
                        OutlinedButton(
                            onClick = {
                                if (email.isBlank() || password.isEmpty()) emptyFields = true
                                else run { onEmailNew(email, password) }
                            },
                            enabled = !busy
                        ) { Text(stringResource(R.string.auth_create), color = AmixText) }
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    if (emptyFields) Text(stringResource(R.string.auth_err_empty), color = Color(0xFFFF9A9A), fontSize = 13.sp)
                    failure?.let { Text(authErrorText(it), color = Color(0xFFFF9A9A), fontSize = 13.sp) }
                }
            }
        },
        confirmButton = {
            if (user != null) {
                TextButton(onClick = {
                    onSignOut()
                    onDismiss()
                }) { Text(stringResource(R.string.logout), color = AmixText) }
            }
        },
        dismissButton = if (blocking) null else {
            {
                TextButton(onClick = { if (!busy) onDismiss() }) { Text(stringResource(R.string.close), color = AmixMuted) }
            }
        }
    )
}
