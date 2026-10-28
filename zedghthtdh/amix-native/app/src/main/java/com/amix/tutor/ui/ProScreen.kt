package com.amix.tutor.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amix.tutor.R
import com.amix.tutor.auth.PayStart
import com.amix.tutor.auth.PaySync
import com.amix.tutor.auth.PromoOutcome
import com.amix.tutor.ui.theme.AmixMuted
import java.text.DateFormat
import java.util.Date

/** Экран Amix Pro: тарифы (1 / 6 / 12 месяцев), оплата ЮKassa и строка промокода. */
@Composable
fun ProScreen(
    proUntil: Long,
    signedIn: Boolean,
    pendingPayment: Boolean,
    onStartPay: suspend (Plan) -> PayStart,
    onCheckPay: suspend () -> PaySync,
    onRedeem: suspend (String) -> PromoOutcome,
    onNeedLogin: () -> Unit
) {
    ScreenColumn {
        PanelCard {
            Text(
                stringResource(R.string.pro_eyebrow),
                color = AmixMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.title_pro), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.pro_description), color = AmixMuted, fontSize = 14.sp)
            if (proUntil > System.currentTimeMillis()) {
                Spacer(Modifier.height(10.dp))
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(proUntil))
                Text(stringResource(R.string.pro_active_until, date), color = Color(0xFF4ADE80), fontSize = 13.sp)
            }
        }
        PanelCard { PayCard(signedIn, pendingPayment, onStartPay, onCheckPay, onNeedLogin) }
        PanelCard { PromoCard(signedIn, onRedeem, onNeedLogin) }
    }
}
