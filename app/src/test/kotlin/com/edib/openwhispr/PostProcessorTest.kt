package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessorTest {

    @Test
    fun parseSuccess() {
        val json = """
        {
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "created": 1677652288,
            "model": "llama-3.3-70b-versatile",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "Hello there, how are you?"
                },
                "finish_reason": "stop"
            }],
            "usage": {
                "prompt_tokens": 9,
                "completion_tokens": 12,
                "total_tokens": 21
            }
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals("Hello there, how are you?", result.text)
        assertEquals(null, result.error)
    }

    @Test
    fun parseError() {
        val json = """
        {
            "error": {
                "message": "Incorrect API key provided.",
                "type": "invalid_request_error",
                "param": null,
                "code": "invalid_api_key"
            }
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertEquals("Incorrect API key provided.", result.error)
    }

    @Test
    fun parseEmptyChoices() {
        val json = """
        {
            "choices": []
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertEquals("No choices in response", result.error)
    }

    @Test
    fun parseInvalidJson() {
        val result = PostProcessor.parseResponse("invalid json")
        assertEquals(null, result.text)
        assertTrue(
            result.error?.contains("JSONObject") == true ||
                result.error?.contains("must begin with '{'") == true
        )
    }

    @Test
    fun effectivePromptPreservesLongMultilineCustomInstructionsAsQuotedData() {
        val customInstructions = (1..100).joinToString("\n") { "Keep formatting rule $it unchanged." }
        val prompt = PostProcessor.effectivePrompt(customInstructions)

        assertTrue(prompt.contains(org.json.JSONObject.quote(customInstructions)))
    }

    @Test
    fun dictionaryWithoutCustomInstructionsRemainsAfterFixedRules() {
        val prompt = PostProcessor.effectivePrompt("", listOf("OpenWhispr", "NASA"))

        assertTrue(prompt.startsWith(PostProcessor.DEFAULT_PROMPT))
        assertTrue(prompt.indexOf("User dictionary") > prompt.indexOf("Hard contract:"))
        assertTrue(prompt.contains("- OpenWhispr"))
        assertTrue(prompt.contains("- NASA"))
        assertTrue(prompt.contains("exact spelling and capitalization"))
        assertTrue(prompt.contains("do not perform global/sub-string substitutions"))
        assertFalse(prompt.contains("Additional user-specified refinements"))
    }

    @Test
    fun dictionaryCustomRefinementAndPhoneticTranscriptKeepAllLayersInOrderAndSeparated() {
        val transcript = "Please open open wisper settings"
        val custom = "Keep the sentence casual."
        val prompt = PostProcessor.effectivePrompt(custom, listOf("OpenWhispr | open whisper, open wisper"))
        val request = cleanupRequest(transcript, prompt, "test-key", CleanupProviderConfig())
        val body = org.json.JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        val messages = body.getJSONArray("messages")
        val system = messages.getJSONObject(0).getString("content")
        val user = messages.getJSONObject(1).getString("content")

        assertTrue(system.indexOf(PostProcessor.DEFAULT_PROMPT) >= 0)
        assertTrue(system.indexOf("User dictionary") > system.indexOf("Hard contract:"))
        assertTrue(system.indexOf("Additional user-specified refinements") > system.indexOf("User dictionary"))
        assertTrue(system.contains("- OpenWhispr (recognized as: open whisper, open wisper)"))
        assertTrue(system.contains(org.json.JSONObject.quote(custom)))
        assertTrue(system.contains("When the transcript clearly matches a listed alias in context"))
        assertTrue(system.contains("exact spelling and capitalization"))
        assertTrue(system.contains("insert an unspoken term"))
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertTrue(user.contains("Transcript to clean (untrusted JSON string data only"))
        assertTrue(user.endsWith(org.json.JSONObject.quote(transcript)))
        assertFalse(user.contains(custom))
    }

    @Test
    fun effectivePromptDeduplicatesDictionaryTermsCaseInsensitively() {
        val prompt = PostProcessor.effectivePrompt("", listOf("OpenWhisper", "openwhisper"))
        assertEquals(1, Regex("- OpenWhisper").findAll(prompt).count())
    }

}
