package com.amix.tutor

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обёртка над MediaPipe LLM Inference.
 * Полностью работает офлайн после загрузки .task-модели.
 */
class LlmEngine private constructor(private val inference: LlmInference) {

    private val executor = Executors.newSingleThreadExecutor()

    fun generate(prompt: String, onToken: (String) -> Unit, onDone: (String) -> Unit, onError: (Throwable) -> Unit) {
        val cancelled = AtomicBoolean(false)
        executor.execute {
            try {
                val sb = StringBuilder()
                inference.generateResponseAsync(prompt) { partial, done ->
                    if (cancelled.get()) return@generateResponseAsync
                    val chunk = partial.removePrefix(sb.toString())
                    if (chunk.isNotEmpty()) {
                        sb.append(chunk)
                        onToken(chunk)
                    }
                    if (done) onDone(sb.toString())
                }
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    fun close() {
        try { inference.close() } catch (_: Throwable) {}
        try { executor.shutdownNow() } catch (_: Throwable) {}
    }

    companion object {
        fun create(ctx: Context): LlmEngine {
            val model = ModelDownloader.modelFile(ctx)
            require(model.exists()) { "model not downloaded" }

            // Параметры случайности (topK = 40, temperature = 0.8) в этой версии MediaPipe
            // уже заданы по умолчанию, поэтому отдельно их указывать не нужно.
            val options = LlmInferenceOptions.builder()
                .setModelPath(model.absolutePath)
                .setMaxTokens(1024)
                .build()

            val inference = LlmInference.createFromOptions(ctx, options)
            return LlmEngine(inference)
        }
    }
}