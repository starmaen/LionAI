package com.lion.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * فك وتعديل وتركيب boot.img / recovery.img (ترويسة Android الإصدارات 0 إلى 4).
 * - يستخرج kernel وramdisk وsecond وrecovery_dtbo وdtb وsignature ويحفظ الترويسة في header.json.
 * - يفك ramdisk (gzip + cpio newc) إلى مجلد ramdisk/ مع ramdisk.meta.json لحفظ الصلاحيات.
 * - يعيد التركيب بنفس الترويسة المعدّلة. الصورة الناتجة غير موقّعة (AVB): تحتاج بوتلودر مفتوحاً.
 */
object BootImageTools {
    private val MAGIC = "ANDROID!".toByteArray(Charsets.US_ASCII)
    private const val RAMDISK_DIR = "ramdisk"
    private const val RAMDISK_META = "ramdisk.meta.json"
    private const val HEADER_BYTES = 1660

    /** حد أقصى لصورة boot/recovery. الأكبر يُرفض برسالة واضحة بدل استهلاك ذاكرة يقتل التطبيق. */
    internal const val MAX_IMAGE_BYTES = 128L * 1024 * 1024

    private fun u32(b: ByteBuffer, off: Int): Long = b.getInt(off).toLong() and 0xFFFFFFFFL

    private fun u32At(head: ByteArray, b: ByteBuffer, off: Int): Long =
        if (off + 4 <= head.size) u32(b, off) else 0L

    private fun longAt(head: ByteArray, b: ByteBuffer, off: Int): Long =
        if (off + 8 <= head.size) b.getLong(off) else 0L

    private fun cstr(data: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && end < data.size && data[end] != 0.toByte()) end++
        return String(data, off, end - off, Charsets.UTF_8)
    }

    private fun align(n: Long, page: Long): Long = (n + page - 1) / page * page

    internal class Part(val offset: Long, val size: Long)

    internal class Meta(
        val header: JSONObject,
        val kernel: Part,
        val ramdisk: Part,
        val second: Part,
        val dtbo: Part,
        val dtb: Part,
        val signature: Part
    )

    internal class Parsed(
        val header: JSONObject,
        val kernel: ByteArray,
        val ramdisk: ByteArray,
        val second: ByteArray,
        val dtbo: ByteArray,
        val dtb: ByteArray,
        val signature: ByteArray
    )

    private fun slice(data: ByteArray, off: Long, size: Long, what: String): ByteArray {
        if (size == 0L) return ByteArray(0)
        require(off >= 0 && off + size <= data.size) { "حجم/موضع $what خارج الملف (الصورة تالفة أو غير مدعومة)" }
        return data.copyOfRange(off.toInt(), (off + size).toInt())
    }

    private fun requireInside(p: Part, total: Long, what: String) {
        require(p.size == 0L || (p.offset >= 0 && p.offset + p.size <= total)) {
            "حجم/موضع $what خارج الملف (الصورة تالفة أو غير مدعومة)"
        }
    }

    internal fun parseHeader(head: ByteArray, total: Long): Meta {
        require(head.size >= 8 && head.copyOfRange(0, 8).contentEquals(MAGIC)) {
            "ليست صورة boot بترويسة ANDROID! (قد تكون vendor_boot أو صورة ممسوحة/مضغوطة)"
        }
        require(total >= 1632) { "الصورة أصغر من ترويسة boot" }
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val version = u32At(head, b, 40).toInt()
        val h = JSONObject().put("header_version", version)
        if (version >= 3) {
            val page = 4096L
            val kSize = u32At(head, b, 8)
            val rSize = u32At(head, b, 12)
            h.put("page_size", page).put("os_version", u32At(head, b, 16))
                .put("cmdline", cstr(head, 44, 1536)).put("extra_cmdline", "")
            val kernel = Part(page, kSize).also { requireInside(it, total, "kernel") }
            val rOff = page + align(kSize, page)
            val ramdisk = Part(rOff, rSize).also { requireInside(it, total, "ramdisk") }
            val sigOff = rOff + align(rSize, page)
            val sig = if (version >= 4 && head.size >= 1584) {
                Part(sigOff, u32At(head, b, 1580)).also { requireInside(it, total, "signature") }
            } else Part(0, 0)
            return Meta(h, kernel, ramdisk, Part(0, 0), Part(0, 0), Part(0, 0), sig)
        }
        val page = u32At(head, b, 36)
        require(page >= 512 && page <= 65536 && (page and (page - 1)) == 0L) { "page_size غير صالح: $page" }
        h.put("page_size", page)
            .put("kernel_addr", u32At(head, b, 12)).put("ramdisk_addr", u32At(head, b, 20))
            .put("second_addr", u32At(head, b, 28)).put("tags_addr", u32At(head, b, 32))
            .put("os_version", u32At(head, b, 44))
            .put("name", cstr(head, 48, 16)).put("cmdline", cstr(head, 64, 512))
            .put("extra_cmdline", cstr(head, 608, 1024))
        var off = page
        val kernel = Part(off, u32At(head, b, 8)).also { requireInside(it, total, "kernel") }
        off += align(kernel.size, page)
        val ramdisk = Part(off, u32At(head, b, 16)).also { requireInside(it, total, "ramdisk") }
        off += align(ramdisk.size, page)
        val second = Part(off, u32At(head, b, 24)).also { requireInside(it, total, "second") }
        off += align(second.size, page)
        var dtbo = Part(0, 0)
        var dtb = Part(0, 0)
        if (version >= 1) {
            dtbo = Part(off, u32At(head, b, 1632)).also { requireInside(it, total, "recovery_dtbo") }
            off += align(dtbo.size, page)
        }
        if (version >= 2) {
            h.put("dtb_addr", longAt(head, b, 1652))
            dtb = Part(off, u32At(head, b, 1648)).also { requireInside(it, total, "dtb") }
        }
        return Meta(h, kernel, ramdisk, second, dtbo, dtb, Part(0, 0))
    }

    internal fun parse(data: ByteArray): Parsed {
        require(data.size >= 8) { "الملف فارغ أو تالف" }
        val m = parseHeader(data.copyOf(minOf(data.size, HEADER_BYTES)), data.size.toLong())
        fun read(p: Part, what: String) = slice(data, p.offset, p.size, what)
        return Parsed(
            m.header, read(m.kernel, "kernel"), read(m.ramdisk, "ramdisk"), read(m.second, "second"),
            read(m.dtbo, "recovery_dtbo"), read(m.dtb, "dtb"), read(m.signature, "signature")
        )
    }

    /** يقرأ الترويسة فقط من الملف (بدون تحميل الصورة كاملة) لحماية الذاكرة. */
    internal fun headerOf(file: File): Meta {
        val total = file.length()
        require(total <= MAX_IMAGE_BYTES) {
            "الصورة أكبر من ${MAX_IMAGE_BYTES / (1024 * 1024)} ميجابايت؛ اختر صورة boot/recovery أصغر."
        }
        RandomAccessFile(file, "r").use { raf ->
            val len = minOf(total, HEADER_BYTES.toLong()).toInt()
            require(len >= 8) { "الملف فارغ أو تالف" }
            val head = ByteArray(len)
            raf.readFully(head)
            return parseHeader(head, total)
        }
    }

    private fun isGzipFile(file: File, offset: Long): Boolean {
        if (file.length() - offset < 2) return false
        val pair = ByteArray(2)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(pair)
        }
        return pair[0] == 0x1f.toByte() && pair[1] == 0x8b.toByte()
    }

    fun info(context: Context, path: String): String {
        val file = FileTools.file(context, path)
        require(file.isFile) { "غير موجود: $path" }
        val m = headerOf(file)
        val h = m.header
        return buildString {
            append("📦 ").append(FileTools.relative(context, file)).append(" (").append(file.length()).append(" بايت)\n")
            append("إصدار الترويسة: ").append(h.getInt("header_version"))
                .append("، page_size=").append(h.getLong("page_size")).append('\n')
            append("kernel=").append(m.kernel.size).append("  ramdisk=").append(m.ramdisk.size)
                .append(if (isGzipFile(file, m.ramdisk.offset)) " (gzip)" else "")
                .append("  second=").append(m.second.size).append("  dtbo=").append(m.dtbo.size)
                .append("  dtb=").append(m.dtb.size).append("  signature=").append(m.signature.size).append('\n')
            append("cmdline: ").append(h.optString("cmdline"))
            if (h.optString("extra_cmdline").isNotBlank()) append(' ').append(h.optString("extra_cmdline"))
        }
    }

    // ───────────────────────── cpio (newc) ─────────────────────────

    internal class Cpio(
        val name: String, val mode: Long, val uid: Long, val gid: Long, val nlink: Long, val mtime: Long,
        val devMajor: Long, val devMinor: Long, val rdevMajor: Long, val rdevMinor: Long, val data: ByteArray
    )

    private fun hex(s: ByteArray, off: Int): Long = String(s, off, 8, Charsets.US_ASCII).toLong(16)

    internal fun parseCpio(raw: ByteArray): List<Cpio> {
        val out = mutableListOf<Cpio>()
        var pos = 0
        while (pos + 110 <= raw.size) {
            require(String(raw, pos, 6, Charsets.US_ASCII) == "070701") { "ramdisk ليس بصيغة cpio newc" }
            val mode = hex(raw, pos + 14); val uid = hex(raw, pos + 22); val gid = hex(raw, pos + 30)
            val nlink = hex(raw, pos + 38); val mtime = hex(raw, pos + 46); val fsize = hex(raw, pos + 54).toInt()
            val dMaj = hex(raw, pos + 62); val dMin = hex(raw, pos + 70)
            val rMaj = hex(raw, pos + 78); val rMin = hex(raw, pos + 86)
            val nsize = hex(raw, pos + 94).toInt()
            val nameStart = pos + 110
            val name = String(raw, nameStart, nsize - 1, Charsets.UTF_8)
            val dataStart = ((nameStart + nsize + 3) / 4) * 4
            if (name == "TRAILER!!!") break
            require(dataStart + fsize <= raw.size) { "cpio تالف عند $name" }
            out.add(Cpio(name, mode, uid, gid, nlink, mtime, dMaj, dMin, rMaj, rMin, raw.copyOfRange(dataStart, dataStart + fsize)))
            pos = ((dataStart + fsize + 3) / 4) * 4
        }
        return out
    }

    internal fun buildCpio(entries: List<Cpio>): ByteArray {
        val out = ByteArrayOutputStream()
        fun pad() { while (out.size() % 4 != 0) out.write(0) }
        fun put(e: Cpio, ino: Int) {
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            val header = "070701" + listOf(
                ino.toLong(), e.mode, e.uid, e.gid, e.nlink, e.mtime, e.data.size.toLong(),
                e.devMajor, e.devMinor, e.rdevMajor, e.rdevMinor, (nameBytes.size + 1).toLong(), 0L
            ).joinToString("") { String.format("%08X", it) }
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(nameBytes); out.write(0); pad()
            out.write(e.data); pad()
        }
        entries.forEachIndexed { i, e -> put(e, i + 1) }
        put(Cpio("TRAILER!!!", 0, 0, 0, 1, 0, 0, 0, 0, 0, ByteArray(0)), entries.size + 1)
        return out.toByteArray()
    }

    private fun safeChild(root: File, name: String): File {
        val clean = name.trimStart('/').ifBlank { "." }
        val f = File(root, clean).canonicalFile
        require(f.path == root.canonicalPath || f.path.startsWith(root.canonicalPath + File.separator)) { "مسار غير آمن في ramdisk: $name" }
        return f
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalArgumentException("cpio تالف: ملف مقطوع")
            off += n
        }
    }

    private fun readFullyOrEof(input: InputStream, buf: ByteArray): Boolean {
        val first = input.read()
        if (first < 0) return false
        buf[0] = first.toByte()
        var off = 1
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalArgumentException("cpio تالف: ملف مقطوع")
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        if (count <= 0) return
        val buf = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IllegalArgumentException("cpio تالف: ملف مقطوع")
            remaining -= n
        }
    }

    private fun skipPad(input: InputStream, size: Long) {
        val pad = (4 - (size % 4)) % 4
        if (pad > 0) skipFully(input, pad)
    }

    private fun copyExactly(input: InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IllegalArgumentException("cpio تالف: ملف مقطوع")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    /** يفك ramdisk من ملفه مباشرة إلى القرص، دون تحميل الصورة أو cpio كاملاً في الذاكرة. */
    internal fun extractRamdisk(raw: File, outDir: File): String {
        if (raw.length() == 0L) return "ramdisk فارغ"
        if (!isGzipFile(raw, 0)) return "ramdisk غير gzip (مثل lz4/xz): حُفظ خاماً في ramdisk.raw ولم يُفك"
        val root = File(outDir, RAMDISK_DIR).apply { deleteRecursively(); mkdirs() }
        val meta = JSONArray()
        var count = 0
        FileInputStream(raw).use { fis ->
            GZIPInputStream(fis, 64 * 1024).use { input ->
                while (true) {
                    val header = ByteArray(110)
                    if (!readFullyOrEof(input, header)) break
                    require(String(header, 0, 6, Charsets.US_ASCII) == "070701") { "ramdisk ليس بصيغة cpio newc" }
                    val mode = hex(header, 14); val uid = hex(header, 22); val gid = hex(header, 30)
                    val nlink = hex(header, 38); val mtime = hex(header, 46); val fsize = hex(header, 54)
                    val dMaj = hex(header, 62); val dMin = hex(header, 70)
                    val rMaj = hex(header, 78); val rMin = hex(header, 86)
                    val nsize = hex(header, 94)
                    require(nsize in 1..65536) { "cpio تالف: اسم بطول غير صالح" }
                    val nameBytes = ByteArray(nsize.toInt())
                    readFully(input, nameBytes)
                    val name = String(nameBytes, 0, nsize.toInt() - 1, Charsets.UTF_8)
                    skipPad(input, 110L + nsize)
                    if (name == "TRAILER!!!") break
                    val type = mode and 0xF000
                    if (name != ".") {
                        val f = safeChild(root, name)
                        when (type) {
                            0x4000L -> f.mkdirs()
                            0x8000L, 0xA000L -> {
                                f.parentFile?.mkdirs()
                                f.outputStream().buffered().use { out -> copyExactly(input, out, fsize) }
                            }
                            else -> skipFully(input, fsize)
                        }
                    } else {
                        skipFully(input, fsize)
                    }
                    skipPad(input, fsize)
                    meta.put(
                        JSONObject().put("name", name).put("mode", mode).put("uid", uid).put("gid", gid)
                            .put("nlink", nlink).put("mtime", mtime).put("devmajor", dMaj).put("devminor", dMin)
                            .put("rdevmajor", rMaj).put("rdevminor", rMin)
                    )
                    count++
                }
            }
        }
        File(outDir, RAMDISK_META).writeText(meta.toString(1))
        return "ramdisk فُك إلى $RAMDISK_DIR/ ($count عنصراً)"
    }

    private fun packRamdisk(dir: File): ByteArray? {
        val root = File(dir, RAMDISK_DIR)
        val metaFile = File(dir, RAMDISK_META)
        if (!root.isDirectory || !metaFile.isFile) return null
        val meta = JSONArray(metaFile.readText())
        val known = HashSet<String>()
        val entries = mutableListOf<Cpio>()
        for (i in 0 until meta.length()) {
            val m = meta.getJSONObject(i)
            val name = m.getString("name")
            known.add(name)
            val mode = m.getLong("mode")
            val type = mode and 0xF000
            var data = ByteArray(0)
            if (name != ".") {
                val f = safeChild(root, name)
                when (type) {
                    0x4000L -> if (!f.isDirectory) continue
                    0x8000L, 0xA000L -> { if (!f.isFile) continue; data = f.readBytes() }
                    else -> Unit
                }
            }
            entries.add(Cpio(name, mode, m.getLong("uid"), m.getLong("gid"), m.getLong("nlink"), m.getLong("mtime"),
                m.getLong("devmajor"), m.getLong("devminor"), m.getLong("rdevmajor"), m.getLong("rdevminor"), data))
        }
        // ملفات ومجلدات أُضيفت يدوياً: صلاحيات افتراضية root 0644/0755.
        val added = root.walkTopDown().filter { it != root }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filter { it !in known }.sorted().toList()
        added.forEach { name ->
            val f = File(root, name)
            if (f.isDirectory) entries.add(Cpio(name, 0x41EDL, 0, 0, 2, 0, 0, 0, 0, 0, ByteArray(0)))
            else entries.add(Cpio(name, 0x81A4L, 0, 0, 1, 0, 0, 0, 0, 0, f.readBytes()))
        }
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(buildCpio(entries)) }
        return gz.toByteArray()
    }

    // ───────────────────────── unpack / repack ─────────────────────────

    /** ينسخ مقطعاً من الصورة إلى ملف بدون تحميل الصورة كاملة في الذاكرة. */
    private fun copyRange(src: File, part: Part, dst: File) {
        if (part.size == 0L) return
        RandomAccessFile(src, "r").use { raf ->
            raf.seek(part.offset)
            dst.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                var remaining = part.size
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IllegalArgumentException("الصورة أقصر من المتوقع (ملف مقطوع)")
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }

    fun unpack(context: Context, path: String, outDirPath: String): String {
        val file = FileTools.file(context, path)
        require(file.isFile) { "غير موجود: $path" }
        val m = headerOf(file)
        val dir = FileTools.file(context, outDirPath.ifBlank { "boot_unpacked" })
        dir.mkdirs()
        File(dir, "header.json").writeText(m.header.toString(2))
        copyRange(file, m.kernel, File(dir, "kernel"))
        copyRange(file, m.second, File(dir, "second"))
        copyRange(file, m.dtbo, File(dir, "recovery_dtbo"))
        copyRange(file, m.dtb, File(dir, "dtb"))
        copyRange(file, m.signature, File(dir, "signature"))
        copyRange(file, m.ramdisk, File(dir, "ramdisk.raw"))
        val rd = extractRamdisk(File(dir, "ramdisk.raw"), dir)
        return "✅ فُكّت ${FileTools.relative(context, file)} إلى ${FileTools.relative(context, dir)}\n" +
            "kernel=${m.kernel.size} ramdisk=${m.ramdisk.size} second=${m.second.size} dtbo=${m.dtbo.size} dtb=${m.dtb.size}\n" +
            rd + "\nعدّل الملفات (cmdline في header.json، أو ملفات ramdisk/) ثم استدعِ boot_repack."
    }

    internal fun buildImage(
        h: JSONObject, kernel: ByteArray, ramdisk: ByteArray, second: ByteArray,
        dtbo: ByteArray, dtb: ByteArray, signature: ByteArray
    ): ByteArray {
        val version = h.getInt("header_version")
        val page = h.getLong("page_size")
        val cmdline = h.optString("cmdline").toByteArray(Charsets.UTF_8)
        val extra = h.optString("extra_cmdline").toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        val hdr = ByteBuffer.allocate(page.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put(MAGIC)
        if (version >= 3) {
            require(cmdline.size <= 1535) { "cmdline أطول من 1535 بايت" }
            hdr.putInt(8, kernel.size).putInt(12, ramdisk.size).putInt(16, h.getLong("os_version").toInt())
            hdr.putInt(20, if (version >= 4) 1584 else 1580).putInt(40, version)
            hdr.position(44); hdr.put(cmdline)
            if (version >= 4) hdr.putInt(1580, signature.size)
        } else {
            require(cmdline.size <= 512) { "cmdline أطول من 512 بايت (انقل الزائد إلى extra_cmdline)" }
            require(extra.size <= 1024) { "extra_cmdline أطول من 1024 بايت" }
            fun addr(k: String) = h.optLong(k).toInt()
            hdr.putInt(8, kernel.size).putInt(12, addr("kernel_addr")).putInt(16, ramdisk.size).putInt(20, addr("ramdisk_addr"))
                .putInt(24, second.size).putInt(28, addr("second_addr")).putInt(32, addr("tags_addr"))
                .putInt(36, page.toInt()).putInt(40, version).putInt(44, h.optLong("os_version").toInt())
            hdr.position(48); hdr.put(h.optString("name").toByteArray(Charsets.UTF_8).copyOf(minOf(15, h.optString("name").toByteArray().size)))
            hdr.position(64); hdr.put(cmdline)
            hdr.position(608); hdr.put(extra)
            val sha = MessageDigest.getInstance("SHA-1")
            fun feed(d: ByteArray) { sha.update(d); sha.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(d.size).array()) }
            feed(kernel); feed(ramdisk); feed(second)
            var dtboOffset = 0L
            if (version >= 1) {
                feed(dtbo)
                dtboOffset = page + align(kernel.size.toLong(), page) + align(ramdisk.size.toLong(), page) + align(second.size.toLong(), page)
                hdr.putInt(1632, dtbo.size).putLong(1636, dtboOffset).putInt(1644, if (version >= 2) 1660 else 1648)
            }
            if (version >= 2) {
                feed(dtb)
                hdr.putInt(1648, dtb.size).putLong(1652, h.optLong("dtb_addr"))
            }
            hdr.position(576); hdr.put(sha.digest())
        }
        out.write(hdr.array())
        fun part(d: ByteArray) {
            out.write(d)
            val padLen = (align(d.size.toLong(), page) - d.size).toInt()
            if (padLen > 0) out.write(ByteArray(padLen))
        }
        part(kernel); part(ramdisk)
        if (version < 3) { part(second); if (version >= 1) part(dtbo); if (version >= 2) part(dtb) }
        else if (version >= 4) part(signature)
        return out.toByteArray()
    }

    fun repack(context: Context, dirPath: String, targetPath: String): String {
        val dir = FileTools.file(context, dirPath)
        require(dir.isDirectory) { "مجلد الفك غير موجود: $dirPath" }
        val h = JSONObject(File(dir, "header.json").also { require(it.isFile) { "header.json مفقود" } }.readText())
        val version = h.getInt("header_version")
        val page = h.getLong("page_size")
        fun read(name: String) = File(dir, name).takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
        val kernel = read("kernel")
        val ramdisk = packRamdisk(dir) ?: read("ramdisk.raw")
        val second = read("second")
        val dtbo = read("recovery_dtbo")
        val dtb = read("dtb")
        val signature = read("signature")
        val image = buildImage(h, kernel, ramdisk, second, dtbo, dtb, signature)
        val target = FileTools.file(context, targetPath.ifBlank { "deliverables/boot-new.img" })
        target.parentFile?.mkdirs()
        target.writeBytes(image)
        return "✅ رُكّبت ${FileTools.relative(context, target)} (${target.length()} بايت)\n" +
            "⚠️ الصورة غير موقّعة (AVB/vbmeta): تعمل فقط مع بوتلودر مفتوح، وقد تحتاج تعطيل التحقق عند الفلاش. احتفظ بنسخة من الأصلية."
    }
}
