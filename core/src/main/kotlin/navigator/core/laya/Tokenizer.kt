package navigator.core.laya

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/**
 * Kotlin port of the `laya` tokenizer (vision 05 §6: a first-class work item — "it gates
 * the entire shadow track"; reference: the `receptron/laya` TypeScript port, which runs
 * `@huggingface/tokenizers` on the checkpoint's `tokenizer/tokenizer.json`).
 *
 * IMPORTANT — the real laya tokenizer is NOT WordPiece. `convaiinnovations/laya`
 * (revision 55cf4c4ebb4ebe31b2550e8bdf3bd21b99753851) ships a GPT-2-style tokenizer:
 * NFC normalizer, ByteLevel pre-tokenizer (GPT-2 regex, `add_prefix_space=false`),
 * BPE model (50280 vocab, 50009 merges) plus 116 added tokens. Phase doc §2.2's
 * "WordPiece" label mischaracterizes it; the binding correctness criterion (Gate 2.2)
 * is 100% exact match against reference vectors from the real tokenizer, which this
 * port implements. Token ids that disagree with the checkpoint's tokenizer would make
 * the Phase-8 shadow meaningless, so "match the real thing" is the only faithful reading.
 *
 * Pipeline (mirrors HF `tokenizers` `Tokenizer.encode(text, add_special_tokens=false)`):
 *  1. split raw input on non-normalized added tokens (leftmost-longest, honoring
 *     `lstrip`/`rstrip` whitespace swallowing);
 *  2. NFC-normalize the residual chunks, split those on normalized added tokens;
 *  3. ByteLevel pre-tokenization of the remaining text (GPT-2 regex, then each piece's
 *     UTF-8 bytes mapped through the GPT-2 byte→unicode alphabet);
 *  4. BPE per piece: repeatedly merge the lowest-ranked adjacent pair.
 *
 * Pure JVM (java.text.Normalizer + java.util.regex) — no Android types (:core boundary).
 */
class Tokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val tokenById: Map<Int, String>,
    private val merges: Map<Pair<String, String>, Int>,
    private val addedTokens: List<AddedToken>,
) {

    /** One entry of `added_tokens`: matched before the BPE model sees the text. */
    private data class AddedToken(
        val id: Int,
        val content: String,
        /** true → matched on the NFC-normalized text; false → matched on the raw text. */
        val normalized: Boolean,
        /** true → the match swallows the whitespace run preceding the content (laya's `[MASK]`). */
        val lstrip: Boolean,
        /** true → the match swallows the whitespace run following the content. */
        val rstrip: Boolean,
    ) {
        /** Full match length at [start]: the content plus any stripped whitespace. */
        fun lengthAt(text: String, start: Int): Int {
            var end = start + content.length
            if (rstrip) {
                while (end < text.length && text[end].code in WHITE_SPACE_CODE_POINTS) end++
            }
            return end - start
        }
    }

    /** Token ids for [text], special tokens NOT added (the receptron port's encode mode). */
    fun tokenIds(text: String): List<Int> {
        val out = mutableListOf<Int>()
        var chunkStart = 0
        var i = 0
        while (i < text.length) {
            val match = longestAddedToken(text, i, normalized = false)
            if (match != null) {
                if (i > chunkStart) out += encodeResidual(text.substring(chunkStart, i))
                out += match.token.id
                i += match.span
                chunkStart = i
            } else {
                i++
            }
        }
        if (chunkStart < text.length) out += encodeResidual(text.substring(chunkStart))
        return out
    }

    /** Token strings (vocab forms, e.g. `"Ġpie"`) for [text] — the HF `encoding.tokens`. */
    fun tokenize(text: String): List<String> = tokenIds(text).map { id ->
        tokenById[id] ?: error("tokenizer produced unknown token id $id")
    }

    /** Number of tokens [text] encodes to — the state-text budget counter (vision 03 §4). */
    fun count(text: String): Int = tokenIds(text).size

    // -- pipeline stages -----------------------------------------------------------

    /** NFC-normalizes a residual chunk, then splits on normalized added tokens. */
    private fun encodeResidual(chunk: String): List<Int> {
        val text = Normalizer.normalize(chunk, Normalizer.Form.NFC)
        val out = mutableListOf<Int>()
        var chunkStart = 0
        var i = 0
        while (i < text.length) {
            val match = longestAddedToken(text, i, normalized = true)
            if (match != null) {
                if (i > chunkStart) out += bpePreTokenize(text.substring(chunkStart, i))
                out += match.token.id
                i += match.span
                chunkStart = i
            } else {
                i++
            }
        }
        if (chunkStart < text.length) out += bpePreTokenize(text.substring(chunkStart))
        return out
    }

    /** One added-token hit: the matched token and the full span it consumes. */
    private data class AddedMatch(val token: AddedToken, val span: Int)

    /**
     * Leftmost-longest added-token match starting at [at]. An `lstrip` token's match may
     * start inside the whitespace run before its content: the scan reaches that run first
     * and the match extends forward through whitespace to the content — HF's behavior.
     * The returned span includes any stripped whitespace.
     */
    private fun longestAddedToken(text: String, at: Int, normalized: Boolean): AddedMatch? {
        var best: AddedMatch? = null
        for (token in addedTokens) {
            if (token.normalized != normalized) continue
            var contentStart = at
            if (token.lstrip) {
                while (contentStart < text.length &&
                    text[contentStart].code in WHITE_SPACE_CODE_POINTS &&
                    contentStart - at < MAX_LSTRIP_LOOKAHEAD
                ) contentStart++
            }
            if (text.startsWith(token.content, contentStart)) {
                val span = contentStart - at + token.lengthAt(text, contentStart)
                if (best == null || span > best.span) best = AddedMatch(token, span)
            }
        }
        return best
    }

    /** ByteLevel pre-tokenization + per-piece BPE. */
    private fun bpePreTokenize(text: String): List<Int> {
        val out = mutableListOf<Int>()
        for (match in GPT2_REGEX.findAll(text)) {
            out += bpe(byteLevelEncode(match.value))
        }
        return out
    }

    /** Maps a pre-token's UTF-8 bytes through the GPT-2 byte→unicode alphabet. */
    private fun byteLevelEncode(piece: String): List<String> =
        piece.toByteArray(Charsets.UTF_8).map { b ->
            byteToChar[b.toInt() and 0xFF]
                ?: error("byte $b missing from the GPT-2 byte-level alphabet")
        }

    /** Classic BPE: merge the lowest-ranked adjacent pair until no merge applies. */
    private fun bpe(pieceChars: List<String>): List<Int> {
        if (pieceChars.isEmpty()) return emptyList()
        var word = pieceChars
        while (word.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestPair: Pair<String, String>? = null
            for (w in 0 until word.size - 1) {
                val pair = word[w] to word[w + 1]
                val rank = merges[pair]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestPair = pair
                }
            }
            val pair = bestPair ?: break
            val merged = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == pair.first && word[i + 1] == pair.second) {
                    merged += pair.first + pair.second
                    i += 2
                } else {
                    merged += word[i]
                    i++
                }
            }
            word = merged
        }
        return word.map { piece ->
            vocab[piece] ?: error("BPE produced token \"$piece\" missing from the vocab")
        }
    }

    companion object {

        /**
         * The Unicode White_Space code points — the exact property behind the GPT-2
         * regex's `\s` (Rust regex crates define `\s` as Unicode White_Space, which is
         * wider than Java's `\s`). Spelled out as ints so no whitespace escapes can be
         * mangled in this file. Used by added-token `lstrip`/`rstrip`.
         */
        private val WHITE_SPACE_CODE_POINTS = buildSet {
            for (cp in intArrayOf(9, 10, 11, 12, 13, 32, 133, 160, 5760)) add(cp)
            for (cp in 8192..8202) add(cp)
            for (cp in intArrayOf(8232, 8233, 8239, 8287, 12288)) add(cp)
        }

        /** Bound on whitespace swallowed by one `lstrip` (laya's longest run token is 24). */
        private const val MAX_LSTRIP_LOOKAHEAD = 256

        /**
         * GPT-2 pre-tokenizer regex (HF ByteLevel `use_regex=true`). Java's `\s` is
         * narrower than Rust's Unicode White_Space `\s`, so the pattern is built with
         * the White_Space set spelled out as an explicit class via [whiteSpaceClass].
         */
        private val GPT2_REGEX: Regex = Regex(
            buildString {
                append("'s|'t|'re|'ve|'m|'ll|'d")
                append("| ?")
                append(LETTER_CLASS)
                append('+')
                append("| ?")
                append(DIGIT_CLASS)
                append('+')
                append("| ?[^")
                append(whiteSpaceClass())
                append(LETTER_CLASS)
                append(DIGIT_CLASS)
                append("]+")
                append('|')
                append(whiteSpaceClass())
                append("+(?!\\S)")
                append('|')
                append(whiteSpaceClass())
                append('+')
            },
        )

        /** Java Unicode category class for letters (matches Rust `\p{L}`). */
        private const val LETTER_CLASS = "\\p{L}"

        /** Java Unicode category class for numbers (matches Rust `\p{N}`). */
        private const val DIGIT_CLASS = "\\p{N}"

        /** The White_Space set as an explicit regex char class (e.g. `[\t\n ...]`). */
        private fun whiteSpaceClass(): String = buildString {
            append('[')
            var runStart = -1
            var prev = -1
            fun flush() {
                if (runStart < 0) return
                if (runStart == prev) {
                    appendCodePoint(runStart)
                } else {
                    appendCodePoint(runStart)
                    append('-')
                    appendCodePoint(prev)
                }
                runStart = -1
            }
            fun appendCodePoint(cp: Int) {
                when {
                    cp == ' '.code -> append(' ')
                    cp in 0x21..0x7e && cp != '\\'.code && cp != ']'.code && cp != '^'.code &&
                        cp != '-'.code && cp != '['.code -> append(cp.toChar())
                    cp <= 0xFF -> append(String.format("\\x%02x", cp))
                    else -> append(String.format("\\x{%x}", cp))
                }
            }
            for (cp in WHITE_SPACE_CODE_POINTS.sorted()) {
                if (runStart < 0) {
                    runStart = cp
                } else if (cp == prev + 1) {
                    // extend the run
                } else {
                    flush()
                    runStart = cp
                }
                prev = cp
            }
            flush()
            append(']')
        }

        /**
         * GPT-2 `bytes_to_unicode`: printable bytes map to themselves, the rest shift up
         * past 0x100. Indexed by unsigned byte value (`b.toInt() and 0xFF`).
         */
        private val byteToChar: Array<String?> = run {
            val byteToC = HashMap<Int, Char>()
            for (b in '!'..'~') byteToC[b.code] = b
            for (b in 0xA1..0xAC) byteToC[b] = b.toChar()
            for (b in 0xAE..0xFF) byteToC[b] = b.toChar()
            var n = 0
            val table = arrayOfNulls<String>(256)
            for (b in 0..255) {
                val c = byteToC[b] ?: (256 + n++).toChar()
                table[b] = c.toString()
            }
            table
        }

        private val json = Json { ignoreUnknownKeys = true }

        /** Classpath copy of the checkpoint's tokenizer.json (see laya/PROVENANCE.txt). */
        fun loadDefault(): Tokenizer = fromJsonResource("/laya/tokenizer.json")

        fun fromJsonResource(resource: String): Tokenizer {
            val text = Tokenizer::class.java.getResourceAsStream(resource)
                ?.readBytes()?.toString(Charsets.UTF_8)
                ?: error("tokenizer resource not found on classpath: $resource")
            return fromJson(text)
        }

        /** Strict about the parts laya actually uses; unknown extra keys are tolerated. */
        fun fromJson(text: String): Tokenizer {
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("tokenizer.json is not valid JSON: ${e.message}", e)
            }

            val normalizerType =
                (root["normalizer"] as? JsonObject)?.get("type")?.jsonPrimitive?.content
            require(normalizerType == "NFC") {
                "unsupported normalizer \"$normalizerType\" — this port implements the laya NFC normalizer only"
            }
            val pre = root["pre_tokenizer"]?.jsonObject
                ?: throw IllegalArgumentException("tokenizer.json has no pre_tokenizer")
            require(pre["type"]?.jsonPrimitive?.content == "ByteLevel") {
                "unsupported pre_tokenizer ${pre["type"]} — this port implements ByteLevel only"
            }

            val model = root["model"]?.jsonObject
                ?: throw IllegalArgumentException("tokenizer.json has no model")
            require(model["type"]?.jsonPrimitive?.content == "BPE") {
                "unsupported model ${model["type"]} — this port implements BPE only"
            }
            val vocabJson = model["vocab"]?.jsonObject
                ?: throw IllegalArgumentException("BPE model has no vocab")
            val vocab = LinkedHashMap<String, Int>(vocabJson.size * 2)
            for ((token, id) in vocabJson) {
                require(vocab.put(token, id.jsonPrimitive.content.toInt()) == null) {
                    "duplicate vocab entry: \"$token\""
                }
            }
            val merges = HashMap<Pair<String, String>, Int>()
            val mergesJson = model["merges"]?.jsonArray
                ?: throw IllegalArgumentException("BPE model has no merges")
            for ((rank, element) in mergesJson.withIndex()) {
                val pair = when (element) {
                    is JsonArray -> element.map { it.jsonPrimitive.content }
                    is JsonPrimitive -> element.content.split(" ")
                    else -> throw IllegalArgumentException("bad merge entry at rank $rank: $element")
                }
                require(pair.size == 2) { "merge at rank $rank is not a pair: $element" }
                merges[pair[0] to pair[1]] = rank
            }

            val added = root["added_tokens"]?.jsonArray ?: JsonArray(emptyList())
            val addedTokens = added.map { element ->
                val obj = element.jsonObject
                val token = AddedToken(
                    id = obj["id"]!!.jsonPrimitive.content.toInt(),
                    content = obj["content"]!!.jsonPrimitive.content,
                    normalized = obj["normalized"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                    lstrip = obj["lstrip"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                    rstrip = obj["rstrip"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                )
                require(obj["single_word"]?.jsonPrimitive?.content?.toBoolean() != true) {
                    "added token \"${token.content}\" uses single_word=true, which this port does not implement"
                }
                token
            }
            val tokenById = HashMap<Int, String>(vocab.size + addedTokens.size)
            for ((token, id) in vocab) tokenById[id] = token
            for (token in addedTokens) tokenById[token.id] = token.content

            return Tokenizer(vocab, tokenById, merges, addedTokens)
        }
    }
}
