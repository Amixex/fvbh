package com.amix.tutor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.amix.tutor.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class VoiceState { IDLE, ACTIVE, ERROR }

/**
 * Экран «Голосовой ИИ» как в index.html: превью с «чёрной дырой» (фон рисует BlackHoleBackground),
 * микрофон меняет только визуальный эффект и ничего не отправляет.
 * Размеры и тексты взяты из reference/voice.css (мобильные значения).
 */
@Composable
fun VoiceScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val meter = remember { VoiceMeter() }
    var state by remember { mutableStateOf(VoiceState.IDLE) }

    fun startMeter() {
        state = try {
            if (meter.start()) VoiceState.ACTIVE else VoiceState.ERROR
        } catch (e: Throwable) {
            com.amix.tutor.CrashReporter.reportNonFatal(ctx, e, "VoiceMeter.start")
            VoiceState.ERROR
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMeter() else state = VoiceState.ERROR
    }

    fun toggle() {
        if (state == VoiceState.ACTIVE) {
            meter.stop()
            state = VoiceState.IDLE
            return
        }
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startMeter() else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) { onDispose { meter.stop() } }
    // как visibilitychange в вебе: при сворачивании микрофон выключается
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        meter.stop()
        if (state == VoiceState.ACTIVE) state = VoiceState.IDLE
    }

    val easing = CubicBezierEasing(.15f, .8f, .18f, 1f)
    val stage = remember { Animatable(0f) }
    val topReveal = remember { Animatable(0f) }
    val bottomReveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { delay(650); stage.animateTo(1f, tween(1050, easing = easing)) }
        launch { delay(150); topReveal.animateTo(1f, tween(800, easing = easing)) }
        launch { delay(250); bottomReveal.animateTo(1f, tween(900, easing = easing)) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = stage.value
                val s = .92f + .08f * stage.value
                scaleX = s
                scaleY = s
            }
    ) {
        VoiceOrbits(Modifier.align(Alignment.Center))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 18.dp, top = 78.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            VoiceTop(
                Modifier.graphicsLayer {
                    alpha = topReveal.value
                    translationY = (1f - topReveal.value) * 14.dp.toPx()
                }
            )
            VoiceBottom(
                state = state,
                onToggle = { toggle() },
                onBack = onBack,
                modifier = Modifier.graphicsLayer {
                    alpha = bottomReveal.value
                    translationY = (1f - bottomReveal.value) * 14.dp.toPx()
                }
            )
        }
    }
}

@Composable
private fun VoiceOrbits(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "orbits")
    val a by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(19000, easing = LinearEasing)), label = "a")
    val b by inf.animateFloat(360f, 0f, infiniteRepeatable(tween(13000, easing = LinearEasing)), label = "b")
    val c by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(28000, easing = LinearEasing)), label = "c")
    val pulse by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        modifier
            .fillMaxWidth(.95f)
            .aspectRatio(1f)
            .graphicsLayer { alpha = .55f },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val stroke = Stroke(width = 1.dp.toPx())
            fun orbit(wFrac: Float, hFrac: Float, deg: Float, color: Color) {
                val ow = s * wFrac
                val oh = s * hFrac
                rotate(deg, center) {
                    drawOval(color, Offset(center.x - ow / 2f, center.y - oh / 2f), Size(ow, oh), style = stroke)
                }
            }
            orbit(.94f, .48f, a, Color(0x20FFCCAA))
            orbit(.75f, .37f, b, Color(0x35FFC28F))
            orbit(1.12f, .57f, c, Color(0x238E9DFF))
            // кольцо метки: 260% от метки (12% размера), пульсация 0,82..1,18 и прозрачность 0,25..0,8
            val ringR = s * .12f * 2.6f / 2f
            scale(.82f + .36f * pulse, center) {
                drawCircle(
                    Color(0xFFFFE1C6).copy(alpha = (0x22 / 255f) * (.25f + .55f * pulse)),
                    radius = ringR, center = center, style = stroke
                )
            }
        }
        Text(
            "AMIX",
            modifier = Modifier.graphicsLayer { alpha = .45f },
            style = TextStyle(
                fontSize = 8.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFFFF2E4),
                shadow = Shadow(Color(0xFFFF7E4C), Offset.Zero, 26f)
            )
        )
    }
}

@Composable
private fun VoiceTop(modifier: Modifier) {
    val screenW = LocalConfiguration.current.screenWidthDp
    val titleSize = (screenW * .10f).coerceIn(34f, 48f)
    Column(
        modifier
            .widthIn(max = 700.dp)
            .padding(horizontal = 5.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VoiceBadge()
        Text(
            stringResource(R.string.voice_overline),
            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
            style = TextStyle(
                fontSize = 11.sp,
                letterSpacing = 4.sp,
                color = Color(0xFFFFBD91),
                textAlign = TextAlign.Center,
                shadow = Shadow(Color(0x80FF884A), Offset.Zero, 20f)
            )
        )
        Text(
            stringResource(R.string.voice_title),
            style = TextStyle(
                fontSize = titleSize.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp,
                lineHeight = (titleSize * 1.02f).sp,
                color = Color(0xFFF5F5F5),
                textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black, Offset(0f, 6f), 30f)
            )
        )
        Text(
            stringResource(R.string.voice_description),
            modifier = Modifier.widthIn(max = 350.dp).padding(top = 14.dp),
            style = TextStyle(
                fontSize = 13.sp,
                lineHeight = 21.45.sp,
                color = Color(0xFFD0D0D0),
                textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black, Offset(0f, 2f), 15f)
            )
        )
    }
}

@Composable
private fun VoiceBadge() {
    val inf = rememberInfiniteTransition(label = "badge")
    val pulse by inf.animateFloat(
        .62f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dot"
    )
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(Color(0x72050505))
            .border(1.dp, Color(0x22FFFFFF), shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .graphicsLayer {
                    alpha = pulse
                    scaleX = .9f + .28f * ((pulse - .62f) / .38f)
                    scaleY = scaleX
                }
                .clip(CircleShape)
                .background(Color(0xFFFF9A62))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.voice_badge),
            style = TextStyle(
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFDDDDDD)
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceBottom(state: VoiceState, onToggle: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(19.dp)
    Column(
        modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xBA101010), Color(0x8F050505))))
            .border(1.dp, Color(0x18FFFFFF), shape)
            .padding(horizontal = 11.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Статусные надписи убраны по просьбе; остаётся только сообщение, если микрофон не включился
        if (state == VoiceState.ERROR) {
            Text(
                stringResource(R.string.voice_status_error),
                modifier = Modifier.padding(bottom = 13.dp),
                style = TextStyle(fontSize = 12.sp, color = Color(0xFFFF9A9A), textAlign = TextAlign.Center)
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val btnShape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .heightIn(min = 43.dp)
                    .clip(btnShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFFFF1E5), Color(0xFFFFB07A))))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(if (state == VoiceState.ACTIVE) R.string.voice_mic_stop else R.string.voice_mic_start),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF150B06))
                )
            }
            Box(
                Modifier
                    .heightIn(min = 43.dp)
                    .clip(btnShape)
                    .background(Color(0xFF111111))
                    .border(1.dp, Color(0x20FFFFFF), btnShape)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "←  " + stringResource(R.string.voice_back_chat),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                )
            }
        }
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    val inf = rememberInfiniteTransition(label = "status")
    val pulse by inf.animateFloat(
        .62f, 1f,
        infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Canvas(Modifier.size(17.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        if (active) {
            drawCircle(Color(0x15FF9E4A), radius = 5.dp.toPx() + 3.5.dp.toPx(), center = c)
            drawCircle(Color(0x55FF874D), radius = 7.dp.toPx() * (.9f + .28f * ((pulse - .62f) / .38f)), center = c)
            drawCircle(Color(0xFFFFAC7B), radius = 3.5.dp.toPx(), center = c, alpha = pulse)
        } else {
            drawCircle(Color(0x0AFFFFFF), radius = 3.5.dp.toPx() + 4.dp.toPx(), center = c)
            drawCircle(Color(0xFF777777), radius = 3.5.dp.toPx(), center = c)
        }
    }
}
