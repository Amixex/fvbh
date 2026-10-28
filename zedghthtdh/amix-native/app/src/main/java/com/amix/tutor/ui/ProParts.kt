package com.amix.tutor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.amix.tutor.R
import com.amix.tutor.auth.PayStart
import com.amix.tutor.auth.PaySync
import com.amix.tutor.auth.PromoOutcome
import com.amix.tutor.ui.theme.AmixBg
import com.amix.tutor.ui.theme.AmixLine
import com.amix.tutor.ui.theme.AmixMuted
import com.amix.tutor.ui.theme.AmixPanel
import com.amix.tutor.ui.theme.AmixPanel2
import com.amix.tutor.ui.theme.AmixText
import kotlinx.coroutines.launch

private val Green = Color(0xFF4ADE80)
private val Red = Color(0xFFFF9A9A)

/** Тарифы: цена и срок на сервере (m1 = 200 ₽, m6 = 900 ₽ со скидкой 25%, m12 = 1500 ₽ со скидкой 37%). */
enum class Plan(val id: String, val title: Int, val price: Int, val eq: Int?, val off: Int?) {
    M1("m1", R.string.plan_1m, R.string.plan_price_1m, null, null),
    M6("m6", R.string.plan_6m, R.string.plan_price_6m, R.string.plan_eq_6m, R.string.plan_off_6m),
    M12("m12", R.string.plan_12m, R.string.plan_price_12m, R.string.plan_eq_12m, R.string.plan_off_12m)
}

@Composable
fun PlanPicker(selected: Plan, onSelect: (Plan) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Plan.values().forEach { plan ->
            val sel = plan == selected
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (sel) AmixPanel2 else Color.Transparent)
                    .border(1.dp, if (sel) Color(0x66FFFFFF) else AmixLine, shape)
                    .clickable { onSelect(plan) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(18.dp).border(1.5.dp, if (sel) Color.White else AmixMuted, CircleShape).padding(4.dp)) {
                    if (sel) Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(plan.title), fontWeight = FontWeight.SemiBold)
                    plan.eq?.let { Text(stringResource(it), color = AmixMuted, fontSize = 12.sp) }
                }
                plan.off?.let {
                    Text(
                        stringResource(it), color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Color(0x224ADE80)).padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(plan.price), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Выбор тарифа и оплата через ЮKassa: страница оплаты открывается в браузере, после возврата Pro подтверждает сервер. */
@Composable
fun PayCard(
    signedIn: Boolean,
    pending: Boolean,
    onStart: suspend (Plan) -> PayStart,
    onCheck: suspend () -> PaySync,
    onNeedLogin: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val activity = LocalHostActivity.current
    var plan by remember { mutableStateOf(Plan.M6) }
    var busy by remember { mutableStateOf(false) }
    var showCheck by remember { mutableStateOf(pending) }
    var msgRes by remember { mutableIntStateOf(0) }
    var ok by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.plan_title), fontWeight = FontWeight.SemiBold)
        PlanPicker(plan) { plan = it }
        Button(
            onClick = {
                if (!signedIn) {
                    msgRes = R.string.pay_login
                    ok = false
                    onNeedLogin()
                } else {
                    scope.launch {
                        busy = true
                        when (val r = onStart(plan)) {
                            is PayStart.Url -> {
                                msgRes = R.string.pay_started
                                ok = true
                                showCheck = true
                                try {
                                    activity?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)))
                                } catch (_: Throwable) {
                                    msgRes = R.string.pay_error
                                    ok = false
                                }
                            }
                            PayStart.LoginRequired -> { msgRes = R.string.pay_login; ok = false; onNeedLogin() }
                            PayStart.NoConnection -> { msgRes = R.string.no_internet; ok = false }
                            is PayStart.Failed -> { msgRes = R.string.pay_error; ok = false }
                        }
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AmixBg)
        ) { Text(stringResource(R.string.pay_button), fontWeight = FontWeight.Bold) }

        if (showCheck && signedIn) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        when (onCheck()) {
                            is PaySync.Granted -> { msgRes = R.string.pay_done; ok = true; showCheck = false }
                            PaySync.Pending -> { msgRes = R.string.pay_pending; ok = false }
                            PaySync.NoConnection -> { msgRes = R.string.no_internet; ok = false }
                            PaySync.None -> { msgRes = R.string.pay_error; ok = false; showCheck = false }
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.pay_check), color = AmixText) }
        }
        if (msgRes != 0) Text(stringResource(msgRes), color = if (ok) Green else Red, fontSize = 13.sp)
    }
}

/** Строка промокода: проверка только на сервере, нужен интернет и вход. */
@Composable
fun PromoCard(signedIn: Boolean, onRedeem: suspend (String) -> PromoOutcome, onNeedLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var messageRes by remember { mutableIntStateOf(0) }
    var messageDays by remember { mutableIntStateOf(0) }
    var messageOk by remember { mutableStateOf(false) }

    fun submit() {
        if (code.isBlank()) { messageRes = R.string.promo_enter; messageOk = false; return }
        if (!signedIn) { messageRes = R.string.promo_login_first; messageOk = false; onNeedLogin(); return }
        scope.launch {
            busy = true
            val r = onRedeem(code)
            busy = false
            messageOk = r is PromoOutcome.Ok
            if (r is PromoOutcome.Ok) messageDays = r.days
            messageRes = when (r) {
                is PromoOutcome.Ok -> R.string.promo_ok
                PromoOutcome.NotFound, PromoOutcome.BadRequest -> R.string.promo_not_found
                PromoOutcome.Used -> R.string.promo_used
                PromoOutcome.RateLimited -> R.string.promo_rate_limited
                PromoOutcome.NoConnection -> R.string.no_internet
                PromoOutcome.LoginRequired -> R.string.promo_login_first
                is PromoOutcome.Failed -> R.string.promo_error
            }
            if (r is PromoOutcome.Ok) code = ""
            if (r is PromoOutcome.LoginRequired) onNeedLogin()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.promo_title), fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.promo_ph), color = AmixMuted) },
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
        ) { Text(stringResource(R.string.promo_apply), fontWeight = FontWeight.Bold) }
        if (messageRes != 0) {
            Text(
                stringResource(messageRes).replace("{days}", messageDays.toString()),
                color = if (messageOk) Green else Red,
                fontSize = 13.sp
            )
        }
    }
}

/** Окно после окончания 7 дней: не закрывается, пока не оплачен тариф (или не активирован промокод). */
@Composable
fun PaywallDialog(
    signedIn: Boolean,
    pending: Boolean,
    onStart: suspend (Plan) -> PayStart,
    onCheck: suspend () -> PaySync,
    onRedeem: suspend (String) -> PromoOutcome,
    onNeedLogin: () -> Unit,
    onSignOut: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = AmixPanel,
        title = { Text(stringResource(R.string.paywall_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.paywall_text), color = AmixMuted, fontSize = 13.sp)
                PayCard(signedIn, pending, onStart, onCheck, onNeedLogin)
                PromoCard(signedIn, onRedeem, onNeedLogin)
            }
        },
        confirmButton = {
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.logout), color = AmixMuted) }
        }
    )
}
