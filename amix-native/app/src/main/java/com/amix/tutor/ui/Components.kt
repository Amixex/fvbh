package com.amix.tutor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amix.tutor.ui.theme.AmixBg
import com.amix.tutor.ui.theme.AmixLine
import com.amix.tutor.ui.theme.AmixMuted
import com.amix.tutor.ui.theme.AmixPanel

/** Логотип: серебристый скруглённый квадрат с ромбом ◈, как в веб-версии. */
@Composable
fun AmixLogo(size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.33f))
            .background(Brush.linearGradient(listOf(Color(0xFFF1F1F1), Color(0xFF9A9A9A)))),
        contentAlignment = Alignment.Center
    ) {
        Text("◈", color = Color(0xFF111111), fontSize = (size.value * 0.55f).sp)
    }
}

@Composable
fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AmixPanel)
            .border(1.dp, AmixLine, shape)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
fun Chip(text: String) {
    val shape = RoundedCornerShape(99.dp)
    Text(
        text = text,
        color = AmixMuted,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, AmixLine, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/** Оставшееся время: «6д 12:30» (если есть дни) или «12:30:05». */
fun formatLeft(ms: Long, dayUnit: String): String {
    val total = maxOf(0L, ms / 1000L)
    val d = total / 86400L
    val h = (total % 86400L) / 3600L
    val m = (total % 3600L) / 60L
    val sec = total % 60L
    return if (d > 0) String.format(java.util.Locale.US, "%d%s %02d:%02d", d, dayUnit, h, m)
    else String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, sec)
}
