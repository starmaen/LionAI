package com.lion.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * أداة قراءة وتحليل الصور للوكيل (مستقلة عن محرر الصور المدمج).
 *
 * - `image_info`: الصيغة الحقيقية (من البايتات لا من الامتداد)، الأبعاد، الحجم، اتجاه EXIF، الكاميرا والتاريخ.
 * - `image_read`: يقرأ الصورة أو صفحات PDF الممسوحة ويعيد **نصاً** يستفيد منه أي مزود حتى لو كان لا يرى الصور:
 *     1) طبقة الرؤية: موديل رؤية من مزودات المستخدم (العربية + وصف + جداول + واجهات) مع تحوّل تلقائي عند الفشل.
 *     2) طبقة OCR المحلية (بلا مفتاح وبلا إنترنت): نص لاتيني وأرقام وأكواد وسجلات، تعمل كاحتياط دائم.
 *
 * ملاحظة صدق: OCR المحلي لا يقرأ العربية؛ العربية تحتاج مزود رؤية، وتُذكر هذه الحقيقة في النتيجة عند الحاجة.
 * لا تُذكر أسماء المزودات في أي نتيجة (قاعدة التطبيق).
 */
object ImageTools {
    enum class Kind(val label: String) {
        JPEG("JPEG"), PNG("PNG"), GIF("GIF"), WEBP("WebP"), BMP("BMP"), HEIF("HEIC/HEIF"), AVIF("AVIF"), PDF("PDF"), SVG("SVG"), UNKNOWN("غير معروف")
    }

    private const val MAX_IMAGES = 10
    private const val MAX_FILE_BYTES = 40L * 1024 * 1024
    private const val SEND_MAX_SIDE = 1800
    private const val SEND_MAX_BYTES = 3_500_000
    private const val PDF_RENDER_WIDTH = 1500

    val MODES = setOf("auto", "ocr", "describe", "ui", "table", "error")

    /** تعليمات كل وضع للموديل الرؤيوي. */
    internal fun instructionFor(mode: String): String? = when (mode) {
        "ocr" -> "استخرج كل النص الظاهر في الصور حرفياً وبنفس لغته واتجاهه وترتيبه وفواصل أسطره (OCR) دون تلخيص أو تصحيح. " +
            "حافظ على الأرقام والرموز والأكواد كما هي. ضع [غير مقروء] مكان الجزء الذي لا يُقرأ. لا تضف وصفاً للصورة."
        "describe" -> "صف كل صورة بدقة: المشهد والعناصر والأشخاص والأشياء والألوان والتخطيط، ثم أي نص ظاهر باختصار. لا تخمّن ما لا يُرى."
        "ui" -> "هذه لقطة شاشة لتطبيق أو موقع. اذكر: اسم الشاشة/الصفحة، عناصر الواجهة الرئيسية بترتيبها (أزرار، حقول، قوائم، تبويبات) بنصوصها الحرفية، " +
            "الحالة الظاهرة (تحميل، تعطيل، تحديد)، أي رسالة خطأ أو تنبيه حرفياً، وأي مشكلة تصميم واضحة (تداخل، قص، تباين ضعيف)."
        "table" -> "استخرج كل جدول أو مخطط ظاهر: الجداول بصيغة Markdown بنفس ترتيب الأعمدة والصفوف والأرقام حرفياً؛ " +
            "المخططات: نوعها، المحاور، الوحدات، وكل القيم المقروءة. اذكر أي خلية غير مقروءة."
        "error" -> "هذه لقطة لخطأ أو سجل أو طرفية أو شيفرة. انسخ نص الخطأ/السجل/الشيفرة حرفياً بنفس الأسطر والمسافات ورقم السطر والملف إن ظهرا، " +
            "ثم لخّص بسطر واحد ما الذي يفشل وأين. لا تقترح حلولاً."
        else -> null
    }

    // ───────────────────────── كشف الصيغة ─────────────────────────

    internal fun sniff(head: ByteArray): Kind {
        fun b(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        fun ascii(from: Int, s: String) = s.indices.all { b(from + it) == s[it].code }
        return when {
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> Kind.JPEG
            b(0) == 0x89 && ascii(1, "PNG") -> Kind.PNG
            ascii(0, "GIF8") -> Kind.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Kind.WEBP
            ascii(0, "BM") -> Kind.BMP
            ascii(0, "%PDF") -> Kind.PDF
            ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis")) -> Kind.AVIF
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "msf1") || ascii(8, "heif") || ascii(8, "hevc")) -> Kind.HEIF
            String(head, Charsets.UTF_8).trimStart().let { it.startsWith("<svg") || (it.startsWith("<?xml") && it.contains("<svg")) } -> Kind.SVG
            else -> Kind.UNKNOWN
        }
    }

    private fun kindOf(file: File): Kind {
        val head = ByteArray(512)
        val n = file.inputStream().use { it.read(head) }
        return sniff(head.copyOf(maxOf(n, 0)))
    }

    internal fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        var side = maxOf(width, height)
        while (side / 2 >= maxSide) { sample *= 2; side /= 2 }
        return sample
    }

    /** "1-3,5" → قائمة صفحات (من 1) ضمن العدد الكلي وبحد أقصى. */
    internal fun pageList(spec: String, total: Int, limit: Int): List<Int> {
        val pages = linkedSetOf<Int>()
        spec.ifBlank { "1" }.split(',', '،', ' ').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val r = part.split('-')
            val from = r.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val to = r.getOrNull(1)?.toIntOrNull() ?: from
            for (p in minOf(from, to)..maxOf(from, to)) if (p in 1..total) pages.add(p)
        }
        return pages.toList().take(limit)
    }

    // ───────────────────────── فك الترميز ─────────────────────────

    internal fun extensionsOf(kind: Kind): Set<String> = when (kind) {
        Kind.JPEG -> setOf("jpg", "jpeg", "jpe", "jfif")
        Kind.PNG -> setOf("png")
        Kind.GIF -> setOf("gif")
        Kind.WEBP -> setOf("webp")
        Kind.BMP -> setOf("bmp")
        Kind.HEIF -> setOf("heic", "heif")
        Kind.AVIF -> setOf("avif")
        Kind.PDF -> setOf("pdf")
        Kind.SVG -> setOf("svg")
        Kind.UNKNOWN -> emptySet()
    }

    private fun existing(context: Context, path: String): File {
        val file = FileTools.file(context, path)
        require(file.isFile) { "غير موجود: $path" }
        require(file.length() <= MAX_FILE_BYTES) { "الملف كبير جداً (${file.length() / 1024 / 1024}MB) — الحد 40MB" }
        return file
    }

    private fun exifRotation(file: File): Int = runCatching {
        when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun decodeImage(file: File, kind: Kind, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = if (bounds.outWidth > 0) sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide) else 1
        var bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // HEIC/AVIF وغيرها مما لا يفكّه BitmapFactory على بعض الإصدارات
            bmp = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height, maxSide))
                }
            }.getOrNull()
        }
        requireNotNull(bmp) {
            when (kind) {
                Kind.HEIF, Kind.AVIF -> "تعذّر فك ${kind.label} على هذا الإصدار من أندرويد — حوّل الصورة إلى JPEG/PNG أو أعد التقاطها."
                else -> "تعذّر فك الصورة (ملف تالف أو صيغة غير مدعومة)"
            }
        }
        val rot = if (kind == Kind.JPEG) exifRotation(file) else 0
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    private fun renderPdfPages(file: File, spec: String, limit: Int): Pair<List<Pair<Int, Bitmap>>, Int> {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val total = renderer.pageCount
                val pages = pageList(spec, total, limit)
                require(pages.isNotEmpty()) { "الصفحات المطلوبة خارج النطاق (الملف $total صفحة)" }
                val out = pages.map { number ->
                    renderer.openPage(number - 1).use { page ->
                        val scale = PDF_RENDER_WIDTH.toFloat() / page.width
                        val bmp = Bitmap.createBitmap(PDF_RENDER_WIDTH, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        number to bmp
                    }
                }
                return out to total
            }
        }
    }

    /** يحمّل ملفاً كصور (صورة واحدة، أو صفحات PDF). يعيد (تسمية، صورة) لكل عنصر. */
    private fun load(context: Context, path: String, pages: String, limit: Int): List<Pair<String, Bitmap>> {
        val file = existing(context, path)
        val rel = FileTools.relative(context, file)
        return when (val kind = kindOf(file)) {
            Kind.PDF -> renderPdfPages(file, pages, limit).first.map { (n, bmp) -> "$rel (صفحة $n)" to bmp }
            Kind.SVG -> throw IllegalArgumentException("$rel ملف SVG نصي — اقرأه بـ file_read (هو شيفرة وليس صورة نقطية)")
            Kind.UNKNOWN -> throw IllegalArgumentException("$rel ليس صورة أو PDF معروفاً (تحقّق من الملف بـ image_info)")
            else -> listOf(rel to decodeImage(file, kind, SEND_MAX_SIDE))
        }
    }

    // ───────────────────────── الإرسال والـ OCR ─────────────────────────

    private fun flatten(bmp: Bitmap): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
        return out
    }

    private fun toAttachment(name: String, source: Bitmap): ImageAttachment {
        var bmp = flatten(source)
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > SEND_MAX_SIDE) {
            val s = SEND_MAX_SIDE.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        }
        var quality = 88
        var bytes: ByteArray
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            if (bytes.size <= SEND_MAX_BYTES || quality <= 55) break
            quality -= 11
        }
        return ImageAttachment(name, "image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun localOcr(source: Bitmap): String? = runCatching {
        var bmp = flatten(source)
        if (maxOf(bmp.width, bmp.height) < 700) bmp = Bitmap.createScaledBitmap(bmp, bmp.width * 2, bmp.height * 2, true)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 30, TimeUnit.SECONDS)
            result.textBlocks.joinToString("\n") { it.text }.trim().ifBlank { null }
        } finally {
            recognizer.close()
        }
    }.getOrNull()

    /**
     * OCR محلي لمرفقات الصور (بلا مفتاح وبلا إنترنت): يفكّ Base64 ثم يقرأ النص اللاتيني والأرقام.
     * يعيد «الاسم: النص» لكل صورة عليها نص، أو null إن لم يُعثر على شيء. حاجب: يُستدعى من خيط IO.
     */
    fun localOcrAttachments(images: ImageAttachment): String? {
        val out = images.all().mapNotNull { att ->
            val bmp = runCatching {
                val bytes = Base64.decode(att.base64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull() ?: return@mapNotNull null
            localOcr(bmp)?.let { "${att.name}:\n$it" }
        }
        return out.joinToString("\n\n").trim().ifBlank { null }
    }

    // ───────────────────────── الأدوات ─────────────────────────

    fun info(context: Context, path: String): String {
        val file = existing(context, path)
        val rel = FileTools.relative(context, file)
        val kind = kindOf(file)
        val ext = file.extension.lowercase()
        val sb = StringBuilder("$rel — ${kind.label}، ${file.length()} بايت")
        if (kind == Kind.UNKNOWN) return sb.append("\nليست صورة أو PDF معروفاً.").toString()
        if (ext.isNotEmpty() && ext !in extensionsOf(kind)) {
            sb.append("\n⚠ الامتداد .$ext لا يطابق المحتوى الحقيقي (${kind.label}) — يُعتمد المحتوى.")
        }
        when (kind) {
            Kind.PDF -> runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r -> sb.append("\nعدد الصفحات: ${r.pageCount} — استخدم image_read مع pages لقراءة صفحات ممسوحة (للنص الرقمي استخدم pdf_read).") }
                }
            }.onFailure { sb.append("\nتعذّر فتح PDF (مشفّر أو تالف).") }
            Kind.SVG -> sb.append("\nنص SVG — اقرأه بـ file_read.")
            else -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, o)
                if (o.outWidth > 0) sb.append("\nالأبعاد: ${o.outWidth}×${o.outHeight}")
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, i, _ ->
                        sb.append("\nالأبعاد: ${i.size.width}×${i.size.height}")
                        d.setTargetSampleSize(64)
                    }
                }
                if (o.outMimeType != null) sb.append("، النوع: ${o.outMimeType}")
                if (kind == Kind.PNG || kind == Kind.WEBP || kind == Kind.GIF) sb.append("\nقد تحوي شفافية (تُدمج على خلفية بيضاء عند القراءة).")
                runCatching {
                    val ex = ExifInterface(file.absolutePath)
                    val rot = exifRotation(file)
                    if (rot != 0) sb.append("\nاتجاه EXIF: تدوير $rot° (يُصحَّح تلقائياً عند القراءة)")
                    listOf("Make" to "الشركة", "Model" to "الجهاز", "DateTimeOriginal" to "تاريخ الالتقاط").forEach { (tag, label) ->
                        ex.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { sb.append("\n$label: $it") }
                    }
                    if (ex.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null) sb.append("\n⚠ تحوي إحداثيات GPS — انتبه عند المشاركة.")
                }
            }
        }
        return sb.toString()
    }

    /** يقرأ صورة (أو صفحات PDF) ويعيد نصاً. حاجب: يُستدعى من خيط الأدوات لا الرئيسي. */
    fun read(ctx: ToolContext, args: JSONObject): String {
        val path = args.optString("path").trim()
        require(path.isNotEmpty()) { "حدّد path للصورة" }
        val more = args.optJSONArray("more")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val mode = args.optString("mode", "auto").lowercase().ifBlank { "auto" }.also { require(it in MODES) { "mode غير معروف: $it — المتاح: ${MODES.joinToString("|")}" } }
        val engine = args.optString("engine", "auto").lowercase().ifBlank { "auto" }.also { require(it in setOf("auto", "vision", "local")) { "engine: auto|vision|local" } }
        val question = args.optString("question").trim()
        val maxChars = args.optInt("max_chars", 12_000).coerceIn(500, 30_000)
        val pages = args.optString("pages", "1")

        val items = ArrayList<Pair<String, Bitmap>>()
        val failures = ArrayList<String>()
        for (p in listOf(path) + more.take(MAX_IMAGES - 1)) {
            if (items.size >= MAX_IMAGES) break
            runCatching { load(ctx.context, p, pages, MAX_IMAGES - items.size) }
                .onSuccess { items += it }
                .onFailure { failures += "${p}: ${it.message}" }
        }
        if (items.isEmpty()) throw IllegalArgumentException(failures.joinToString("\n").ifBlank { "لا توجد صور قابلة للقراءة" })
        val names = items.joinToString("، ") { it.first }

        val wantVision = engine != "local"
        val wantLocal = engine != "vision" && (mode == "ocr" || mode == "error" || engine == "local" || mode == "auto" || mode == "ui" || mode == "table")

        var vision: String? = null
        if (wantVision) {
            val attachment = ImageAttachment.merge(items.map { (n, b) -> toAttachment(n, b) })
            if (attachment != null) {
                vision = ImageToText.describe(ctx.prefs, attachment, question, instructionFor(mode), preferred = ctx.provider.takeIf { it.isNotBlank() })
            }
        }
        // OCR المحلي: دائماً احتياط إن لم تنجح الرؤية، وللتحقق المتقاطع في وضع ocr/error
        val runLocal = wantLocal && (vision == null || mode == "ocr" || mode == "error")
        val local = if (runLocal) items.mapNotNull { (n, b) -> localOcr(b)?.let { n to it } } else emptyList()

        val out = StringBuilder("[قراءة الصور: $names | الوضع: $mode]")
        if (failures.isNotEmpty()) out.append("\nتعذّر: ").append(failures.joinToString(" ؛ "))
        if (vision != null) out.append("\n\n## تحليل الرؤية\n").append(vision)
        if (local.isNotEmpty()) {
            out.append(if (vision != null) "\n\n## OCR محلي للتحقق (لاتيني/أرقام)\n" else "\n\n## OCR محلي (لاتيني/أرقام فقط — لا يقرأ العربية)\n")
            out.append(if (local.size == 1) local[0].second else local.joinToString("\n\n") { "— ${it.first}:\n${it.second}" })
        }
        if (vision == null && local.isEmpty()) {
            out.append("\n\nلم أستطع قراءة محتوى الصور: ")
            out.append(
                when {
                    !wantVision -> "OCR المحلي لم يجد نصاً لاتينياً (العربية والخط اليدوي يحتاجان نموذج رؤية؛ أعد الطلب بـ engine=auto)."
                    ImageToText.candidates(ctx.prefs).isEmpty() -> "لا يوجد مزود رؤية مفعّل بمفتاح، وOCR المحلي لم يجد نصاً لاتينياً. أضف مفتاح مزود يدعم الصور (مثل Gemini أو OpenAI أو Claude) من إعدادات المزودات."
                    else -> "فشلت محاولات مزودات الرؤية (حصة أو خطأ شبكة) وOCR المحلي لم يجد نصاً. أعد المحاولة لاحقاً."
                }
            )
        }
        val text = out.toString()
        return if (text.length > maxChars) text.take(maxChars) + "\n…[اقتُطع؛ ارفع max_chars أو اقرأ صفحات/صوراً أقل]" else text
    }
}
