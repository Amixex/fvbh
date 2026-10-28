package com.amix.tutor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

object IntroState {
    @Volatile
    var shown = false
}

/** Заставка при запуске: логотип «выплывает» из тьмы внутри раскрывающегося кольца диска, затем всё растворяется в приложение. */
@Composable
fun AppIntro(onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(2600, easing = LinearEasing))
        onDone()
    }
    val p = t.value
    val exit = ((p - .80f) / .20f).coerceIn(0f, 1f)
    val textP = ((p - .30f) / .30f).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer {
                alpha = 1f - exit
                val s = 1f + .12f * exit
                scaleX = s
                scaleY = s
            }
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension * .22f
            val grow = FastOutSlowInEasing.transform((p / .8f).coerceIn(0f, 1f))
            val glowAlpha = sin((p.coerceIn(0f, 1f)) * PI.toFloat()).coerceIn(0f, 1f)
            drawCircle(
                Brush.radialGradient(listOf(Color(0x66FFB070).copy(alpha = .4f * glowAlpha), Color.Transparent), center = c, radius = base * 1.8f),
                radius = base * 1.8f, center = c
            )
            for (i in 0..2) {
                val r = base * (.9f + i * .28f) * (.55f + .75f * grow)
                val a = glowAlpha * (.6f - i * .14f)
                rotate((-12f + p * 140f) * (if (i % 2 == 0) 1f else -1f), c) {
                    drawOval(
                        (if (i % 2 == 0) Color(0xFFFF8A3D) else Color(0xFFFFD2A0)).copy(alpha = a),
                        Offset(c.x - r * 1.45f, c.y - r * .5f), Size(r * 2.9f, r), style = Stroke(1.5.dp.toPx())
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.graphicsLayer {
                    alpha = (p / .25f).coerceIn(0f, 1f)
                    val s = .6f + .4f * FastOutSlowInEasing.transform((p / .4f).coerceIn(0f, 1f)) + .03f * sin(p * 2f * PI.toFloat())
                    scaleX = s
                    scaleY = s
                }
            ) { AmixLogo(96.dp) }
            Spacer(Modifier.height(20.dp))
            Text(
                "AMIX",
                modifier = Modifier.graphicsLayer { alpha = textP },
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (14f - 10f * FastOutSlowInEasing.transform(textP)).sp,
                    color = Color(0xFFF5F5F5)
                )
            )
        }
    }
}
