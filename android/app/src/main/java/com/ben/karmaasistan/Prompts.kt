package com.ben.karmaasistan

import android.content.Context

/** One command the speaker reads out. Mirrors data/pilot/prompts.csv. */
data class Prompt(
    val id: String,
    val intent: String,
    val langType: String,      // tr | en | mixed
    val enTerms: List<String>, // English words inside a mixed command
    val text: String,
)

object PromptRepository {
    /** Reads assets/prompts.csv (columns: id,intent,lang_type,en_terms,text). */
    fun load(context: Context): List<Prompt> =
        context.assets.open("prompts.csv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1) // header
                .filter { it.isNotBlank() }
                .map { line ->
                    // text is the last column, so it may contain commas
                    val p = line.split(",", limit = 5)
                    Prompt(
                        id = p[0].trim(),
                        intent = p[1].trim(),
                        langType = p[2].trim(),
                        enTerms = p[3].split("|").map { it.trim() }.filter { it.isNotEmpty() },
                        text = p[4].trim(),
                    )
                }
                .toList()
        }
}
