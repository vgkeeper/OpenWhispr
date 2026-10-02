package com.edib.openwhispr

import org.junit.Assert.*
import org.junit.Test

class TranscriberClientTest {

    @Test fun `parses success response`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `parses error response`() {
        val r = TranscriberClient.parseResponse("""{"error":{"message":"Invalid key","type":"auth"}}""")
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }

    @Test fun `handles unknown format`() {
        val r = TranscriberClient.parseResponse("""{"foo":"bar"}""")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `handles malformed json`() {
        val r = TranscriberClient.parseResponse("not json")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `multipart request includes dictionary context and no cleanup instructions`() {
        val request = TranscriberClient.transcriptionRequest(
            byteArrayOf(1, 2, 3),
            "test-key",
            listOf("OpenWhispr | open whisper", "OAPO | o a p o, eau à peau"),
        )
        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()

        assertEquals("POST", request.method)
        assertTrue(body.contains("name=\"prompt\""))
        assertTrue(body.contains("OpenWhispr"))
        assertTrue(body.contains("open whisper"))
        assertTrue(body.contains("OAPO"))
        assertTrue(body.contains("o a p o"))
        assertFalse(body.contains("cleanup"))
        assertFalse(body.contains("custom instructions"))
    }

    @Test fun `ASR prompt stays within conservative byte budget with unicode and large vocabulary`() {
        val longVocabulary = listOf("Élodie") + (1..100).map { "ÉlémentDictionary$it".repeat(40) }
        val prompt = TranscriberClient.dictionaryPrompt(longVocabulary)

        assertTrue(prompt.isNotEmpty())
        assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= TranscriberClient.MAX_DICTIONARY_PROMPT_BYTES)
    }

    @Test fun `empty dictionary omits prompt multipart field`() {
        val request = TranscriberClient.transcriptionRequest(byteArrayOf(1), "test-key", emptyList())
        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()

        assertFalse(body.contains("name=\"prompt\""))
        assertFalse(body.contains("Spelling hints:"))
    }
}
