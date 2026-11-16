package com.amix.tutor.ui

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Микрофон только меняет VoiceLevel (визуальный эффект). Звук нигде не сохраняется и не отправляется.
 * Формулы из index.html (reference/voice-preview.js):
 *   level = min(1, rms * 15.5); рост: prev*.38 + level*.62; спад: prev*.84 + level*.16 (раз в кадр, ~16 мс).
 */
class VoiceMeter {
    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val sampleRate = 44100
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // платформенное эхо- и шумоподавление
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, 4096)
            )
        } catch (_: Throwable) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (_: Throwable) {
            rec.release()
            return false
        }
        running = true
        VoiceLevel.value = 0f
        Thread {
            val buf = ShortArray(512)
            var lastUpdate = 0L
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = System.nanoTime()
                if (now - lastUpdate < 16_000_000L) continue
                lastUpdate = now
                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i] / 32768.0
                    sum += v * v
                }
                val rms = sqrt(sum / n)
                val level = min(1.0, rms * 15.5).toFloat()
                val prev = VoiceLevel.value
                VoiceLevel.value = if (level > prev) prev * .38f + level * .62f else prev * .84f + level * .16f
            }
            try {
                rec.stop()
            } catch (_: Throwable) {
            }
            rec.release()
            VoiceLevel.value = 0f
        }.also {
            it.isDaemon = true
            it.start()
        }
        return true
    }

    fun stop() {
        running = false
    }
}
