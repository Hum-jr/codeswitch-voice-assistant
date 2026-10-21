package com.ben.karmaasistan

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
        engine: WhisperEngine,
        recordingsDir: File,
        onProgress: (done: Int, total: Int) -> Unit,
        isActive: () -> Boolean,
    ): File {
        val items = loadManifest(recordingsDir)
        require(items.isNotEmpty()) { "Kayıt bulunamadı" }
        val out = File(recordingsDir, "asr_${engine.runLabel}_${System.currentTimeMillis()}.csv")
        out.bufferedWriter().use { w ->
            w.write("model,speaker,condition,prompt_id,language,p_tr,p_en,mean_logprob,min_p,n_tokens,latency_ms,audio_s,audio_ctx,hypothesis\n")
            for ((i, item) in items.withIndex()) {
                if (!isActive()) break
                val r = engine.transcribe(WavIO.readMono16(item.file))
                w.write(listOf(
                    engine.runLabel, item.speaker, item.condition, item.promptId, r.language,
                    fmt(r.pTr.toDouble()), fmt(r.pEn.toDouble()), fmt(r.meanLogProb), fmt(r.minTokenP),
                    r.nTokens.toString(), r.latencyMs.toString(), fmt(r.audioSec), r.audioCtx.toString(), quote(r.text),
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
