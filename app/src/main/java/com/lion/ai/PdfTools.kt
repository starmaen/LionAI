package com.lion.ai

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** أدوات PDF للوكيل: معلومات، قراءة نص، دمج، تقسيم، وإنشاء من نص. كلها داخل مساحة العمل. */
object PdfTools {
    private const val MAX_PAGES_READ = 120

    private fun init(context: Context) = PDFBoxResourceLoader.init(context.applicationContext)

    private fun existing(context: Context, path: String): File {
        val file = FileTools.file(context, path)
        require(file.isFile) { "غير موجود: $path" }
        return file
    }

    private fun output(context: Context, path: String): File {
        val clean = path.trim().ifBlank { "deliverables/output.pdf" }
        val withExt = if (clean.lowercase().endsWith(".pdf")) clean else "$clean.pdf"
        val file = FileTools.file(context, withExt)
        file.parentFile?.mkdirs()
        return file
    }

    /** "1-3,5" → [1,2,3,5] ضمن حدود عدد الصفحات. */
    internal fun parsePages(spec: String, total: Int): List<Int> {
        val pages = linkedSetOf<Int>()
        spec.split(',', '،', ' ').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val range = part.split('-')
            val from = range.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val to = (range.getOrNull(1)?.toIntOrNull() ?: from)
            for (p in minOf(from, to)..maxOf(from, to)) if (p in 1..total) pages.add(p)
        }
        return pages.toList()
    }

    fun info(context: Context, path: String): String {
        init(context)
        val file = existing(context, path)
        return PDDocument.load(file).use { doc ->
            val title = runCatching { doc.documentInformation?.title }.getOrNull().orEmpty()
            "📄 ${FileTools.relative(context, file)}: ${doc.numberOfPages} صفحة، ${file.length()} بايت" +
                (if (title.isNotBlank()) "، العنوان: $title" else "") +
                (if (doc.isEncrypted) "، ⚠️ مشفّر" else "")
        }
    }

    fun read(context: Context, path: String, pages: String, maxChars: Int): String {
        init(context)
        val file = existing(context, path)
        return PDDocument.load(file).use { doc ->
            val total = doc.numberOfPages
            val wanted = if (pages.isBlank()) (1..minOf(total, MAX_PAGES_READ)).toList() else parsePages(pages, total)
            require(wanted.isNotEmpty()) { "نطاق الصفحات خارج 1..$total" }
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val out = StringBuilder()
            for (p in wanted) {
                stripper.startPage = p
                stripper.endPage = p
                val text = stripper.getText(doc).trim()
                if (text.isNotEmpty()) out.append("--- صفحة ").append(p).append(" ---\n").append(text).append("\n\n")
                if (out.length >= maxChars) break
            }
            val body = out.toString().trim()
            if (body.isBlank()) "📄 لا نص قابل للاستخراج (قد يكون PDF ممسوحاً ضوئياً). عدد الصفحات: $total"
            else "📄 ${FileTools.relative(context, file)} ($total صفحة)\n" + body.take(maxChars) +
                if (body.length > maxChars) "\n…[مقتطع]" else ""
        }
    }

    fun merge(context: Context, paths: List<String>, target: String): String {
        require(paths.size >= 2) { "أرسل ملفين PDF على الأقل في paths" }
        init(context)
        val out = output(context, target)
        val sources = mutableListOf<PDDocument>()
        val merged = PDDocument()
        try {
            paths.forEach { sources.add(PDDocument.load(existing(context, it))) }
            sources.forEach { src ->
                for (i in 0 until src.numberOfPages) merged.importPage(src.getPage(i))
            }
            merged.save(out)
            return "✅ دُمج ${paths.size} ملفات في ${FileTools.relative(context, out)} (${merged.numberOfPages} صفحة)"
        } finally {
            runCatching { merged.close() }
            sources.forEach { runCatching { it.close() } }
        }
    }

    fun split(context: Context, path: String, pages: String, target: String): String {
        init(context)
        val src = existing(context, path)
        val out = output(context, target)
        PDDocument.load(src).use { doc ->
            val wanted = parsePages(pages, doc.numberOfPages)
            require(wanted.isNotEmpty()) { "نطاق الصفحات غير صالح. عدد الصفحات: ${doc.numberOfPages}" }
            val result = PDDocument()
            try {
                wanted.forEach { result.importPage(doc.getPage(it - 1)) }
                result.save(out)
            } finally {
                runCatching { result.close() }
            }
            return "✅ استُخرجت ${wanted.size} صفحة إلى ${FileTools.relative(context, out)}"
        }
    }

    /** إنشاء PDF من نص عبر محرك Android (يدعم العربية والاتجاه من اليمين). */
    fun create(context: Context, text: String, target: String): String {
        require(text.isNotBlank()) { "النص فارغ" }
        val out = output(context, target)
        val pageW = 595
        val pageH = 842
        val margin = 40
        val width = pageW - margin * 2
        val usableH = pageH - margin * 2
        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = 12f
            isAntiAlias = true
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1f)
            .build()
        val doc = PdfDocument()
        try {
            var line = 0
            var pageNo = 1
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= usableH) end++
                if (end == line) end = line + 1
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                val canvas = page.canvas
                canvas.save()
                canvas.translate(margin.toFloat(), margin - top.toFloat())
                canvas.clipRect(0f, top.toFloat(), width.toFloat(), layout.getLineBottom(end - 1).toFloat())
                layout.draw(canvas)
                canvas.restore()
                doc.finishPage(page)
                line = end
                pageNo++
            }
            out.outputStream().use { doc.writeTo(it) }
            return "✅ أُنشئ ${FileTools.relative(context, out)} (${pageNo - 1} صفحة)"
        } finally {
            doc.close()
        }
    }
}
