package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DictionaryTest {
    @Test fun parsesLegacyLinesAsCanonicalEntriesAndKeepsCanonicalSpelling() {
        assertEquals(listOf("OpenAI", "sherpa-onnx;"), Dictionary.parse(" OpenAI\nopenai\nsherpa-onnx; "))
        assertEquals(listOf(Dictionary.Entry("OpenAI", emptyList())), Dictionary.parseEntries("OpenAI\nopenai"))
    }

    @Test fun parsesCanonicalTermsAndDeduplicatesAliasesWithoutChangingCase() {
        assertEquals(
            listOf(Dictionary.Entry("OAPO", listOf("o a p o", "eau à peau"))),
            Dictionary.parseEntries("OAPO | o a p o, eau à peau, OAPO, o a p o"),
        )
        assertEquals(listOf("OAPO | o a p o, eau à peau"), Dictionary.parse("OAPO | o a p o, eau à peau"))
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

    @Test fun selectedTextAddsCanonicalLineAndPreservesExistingAliases() {
        assertEquals(
            listOf("OpenWhispr | open whisper", "NASA"),
            Dictionary.withSelectedText(listOf("OpenWhispr | open whisper"), "NASA"),
        )
    }

    @Test fun sherpaHotwordsContainCanonicalAndAliasesAsSeparateTerms() {
        val file = File.createTempFile("hotwords", ".txt")
        try {
            Dictionary.writeHotwords(file, listOf("OAPO | o a p o, eau à peau", "OpenWhispr"))
            assertEquals("OAPO\no a p o\neau à peau\nOpenWhispr", file.readText())
        } finally {
            file.delete()
        }
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