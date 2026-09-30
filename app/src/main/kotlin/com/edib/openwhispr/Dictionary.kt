package com.edib.openwhispr

import android.content.SharedPreferences
import java.io.File

/** Persistent custom vocabulary and sherpa-onnx hotword file generation. */
object Dictionary {
    const val PREF_KEY = "dictionary_words"

    fun parse(raw: String?): List<String> = raw.orEmpty()
        .split('\n', ',', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

    fun save(preferences: SharedPreferences, words: List<String>) {
        preferences.edit().putString(PREF_KEY, parse(words.joinToString("\n")).joinToString("\n")).apply()
    }

    fun load(preferences: SharedPreferences): List<String> = parse(preferences.getString(PREF_KEY, null))

    /** sherpa-onnx hotword format is one phrase (optionally with score) per line. */
    fun writeHotwords(file: File, words: List<String>): String {
        file.parentFile?.mkdirs()
        file.writeText(parse(words.joinToString("\n")).joinToString("\n"))
        return file.absolutePath
    }
}
