package com.ben.karmaasistan

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable
import java.io.File

data class AsrResult(
    val text: String,
    val language: String,     // language used for decoding (tr / en)
    val pTr: Float,           // Whisper language-ID probability for Turkish
    val pEn: Float,           // ... and for English
    val meanLogProb: Double,  // mean token log-probability (ASR confidence signal, FR-08)
    val minTokenP: Double,    // least confident token
    val nTokens: Int,
    val latencyMs: Long,      // language ID + transcription
    val audioSec: Double,
    val audioCtx: Int,        // encoder window used (frames; 0 = full 30 s)
)

/**
 * On-device speech recognition with whisper.cpp, using the best setting from the
 * pilot benchmark (`lid_bias_prompt`): TR/EN-only language choice that defaults to
 * Turkish (FR-04) + a vocabulary-only prompt.
 */
class WhisperEngine private constructor(private var ctx: Long, val modelName: String) : Closeable {

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Encoder window in frames (50 per second). Smaller = faster; 0 = Whisper's full 30 s. */
    var audioCtx: Int = DEFAULT_AUDIO_CTX
        private set

    /** Label used in batch results, e.g. "base-q5_1_ctx512". */
    val runLabel: String get() = "${modelName}_ctx$audioCtx"

    /** Changes the encoder window; the warm-up run makes language detection use it too. Call off the main thread. */
    @Synchronized
    fun setAudioCtx(value: Int) {
        check(ctx != 0L) { "model is closed" }
        audioCtx = value
        WhisperNative.warmUp(ctx, threads, value)
    }

    @Synchronized
    fun transcribe(audio: FloatArray): AsrResult {
        check(ctx != 0L) { "model is closed" }
        val t0 = SystemClock.elapsedRealtime()
        val probs = WhisperNative.detectLanguage(ctx, audio, threads)
        val pTr = probs[0]
        val pEn = probs[1]
        val language = if (pEn >= EN_MIN_PROB && pEn > EN_MARGIN * pTr) "en" else "tr"
        // commands longer than the window fall back to the full 30 s window
        val fits = audioCtx == 0 || audio.size / 16_000.0 * 50 + 32 <= audioCtx
        val usedCtx = if (fits) audioCtx else 0
        val json = JSONObject(WhisperNative.transcribe(ctx, audio, language, VOCAB_PROMPT, threads, usedCtx))
        val latency = SystemClock.elapsedRealtime() - t0
        if (!json.optBoolean("ok")) error(json.optString("error", "transcription failed"))
        return AsrResult(
            text = json.optString("text").trim(),
            language = language,
            pTr = pTr,
            pEn = pEn,
            meanLogProb = json.optDouble("mean_logprob"),
            minTokenP = json.optDouble("min_p"),
            nTokens = json.optInt("n_tokens"),
            latencyMs = latency,
            audioSec = audio.size / 16_000.0,
            audioCtx = usedCtx,
        )
    }

    @Synchronized
    override fun close() {
        if (ctx != 0L) {
            WhisperNative.freeModel(ctx)
            ctx = 0L
        }
    }

    companion object {
        // Keep in sync with ml/asr_pilot/benchmark_whisper.py
        const val VOCAB_PROMPT = "meeting, task, deadline, reminder, snooze, toplantı, görev, hatırlatma"
        private const val EN_MIN_PROB = 0.05f
        private const val EN_MARGIN = 2.0f

        /** Encoder windows offered in the app: 10 s, 15 s, full 30 s. */
        val AUDIO_CTX_OPTIONS = listOf(512, 768, 0)
        const val DEFAULT_AUDIO_CTX = 512

        fun windowLabel(audioCtx: Int): String = if (audioCtx == 0) "30 s" else "${audioCtx / 50} s"

        /** Short summary of the CPU features that are switched on, e.g. "NEON DOTPROD FP16". */
        fun cpuFeatures(): String =
            Regex("""([A-Z0-9_]+) = 1""").findAll(WhisperNative.systemInfo())
                .map { it.groupValues[1] }.joinToString(" ")

        /** Models are copied here with adb (not bundled in the APK): files/models/ggml-*.bin */
        fun modelsDir(context: Context): File =
            File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

        fun availableModels(context: Context): List<File> =
            modelsDir(context)
                .listFiles { f -> f.name.startsWith("ggml-") && f.name.endsWith(".bin") }
                ?.sortedBy { it.length() }
                ?: emptyList()

        fun displayName(file: File): String = file.nameWithoutExtension.removePrefix("ggml-")

        fun load(file: File): WhisperEngine {
            val handle = WhisperNative.loadModel(file.absolutePath)
            require(handle != 0L) { "Model yüklenemedi: ${file.name}" }
            return WhisperEngine(handle, displayName(file)).apply { setAudioCtx(audioCtx) }
        }
    }
}
