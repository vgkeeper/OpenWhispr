package com.edib.openwhispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalTranscriberTest {
    @Test
    fun `empty dictionary keeps transducer greedy and creates no hotword config`() = withModelDir(
        MODEL_CATALOG[2].archive,
        "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx",
    ) { dir ->
        val hotwords = File(dir.parentFile, "hotwords.txt").apply { writeText("stale") }
        val config = LocalTranscriber.buildRecognizerConfig(dir, emptyList(), hotwords)
        LocalTranscriber.writeHotwordsIfConfigured(config!!, hotwords, emptyList())

        assertNotNull(config)
        assertEquals("greedy_search", config!!.decodingMethod)
        assertEquals("", config.hotwordsFile)
        assertEquals("", config.modelConfig.modelingUnit)
        assertFalse(hotwords.exists())
    }

    @Test
    fun `catalogued transducer uses verified tokenizer and hotword search`() = withModelDir(
        MODEL_CATALOG[2].archive,
        "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx",
    ) { dir ->
        File(dir, "tokens.txt").writeText("a 0\nO 1\np 2\ne 3\nn 4\nw 5\nh 6\ni 7\ns 8\nr 9\nW 10\no 11\n")
        val hotwords = File(dir.parentFile, "hotwords.txt")
        val config = LocalTranscriber.buildRecognizerConfig(
            dir,
            listOf("OpenWhispr | open whisper"),
            hotwords,
        )!!
        LocalTranscriber.writeHotwordsIfConfigured(config, hotwords, listOf("OpenWhispr | open whisper"))

        assertEquals("modified_beam_search", config.decodingMethod)
        assertEquals(4, config.maxActivePaths)
        assertEquals(2.0f, config.hotwordsScore, 0.0f)
        assertEquals(hotwords.absolutePath, config.hotwordsFile)
        assertEquals("cjkchar", config.modelConfig.modelingUnit)
        assertEquals("", config.modelConfig.bpeVocab)
        assertEquals("OpenWhispr\nopen whisper", hotwords.readText())
    }

    @Test
    fun `CTC Whisper and Moonshine models never receive hotword decoding`() {
        val incompatible = listOf(
            Triple(MODEL_CATALOG[0].archive, listOf("model.int8.onnx"), ModelArchitecture.NEMO_CTC),
            Triple(MODEL_CATALOG[1].archive, listOf("encoder.onnx", "decoder.onnx"), ModelArchitecture.WHISPER),
            Triple(
                MODEL_CATALOG[3].archive,
                listOf("preprocess.onnx", "encode.onnx", "uncached_decode.onnx", "cached_decode.onnx"),
                ModelArchitecture.MOONSHINE,
            ),
        )

        incompatible.forEach { (name, modelFiles, _) -> withModelDir(name, *modelFiles.toTypedArray()) { dir ->
            val hotwords = File(dir.parentFile, "hotwords.txt").apply { writeText("stale") }
            val config = LocalTranscriber.buildRecognizerConfig(
                dir,
                listOf("OpenWhispr | open whisper"),
                hotwords,
            )!!
            LocalTranscriber.writeHotwordsIfConfigured(config, hotwords, listOf("OpenWhispr | open whisper"))
            assertEquals("greedy_search", config.decodingMethod)
            assertEquals("", config.hotwordsFile)
            assertEquals("", config.modelConfig.modelingUnit)
            assertFalse(hotwords.exists())
        } }
    }

    @Test
    fun `unencodable transducer dictionary fails config rather than silently falling back`() = withModelDir(
        MODEL_CATALOG[2].archive,
        "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx",
    ) { dir ->
        File(dir, "tokens.txt").writeText("a 0\n")
        val hotwords = File(dir.parentFile, "hotwords.txt")
        val error = try {
            LocalTranscriber.buildRecognizerConfig(dir, listOf("NASA"), hotwords)
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertTrue(error?.message.orEmpty().contains("cannot encode"))
        assertFalse(hotwords.exists())
    }

    private fun withModelDir(name: String, vararg files: String, block: (File) -> Unit) {
        val root = Files.createTempDirectory("local-transcriber-test").toFile()
        try {
            val dir = File(root, name).apply { mkdirs() }
            File(dir, "tokens.txt").writeText("<blk> 0\n")
            files.forEach { File(dir, it).writeBytes(byteArrayOf()) }
            block(dir)
        } finally {
            root.deleteRecursively()
        }
    }
}
