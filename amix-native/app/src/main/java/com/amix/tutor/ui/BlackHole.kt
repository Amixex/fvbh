package com.amix.tutor.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.amix.tutor.CrashReporter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Уровень голоса 0..1, аналог window.amixVoiceLevel из index.html. Пишет VoiceMeter, читает рисование. */
object VoiceLevel {
    @Volatile
    var value: Float = 0f
}

private const val TWO_PI = (Math.PI * 2).toFloat()
private const val PI_F = Math.PI.toFloat()
private const val DEG = 57.29578f

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val tw: Float)

private class Plasma(val a: Float, val orbit: Float, val speed: Float, val size: Float, val phase: Float)

private class RingDef(
    val rx: Float, val ry: Float,
    val cr: Int, val cg: Int, val cb: Int,
    val w: Float, val phase: Float
)

private fun rgba(r: Int, g: Int, b: Int, a: Float): Int =
    AColor.argb((a.coerceIn(0f, 1f) * 255f).roundToInt(), r, g, b)

/** Радиальный градиент как createRadialGradient(x, y, r0, x, y, r1) в canvas 2D. */
private fun radial(x: Float, y: Float, r0: Float, r1: Float, stops: List<Pair<Float, Int>>): RadialGradient {
    val radius = max(r1, 0.001f)
    val positions = ArrayList<Float>()
    val colors = ArrayList<Int>()
    if (r0 > 0f) {
        positions.add(0f)
        colors.add(stops.first().second)
    }
    for ((p, c) in stops) {
        val pos = ((r0 + p * (r1 - r0)) / radius).coerceIn(0f, 1f)
        positions.add(if (positions.isEmpty()) pos else max(pos, positions.last()))
        colors.add(c)
    }
    return RadialGradient(x, y, radius, colors.toIntArray(), positions.toFloatArray(), Shader.TileMode.CLAMP)
}

/**
 * Порт initAmixBlackHole() из index.html (reference/blackhole.js) формула в формулу.
 * Размеры считаются в dp, как CSS-пиксели в вебе.
 */
private class BlackHoleSim {
    var w = 0f
    var h = 0f
    var t = 0f
    var voiceExpansion = 0f
    var voiceDelay = 0f
    var lastDt = 0f
    var broken = false
    var stars: List<Star> = emptyList()
    var plasma: List<Plasma> = emptyList()

    private val rnd = java.util.Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun rand(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    fun resize(nw: Float, nh: Float) {
        if (nw == w && nh == h) return
        w = max(1f, nw)
        h = max(1f, nh)
        val count = min(200, (w * h / 6000f).roundToInt())
        stars = List(count) {
            Star(rnd.nextFloat() * w, rnd.nextFloat() * h, rand(.25f, 1.15f), rand(0f, TWO_PI), rand(.25f, 1.2f))
        }
        plasma = List(124) {
            Plasma(
                rand(0f, TWO_PI), rand(1.08f, 2.08f),
                rand(.22f, .64f) * (if (rnd.nextFloat() < .5f) -1f else 1f),
                rand(.45f, 1.6f), rand(0f, 7f)
            )
        }
    }

    fun step(dt: Float, wantsVoice: Boolean) {
        lastDt = dt
        t += dt
        if (wantsVoice) {
            if (voiceDelay < 700f) voiceDelay += dt * 1000f
        } else {
            voiceDelay = 0f
        }
        val target = if (wantsVoice && voiceDelay >= 700f) 1f else 0f
        val k = 1f - (1f - .035f).pow(dt * 60f)
        voiceExpansion += (target - voiceExpansion) * k
        if (voiceExpansion < .005f) voiceExpansion = 0f
        if (voiceExpansion > .995f) voiceExpansion = 1f
        HoleState.expansion = voiceExpansion
    }

    /** point() из эталона: дополнительно подтягивает расширение к цели и возвращает центр дыры. */
    private fun point(wantsVoice: Boolean): Pair<Float, Float> {
        val target = if (wantsVoice) 1f else 0f
        val k = 1f - (1f - .045f).pow(lastDt * 60f)
        voiceExpansion += (target - voiceExpansion) * k
        if (abs(target - voiceExpansion) < .001f) voiceExpansion = target
        val nx = if (w < 700f) w * .74f else w * .715f
        val ny = if (w < 700f) h * .39f else h * .50f
        return Pair(nx + (w * .5f - nx) * voiceExpansion, ny + (h * .5f - ny) * voiceExpansion)
    }

    private fun vignette(cv: AndroidCanvas, r0: Float, r1: Float, alpha: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.shader = radial(
            w * .5f, h * .48f, min(w, h) * r0, max(w, h) * r1,
            listOf(0f to rgba(0, 0, 0, 0f), 1f to rgba(0, 0, 0, alpha))
        )
        cv.drawRect(0f, 0f, w, h, paint)
    }

    private fun softRing(cv: AndroidCanvas, d: RingDef, a0: Float, a1: Float, mult: Float) {
        val n = max(8, (72f * (a1 - a0) / TWO_PI).roundToInt())
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = d.w
        rect.set(-d.rx, -d.ry, d.rx, d.ry)
        for (k in 0 until n) {
            val st = a0 + (a1 - a0) * k / n
            val en = a0 + (a1 - a0) * min(1f, (k + 1.5f) / n)
            val m = (st + en) / 2f
            val al = (.18f + .82f * (.5f + .5f * cos(m - d.phase)).pow(2.2f)) * .62f * mult
            paint.color = rgba(d.cr, d.cg, d.cb, al)
            cv.drawArc(rect, st * DEG, (en - st) * DEG, false, paint)
        }
    }

    private fun taperArc(
        cv: AndroidCanvas, x: Float, y: Float, rx: Float, ry: Float, a0: Float, a1: Float,
        cr: Int, cg: Int, cb: Int, alpha: Float, width: Float
    ) {
        val n = 48
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = width
        rect.set(x - rx, y - ry, x + rx, y + ry)
        for (k in 0 until n) {
            val st = k.toFloat() / n
            val en = min(1f, (k + 1.5f) / n)
            val m = (st + en) / 2f
            val taper = sin(m * PI_F).pow(.75f)
            paint.color = rgba(cr, cg, cb, alpha * taper)
            cv.drawArc(rect, (a0 + (a1 - a0) * st) * DEG, ((a1 - a0) * (en - st)) * DEG, false, paint)
        }
    }

    /** Окружность со свечением. shadowBlur из canvas заменён тремя более широкими прозрачными проходами. */
    private fun glowStroke(
        cv: AndroidCanvas, x: Float, y: Float, rx: Float, ry: Float,
        cr: Int, cg: Int, cb: Int, alpha: Float, width: Float, blur: Float
    ) {
        rect.set(x - rx, y - ry, x + rx, y + ry)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        if (blur > 0f) {
            paint.strokeWidth = width + blur * 1.6f
            paint.color = rgba(cr, cg, cb, alpha * .10f)
            cv.drawOval(rect, paint)
            paint.strokeWidth = width + blur * 0.96f
            paint.color = rgba(cr, cg, cb, alpha * .16f)
            cv.drawOval(rect, paint)
            paint.strokeWidth = width + blur * 0.48f
            paint.color = rgba(cr, cg, cb, alpha * .26f)
            cv.drawOval(rect, paint)
        }
        paint.strokeWidth = width
        paint.color = rgba(cr, cg, cb, alpha)
        cv.drawOval(rect, paint)
    }

    private fun drawPlasma(cv: AndroidCanvas, front: Boolean, cx: Float, cy: Float, r: Float, a: Float, vl: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        for (p in plasma) {
            val aa = p.a + t * p.speed * (1f + voiceExpansion * 1.6f + vl * 2.8f)
            if ((sin(aa) > 0f) != front) continue
            val rr = r * p.orbit
            val px = cx + cos(aa) * rr * 1.54f
            val py = cy + sin(aa) * rr * .34f
            val tailA = aa - p.speed * .10f
            val tx = cx + cos(tailA) * rr * 1.54f
            val ty = cy + sin(tailA) * rr * .34f
            val hot = .45f + .55f * (.5f + .5f * sin(aa * 2f + p.phase))
            val alpha = (.2f + .62f * hot) * a
            // свечение вместо shadowBlur
            paint.strokeWidth = p.size + (4f + hot * 5f) * .7f
            paint.color = rgba(255, 139, 69, alpha * .22f)
            cv.drawLine(tx, ty, px, py, paint)
            paint.strokeWidth = p.size
            paint.color = when {
                hot > .77f -> rgba(0xFF, 0xF0, 0xCC, alpha)
                hot > .48f -> rgba(0xFF, 0xB4, 0x6F, alpha)
                else -> rgba(0xDD, 0x70, 0x46, alpha)
            }
            cv.drawLine(tx, ty, px, py, paint)
        }
    }

    fun draw(cv: AndroidCanvas, wantsVoice: Boolean, vl: Float) {
        // звёзды
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        for (s in stars) {
            val alpha = .10f + .24f * (.5f + .5f * sin(t * s.tw + s.phase))
            paint.color = if (s.phase > 3f) rgba(0xD8, 0xE6, 0xFF, alpha) else rgba(0xF6, 0xF0, 0xE9, alpha)
            cv.drawCircle(s.x, s.y, s.r, paint)
        }

        if (voiceExpansion < .015f || broken) {
            vignette(cv, .10f, .80f, .55f)
            return
        }

        val c = point(wantsVoice)
        val a = voiceExpansion
        val wobble = a * (.25f + vl * 5.8f)
        val cx = c.first + sin(t * 63f) * wobble
        val cy = c.second + cos(t * 51f) * wobble * .72f
        val normalR = max(49f, min(119f, min(w, h) * .13f))
        val giantR = min(w, h) * .465f
        val voiceBreath = 1f + a * (.008f * sin(t * 2.4f)) + vl * (.028f + .018f * sin(t * 70f))
        val rRaw = (normalR + (giantR - normalR) * a) * voiceBreath
        val fitR = (w * .5f) / 2.2f
        val r = rRaw + (min(rRaw, fitR) - rRaw) * a
        val diskRX = r * 2.55f
        val diskRY = r * .79f
        val spin = t * (.16f + a * .58f + vl * 1.55f)

        // ореол
        cv.save()
        cv.translate(cx, cy)
        cv.rotate(-.045f * DEG)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.shader = radial(
            0f, 0f, r * .35f, r * 3.05f,
            listOf(
                0f to rgba(0, 0, 0, 0f),
                .25f to rgba(255, 158, 86, .045f * a),
                .43f to rgba(235, 105, 43, .10f * a),
                .61f to rgba(112, 95, 180, .055f * a),
                1f to rgba(0, 0, 0, 0f)
            )
        )
        rect.set(-diskRX * 1.45f, -diskRY * 2.25f, diskRX * 1.45f, diskRY * 2.25f)
        cv.drawOval(rect, paint)
        cv.restore()

        // диск и кольца (сплющенное пространство)
        val tilt = (-.055f + sin(t * .13f) * .012f) * DEG
        cv.save()
        cv.translate(cx, cy)
        cv.rotate(tilt)
        cv.scale(1f, .31f)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.shader = radial(
            0f, 0f, r * .48f, r * 2.7f,
            listOf(
                0f to rgba(0, 0, 0, 0f),
                .24f to rgba(29, 18, 19, .12f * a),
                .40f to rgba(123, 47, 32, .43f * a),
                .54f to rgba(244, 119, 54, .88f * a),
                .60f to rgba(255, 225, 169, .95f * a),
                .66f to rgba(255, 137, 61, .93f * a),
                .77f to rgba(184, 60, 37, .68f * a),
                .91f to rgba(73, 38, 49, .18f * a),
                1f to rgba(0, 0, 0, 0f)
            )
        )
        cv.drawCircle(0f, 0f, r * 2.7f, paint)
        val rings = ArrayList<RingDef>(5)
        for (i in 0 until 5) {
            val rr = r * (1.05f + i * .28f)
            val phase = spin * (if (i % 2 == 1) 1f else -1f) + i * .9f
            val def = if (i % 2 == 1) {
                RingDef(rr * 1.45f, rr * .88f, 255, 201, 141, max(.9f, 2.3f - i * .28f), phase)
            } else {
                RingDef(rr * 1.45f, rr * .88f, 255, 106, 47, max(.9f, 2.3f - i * .28f), phase)
            }
            rings.add(def)
            softRing(cv, def, 0f, TWO_PI, a)
        }
        cv.restore()

        drawPlasma(cv, false, cx, cy, r, a, vl)

        // тень и ободок
        val holeR = r * .68f
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.shader = radial(
            cx, cy, holeR * .15f, holeR * 1.27f,
            listOf(
                0f to rgba(0, 0, 0, a),
                .72f to rgba(0, 0, 0, a),
                .87f to rgba(3, 3, 3, a),
                .96f to rgba(255, 213, 168, .2f * a),
                1f to rgba(255, 148, 83, 0f)
            )
        )
        cv.drawCircle(cx, cy, holeR * 1.27f, paint)
        glowStroke(cv, cx, cy, holeR * 1.02f, holeR * .99f, 255, 239, 215, .78f * a, max(1.1f, r * .017f), 12f)
        glowStroke(cv, cx, cy, holeR * 1.10f, holeR * 1.055f, 255, 136, 72, .65f * a, max(.8f, r * .012f), 18f)

        cv.save()
        cv.translate(cx, cy)
        cv.rotate(-.035f * DEG)
        val lw = max(1.1f, r * .018f)
        taperArc(cv, 0f, -holeR * .03f, holeR * 1.23f, holeR * 1.15f, PI_F, TWO_PI, 255, 160, 95, .2f * a, lw * 3.6f)
        taperArc(cv, 0f, -holeR * .03f, holeR * 1.23f, holeR * 1.15f, PI_F, TWO_PI, 255, 236, 208, .8f * a, lw)
        taperArc(cv, 0f, holeR * .05f, holeR * 1.20f, holeR * 1.12f, 0f, PI_F, 255, 110, 55, .18f * a, lw * 3.2f)
        taperArc(cv, 0f, holeR * .05f, holeR * 1.20f, holeR * 1.12f, 0f, PI_F, 255, 128, 67, .7f * a, lw * .8f)
        cv.restore()

        // ядро
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.shader = radial(
            cx, cy, holeR * .15f, holeR * 1.01f,
            listOf(
                0f to rgba(0, 0, 0, a),
                .90f to rgba(0, 0, 0, a),
                .985f to rgba(3, 3, 3, a),
                1f to rgba(0, 0, 0, 0f)
            )
        )
        cv.drawCircle(cx, cy, holeR * 1.01f, paint)

        // передняя половина колец
        cv.save()
        cv.translate(cx, cy)
        cv.rotate(tilt)
        cv.scale(1f, .31f)
        for (d in rings) softRing(cv, d, 0f, PI_F, .85f * a)
        cv.restore()

        drawPlasma(cv, true, cx, cy, r, a, vl)
        vignette(cv, .12f, .78f, .44f + .14f * (1f - a))
    }
}

/**
 * Фон приложения. На обычных экранах звёзды и виньетка, на экране «Голосовой ИИ»
 * (voiceMode = true) через 0,7 с появляется «чёрная дыра», как в index.html.
 */
@Composable
fun BlackHoleBackground(voiceMode: Boolean, modifier: Modifier = Modifier) {
    val sim = remember { BlackHoleSim() }
    val ctx = LocalContext.current
    val reduce = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val disabled = remember { CrashReporter.blackHoleDisabled(ctx) }
    val wantsVoice by rememberUpdatedState(voiceMode && !disabled)
    var tick by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(reduce, lifecycleOwner) {
        if (reduce) {
            tick += 1 // один статичный кадр, как при prefers-reduced-motion
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            var guardOn = false
            var guardCleared = false
            var guardSince = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, .04f)
                    last = now
                    sim.step(dt, wantsVoice)
                    // Если приложение закроется, пока дыра на экране, при следующем запуске анимация отключится
                    if (wantsVoice && sim.voiceExpansion >= .015f) {
                        if (!guardOn) {
                            CrashReporter.guardStart(ctx)
                            guardOn = true
                            guardCleared = false
                            guardSince = now
                        } else if (!guardCleared && now - guardSince > 4_000_000_000L) {
                            CrashReporter.guardOk(ctx)
                            guardCleared = true
                        }
                    } else if (guardOn && !wantsVoice) {
                        CrashReporter.guardOk(ctx)
                        guardOn = false
                    }
                    tick = now
                }
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val frame = tick // чтение состояния заставляет перерисовывать кадр
        val d = density
        sim.resize(size.width / d, size.height / d)
        drawIntoCanvas { c ->
            val cv = c.nativeCanvas
            val saved = cv.save()
            cv.scale(d, d)
            try {
                sim.draw(cv, wantsVoice, VoiceLevel.value)
            } catch (e: Throwable) {
                sim.broken = true
                CrashReporter.reportNonFatal(ctx, e, "BlackHole.draw")
            } finally {
                cv.restoreToCount(saved)
            }
        }
        if (frame < 0L) return@Canvas
    }
}
