package com.lion.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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

    private fun u32(b: ByteBuffer, off: Int): Long = b.getInt(off).toLong() and 0xFFFFFFFFL

    private fun cstr(data: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && data[end] != 0.toByte()) end++
        return String(data, off, end - off, Charsets.UTF_8)
    }

    private fun align(n: Long, page: Long): Long = (n + page - 1) / page * page

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

    internal fun parse(data: ByteArray): Parsed {
        require(data.size >= 1632 && data.copyOfRange(0, 8).contentEquals(MAGIC)) {
            "ليست صورة boot بترويسة ANDROID! (قد تكون vendor_boot أو صورة ممسوحة/مضغوطة)"
        }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val version = u32(b, 40).toInt()
        val h = JSONObject().put("header_version", version)
        if (version >= 3) {
            val page = 4096L
            val kSize = u32(b, 8)
            val rSize = u32(b, 12)
            h.put("page_size", page).put("os_version", u32(b, 16))
                .put("cmdline", cstr(data, 44, 1536)).put("extra_cmdline", "")
            var off = page
            val kernel = slice(data, off, kSize, "kernel"); off += align(kSize, page)
            val ramdisk = slice(data, off, rSize, "ramdisk"); off += align(rSize, page)
            var sig = ByteArray(0)
            if (version >= 4 && data.size >= 1584) {
                sig = slice(data, off, u32(b, 1580), "signature")
            }
            return Parsed(h, kernel, ramdisk, ByteArray(0), ByteArray(0), ByteArray(0), sig)
        }
        val page = u32(b, 36)
        require(page >= 512 && page <= 65536 && (page and (page - 1)) == 0L) { "page_size غير صالح: $page" }
        val kSize = u32(b, 8)
        val rSize = u32(b, 16)
        val sSize = u32(b, 24)
        h.put("page_size", page)
            .put("kernel_addr", u32(b, 12)).put("ramdisk_addr", u32(b, 20)).put("second_addr", u32(b, 28))
            .put("tags_addr", u32(b, 32)).put("os_version", u32(b, 44))
            .put("name", cstr(data, 48, 16)).put("cmdline", cstr(data, 64, 512))
            .put("extra_cmdline", cstr(data, 608, 1024))
        var off = page
        val kernel = slice(data, off, kSize, "kernel"); off += align(kSize, page)
        val ramdisk = slice(data, off, rSize, "ramdisk"); off += align(rSize, page)
        val second = slice(data, off, sSize, "second"); off += align(sSize, page)
        var dtbo = ByteArray(0)
        var dtb = ByteArray(0)
        if (version >= 1) {
            val dSize = u32(b, 1632)
            dtbo = slice(data, off, dSize, "recovery_dtbo"); off += align(dSize, page)
        }
        if (version >= 2) {
            val tSize = u32(b, 1648)
            h.put("dtb_addr", b.getLong(1652))
            dtb = slice(data, off, tSize, "dtb")
        }
        return Parsed(h, kernel, ramdisk, second, dtbo, dtb, ByteArray(0))
    }

    private fun isGzip(d: ByteArray) = d.size > 2 && d[0] == 0x1f.toByte() && d[1] == 0x8b.toByte()

    fun info(context: Context, path: String): String {
        val file = FileTools.file(context, path)
        require(file.isFile) { "غير موجود: $path" }
        val p = parse(file.readBytes())
        val h = p.header
        return buildString {
            append("📦 ").append(FileTools.relative(context, file)).append(" (").append(file.length()).append(" بايت)\n")
            append("إصدار الترويسة: ").append(h.getInt("header_version"))
                .append("، page_size=").append(h.getLong("page_size")).append('\n')
            append("kernel=").append(p.kernel.size).append("  ramdisk=").append(p.ramdisk.size)
                .append(if (isGzip(p.ramdisk)) " (gzip)" else "")
                .append("  second=").append(p.second.size).append("  dtbo=").append(p.dtbo.size)
                .append("  dtb=").append(p.dtb.size).append("  signature=").append(p.signature.size).append('\n')
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

    private fun extractRamdisk(ramdisk: ByteArray, outDir: File): String {
        if (ramdisk.isEmpty()) return "ramdisk فارغ"
        if (!isGzip(ramdisk)) return "ramdisk غير gzip (مثل lz4/xz): حُفظ خاماً في ramdisk.raw ولم يُفك"
        val cpio = GZIPInputStream(ByteArrayInputStream(ramdisk)).use { it.readBytes() }
        val entries = parseCpio(cpio)
        val root = File(outDir, RAMDISK_DIR).apply { deleteRecursively(); mkdirs() }
        val meta = JSONArray()
        entries.forEach { e ->
            val type = e.mode and 0xF000
            if (e.name != ".") {
                val f = safeChild(root, e.name)
                when (type) {
                    0x4000L -> f.mkdirs()
                    0x8000L, 0xA000L -> { f.parentFile?.mkdirs(); f.writeBytes(e.data) }
                    else -> Unit
                }
            }
            meta.put(
                JSONObject().put("name", e.name).put("mode", e.mode).put("uid", e.uid).put("gid", e.gid)
                    .put("nlink", e.nlink).put("mtime", e.mtime).put("devmajor", e.devMajor).put("devminor", e.devMinor)
                    .put("rdevmajor", e.rdevMajor).put("rdevminor", e.rdevMinor)
            )
        }
        File(outDir, RAMDISK_META).writeText(meta.toString(1))
        return "ramdisk فُك إلى $RAMDISK_DIR/ (${entries.size} عنصراً)"
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

    fun unpack(context: Context, path: String, outDirPath: String): String {
        val file = FileTools.file(context, path)
        require(file.isFile) { "غير موجود: $path" }
        val p = parse(file.readBytes())
        val dir = FileTools.file(context, outDirPath.ifBlank { "boot_unpacked" })
        dir.mkdirs()
        File(dir, "header.json").writeText(p.header.toString(2))
        fun save(name: String, bytes: ByteArray) { if (bytes.isNotEmpty()) File(dir, name).writeBytes(bytes) }
        save("kernel", p.kernel)
        save("second", p.second)
        save("recovery_dtbo", p.dtbo)
        save("dtb", p.dtb)
        save("signature", p.signature)
        save("ramdisk.raw", p.ramdisk)
        val rd = extractRamdisk(p.ramdisk, dir)
        return "✅ فُكّت ${FileTools.relative(context, file)} إلى ${FileTools.relative(context, dir)}\n" +
            "kernel=${p.kernel.size} ramdisk=${p.ramdisk.size} second=${p.second.size} dtbo=${p.dtbo.size} dtb=${p.dtb.size}\n" +
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
