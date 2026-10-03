package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DictionaryCorrectorTest {
    @Test
    fun fuzzyMatchingCorrectsHighConfidenceWholePhrases() {
        val dictionary = listOf("Infisical")

        assertEquals("Infisical", DictionaryCorrector.correct("in physical", dictionary))
        assertEquals("Infisical", DictionaryCorrector.correct("in fiscal", dictionary))
    }

    @Test
    fun fuzzyMatchingAbstainsWhenDistinctCanonicalTermsTie() {
        val transcript = "in physical"

        assertEquals(
            transcript,
            DictionaryCorrector.correct(transcript, listOf("Infisical", "Infisikal")),
        )
        assertEquals(
            transcript,
            DictionaryCorrector.correct(transcript, listOf("Infisikal", "Infisical")),
        )
    }

    @Test
    fun exactCanonicalIsNoOpAndCaseVariantsRestoreCanonicalSpelling() {
        val dictionary = listOf("Infisical")

        assertEquals("Infisical", DictionaryCorrector.correct("Infisical", dictionary))
        assertEquals("Infisical", DictionaryCorrector.correct("INFISICAL", dictionary))
    }

    @Test
    fun multipleCanonicalTermsCanMatchInOneTranscript() {
        val dictionary = listOf("OAPO", "OpenWhispr", "Infisical")

        assertEquals(
            "OAPO and OpenWhispr use Infisical",
            DictionaryCorrector.correct("o a p o and open whisper use in physical", dictionary),
        )
    }

    @Test
    fun explicitAliasesWinOverFuzzyCandidatesDeterministically() {
        val dictionary = listOf("Infisical", "PhysicsCorp | in physical", "OtherCorp | in physical")

        repeat(3) {
            assertEquals("PhysicsCorp", DictionaryCorrector.correct("in physical", dictionary))
        }
    }

    @Test
    fun unrelatedAndCommonLanguagePhrasesRemainUnchanged() {
        val dictionary = listOf("Infisical")
        val phrases = listOf(
            "physical",
            "in physical therapy",
            "physical exam",
            "in fiscal year",
            "in fiscal policy",
            "The physical therapy office closes at five.",
            "The fiscal year starts in July.",
            "We discussed unrelated topics.",
        )

        phrases.forEach { phrase ->
            assertEquals(phrase, DictionaryCorrector.correct(phrase, dictionary))
        }
    }

    @Test
    fun punctuationAroundMatchedPhraseIsPreserved() {
        assertEquals(
            "Hey, Infisical! (thanks)",
            DictionaryCorrector.correct("Hey, in physical! (thanks)", listOf("Infisical")),
        )
    }

    @Test
    fun emptyDictionaryIsAnExactNoOp() {
        val text = "Keep this exactly as-is: in physical, https://example.com/path"
        assertEquals(text, DictionaryCorrector.correct(text, emptyList()))
    }

    @Test
    fun urlsPathsFlagsAndCodeIdentifiersAreNotChanged() {
        val dictionary = listOf("Infisical")
        val text = "https://example.com/infisical /in/physical --infisical some_infisical `in physical`"

        assertEquals(text, DictionaryCorrector.correct(text, dictionary))
    }

    @Test
    fun fuzzyMatchingDoesNotAcceptSingleWordOrMoreThanFourTokenTerms() {
        assertEquals("physical", DictionaryCorrector.correct("physical", listOf("Infisical")))
        val longTerm = listOf("one two three four five")
        assertEquals("one two three four five", DictionaryCorrector.correct("one two three four five", longTerm))
    }

    @Test
    fun similarSubstringsInsideLargerWordsAreNotReplaced() {
        val text = "prefixinphysicalsuffix and xInfisicalx"
        assertEquals(text, DictionaryCorrector.correct(text, listOf("Infisical")))
    }

    @Test
    fun indexWorkIsBoundedAndCachedByDictionaryContents() {
        val dictionary = (0 until 205).map { "Product${letters(it)} | Spoken${letters(it)}" }

        DictionaryCorrector.prepare(dictionary)
        val first = DictionaryCorrector.cacheInfo(dictionary)
        DictionaryCorrector.correct("Unrelated words stay unchanged", dictionary)
        val repeated = DictionaryCorrector.cacheInfo(dictionary)

        assertEquals(200, first.candidateCount)
        assertEquals(200, first.aliasCount)
        assertEquals(first, repeated)

        val updated = dictionary.drop(1)
        assertNotEquals(first.buildId, DictionaryCorrector.cacheInfo(updated).buildId)
        assertEquals(0, DictionaryCorrector.cacheInfo(emptyList()).candidateCount)
    }

    private fun letters(number: Int): String {
        var value = number
        val result = StringBuilder()
        do {
            result.append(('a'.code + value % 26).toChar())
            value = value / 26 - 1
        } while (value >= 0)
        return result.reverse().toString()
    }
}
