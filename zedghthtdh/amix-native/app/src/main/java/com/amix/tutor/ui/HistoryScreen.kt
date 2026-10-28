package com.amix.tutor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.amix.tutor.R
import com.amix.tutor.data.ChatEntity
import com.amix.tutor.ui.theme.AmixMuted
import com.amix.tutor.ui.theme.AmixText
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(chats: List<ChatEntity>, onOpen: (Long) -> Unit, onClear: () -> Unit) {
    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.hist_h),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (chats.isNotEmpty()) {
                OutlinedButton(onClick = onClear) {
                    Text(stringResource(R.string.hist_clear), color = AmixText)
                }
            }
        }
        if (chats.isEmpty()) {
            Text(stringResource(R.string.hist_empty), color = AmixMuted)
        } else {
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            chats.forEach { chat ->
                PanelCard(Modifier.clickable { onOpen(chat.id) }) {
                    Column {
                        Text(chat.title, maxLines = 2, fontWeight = FontWeight.SemiBold)
                        Text(
                            format.format(Date(chat.updatedAt)),
                            color = AmixMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
