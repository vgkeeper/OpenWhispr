package com.edib.openwhispr

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/** Bounded, local-only correction of whole transcript terms from the user dictionary. */
object DictionaryCorrector {
    private const val MAX_ENTRIES = 200
    private const val MAX_ALIASES = 200
    private const val MAX_CANDIDATES = 200
    private const val MAX_TERM_LENGTH = 48
    private const val MAX_WINDOW_TOKENS = 4
    private const val PREFIX_LENGTH = 4

    private val tokenPattern = Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val whitespacePattern = Regex("\\s+")
    private val buildCounter = AtomicLong()
    private val cacheLock = Any()

    @Volatile
    private var cachedIndex: Index? = null

    private data class Token(
        val normalized: String,
        val start: Int,
        val end: Int,
        val technicalSyntax: Boolean,
    )

    private data class Candidate(
        val canonical: String,
        val phonetic: String,
    )

    private data class Index(
        val source: List<String>,
        val canonicalByKey: Map<String, String>,
        val aliasByKey: Map<String, String>,
        val candidatesByPrefix: Map<String, List<Candidate>>,
        val candidateCount: Int,
        val aliasCount: Int,
        val buildId: Long,
    )

    internal data class CacheInfo(val candidateCount: Int, val aliasCount: Int, val buildId: Long)

    private data class Replacement(val start: Int, val end: Int, val value: String)

    /** Warms or invalidates the cached dictionary index when settings are loaded or saved. */
    fun prepare(words: List<String>) {
        indexFor(words)
    }

    fun correct(text: String, words: List<String>): String {
        if (text.isEmpty() || words.isEmpty()) return text
        val index = indexFor(words)
        if (index.canonicalByKey.isEmpty() && index.aliasByKey.isEmpty()) return text

        val tokens = tokenize(text)
        if (tokens.isEmpty()) return text
        val replacements = ArrayList<Replacement>()
        var tokenIndex = 0
        while (tokenIndex < tokens.size) {
            val match = findMatch(text, tokens, tokenIndex, index)
            if (match == null) {
                tokenIndex++
            } else {
                val (endToken, canonical) = match
                val first = tokens[tokenIndex]
                val last = tokens[endToken]
                val original = text.substring(first.start, last.end)
                if (original != canonical) {
                    replacements += Replacement(first.start, last.end, canonical)
                }
                tokenIndex = endToken + 1
            }
        }
        if (replacements.isEmpty()) return text

        return buildString(text.length + replacements.sumOf { it.value.length - (it.end - it.start) }) {
            var cursor = 0
            replacements.forEach { replacement ->
                append(text, cursor, replacement.start)
                append(replacement.value)
                cursor = replacement.end
            }
            append(text, cursor, text.length)
        }
    }

    internal fun cacheInfo(words: List<String>): CacheInfo {
        val index = indexFor(words)
        return CacheInfo(index.candidateCount, index.aliasCount, index.buildId)
    }

    private fun indexFor(words: List<String>): Index {
        cachedIndex?.takeIf { matchesSource(it.source, words) }?.let { return it }
        return synchronized(cacheLock) {
            cachedIndex?.takeIf { matchesSource(it.source, words) } ?: buildIndex(
                words.take(MAX_ENTRIES).map { it.take(MAX_TERM_LENGTH * 8) },
            ).also { cachedIndex = it }
        }
    }

    private fun matchesSource(source: List<String>, words: List<String>): Boolean {
        if (words.size < source.size || (source.size < MAX_ENTRIES && words.size != source.size)) return false
        return source.indices.all { index ->
            val word = words[index]
            val indexedWord = source[index]
            if (word.length <= MAX_TERM_LENGTH * 8) indexedWord == word
            else indexedWord.length == MAX_TERM_LENGTH * 8 && word.startsWith(indexedWord)
        }
    }

    private fun buildIndex(source: List<String>): Index {
        val canonicalByKey = LinkedHashMap<String, String>()
        val aliasByKey = LinkedHashMap<String, String>()
        val candidates = LinkedHashMap<String, MutableList<Candidate>>()
        var candidateCount = 0
        var aliasCount = 0
        var aliasesVisited = 0

        source.asSequence().take(MAX_ENTRIES).forEach { line ->
            if (line.length > MAX_TERM_LENGTH * 8) return@forEach
            val separator = line.indexOf('|')
            val canonicalEnd = if (separator < 0) line.length else separator
            val canonical = trimmedTerm(line, 0, canonicalEnd) ?: return@forEach
            val canonicalKey = termKey(canonical) ?: return@forEach
            val existingCanonical = canonicalByKey[canonicalKey]
            val canonicalSpelling = existingCanonical ?: canonical
            if (existingCanonical == null) canonicalByKey[canonicalKey] = canonical
            if (existingCanonical == null && candidateCount < MAX_CANDIDATES &&
                isFuzzyTerm(canonical, canonicalKey)
            ) {
                val phonetic = phoneticKey(canonicalKey)
                if (phonetic.length >= 7) {
                    candidates.getOrPut(prefix(phonetic)) { ArrayList() }
                        .add(Candidate(canonicalSpelling, phonetic))
                    candidateCount++
                }
            }

            if (separator >= 0 && aliasCount < MAX_ALIASES) {
                var aliasStart = separator + 1
                while (aliasStart <= line.length && aliasesVisited < MAX_ALIASES) {
                    val comma = line.indexOf(',', aliasStart).let { if (it < 0) line.length else it }
                    aliasesVisited++
                    val alias = trimmedTerm(line, aliasStart, comma)
                    if (alias != null) {
                        val aliasKey = termKey(alias)
                        if (aliasKey != null && aliasKey != canonicalKey && aliasKey !in aliasByKey) {
                            aliasByKey[aliasKey] = canonicalSpelling
                            aliasCount++
                        }
                    }
                    if (comma == line.length) break
                    aliasStart = comma + 1
                }
            }
        }

        return Index(
            source = source,
            canonicalByKey = canonicalByKey,
            aliasByKey = aliasByKey,
            candidatesByPrefix = candidates,
            candidateCount = candidates.values.sumOf { it.size },
            aliasCount = aliasCount,
            buildId = buildCounter.incrementAndGet(),
        )
    }

    private fun findMatch(
        text: String,
        tokens: List<Token>,
        startToken: Int,
        index: Index,
    ): Pair<Int, String>? {
        if (tokens[startToken].technicalSyntax) return null
        val spans = ArrayList<Pair<Int, String>>(MAX_WINDOW_TOKENS)
        val key = StringBuilder(MAX_TERM_LENGTH)
        val maxTokens = minOf(MAX_WINDOW_TOKENS, tokens.size - startToken)
        var previousEnd = tokens[startToken].end
        for (offset in 0 until maxTokens) {
            val token = tokens[startToken + offset]
            if (token.technicalSyntax || token.normalized.isEmpty() ||
                (offset > 0 && text.substring(previousEnd, token.start).any { !it.isWhitespace() }) ||
                key.length + token.normalized.length > MAX_TERM_LENGTH
            ) break
            key.append(token.normalized)
            spans.add(Pair(offset + 1, key.toString()))
            previousEnd = token.end
        }

        for ((tokenCount, termKey) in spans.asReversed()) {
            val first = tokens[startToken]
            val last = tokens[startToken + tokenCount - 1]
            if (!hasSafeBoundaries(text, first.start, last.end)) continue
            val canonical = index.canonicalByKey[termKey]
            if (canonical != null) return (startToken + tokenCount - 1) to canonical
            val alias = index.aliasByKey[termKey]
            if (alias != null) return (startToken + tokenCount - 1) to alias
        }

        for ((tokenCount, termKey) in spans.asReversed()) {
            val first = tokens[startToken]
            val last = tokens[startToken + tokenCount - 1]
            if (!hasSafeBoundaries(text, first.start, last.end) ||
                isCommonCollocation(tokens, startToken, tokenCount)
            ) continue
            val phonetic = phoneticKey(termKey)
            val candidates = index.candidatesByPrefix[prefix(phonetic)] ?: continue
            var best: Candidate? = null
            var bestDistance = Int.MAX_VALUE
            var ambiguousBest = false
            for (candidate in candidates) {
                val distance = when {
                    candidate.phonetic.length < 7 || phonetic.length < 7 -> -1
                    candidate.phonetic == phonetic -> 0
                    else -> editDistanceAtMostOne(candidate.phonetic, phonetic)
                }
                if (distance < 0) continue
                when {
                    distance < bestDistance -> {
                        best = candidate
                        bestDistance = distance
                        ambiguousBest = false
                    }
                    distance == bestDistance && candidate.canonical != best?.canonical -> ambiguousBest = true
                }
            }
            if (ambiguousBest) return null
            if (best != null) return (startToken + tokenCount - 1) to best.canonical
        }
        return null
    }

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        val matcher = tokenPattern.matcher(text)
        var cursor = 0
        var inCode = false
        while (matcher.find()) {
            for (i in cursor until matcher.start()) {
                if (text[i] == '`') inCode = !inCode
            }
            val start = matcher.start()
            val end = matcher.end()
            val rawLength = end - start
            val technicalSyntax = inCode || rawLength > MAX_TERM_LENGTH ||
                text.substring(start, end).any { it.isDigit() } || isTechnicalChunk(text, start, end)
            val normalized = if (rawLength <= MAX_TERM_LENGTH) normalize(text.substring(start, end)) else ""
            tokens += Token(normalized, start, end, technicalSyntax)
            cursor = end
        }
        return tokens
    }

    private fun hasSafeBoundaries(text: String, start: Int, end: Int): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        if ((before != null && before in protectedBoundaryChars) ||
            (after != null && after in protectedBoundaryChars)
        ) return false
        if (before == '.' && text.getOrNull(start - 2)?.let(::isWordCharacter) == true) return false
        if (after == '.' && text.getOrNull(end + 1)?.let(::isWordCharacter) == true) return false
        if (after == '(') return false
        return true
    }

    private val protectedBoundaryChars = setOf('_', '/', '\\', '#', '$', '`', '-', '@')

    private fun isTechnicalChunk(text: String, start: Int, end: Int): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        var previousNonSpace = start - 1
        while (previousNonSpace >= 0 && text[previousNonSpace].isWhitespace()) previousNonSpace--
        var nextNonSpace = end
        while (nextNonSpace < text.length && text[nextNonSpace].isWhitespace()) nextNonSpace++
        val previous = text.getOrNull(previousNonSpace)
        val next = text.getOrNull(nextNonSpace)
        return (before != null && before in protectedBoundaryChars) ||
            (after != null && after in protectedBoundaryChars) ||
            (before == '.' && text.getOrNull(start - 2)?.let(::isWordCharacter) == true) ||
            (after == '.' && text.getOrNull(end + 1)?.let(::isWordCharacter) == true) ||
            previous == '=' || previous == ';' || previous == '{' || previous == '[' ||
            next == '=' || next == ';' || next == '}' || next == ']' ||
            (after == ':' && text.getOrNull(end + 1)?.isWhitespace() == false) ||
            (previous == '(' && text.getOrNull(previousNonSpace - 1)?.let(::isWordCharacter) == true)
    }

    private fun trimmedTerm(text: String, start: Int, end: Int): String? {
        var first = start
        var last = end
        while (first < last && text[first].isWhitespace()) first++
        while (last > first && text[last - 1].isWhitespace()) last--
        return if (first == last || last - first > MAX_TERM_LENGTH) null else text.substring(first, last)
    }

    private fun isCommonCollocation(tokens: List<Token>, start: Int, count: Int): Boolean {
        val finalWord = tokens[start + count - 1].normalized
        val nextWord = tokens.getOrNull(start + count)?.normalized ?: return false
        return nextWord in commonCollocationFollowers[finalWord].orEmpty()
    }

    private val commonCollocationFollowers = mapOf(
        "physical" to setOf(
            "therapy", "therapist", "therapists", "exam", "exams", "examination", "education",
            "condition", "conditions", "activity", "activities", "conditioning", "contact", "health", "fitness",
            "pain", "danger", "terms", "form", "shape", "space", "world", "environment", "reality",
        ),
        "fiscal" to setOf("year", "years", "policy", "policies", "budget", "quarter", "quarters"),
    )

    private fun termKey(term: String): String? {
        val trimmed = term.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TERM_LENGTH ||
            trimmed.any { !it.isLetter() && !it.isWhitespace() && it != '\'' && it != '’' }
        ) return null
        val key = normalize(trimmed).filterNot { it.isWhitespace() }
        return key.takeIf { it.isNotEmpty() && it.length <= MAX_TERM_LENGTH }
    }

    private fun isFuzzyTerm(term: String, key: String): Boolean =
        whitespacePattern.split(term.trim()).size <= MAX_WINDOW_TOKENS &&
            key.length <= MAX_TERM_LENGTH && key.all { it.isLetter() }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        return buildString(decomposed.length) {
            decomposed.forEach { char ->
                when (Character.getType(char)) {
                    Character.NON_SPACING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt() -> Unit
                    else -> if (char.isLetterOrDigit()) append(char)
                }
            }
        }
    }

    private fun phoneticKey(value: String): String = value
        .replace("ph", "f")
        .replace("wh", "w")
        .replace("ck", "k")
        .replace("qu", "k")
        .replace('c', 'k')
        .replace('y', 'i')

    private fun prefix(value: String): String = value.take(PREFIX_LENGTH)

    private fun editDistanceAtMostOne(first: String, second: String): Int {
        if (first == second) return 0
        if (kotlin.math.abs(first.length - second.length) > 1) return -1
        var firstIndex = 0
        var secondIndex = 0
        var edits = 0
        while (firstIndex < first.length && secondIndex < second.length) {
            if (first[firstIndex] == second[secondIndex]) {
                firstIndex++
                secondIndex++
            } else {
                if (++edits > 1) return -1
                when {
                    first.length > second.length -> firstIndex++
                    second.length > first.length -> secondIndex++
                    else -> { firstIndex++; secondIndex++ }
                }
            }
        }
        if (firstIndex < first.length || secondIndex < second.length) edits++
        return if (edits <= 1) edits else -1
    }

    private fun isWordCharacter(char: Char): Boolean = char.isLetterOrDigit() || char == '_'
}
