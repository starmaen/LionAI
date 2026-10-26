package com.lion.ai

import kotlin.math.max
import kotlin.math.min

/**
 * استرجاع محلي بسيط للملفات الطويلة: يقسّم النص إلى مقاطع متداخلة،
 * يرتّبها حسب صلة كلمات السؤال، ثم يرسل أفضل المقاطع ضمن ميزانية أحرف.
 * لا يرسل الملف كاملاً عندما يتجاوز الحد، ولا يحتاج أي خدمة خارجية.
 */
object LocalRag {
    private const val CHUNK_SIZE = 1_600
    private const val CHUNK_OVERLAP = 180
    private const val DEFAULT_MAX_CHARS = 12_000

    private val SUMMARIZE_WORDS = Regex(
        "(لخص|تلخيص|ملخص|راجع|مراجعه|حلل|تحليل|استخرج|summary|summarize|review|analyze|extract)",
        RegexOption.IGNORE_CASE
    )

    private val STOP_WORDS = setOf(
        "في", "من", "علي", "على", "الي", "الى", "إلى", "عن", "ان", "أن", "إن", "هو", "هي",
        "هذا", "هذه", "التي", "الذي", "كان", "كانت", "تم", "مع", "كل", "بعد", "قبل", "بين",
        "او", "أو", "ثم", "لا", "ما", "قد", "كما", "عند", "حتى", "هناك", "يمكن", "يجب",
        "ذلك", "تلك", "هؤلاء", "الذين", "نحن", "انت", "أنت", "انا", "أنا", "له", "لها",
        "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "has",
        "have", "had", "not", "but", "you", "your", "his", "her", "its", "their", "they",
        "them", "will", "would", "can", "could", "should", "about", "into", "than", "then",
        "there", "here", "which", "who", "what", "when", "where", "why", "how", "also",
        "more", "most", "some", "such", "only", "very", "just", "over", "under", "between",
        "after", "before", "during", "without", "within", "these", "those", "been", "being",
        "of", "to", "in", "on", "at", "by", "as", "is", "it", "be", "or", "an", "a"
    )

    /** يعيد النص كاملاً إن كان قصيراً، وإلا أفضل المقاطع المرتبة حسب موقعها الأصلي. */
    fun select(document: String, query: String, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val doc = document.trim()
        if (doc.length <= maxChars) return doc
        val chunks = split(doc)
        if (chunks.isEmpty()) return doc.take(maxChars)
        if (SUMMARIZE_WORDS.containsMatchIn(TextNorm.forMatch(query))) {
            return spread(chunks, maxChars)
        }

        val queryWords = words(query)
        if (queryWords.isEmpty()) return spread(chunks, maxChars)

        val scored = chunks.indices
            .map { it to score(chunks[it], queryWords) }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })

        val picked = mutableListOf<Int>()
        var budget = maxChars
        fun add(index: Int) {
            if (index in picked || index !in chunks.indices) return
            val length = chunks[index].length
            if (length <= budget) {
                picked += index
                budget -= length
            }
        }

        add(0)
        for ((index, relevance) in scored) {
            if (relevance <= 0) break
            add(index)
            if (budget <= 0) break
        }
        add(chunks.lastIndex)
        if (picked.isEmpty()) return doc.take(maxChars)
        return picked.sorted()
            .joinToString("\n\n…\n\n") { chunks[it] }
            .take(maxChars)
    }

    private fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = min(start + CHUNK_SIZE, text.length)
            if (end < text.length) {
                val boundary = boundaryBefore(text, start, end)
                if (boundary > start + CHUNK_SIZE / 2) end = boundary
            }
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) chunks += part
            if (end >= text.length) break
            start = max(end - CHUNK_OVERLAP, start + 1)
        }
        return chunks
    }

    private fun boundaryBefore(text: String, start: Int, end: Int): Int {
        val minBoundary = start + CHUNK_SIZE / 2
        for (i in end downTo minBoundary) {
            val c = text[i - 1]
            if (c == '\n' || c == '.' || c == '؟' || c == '!' || c == ';' || c == '،') return i
        }
        return end
    }

    private fun spread(chunks: List<String>, maxChars: Int): String {
        if (chunks.isEmpty()) return ""
        val slots = max(1, maxChars / 900)
        val perChunk = max(1, maxChars / slots)
        val pieces = mutableListOf<String>()
        val seen = mutableSetOf<Int>()
        for (slot in 0 until slots) {
            val index = ((slot.toLong() * chunks.size) / slots).toInt()
                .coerceIn(0, chunks.lastIndex)
            if (!seen.add(index)) continue
            val piece = chunks[index].take(perChunk).trim()
            if (piece.isNotEmpty()) pieces += piece
        }
        if (pieces.isEmpty()) return chunks.first().take(maxChars)
        return pieces.joinToString("\n\n…\n\n").take(maxChars)
    }

    private fun score(chunk: String, queryWords: Set<String>): Int {
        val chunkWords = words(chunk)
        if (chunkWords.isEmpty()) return 0
        var hits = 0
        for (word in queryWords) {
            if (word in chunkWords) hits++
        }
        return hits
    }

    private fun words(text: String): Set<String> =
        TextNorm.forMatch(text)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
}
