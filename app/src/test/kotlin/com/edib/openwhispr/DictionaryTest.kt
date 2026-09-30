package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DictionaryTest {
    @Test fun parsesNewlineSeparatedWordsAndDeduplicatesIgnoringCase() {
        assertEquals(listOf("OpenAI", "sherpa-onnx;"), Dictionary.parse(" OpenAI\nopenai\nsherpa-onnx; "))
    }

    @Test fun selectedTextIsAddedAsOnePunctuatedPhrase() {
        assertEquals(
            listOf("OpenWispr", "New York, NY"),
            Dictionary.withSelectedText(listOf("OpenWispr"), "  New York, NY  ")
        )
    }

    @Test fun selectedTextDuplicateIsNotAddedAgain() {
        assertEquals(listOf("OpenWispr"), Dictionary.withSelectedText(listOf("OpenWispr"), "openwispr"))
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