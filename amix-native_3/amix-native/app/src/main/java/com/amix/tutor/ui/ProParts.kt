package com.amix.tutor.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.amix.tutor.R
import com.amix.tutor.auth.LicenseOutcome
import com.amix.tutor.ui.theme.AmixBg
import com.amix.tutor.ui.theme.AmixLine
import com.amix.tutor.ui.theme.AmixMuted
import com.amix.tutor.ui.theme.AmixPanel
import com.amix.tutor.ui.theme.AmixText
import kotlinx.coroutines.launch

private val Green = Color(0xFF4ADE80)
private val Red = Color(0xFFFF9A9A)

/** Открывает Telegram продавца: через установленное приложение, а если его нет, через браузер. Без промежуточных окон. */
fun openBuyLink(activity: Activity?, url: String) {
    try {
        activity?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) {
    }
}

/** Единая кнопка «Приобрести PRO». Ссылка берётся из res/values/config.xml (buy_url). */
@Composable
fun BuyProButton(modifier: Modifier = Modifier.fillMaxWidth()) {
    val activity = LocalHostActivity.current
    val url = stringResource(R.string.buy_url)
    Button(
        onClick = { openBuyLink(activity, url) },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AmixBg)
    ) {
        Text(stringResource(R.string.buy_pro), fontWeight = FontWeight.Bold)
    }
}

enum class CodeKind { KEY, PROMO }

/** Поле ввода кода (лицензионный ключ или промокод) с кнопкой и понятным сообщением о результате. */
@Composable
fun CodeCard(kind: CodeKind, onSubmit: suspend (String) -> LicenseOutcome) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var messageRes by remember { mutableIntStateOf(0) }
    var days by remember { mutableIntStateOf(0) }
    var ok by remember { mutableStateOf(false) }
    val key = kind == CodeKind.KEY

    fun submit() {
        if (code.isBlank()) {
            messageRes = if (key) R.string.key_enter else R.string.promo_enter
            ok = false
            return
        }
        scope.launch {
            busy = true
            val r = onSubmit(code)
            busy = false
            ok = r is LicenseOutcome.Ok
            if (r is LicenseOutcome.Ok) days = r.days
            messageRes = when (r) {
                is LicenseOutcome.Ok -> if (key) R.string.key_ok else R.string.promo_ok
                LicenseOutcome.NotFound, LicenseOutcome.BadRequest -> if (key) R.string.key_not_found else R.string.promo_not_found
                LicenseOutcome.Used -> if (key) R.string.key_used else R.string.promo_used
                LicenseOutcome.Expired -> if (key) R.string.key_expired else R.string.promo_expired
                LicenseOutcome.Revoked -> if (key) R.string.key_revoked else R.string.promo_revoked
                LicenseOutcome.RateLimited -> R.string.promo_rate_limited
                LicenseOutcome.NoConnection -> R.string.no_internet
                is LicenseOutcome.Failed -> if (key) R.string.key_error else R.string.promo_error
            }
            if (r is LicenseOutcome.Ok) code = ""
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(if (key) R.string.key_title else R.string.promo_title), fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            placeholder = { Text(stringResource(if (key) R.string.key_hint else R.string.promo_ph), color = AmixMuted) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AmixText, unfocusedTextColor = AmixText, cursorColor = AmixText,
                focusedBorderColor = Color(0x33FFFFFF), unfocusedBorderColor = AmixLine
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { submit() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AmixBg)
        ) {
            Text(stringResource(if (key) R.string.key_apply else R.string.promo_apply_btn), fontWeight = FontWeight.Bold)
        }
        if (messageRes != 0) {
            Text(
                stringResource(messageRes).replace("{days}", days.toString()),
                color = if (ok) Green else Red,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * Окно после окончания 7 дней: не закрывается (ни кнопкой «Назад», ни касанием снаружи),
 * пока не активирован ключ или промокод. Порядок: ключ, покупка, промокод.
 */
@Composable
fun ActivationDialog(onKey: suspend (String) -> LicenseOutcome, onPromo: suspend (String) -> LicenseOutcome) {
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = AmixPanel,
        title = { Text(stringResource(R.string.trial_over_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.trial_over_text), color = AmixMuted, fontSize = 14.sp)
                CodeCard(CodeKind.KEY, onKey)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BuyProButton()
                    Text(stringResource(R.string.buy_hint), color = AmixMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
                CodeCard(CodeKind.PROMO, onPromo)
            }
        },
        confirmButton = { }
    )
}
