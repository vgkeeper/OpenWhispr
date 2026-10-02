package com.edib.openwhispr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Local on-device transcription via sherpa-onnx. */
class LocalTranscriber private constructor(private val recognizer: OfflineRecognizer) {

    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    companion object {
        private const val TAG = "LocalTranscriber"
        private const val HOTWORDS_SCORE = 2.0f
        private const val MAX_ACTIVE_PATHS = 4

        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }

        fun create(ctx: Context, modelName: String): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            val hotwordsFile = File(ctx.filesDir, "dictionary/hotwords.txt")
            if (!modelDir.exists()) {
                hotwordsFile.delete()
                Log.e(TAG, "Model directory is missing: $modelName")
                return null
            }

            val dictionary = Dictionary.load(ctx.getSharedPreferences("openwhispr", Context.MODE_PRIVATE))
            val config = try {
                buildRecognizerConfig(modelDir, dictionary, hotwordsFile)
            } catch (e: Exception) {
                hotwordsFile.delete()
                val model = MODEL_CATALOG.firstOrNull { it.archive == modelName }
                Log.e(TAG, "Hotword configuration failed: model=$modelName, architecture=${model?.architecture}, " +
                    "decoding=modified_beam_search, score=$HOTWORDS_SCORE, maxActivePaths=$MAX_ACTIVE_PATHS, " +
                    "modelingUnit=${model?.hotwordModelingUnit}, bpeVocabConfigured=false, cause=${safeCause(e, dictionary)}")
                return null
            } ?: run {
                hotwordsFile.delete()
                Log.e(TAG, "Could not identify a supported model architecture: model=$modelName")
                return null
            }
            try {
                writeHotwordsIfConfigured(config, hotwordsFile, dictionary)
            } catch (e: Exception) {
                hotwordsFile.delete()
                Log.e(TAG, "Hotword setup failed: model=$modelName, decoding=${config.decodingMethod}, " +
                    "modelingUnit=${config.modelConfig.modelingUnit}, bpeVocabConfigured=${config.modelConfig.bpeVocab.isNotEmpty()}, " +
                    "cause=${safeCause(e, dictionary)}")
                return null
            }

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(recognizer)
            } catch (e: Exception) {
                hotwordsFile.delete()
                logRecognizerFailure(e, modelName, modelDir, config, dictionary)
                null
            } catch (e: LinkageError) {
                hotwordsFile.delete()
                logRecognizerFailure(e, modelName, modelDir, config, dictionary)
                null
            }
        }

        private fun logRecognizerFailure(
            error: Throwable,
            modelName: String,
            modelDir: File,
            config: OfflineRecognizerConfig,
            dictionary: List<String>,
        ) {
            Log.e(TAG, "Recognizer creation failed: model=$modelName, architecture=${detectedArchitecture(modelDir)}, " +
                "decoding=${config.decodingMethod}, hotwords=${config.hotwordsFile.isNotEmpty()}, " +
                "modelingUnit=${config.modelConfig.modelingUnit}, bpeVocabConfigured=${config.modelConfig.bpeVocab.isNotEmpty()}, " +
                "score=${config.hotwordsScore}, maxActivePaths=${config.maxActivePaths}, cause=${safeCause(error, dictionary)}")
        }

        internal fun buildRecognizerConfig(
            dir: File,
            vocabulary: List<String>,
            hotwordsFile: File,
        ): OfflineRecognizerConfig? {
            val detected = detectModelConfig(dir) ?: return null
            val config = detected.config
            val terms = Dictionary.recognitionTerms(vocabulary)
            if (terms.isEmpty()) return config

            val catalogModel = MODEL_CATALOG.firstOrNull { it.archive == dir.name } ?: return config
            if (detected.architecture != ModelArchitecture.NEMO_TRANSDUCER ||
                catalogModel.architecture != ModelArchitecture.NEMO_TRANSDUCER
            ) return config
            val modelingUnit = catalogModel.hotwordModelingUnit ?: return config

            require(config.modelConfig.transducer.encoder.isNotBlank() &&
                config.modelConfig.transducer.decoder.isNotBlank() &&
                config.modelConfig.transducer.joiner.isNotBlank()) {
                "Transducer hotwords require encoder, decoder, and joiner model files"
            }
            require(terms.none { term ->
                term.split(Regex("\\s+")).any { it.startsWith(':') || it.startsWith('#') || it.startsWith('@') }
            }) { "Dictionary entry conflicts with sherpa-onnx hotword metadata syntax" }
            require(modelingUnit == "cjkchar" || modelingUnit == "bpe" || modelingUnit == "cjkchar+bpe") {
                "Unsupported hotword modeling unit: $modelingUnit"
            }
            if (modelingUnit == "bpe" || modelingUnit == "cjkchar+bpe") {
                require(config.modelConfig.bpeVocab.isNotBlank() && File(config.modelConfig.bpeVocab).isFile) {
                    "BPE hotwords require a model-compatible vocabulary file"
                }
            }

            config.decodingMethod = "modified_beam_search"
            config.maxActivePaths = MAX_ACTIVE_PATHS
            config.hotwordsScore = HOTWORDS_SCORE
            config.hotwordsFile = hotwordsFile.absolutePath
            config.modelConfig.modelingUnit = modelingUnit
            requireHotwordSymbolsExist(dir, terms, modelingUnit)
            return config
        }

        private data class DetectedConfig(
            val config: OfflineRecognizerConfig,
            val architecture: ModelArchitecture,
        )

        private fun detectedArchitecture(dir: File): ModelArchitecture? = detectModelConfig(dir)?.architecture

        private fun detectModelConfig(dir: File): DetectedConfig? {
            val p = dir.absolutePath
            val tokens = "$p/tokens.txt"
            if (!File(tokens).exists()) return null

            if (File("$p/preprocess.onnx").exists()) {
                return DetectedConfig(
                    OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            moonshine = OfflineMoonshineModelConfig(
                                preprocessor = "$p/preprocess.onnx",
                                encoder = findFile(p, "encode") ?: return null,
                                uncachedDecoder = findFile(p, "uncached_decode") ?: return null,
                                cachedDecoder = findFile(p, "cached_decode") ?: return null,
                            ),
                            tokens = tokens,
                            numThreads = 2,
                        ),
                    ),
                    ModelArchitecture.MOONSHINE,
                )
            }

            val encoder = findFile(p, "encoder")
            val decoder = findFile(p, "decoder")
            val joiner = findFile(p, "joiner")
            if (encoder != null && decoder != null && joiner != null) {
                return DetectedConfig(
                    OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(
                                encoder = encoder,
                                decoder = decoder,
                                joiner = joiner,
                            ),
                            tokens = tokens,
                            numThreads = 2,
                            modelType = "nemo_transducer",
                        ),
                    ),
                    ModelArchitecture.NEMO_TRANSDUCER,
                )
            }

            if (encoder != null && decoder != null) {
                return DetectedConfig(
                    OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(encoder = encoder, decoder = decoder),
                            tokens = tokens,
                            numThreads = 2,
                            modelType = "whisper",
                        ),
                    ),
                    ModelArchitecture.WHISPER,
                )
            }

            val ctcModel = findFile(p, "model") ?: return null
            return DetectedConfig(
                OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        tokens = tokens,
                        numThreads = 2,
                    ),
                ),
                ModelArchitecture.NEMO_CTC,
            )
        }

        internal fun writeHotwordsIfConfigured(
            config: OfflineRecognizerConfig,
            hotwordsFile: File,
            vocabulary: List<String>,
        ) {
            if (config.hotwordsFile.isEmpty()) {
                hotwordsFile.delete()
                return
            }
            require(config.hotwordsFile == hotwordsFile.absolutePath) {
                "Hotword output path does not match recognizer configuration"
            }
            require(Dictionary.recognitionTerms(vocabulary).isNotEmpty()) {
                "Hotword configuration cannot be used with an empty dictionary"
            }
            Dictionary.writeHotwords(hotwordsFile, vocabulary)
        }

        private fun requireHotwordSymbolsExist(dir: File, terms: List<String>, modelingUnit: String) {
            if (modelingUnit != "cjkchar") return
            val tokenFile = File(dir, "tokens.txt")
            val symbols = tokenFile.useLines { lines ->
                lines.mapNotNull { line ->
                    val separator = line.lastIndexOf(' ')
                    if (separator <= 0) null else line.substring(0, separator)
                }.toSet()
            }
            val required = mutableSetOf<String>()
            terms.forEach { term ->
                term.split(Regex("\\s+")).filter(String::isNotEmpty).forEach { word ->
                    word.codePoints().forEach { codePoint -> required += String(Character.toChars(codePoint)) }
                }
            }
            val missingCount = required.count { it !in symbols }
            require(missingCount == 0) {
                "The selected tokenizer cannot encode $missingCount hotword symbol(s) from this dictionary"
            }
        }

        private fun safeCause(error: Throwable, dictionary: List<String>): String {
            val message = error.message ?: error.javaClass.simpleName
            return Dictionary.recognitionTerms(dictionary)
                .sortedByDescending(String::length)
                .fold(message) { safe, term -> safe.replace(term, "[dictionary entry]", ignoreCase = true) }
        }

        private fun findFile(dir: String, prefix: String): String? {
            val directory = File(dir)
            directory.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.name.contains("int8") }
                ?.let { return it.absolutePath }
            return directory.listFiles()?.firstOrNull {
                it.name.startsWith(prefix) && (it.name.endsWith(".onnx") || it.name.endsWith(".ort"))
            }?.absolutePath
        }
    }
}
