package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DictionaryTest {
    @Test fun parsesAndDeduplicatesWordsIgnoringCase() {
        assertEquals(listOf("OpenAI", "sherpa-onnx"), Dictionary.parse(" OpenAI, openai\nsherpa-onnx; "))
    }

    @Test fun writesSherpaHotwordFile() {
        val file = File.createTempFile("hotwords", ".txt")
        try {
            val path = Dictionary.writeHotwords(file, listOf("OpenAI", "Kotlin"))
            assertEquals(file.absolutePath, path)
            assertEquals("OpenAI\nKotlin", file.readText())
        } finally {
            file.delete()
        }
    }
}