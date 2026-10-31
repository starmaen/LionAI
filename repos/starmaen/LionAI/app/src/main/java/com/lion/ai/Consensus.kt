package com.lion.ai

import kotlin.math.roundToInt

/** مقارنة محلية بين إجابتين لقياس درجة الاتفاق دون أي طلب شبكة إضافي. */
object Consensus {
    private val STOP_WORDS = setOf(
        "في", "من", "علي", "على", "الي", "الى", "إلى", "عن", "ان", "أن", "إن", "هو", "هي", "هذا", "هذه",
        "التي", "الذي", "كان", "كانت", "تم", "مع", "كل", "بعد", "قبل", "بين", "او", "أو",
        "ثم", "لا", "ما", "قد", "كما", "عند", "حتى", "هناك", "يمكن", "يجب", "ذلك", "تلك",
        "هؤلاء", "الذين", "نحن", "انت", "أنت", "انا", "أنا", "له", "لها", "لديه", "لديها",
        "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "has",
        "have", "had", "not", "but", "you", "your", "his", "her", "its", "their", "they",
        "them", "will", "would", "can", "could", "should", "about", "into", "than", "then",
        "there", "here", "which", "who", "what", "when", "where", "why", "how", "also",
        "more", "most", "some", "such", "only", "very", "just", "over", "under", "between",
        "after", "before", "during", "without", "within", "these", "those", "been", "being",
        "of", "to", "in", "on", "at", "by", "as", "is", "it", "be", "or", "an", "a"
    )

    /** 0 = لا اتفاق، 100 = تطابق تام في الكلمات المحتوى. */
    fun compare(first: String, second: String): Int {
        val a = tokens(first)
        val b = tokens(second)
        if (a.isEmpty() || b.isEmpty()) return 0
        val common = a.count { it in b }
        return ((common.toDouble() / minOf(a.size, b.size)) * 100).roundToInt().coerceIn(0, 100)
    }

    internal fun tokens(text: String): Set<String> =
        TextNorm.forMatch(text)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

    fun label(percent: Int): String = when {
        percent >= 70 -> "اتفاق قوي"
        percent >= 40 -> "اتفاق جزئي"
        else -> "اختلاف"
    }
}
