package com.ben.karmaasistan

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.util.Locale

/**
 * Runs on-device ASR over every recording listed in recordings/manifest.csv and writes
 * recordings/asr_<model>_<time>.csv. Pull it to the PC with the recordings and score it
 * with ml/asr_pilot/score_device.py (WER per speaker + on-device latency for H4).
 */
object BatchAsr {

    data class Item(val speaker: String, val condition: String, val promptId: String, val file: File)

    fun loadManifest(recordingsDir: File): List<Item> {
        val manifest = File(recordingsDir, "manifest.csv")
        if (!manifest.exists()) return emptyList()
        val latest = LinkedHashMap<String, Item>()  // re-recordings: keep the last take
        manifest.readLines().drop(1).filter { it.isNotBlank() }.forEach { line ->
            val p = line.split(",")
            if (p.size >= 4) latest["${p[0]}|${p[1]}|${p[2]}"] = Item(p[0], p[1], p[2], File(recordingsDir, p[3]))
        }
        return latest.values.filter { it.file.exists() }
    }

    fun run(
        context: Context,
        engine: WhisperEngine,
        recordingsDir: File,
        onProgress: (done: Int, total: Int) -> Unit,
        isActive: () -> Boolean,
    ): File {
        val items = loadManifest(recordingsDir)
        require(items.isNotEmpty()) { "Kayıt bulunamadı" }
        val out = File(recordingsDir, "asr_${engine.runLabel}_${System.currentTimeMillis()}.csv")
        out.bufferedWriter().use { w ->
            // power_save / thermal are logged per clip: throttled phones give invalid latency numbers
            w.write("model,speaker,condition,prompt_id,language,p_tr,p_en,mean_logprob,min_p,n_tokens,latency_ms,audio_s,audio_ctx,power_save,thermal,hypothesis\n")
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            for ((i, item) in items.withIndex()) {
                if (!isActive()) break
                val r = engine.transcribe(WavIO.readMono16(item.file))
                w.write(listOf(
                    engine.runLabel, item.speaker, item.condition, item.promptId, r.language,
                    fmt(r.pTr.toDouble()), fmt(r.pEn.toDouble()), fmt(r.meanLogProb), fmt(r.minTokenP),
                    r.nTokens.toString(), r.latencyMs.toString(), fmt(r.audioSec), r.audioCtx.toString(),
                    if (pm.isPowerSaveMode) "1" else "0",
                    // 0 = none … 6 = shutdown (THERMAL_STATUS_*); -1 = not available before Android 10
                    (if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1).toString(),
                    quote(r.text),
                ).joinToString(","))
                w.write("\n")
                w.flush()
                onProgress(i + 1, items.size)
            }
        }
        return out
    }

    private fun fmt(x: Double) = String.format(Locale.US, "%.4f", x)
    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
