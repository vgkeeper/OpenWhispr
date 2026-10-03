package com.edib.openwhispr

import android.content.SharedPreferences
import java.io.File

/** Persistent custom vocabulary and sherpa-onnx hotword file generation. */
object Dictionary {
    const val PREF_KEY = "dictionary_words"

    data class Entry(val canonical: String, val aliases: List<String>) {
        fun asLine(): String = if (aliases.isEmpty()) canonical
        else "$canonical | ${aliases.joinToString(", ")}"
    }

    fun parse(raw: String?): List<String> = parseEntries(raw).map(Entry::asLine)

    fun parseEntries(raw: String?): List<Entry> {
        val entries = linkedMapOf<String, Entry>()
        raw.orEmpty().lineSequence().forEach { line ->
            val parts = line.split('|', limit = 2)
            val canonical = parts.first().trim()
            if (canonical.isEmpty()) return@forEach
            val aliases = parts.getOrNull(1).orEmpty()
                .split(',')
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.equals(canonical, ignoreCase = true) }
                .distinctBy(String::lowercase)
            val key = canonical.lowercase()
            val existing = entries[key]
            entries[key] = if (existing == null) Entry(canonical, aliases) else Entry(
                existing.canonical,
                (existing.aliases + aliases).distinctBy(String::lowercase),
            )
        }
        return entries.values.toList()
    }

    fun canonicalTerms(words: List<String>): List<String> =
        parseEntries(words.joinToString("\n")).map(Entry::canonical)

    fun recognitionTerms(words: List<String>): List<String> = parseEntries(words.joinToString("\n"))
        .flatMap { listOf(it.canonical) + it.aliases }
        .distinctBy(String::lowercase)

    /** Adds selected text as a canonical entry without changing existing aliases. */
    fun withSelectedText(words: List<String>, selectedText: String?): List<String> {
        val selected = selectedText.orEmpty().trim()
        return if (selected.isBlank()) parse(words.joinToString("\n"))
        else parse((words + selected).joinToString("\n"))
    }

    fun save(preferences: SharedPreferences, words: List<String>) {
        val parsed = parse(words.joinToString("\n"))
        preferences.edit().putString(PREF_KEY, parsed.joinToString("\n")).apply()
        DictionaryCorrector.prepare(parsed)
    }

    fun load(preferences: SharedPreferences): List<String> {
        val words = parse(preferences.getString(PREF_KEY, null))
        DictionaryCorrector.prepare(words)
        return words
    }

    /** sherpa-onnx hotword format is one canonical term or alias per line. */
    fun writeHotwords(file: File, words: List<String>): String {
        file.parentFile?.mkdirs()
        file.writeText(recognitionTerms(words).joinToString("\n"))
        return file.absolutePath
    }
}
