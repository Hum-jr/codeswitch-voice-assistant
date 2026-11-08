package com.ben.karmaasistan

import android.content.Context

/** One command the speaker reads out. Mirrors data/commands.csv (the İP1 command list). */
data class Prompt(
    val id: String,            // C001 … C090
    val intent: String,
    val langType: String,      // tr | en | mixed
    val ambiguity: String,     // none | intent | slot | reference
    val pilotId: String,       // P01 … P30 if this command was in the pilot set, else ""
    val enTerms: List<String>, // English words inside a mixed command
    val text: String,
)

object PromptRepository {
    /**
     * Reads assets/commands.csv
     * (columns: id,intent,lang_type,ambiguity,alt_intent,pilot_id,en_terms,text).
     */
    fun load(context: Context): List<Prompt> {
        val rows = context.assets.open("commands.csv").bufferedReader(Charsets.UTF_8).use { parseCsv(it.readText()) }
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().removePrefix("﻿") }
        fun List<String>.col(name: String) = getOrElse(header.indexOf(name)) { "" }.trim()
        return rows.drop(1).filter { r -> r.any { it.isNotBlank() } }.map { r ->
            Prompt(
                id = r.col("id"),
                intent = r.col("intent"),
                langType = r.col("lang_type"),
                ambiguity = r.col("ambiguity").ifEmpty { "none" },
                pilotId = r.col("pilot_id"),
                enTerms = r.col("en_terms").split("|").map { it.trim() }.filter { it.isNotEmpty() },
                text = r.col("text"),
            )
        }
    }

    /** Minimal RFC 4180 CSV parser (quoted fields may contain commas, quotes and newlines). */
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row.add(field.toString()); field.clear() }
                '\r' -> {}
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
