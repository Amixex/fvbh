package com.amix.tutor.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer

fun reducedMotion(ctx: Context): Boolean =
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Насколько «чёрная дыра» раскрыта (0..1). Пишет BlackHoleBackground, читает переход, чтобы пыль летела в дыру. */
object HoleState {
    @Volatile
    var expansion: Float = 0f
}

enum class FxMode { OUT, IN, VORTEX }

internal class Dust(
    val x: Float, val y: Float, val size: Float, val round: Boolean, val color: Color,
    val dx: Float, val dy: Float, val mx: Float, val my: Float, val qx: Float, val qy: Float,
    val spin: Float, val durMs: Float, val delayMs: Float, val opacity: Float
)

private val OUT_EASE: Easing = CubicBezierEasing(.45f, 0f, .95f, .45f)
private val IN_EASE: Easing = CubicBezierEasing(.16f, 1f, .3f, 1f)
private val COLORS = listOf(0xFFF7F7F7, 0xFFB7BDC7, 0xFFF0BB83, 0xFFF3E4D2, 0xFFFFFFFF, 0xFFD6D6DC, 0xFF8B919C).map { Color(it) }

private fun seg(t: Float, stops: FloatArray, vals: FloatArray, ease: Easing): Float {
    if (t <= stops.first()) return vals.first()
    if (t >= stops.last()) return vals.last()
    var i = 0
    while (t > stops[i + 1]) i++
    val f = (t - stops[i]) / (stops[i + 1] - stops[i])
    return vals[i] + (vals[i + 1] - vals[i]) * ease.transform(f)
}

/**
 * Переход между экранами, как в index.html: содержимое «схлопывается» в чёрную дыру (blackHoleCollapse),
 * разлетается пылью (dustAway; при входе в голосовой режим вихрь dustVortex), затем новый экран собирается
 * из пыли (contentForm + dustGather). Формы путей частиц приближённые.
 */
class ViewFx {
    var active by mutableStateOf(false)
    var contentAlpha by mutableFloatStateOf(1f)
    var contentScale by mutableFloatStateOf(1f)
    var contentRot by mutableFloatStateOf(0f)
    var elapsedMs by mutableFloatStateOf(0f)
    internal var mode: FxMode = FxMode.OUT
    internal var dust: List<Dust> = emptyList()
    var busy = false
        private set

    private fun hole(vortex: Boolean, w: Float, h: Float): Offset {
        if (vortex) return Offset(w * .5f, h * .5f)
        val e = HoleState.expansion
        val nx = if (w < 700f) w * .74f else w * .715f
        val ny = if (w < 700f) h * .39f else h * .50f
        return Offset(nx + (w * .5f - nx) * e, ny + (h * .5f - ny) * e)
    }

    private fun make(m: FxMode, w: Float, h: Float, top: Float): List<Dust> {
        val rnd = java.util.Random()
        fun r() = rnd.nextFloat()
        val count = minOf(260, maxOf(120, (w * h / 4600f).toInt()))
        val hole = hole(m == FxMode.VORTEX, w, h)
        return List(count) {
            val x = 16f + r() * (w - 32f)
            val y = top + r() * (h - top - 24f)
            val dx = hole.x - x
            val dy = hole.y - y
            val k1 = .30f + r() * .10f
            val k2 = .64f + r() * .10f
            val (dur, delay) = when (m) {
                FxMode.OUT -> (.42f + r() * .2f) to (r() * .12f)
                FxMode.IN -> (.52f + r() * .3f) to (r() * .12f)
                FxMode.VORTEX -> (1.0f + r() * .35f) to (r() * .18f)
            }
            Dust(
                x, y, 1f + r() * 3f, r() < .58f, COLORS[(r() * COLORS.size).toInt().coerceAtMost(COLORS.size - 1)],
                dx, dy,
                dx * k1 + (r() - .5f) * 90f, dy * k1 + (r() - .5f) * 90f,
                dx * k2 + (r() - .5f) * 60f, dy * k2 + (r() - .5f) * 60f,
                (r() - .5f) * 1400f, dur * 1000f, delay * 1000f, .35f + r() * .55f
            )
        }
    }

    private suspend fun loop(durationMs: Float, onFrame: (Float) -> Unit) {
        var start = 0L
        while (true) {
            val done = withFrameNanos { now ->
                if (start == 0L) start = now
                val ms = (now - start) / 1_000_000f
                elapsedMs = ms
                onFrame(ms)
                ms >= durationMs
            }
            if (done) break
        }
    }

    suspend fun run(vortex: Boolean, wDp: Float, hDp: Float, topDp: Float, navigate: () -> Unit) {
        if (busy) return
        busy = true
        try {
            mode = if (vortex) FxMode.VORTEX else FxMode.OUT
            dust = make(mode, wDp, hDp, topDp)
            active = true
            val stops = floatArrayOf(0f, .35f, .72f, 1f)
            loop(if (vortex) 1150f else 760f) { ms ->
                val t = (ms / 560f).coerceIn(0f, 1f)
                contentAlpha = seg(t, stops, floatArrayOf(1f, .93f, .54f, 0f), OUT_EASE)
                contentScale = seg(t, stops, floatArrayOf(1f, .985f, .91f, .78f), OUT_EASE)
                contentRot = seg(t, stops, floatArrayOf(0f, -.4f, 1.1f, 3f), OUT_EASE)
            }
            navigate()
            mode = FxMode.IN
            dust = make(FxMode.IN, wDp, hDp, topDp)
            elapsedMs = 0f
            contentAlpha = 0f
            contentScale = .99f
            contentRot = 0f
            loop(980f) { ms ->
                val t = (ms / 580f).coerceIn(0f, 1f)
                val s = floatArrayOf(0f, .65f, 1f)
                contentAlpha = seg(t, s, floatArrayOf(0f, 1f, 1f), IN_EASE)
                contentScale = seg(t, s, floatArrayOf(.99f, 1.003f, 1f), IN_EASE)
            }
        } finally {
            active = false
            contentAlpha = 1f
            contentScale = 1f
            contentRot = 0f
            busy = false
        }
    }
}

/** Применяется к области экранов: «схлопывание» вокруг точки (72%, 48%), как в вебе. */
fun Modifier.fxContent(fx: ViewFx): Modifier = graphicsLayer {
    alpha = fx.contentAlpha
    scaleX = fx.contentScale
    scaleY = fx.contentScale
    rotationZ = fx.contentRot
    transformOrigin = TransformOrigin(.72f, .48f)
}

@Composable
fun DustOverlay(fx: ViewFx) {
    if (!fx.active) return
    Canvas(Modifier.fillMaxSize()) {
        val t = fx.elapsedMs
        val d = density
        val mode = fx.mode
        for (p in fx.dust) {
            if (t < p.delayMs) continue
            val u = ((t - p.delayMs) / p.durMs).coerceIn(0f, 1f)
            val tx: Float
            val ty: Float
            val rot: Float
            val sc: Float
            val op: Float
            when (mode) {
                FxMode.OUT -> {
                    val s = floatArrayOf(0f, .38f, .72f, 1f)
                    tx = seg(u, s, floatArrayOf(0f, p.mx, p.qx, p.dx), OUT_EASE)
                    ty = seg(u, s, floatArrayOf(0f, p.my, p.qy, p.dy), OUT_EASE)
                    rot = seg(u, s, floatArrayOf(0f, p.spin * .3f, p.spin * .75f, p.spin), OUT_EASE)
                    sc = seg(u, s, floatArrayOf(1f, .72f, .32f, .015f), OUT_EASE)
                    op = seg(u, floatArrayOf(0f, .10f, .38f, .72f, 1f), floatArrayOf(0f, p.opacity, p.opacity, .72f, 0f), OUT_EASE)
                }
                FxMode.VORTEX -> {
                    val s = floatArrayOf(0f, .08f, .30f, .62f, .88f, 1f)
                    tx = seg(u, s, floatArrayOf(0f, p.mx * .18f, p.mx, p.qx, p.dx * .92f, p.dx), OUT_EASE)
                    ty = seg(u, s, floatArrayOf(0f, p.my * .18f, p.my, p.qy, p.dy * .92f, p.dy), OUT_EASE)
                    rot = seg(u, s, floatArrayOf(0f, p.spin * .15f, p.spin * .45f, p.spin * .75f, p.spin, p.spin * 1.15f), OUT_EASE)
                    sc = seg(u, s, floatArrayOf(1f, .95f, .7f, .36f, .1f, .02f), OUT_EASE)
                    op = seg(u, s, floatArrayOf(0f, p.opacity, p.opacity, .85f, .55f, 0f), OUT_EASE)
                }
                FxMode.IN -> {
                    val s = floatArrayOf(0f, .68f, 1f)
                    tx = seg(u, s, floatArrayOf(p.dx, p.mx, 0f), IN_EASE)
                    ty = seg(u, s, floatArrayOf(p.dy, p.my, 0f), IN_EASE)
                    rot = seg(u, s, floatArrayOf(p.spin, p.spin * .35f, 0f), IN_EASE)
                    sc = seg(u, s, floatArrayOf(.015f, .55f, .9f), IN_EASE)
                    op = seg(u, floatArrayOf(0f, .22f, .68f, 1f), floatArrayOf(0f, p.opacity, .75f, 0f), IN_EASE)
                }
            }
            if (op <= .01f) continue
            val c = Offset((p.x + tx) * d, (p.y + ty) * d)
            val side = p.size * sc * d
            drawCircle(p.color.copy(alpha = op * .16f), radius = side * 1.9f, center = c)
            if (p.round) {
                drawCircle(p.color.copy(alpha = op), radius = side / 2f, center = c)
            } else {
                rotate(rot, c) {
                    drawRect(p.color.copy(alpha = op), Offset(c.x - side / 2f, c.y - side / 2f), Size(side, side))
                }
            }
        }
    }
}
