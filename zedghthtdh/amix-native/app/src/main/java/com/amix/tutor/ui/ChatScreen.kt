package com.amix.tutor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amix.tutor.AppStage
import com.amix.tutor.ChatMessage
import com.amix.tutor.ChatViewModel
import com.amix.tutor.ErrorKind
import com.amix.tutor.R
import com.amix.tutor.UiError
import com.amix.tutor.ui.theme.AmixAiBorder
import com.amix.tutor.ui.theme.AmixAiBubbleEnd
import com.amix.tutor.ui.theme.AmixAiBubbleStart
import com.amix.tutor.ui.theme.AmixBg
import com.amix.tutor.ui.theme.AmixComposer
import com.amix.tutor.ui.theme.AmixLine
import com.amix.tutor.ui.theme.AmixMuted
import com.amix.tutor.ui.theme.AmixPanel
import com.amix.tutor.ui.theme.AmixText
import com.amix.tutor.ui.theme.AmixUserBubbleEnd
import com.amix.tutor.ui.theme.AmixUserBubbleStart
import com.amix.tutor.ui.theme.AmixUserText
import java.util.Locale

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val stage by vm.stage.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val sysBase = stringResource(R.string.ai_system_base)
    val sysCreator = stringResource(R.string.ai_system_creator)

    Box(Modifier.fillMaxSize()) {
        when (stage) {
            AppStage.NEED_DOWNLOAD -> DownloadScreen(onDownload = { vm.downloadModel() })
            AppStage.DOWNLOADING -> LoadingScreen(progress, stringResource(R.string.model_downloading), null)
            AppStage.COPYING -> LoadingScreen(
                progress,
                stringResource(R.string.model_prepare_title),
                stringResource(R.string.model_prepare_hint)
            )
            AppStage.LOADING -> LoadingScreen(null, stringResource(R.string.model_loading), null)
            AppStage.ERROR -> ErrorScreen(error, onRetry = { vm.retry() })
            AppStage.READY -> ChatBody(
                messages = messages,
                busy = busy,
                onSend = { vm.send(it, sysBase, sysCreator) }
            )
        }
    }
}

@Composable
private fun DownloadScreen(onDownload: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.app_name), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.model_download_hint),
            color = AmixMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDownload,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AmixBg)
        ) {
            Text(stringResource(R.string.model_download_btn), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LoadingScreen(progress: Float?, title: String, hint: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontSize = 18.sp)
        Spacer(Modifier.height(16.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(0.8f),
                color = Color.White,
                trackColor = AmixLine
            )
            Spacer(Modifier.height(8.dp))
            Text("${(progress * 100).toInt()}%")
        } else {
            CircularProgressIndicator(color = Color.White)
        }
        if (hint != null) {
            Spacer(Modifier.height(16.dp))
            Text(hint, color = AmixMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ErrorScreen(error: UiError?, onRetry: () -> Unit) {
    val text = when (error?.kind) {
        ErrorKind.NO_SPACE -> stringResource(
            R.string.err_no_space,
            String.format(Locale.US, "%.1f", error?.missingGb ?: 0f)
        )
        ErrorKind.NO_MEMORY -> stringResource(R.string.err_no_memory)
        else -> stringResource(R.string.err_unknown)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.err_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(text, color = AmixMuted, textAlign = TextAlign.Center)
        val detail = error?.detail.orEmpty()
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(detail, color = AmixMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AmixBg)
        ) {
            Text(stringResource(R.string.retry), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChatBody(messages: List<ChatMessage>, busy: Boolean, onSend: (String) -> Unit) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val lastLength = messages.lastOrNull()?.text?.length ?: 0

    LaunchedEffect(messages.size, lastLength) {
        // +1 за приветственное сообщение в начале списка
        listState.scrollToItem(messages.size)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.chat_title),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f)
            )
            Chip(stringResource(R.string.chat_chip))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Bubble(stringResource(R.string.welcome), false, stringResource(R.string.label_ai))
            }
            items(messages) { m ->
                Bubble(
                    text = m.text,
                    fromUser = m.fromUser,
                    label = if (m.fromUser) stringResource(R.string.you) else stringResource(R.string.label_ai)
                )
            }
        }

        Column(Modifier.fillMaxWidth().background(AmixComposer)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.placeholder), color = AmixMuted) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AmixText,
                        unfocusedTextColor = AmixText,
                        cursorColor = AmixText,
                        focusedBorderColor = Color(0x33FFFFFF),
                        unfocusedBorderColor = AmixLine,
                        focusedContainerColor = AmixPanel,
                        unfocusedContainerColor = AmixPanel
                    )
                )
                Spacer(Modifier.size(8.dp))
                val canSend = !busy && input.isNotBlank()
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (canSend) 1f else 0.4f))
                        .clickable(enabled = canSend) {
                            onSend(input.trim())
                            input = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = AmixBg)
                }
            }
            Text(
                stringResource(R.string.ai_hint),
                color = AmixMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        val shape = RoundedCornerShape(18.dp)
        val brush = if (fromUser) {
            Brush.linearGradient(listOf(AmixUserBubbleStart, AmixUserBubbleEnd))
        } else {
            Brush.linearGradient(listOf(AmixAiBubbleStart, AmixAiBubbleEnd))
        }
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(brush)
                .border(1.dp, if (fromUser) Color.White else AmixAiBorder, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (fromUser) Color(0x99080808) else AmixMuted
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (text.isBlank()) "…" else text,
                color = if (fromUser) AmixUserText else AmixText,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}
