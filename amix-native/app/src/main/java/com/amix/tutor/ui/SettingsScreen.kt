package com.amix.tutor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amix.tutor.R
import com.amix.tutor.ui.theme.AmixBg
import com.amix.tutor.ui.theme.AmixMuted
import com.amix.tutor.ui.theme.AmixPanel
import com.amix.tutor.ui.theme.AmixText

@Composable
fun SettingsScreen(lang: String, onLang: (String) -> Unit, onWipe: () -> Unit, onLegal: (LegalDoc) -> Unit) {
    var confirmWipe by remember { mutableStateOf(false) }

    ScreenColumn {
        Text(stringResource(R.string.set_h), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.set_p_native), color = AmixMuted, fontSize = 14.sp)

        SettingRow(stringResource(R.string.set_theme), stringResource(R.string.set_theme_d)) {
            Chip("Dark")
        }
        SettingRow(stringResource(R.string.set_mode), stringResource(R.string.set_mode_d)) {
            Chip(stringResource(R.string.set_mode_chip))
        }
        SettingRow(stringResource(R.string.set_lang), stringResource(R.string.set_lang_d)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LangButton("RU", lang == "ru") { onLang("ru") }
                LangButton("EN", lang == "en") { onLang("en") }
            }
        }
        PanelCard {
            Text(stringResource(R.string.set_legal), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.set_legal_d), color = AmixMuted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Тексты документов появятся на этапе «Документы»
                OutlinedButton(onClick = { onLegal(LegalDoc.POLICY) }) { Text(stringResource(R.string.lg_policy), color = AmixText, fontSize = 13.sp) }
                OutlinedButton(onClick = { onLegal(LegalDoc.TERMS) }) { Text(stringResource(R.string.lg_terms), color = AmixText, fontSize = 13.sp) }
                OutlinedButton(onClick = { onLegal(LegalDoc.OFFER) }) { Text(stringResource(R.string.lg_offer), color = AmixText, fontSize = 13.sp) }
            }
        }
        SettingRow(stringResource(R.string.set_wipe), stringResource(R.string.set_wipe_d)) {
            OutlinedButton(onClick = { confirmWipe = true }) {
                Text(stringResource(R.string.set_wipe_btn), color = AmixText)
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = AmixPanel,
            title = { Text(stringResource(R.string.set_wipe)) },
            text = { Text(stringResource(R.string.wipe_confirm), color = AmixMuted) },
            confirmButton = {
                TextButton(onClick = {
                    onWipe()
                    confirmWipe = false
                }) { Text(stringResource(R.string.set_wipe_btn), color = AmixText) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text(stringResource(R.string.close), color = AmixMuted)
                }
            }
        )
    }
}

@Composable
private fun SettingRow(title: String, desc: String, trailing: @Composable () -> Unit) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(desc, color = AmixMuted, fontSize = 13.sp)
            }
            trailing()
        }
    }
}

@Composable
private fun LangButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AmixBg)
        ) { Text(label, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label, color = AmixText) }
    }
}
