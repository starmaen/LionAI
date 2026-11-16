package com.lion.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * رموز مهنية حديثة بدل إيموجي الحالة التقليدية.
 *
 * طبقة عرض فقط: لا تغيّر النصوص المخزّنة ولا ما يقرؤه المنطق (كاشفات ✅/❌ في الوكيل تبقى تعمل)،
 * وإنما تُحوِّل رموز الحالة الملوّنة الثقيلة إلى رموز أحادية اللون متناسقة وتُلوّنها دلالياً عند العرض.
 * وتحتفظ بالإيموجي التعبيرية (المشاعر والمواقف) كما هي؛ دليلها المنتقى في [EXPRESSION_GUIDE].
 */
internal object Glyphs {
    private const val TEXT = "\uFE0E" // يفرض العرض النصي أحادي اللون

    const val OK = "✓"
    const val FAIL = "✕"
    const val WARN = "⚠$TEXT"
    const val BLOCKED = "⊘"
    const val INFO = "◆"
    const val RUNNING = "◌"
    const val PAUSE = "❚❚"
    const val STOP = "■"

    /** من الأطول إلى الأقصر حتى لا يلتهم المفرد المتسلسل (✔️ قبل ✔). */
    private val MAP: List<Pair<String, String>> = listOf(
        "✔\uFE0F" to OK, "☑\uFE0F" to OK, "✅" to OK, "✔" to OK,
        "✖\uFE0F" to FAIL, "❌" to FAIL, "❎" to FAIL,
        "⚠\uFE0F" to WARN, "⛔" to BLOCKED, "🚫" to BLOCKED,
        "💬" to INFO, "ℹ\uFE0F" to "ⓘ",
        "🛠\uFE0F" to "⚙$TEXT", "⚙\uFE0F" to "⚙$TEXT", "🔧" to "⚙$TEXT",
        "⏸\uFE0F" to PAUSE, "⏸" to PAUSE, "▶\uFE0F" to "▶$TEXT",
        "⏹\uFE0F" to STOP, "⏹" to STOP,
        "⚡" to "↯", "🔑" to "⚿", "🔗" to "↗",
        "📄" to "▤", "📁" to "▣", "📂" to "▣", "📦" to "▨",
        "➕" to "+", "⭐" to "★", "⚪" to "○",
        "⏳" to RUNNING, "🔄" to "↻", "♻\uFE0F" to "↻",
        "📌" to "◈", "📝" to "✎", "🎯" to "◎", "🏁" to "⚑", "🔍" to "⌕"
    )

    /** يحوّل رموز الحالة التقليدية إلى الرموز المهنية. نص بلا رموز يعود كما هو بلا تكلفة. */
    fun modernize(text: String): String {
        if (text.isEmpty() || text.none { it.code >= 0x2300 }) return text
        var out = text
        for ((from, to) in MAP) if (out.contains(from)) out = out.replace(from, to)
        return out
    }

    private val TINT: Map<Char, Color> = mapOf(
        '✓' to Color(0xFF4CAF7A),
        '✕' to Color(0xFFE5534B),
        '⊘' to Color(0xFFE5534B),
        '⚠' to Color(0xFFFFB800),
        '◆' to Color(0xFF64B5F6),
        '↯' to Color(0xFFFFB800),
        '★' to Color(0xFFFFC94D),
        '↻' to Color(0xFF64B5F6),
        '⚑' to Color(0xFF4CAF7A)
    )

    /** يلوّن رموز الحالة دلالياً (أخضر نجاح، أحمر فشل، كهرماني تحذير) فوق تنسيق Markdown القائم. */
    fun tint(src: AnnotatedString): AnnotatedString {
        if (src.text.none { it in TINT }) return src
        return buildAnnotatedString {
            append(src)
            src.text.forEachIndexed { i, ch ->
                TINT[ch]?.let { addStyle(SpanStyle(color = it, fontWeight = FontWeight.Bold), i, i + 1) }
            }
        }
    }

    /**
     * دليل التعبير المنتقى للمزودين: مجموعة عصرية ومهنية للمشاعر والمواقف والحالات، باستخدام مقتصد.
     * يُلحق بتعليمات النظام لتبقى ردود كل المزودين متسقة بصرياً.
     */
    const val EXPRESSION_GUIDE =
        "\n\nدليل الرموز التعبيرية (اختياري ومقتصد): استعمل رمزاً واحداً أو اثنين كحد أقصى في الرد، عند بداية فقرة أو نهايتها، " +
            "وفق المعنى فقط، ولا تستعملها داخل الكود أو الجداول أو رسائل الأخطاء التقنية أو المواضيع الحزينة والجادة. " +
            "المشاعر: 🙂 ودّ، 😊 ترحيب وارتياح، 😄 فرح، 🤔 تفكير، 😮 دهشة، 😕 حيرة، 😔 تعاطف، 🙏 شكر أو رجاء، 👏 تهنئة، 💪 تشجيع، 🎉 إنجاز كبير، 🤝 اتفاق، 👍 موافقة. " +
            "الحالات: ✅ تم ونجح، ⚠️ تحذير، ❗ مهم، ❓ سؤال، 💡 فكرة أو نصيحة، 🔍 فحص وبحث، 🧪 اختبار، 🛠️ إصلاح، 🚀 إطلاق، ⏳ جارٍ العمل، 🔄 تحديث، " +
            "🔒 أمان، 🔓 صلاحية، 📌 تذكير، 📝 ملاحظة، 📊 بيانات، 📦 بناء وحزمة، 🧩 مكوّن، ⚙️ إعداد، 🌐 شبكة، ☁️ سحابة، 🔔 تنبيه، 🕒 وقت، 📅 موعد، 🏁 اكتمال، 🎯 هدف، 🔥 أولوية، 🛑 إيقاف."
}
