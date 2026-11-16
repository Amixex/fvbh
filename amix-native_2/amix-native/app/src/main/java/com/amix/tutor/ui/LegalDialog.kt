package com.amix.tutor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amix.tutor.R
import com.amix.tutor.ui.theme.AmixBg
import com.amix.tutor.ui.theme.AmixMuted
import com.amix.tutor.ui.theme.AmixText

enum class LegalDoc(val file: String) { POLICY("policy"), TERMS("terms"), OFFER("offer") }

/** Документы лежат текстом в assets/legal (policy/terms/offer на RU и EN), их же можно опубликовать на сайте. */
@Composable
fun LegalDialog(doc: LegalDoc, lang: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val raw = remember(doc, lang) {
        try {
            ctx.assets.open("legal/${doc.file}_$lang.txt").bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            ""
        }
    }
    val lines = remember(raw) { raw.split('\n') }
    val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ").orEmpty()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = AmixBg, contentColor = AmixText) {
            Column(Modifier.systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close), color = AmixMuted) }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (line in lines) {
                        val t = line.trim()
                        when {
                            t.isEmpty() || t.startsWith("# ") -> Unit
                            t.startsWith("## ") -> Text(
                                t.removePrefix("## "), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            t.startsWith("- ") -> Text("•  " + t.removePrefix("- "), fontSize = 14.sp, lineHeight = 21.sp)
                            t.startsWith("_") && t.endsWith("_") -> Text(
                                t.trim('_'), fontSize = 12.sp, color = AmixMuted, fontStyle = FontStyle.Italic
                            )
                            else -> Text(t, fontSize = 14.sp, lineHeight = 21.sp)
                        }
                    }
                }
            }
        }
    }
}
