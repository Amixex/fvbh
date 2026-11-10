package com.amix.tutor.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amix.tutor.R
import com.amix.tutor.auth.AccessKind
import com.amix.tutor.auth.AccessView
import com.amix.tutor.auth.LicenseOutcome
import com.amix.tutor.ui.theme.AmixMuted
import java.text.DateFormat
import java.util.Date

/** Экран Amix PRO: статус лицензии, активация ключа, кнопка «Приобрести PRO» и промокод. */
@Composable
fun ProScreen(
    view: AccessView,
    onKey: suspend (String) -> LicenseOutcome,
    onPromo: suspend (String) -> LicenseOutcome
) {
    val unit = stringResource(R.string.timer_day_unit)
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
            Spacer(Modifier.height(12.dp))
            when (view.kind) {
                AccessKind.PRO -> {
                    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(System.currentTimeMillis() + view.leftMs))
                    Text(stringResource(R.string.pro_active_until, date), color = Color(0xFF4ADE80), fontSize = 13.sp)
                }
                AccessKind.TRIAL -> Text(
                    stringResource(R.string.status_trial, formatLeft(view.leftMs, unit)),
                    color = Color(0xFF4ADE80), fontSize = 13.sp
                )
                AccessKind.EXPIRED -> Text(stringResource(R.string.status_expired), color = Color(0xFFFF9A9A), fontSize = 13.sp)
                AccessKind.LOADING -> Unit
            }
        }
        PanelCard { CodeCard(CodeKind.KEY, onKey) }
        PanelCard {
            BuyProButton()
            Text(
                stringResource(R.string.buy_hint),
                color = AmixMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        PanelCard { CodeCard(CodeKind.PROMO, onPromo) }
    }
}
