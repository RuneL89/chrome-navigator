package navigator.core.laya

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromStream
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
 * Phase 8C added the SECOND checkpoint family (v32b, `ichenney/laya-browser-v32b`
 * @ 161d54d6…): a 256,000-entry mmBERT/Gemma-family BPE — Replace(" "→"▁") normalizer,
 * Metaspace pre-tokenizer (prepend when the replaced text does not already start with
 * "▁", then split on "▁" merged-with-next), byte_fallback for chars outside the vocab,
 * `fuse_unk` (no unk path is reachable in the laya states — a missing byte token fails
 * loudly). The card's "same vocab" claim measured FALSE (hash-proven 2026-10-06); the
 * law stays "port the real checkpoint tokenizer", proven per-vector by the Gate 8C.2
 * reference fixture. The ByteLevel path is untouched (Gate 8.1 goldens still apply).
 *
 * Pipeline (mirrors HF `tokenizers` `Tokenizer.encode(text, add_special_tokens=false)`):
 *  1. split raw input on non-normalized added tokens (leftmost-longest, honoring
 *     `lstrip`/`rstrip` whitespace swallowing);
 *  2. normalize the residual chunks (family: NFC | Replace(" "→"▁")), split those on
 *     normalized added tokens;
 *  3. pre-tokenize the remaining text (family: GPT-2 regex + byte alphabet |
 *     Metaspace prepend/split on "▁");
 *  4. BPE per piece: repeatedly merge the lowest-ranked adjacent pair.
 *
 * Pure JVM (java.text.Normalizer + java.util.regex) — no Android types (:core boundary).
 */
class Tokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val tokenById: Map<Int, String>,
    private val merges: Map<Pair<String, String>, Int>,
    private val addedTokens: List<AddedToken>,
    private val family: Family,
) {

    /** The two supported checkpoint tokenizer families (see [fromJson]). */
    private enum class Family {
        /** convaiinnovations/laya (421M): NFC + ByteLevel (GPT-2) + byte-alphabet BPE. */
        BYTE_LEVEL,

        /** ichenney/laya-browser-v32b: Replace(" "→"▁") + Metaspace + byte-fallback BPE. */
        METASPACE,
    }

    /** One entry of `added_tokens`: matched before the BPE model sees the text. */
    private data class AddedToken(
        val id: Int,
        val content: String,
        /** true → matched on the normalized text; false → matched on the raw text. */
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

    /** Token strings (vocab forms, e.g. `"Ġpie"` / `"▁hello"`) — the HF `encoding.tokens`. */
    fun tokenize(text: String): List<String> = tokenIds(text).map { id ->
        tokenById[id] ?: error("tokenizer produced unknown token id $id")
    }

    /** Number of tokens [text] encodes to — the state-text budget counter (vision 03 §4). */
    fun count(text: String): Int = tokenIds(text).size

    // -- pipeline stages -----------------------------------------------------------

    /** Normalizes a residual chunk, then splits on normalized added tokens. */
    private fun encodeResidual(chunk: String): List<Int> {
        val text = when (family) {
            Family.BYTE_LEVEL -> Normalizer.normalize(chunk, Normalizer.Form.NFC)
            Family.METASPACE -> chunk.replace(' ', METASPACE_CHAR)
        }
        val out = mutableListOf<Int>()
        var chunkStart = 0
        var i = 0
        while (i < text.length) {
            val match = longestAddedToken(text, i, normalized = true)
            if (match != null) {
                if (i > chunkStart) out += preTokenize(text.substring(chunkStart, i))
                out += match.token.id
                i += match.span
                chunkStart = i
            } else {
                i++
            }
        }
        if (chunkStart < text.length) out += preTokenize(text.substring(chunkStart))
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

    /** Family pre-tokenization + per-piece BPE. */
    private fun preTokenize(text: String): List<Int> = when (family) {
        Family.BYTE_LEVEL -> {
            val out = mutableListOf<Int>()
            for (match in GPT2_REGEX.findAll(text)) {
                out += bpe(byteLevelEncode(match.value))
            }
            out
        }
        Family.METASPACE -> {
            // Upstream Metaspace (split=true): prepend "▁" ONLY when the replaced text
            // does not already start with it (the Replace normalizer already turned every
            // space into "▁", so a leading space needs no second prefix — probe-verified
            // against the real tokenizer, 2026-10-06), then split on "▁" with the
            // delimiter merged into the NEXT piece ("▁" prefixes every piece).
            val prepared = if (text.startsWith(METASPACE_CHAR)) text else METASPACE_CHAR.toString() + text
            val out = mutableListOf<Int>()
            var i = 0
            while (i < prepared.length) {
                if (prepared[i] == METASPACE_CHAR) {
                    var j = i + 1
                    while (j < prepared.length && prepared[j] != METASPACE_CHAR) j++
                    out += bpe(prepared.substring(i, j).map { it.toString() })
                    i = j
                } else {
                    // unreachable after the prepend (prepared always starts with "▁");
                    // kept loud rather than silent
                    throw IllegalStateException("metaspace piece does not start at a prefix char: ${prepared.substring(i)}")
                }
            }
            out
        }
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
        val initial = when (family) {
            Family.BYTE_LEVEL -> pieceChars
            Family.METASPACE -> pieceChars.flatMap { char -> vocab[char]?.let { listOf(char) } ?: byteFallbackSymbols(char) }
        }
        var word = initial
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

    /**
     * byte_fallback: a char outside the vocab becomes its UTF-8 bytes as `<0xNN>` tokens
     * (hex, uppercase, two digits). A byte token missing from the vocab fails loudly —
     * silently emitting `<unk>` would poison the marker positions.
     */
    private fun byteFallbackSymbols(symbol: String): List<String> =
        symbol.toByteArray(Charsets.UTF_8).map { b ->
            val token = String.format("<0x%02X>", b)
            require(vocab.containsKey(token)) {
                "byte-fallback token \"$token\" (from '$symbol') missing from the vocab"
            }
            token
        }

    companion object {

        /** The Metaspace replacement char (U+2581 LOWER ONE EIGHTH BLOCK). */
        private const val METASPACE_CHAR = '▁'

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

        /** The streamed-parse configuration (same tolerance, no DOM). */
        private val jsonWide = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Classpath copy of the checkpoint's tokenizer.json (see laya/PROVENANCE.txt). */
        fun loadDefault(): Tokenizer = fromJsonResource("/laya/tokenizer.json")

        fun fromJsonResource(resource: String): Tokenizer {
            val text = Tokenizer::class.java.getResourceAsStream(resource)
                ?.readBytes()?.toString(Charsets.UTF_8)
                ?: error("tokenizer resource not found on classpath: $resource")
            return fromJson(text)
        }

        /**
         * A tokenizer.json from disk — the v32b asset (34 MB, gitignored, hash-pinned in
         * assets/laya/PROVENANCE.txt) is loaded this way, never committed to the classpath.
         * Streams through the lean DTO (see [fromFile]) — the DOM path would materialize
         * ~700 MB of JSON elements for the 34 MB v32b file.
         */
        fun fromJsonFile(path: java.nio.file.Path): Tokenizer = fromFile(path)

        /** Strict about the parts laya actually uses; unknown extra keys are tolerated. */
        fun fromJson(text: String): Tokenizer {
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("tokenizer.json is not valid JSON: ${e.message}", e)
            }
            return fromRoot(root)
        }

        /**
         * Parses a tokenizer.json FROM DISK without the kotlinx JSON DOM: the v32b file
         * is 34 MB and the element tree measures ~10x the text in heap (~700 MB — it
         * breached the emulator's low-memory watermark next to the ~1 GB ONNX session,
         * 2026-10-06). The streamed DTO builds only the structures the port keeps
         * (vocab map + merges + added tokens, ~100 MB); every declared family fact is
         * still validated loudly. `fromJson` (DOM) stays for the small classpath
         * resource and the Phase-2 tests.
         */
        fun fromFile(path: java.nio.file.Path): Tokenizer {
            val dto = jsonWide.decodeFromStream<TokenizerDto>(java.nio.file.Files.newInputStream(path))
            return fromDto(dto)
        }

        // -- shared extraction (DOM or DTO -> the port's structures) --------------------

        private fun fromRoot(root: JsonObject): Tokenizer {
            val normalizer = root["normalizer"] as? JsonObject
            val normalizerType = normalizer?.get("type")?.jsonPrimitive?.content
            val pre = root["pre_tokenizer"]?.jsonObject
                ?: throw IllegalArgumentException("tokenizer.json has no pre_tokenizer")
            val preType = pre["type"]?.jsonPrimitive?.content
            val preSplit = (pre["split"] as? JsonObject)?.let { obj ->
                obj["split"]?.jsonPrimitive?.content?.toBoolean()
            } ?: (pre["split"]?.jsonPrimitive?.content?.toBoolean())
            val normalizerContent = normalizer?.get("content")?.jsonPrimitive?.content
            val model = root["model"]?.jsonObject
                ?: throw IllegalArgumentException("tokenizer.json has no model")
            val modelType = model["type"]?.jsonPrimitive?.content
            val vocabJson = model["vocab"] as? JsonObject
                ?: throw IllegalArgumentException("BPE model has no vocab")
            val mergesJson = model["merges"] as? JsonArray
                ?: throw IllegalArgumentException("BPE model has no merges")
            val addedJson = root["added_tokens"] as? JsonArray ?: JsonArray(emptyList())
            return build(
                family = detectFamily(normalizerType, normalizerContent, preType, preSplit),
                modelType = modelType,
                vocab = {
                    val map = LinkedHashMap<String, Int>(vocabJson.size * 2)
                    for ((token, id) in vocabJson) {
                        require(map.put(token, id.jsonPrimitive.content.toInt()) == null) {
                            "duplicate vocab entry: \"$token\""
                        }
                    }
                    map
                },
                merges = {
                    val map = HashMap<Pair<String, String>, Int>(mergesJson.size * 2)
                    for ((rank, element) in mergesJson.withIndex()) {
                        val pair = when (element) {
                            is JsonArray -> element.map { it.jsonPrimitive.content }
                            is JsonPrimitive -> element.content.split(" ")
                            else -> throw IllegalArgumentException("bad merge entry at rank $rank: $element")
                        }
                        require(pair.size == 2) { "merge at rank $rank is not a pair: $element" }
                        map[pair[0] to pair[1]] = rank
                    }
                    map
                },
                addedTokens = {
                    addedJson.map { element ->
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
                },
            )
        }

        private fun fromDto(dto: TokenizerDto): Tokenizer = build(
            family = detectFamily(
                normalizerType = dto.normalizer?.type,
                normalizerContent = dto.normalizer?.content,
                preType = dto.preTokenizer?.type,
                preSplit = dto.preTokenizer?.split,
            ),
            modelType = dto.model?.type,
            vocab = {
                val map = LinkedHashMap<String, Int>(dto.model?.vocab?.size ?: 0)
                for ((token, id) in dto.model?.vocab ?: emptyMap()) {
                    require(map.put(token, id.toInt()) == null) { "duplicate vocab entry: \"$token\"" }
                }
                map
            },
            merges = {
                val map = HashMap<Pair<String, String>, Int>((dto.model?.merges?.size ?: 0) * 2)
                for ((rank, pair) in (dto.model?.merges ?: emptyList()).withIndex()) {
                    require(pair.size == 2) { "merge at rank $rank is not a pair: $pair" }
                    map[pair[0] to pair[1]] = rank
                }
                map
            },
            addedTokens = {
                dto.addedTokens.map { token ->
                    require(!token.singleWord) {
                        "added token \"${token.content}\" uses single_word=true, which this port does not implement"
                    }
                    AddedToken(
                        id = token.id,
                        content = token.content,
                        normalized = token.normalized,
                        lstrip = token.lstrip,
                        rstrip = token.rstrip,
                    )
                }
            },
        )

        /** The family declares itself through its normalizer + pre_tokenizer pair. */
        private fun detectFamily(
            normalizerType: String?,
            normalizerContent: String?,
            preType: String?,
            preSplit: Boolean?,
        ): Family = when {
            normalizerType == "NFC" && preType == "ByteLevel" -> Family.BYTE_LEVEL
            normalizerType == "Replace" && preType == "Metaspace" -> {
                require(normalizerContent == METASPACE_CHAR.toString()) {
                    "unsupported Replace normalizer content \"$normalizerContent\" — expected the Metaspace char"
                }
                require(preSplit == true) {
                    "unsupported Metaspace split=false — this port implements the split=true family only"
                }
                Family.METASPACE
            }
            else -> throw IllegalArgumentException(
                "unsupported tokenizer family (normalizer \"$normalizerType\" + pre_tokenizer " +
                    "\"$preType\") — this port implements the laya NFC+ByteLevel and " +
                    "Replace+Metaspace families only",
            )
        }

        /** Assembles the immutable tokenizer once the lazy structures are materialized. */
        private fun build(
            family: Family,
            modelType: String?,
            vocab: () -> LinkedHashMap<String, Int>,
            merges: () -> HashMap<Pair<String, String>, Int>,
            addedTokens: () -> List<AddedToken>,
        ): Tokenizer {
            require(modelType == "BPE") {
                "unsupported model $modelType — this port implements BPE only"
            }
            val vocabMap = vocab()
            val mergesMap = merges()
            val added = addedTokens()
            val tokenById = HashMap<Int, String>(vocabMap.size + added.size)
            for ((token, id) in vocabMap) tokenById[id] = token
            for (token in added) tokenById[token.id] = token.content
            return Tokenizer(vocabMap, tokenById, mergesMap, added, family)
        }
    }

}

/**
 * The streamed tokenizer.json DTO (file-scope so the 34 MB v32b parse never builds the
 * kotlinx element tree): only the fields the port reads — everything else is skipped by
 * [Tokenizer.jsonWide]'s ignoreUnknownKeys. Vocab ids are Long in the wild (they fit
 * Int, but the JSON numbers are unsigned elsewhere) and narrowed at the vocab map.
 */
@Serializable
private data class TokenizerDto(
    val normalizer: NormalizerDto? = null,
    @SerialName("pre_tokenizer") val preTokenizer: PreTokenizerDto? = null,
    val model: ModelDto? = null,
    @SerialName("added_tokens") val addedTokens: List<AddedTokenDto> = emptyList(),
)

@Serializable
private data class NormalizerDto(
    val type: String? = null,
    val content: String? = null,
)

@Serializable
private data class PreTokenizerDto(
    val type: String? = null,
    val split: Boolean? = null,
)

@Serializable
private data class ModelDto(
    val type: String? = null,
    val vocab: Map<String, Long> = emptyMap(),
    val merges: List<List<String>> = emptyList(),
)

@Serializable
private data class AddedTokenDto(
    val id: Int,
    val content: String,
    val normalized: Boolean = true,
    val lstrip: Boolean = false,
    val rstrip: Boolean = false,
    @SerialName("single_word") val singleWord: Boolean = false,
)
