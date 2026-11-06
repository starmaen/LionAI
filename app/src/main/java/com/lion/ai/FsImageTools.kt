package com.lion.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * صور system / vendor / product (EXT4 و EROFS، عادية أو sparse).
 * - sparse → raw بكود Kotlin.
 * - الفك/التركيب بأدوات حقيقية داخل بيئة Alpine: debugfs وmke2fs (EXT4)، وfsck.erofs وmkfs.erofs (EROFS).
 * تنبيه صريح: الفك لا ينسخ صلاحيات SELinux ولا المالك الحقيقي، فالصورة المعاد بناؤها
 * تصلح للفحص والتعديلات التجريبية، وليست بديلاً عن صورة موقّعة وموسومة للفلاش النهائي.
 */
object FsImageTools {
    private const val SPARSE_MAGIC = 0xED26FF3AL
    private const val META = ".fsimage.json"
    private const val GUEST_ROOT = "/workspace/"

    enum class Format { EXT4, EROFS, UNKNOWN }

    fun detect(file: File): Format = RandomAccessFile(file, "r").use { f ->
        fun u32(off: Long): Long {
            if (f.length() < off + 4) return -1
            f.seek(off)
            val b = ByteArray(4); f.readFully(b)
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        }
        fun u16(off: Long): Int {
            if (f.length() < off + 2) return -1
            f.seek(off)
            val b = ByteArray(2); f.readFully(b)
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        }
        when {
            u32(1024) == 0xE0F5E1E2L -> Format.EROFS
            u16(1080) == 0xEF53 -> Format.EXT4
            else -> Format.UNKNOWN
        }
    }

    fun isSparse(file: File): Boolean = RandomAccessFile(file, "r").use { f ->
        if (f.length() < 28) return false
        val b = ByteArray(4); f.readFully(b)
        (ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL) == SPARSE_MAGIC
    }

    /** simg2img: يحوّل صورة Android sparse إلى raw. يعيد حجم الناتج بالبايت. */
    fun sparseToRaw(src: File, dst: File): Long {
        RandomAccessFile(src, "r").use { input ->
            val head = ByteArray(28); input.readFully(head)
            val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            require((h.getInt(0).toLong() and 0xFFFFFFFFL) == SPARSE_MAGIC) { "ليست صورة sparse" }
            val fileHdr = h.getShort(8).toInt() and 0xFFFF
            val chunkHdr = h.getShort(10).toInt() and 0xFFFF
            val blockSize = h.getInt(12).toLong() and 0xFFFFFFFFL
            val totalBlocks = h.getInt(16).toLong() and 0xFFFFFFFFL
            val chunks = h.getInt(20).toLong() and 0xFFFFFFFFL
            require(blockSize in 512..(1L shl 20) && fileHdr >= 28 && chunkHdr >= 12) { "ترويسة sparse غير صالحة" }
            input.seek(fileHdr.toLong())
            RandomAccessFile(dst, "rw").use { out ->
                out.setLength(0)
                var written = 0L
                val buf = ByteArray(1 shl 20)
                for (i in 0 until chunks) {
                    val ch = ByteArray(chunkHdr); input.readFully(ch)
                    val c = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
                    val type = c.getShort(0).toInt() and 0xFFFF
                    val sizeBlocks = c.getInt(4).toLong() and 0xFFFFFFFFL
                    val bytes = sizeBlocks * blockSize
                    when (type) {
                        0xCAC1 -> {
                            var left = bytes
                            out.seek(written)
                            while (left > 0) {
                                val n = minOf(left, buf.size.toLong()).toInt()
                                input.readFully(buf, 0, n); out.write(buf, 0, n); left -= n
                            }
                        }
                        0xCAC2 -> {
                            val fill = ByteArray(4); input.readFully(fill)
                            for (k in buf.indices) buf[k] = fill[k % 4]
                            var left = bytes
                            out.seek(written)
                            while (left > 0) {
                                val n = minOf(left, buf.size.toLong()).toInt()
                                out.write(buf, 0, n); left -= n
                            }
                        }
                        0xCAC3 -> Unit
                        0xCAC4 -> input.skipBytes(4)
                        else -> error("نوع chunk غير معروف: 0x" + type.toString(16))
                    }
                    written += bytes
                }
                require(written == totalBlocks * blockSize) { "حجم الصورة بعد التحويل لا يطابق الترويسة" }
                out.setLength(written)
                return written
            }
        }
    }

    internal data class Ext4Info(val label: String, val blockSize: Long, val blocks: Long, val journal: Boolean)

    internal fun ext4Info(file: File): Ext4Info = RandomAccessFile(file, "r").use { f ->
        val sb = ByteArray(264); f.seek(1024); f.readFully(sb)
        val b = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        val label = String(sb, 120, 16, Charsets.UTF_8).trimEnd('\u0000')
        Ext4Info(
            label, 1024L shl b.getInt(24), b.getInt(4).toLong() and 0xFFFFFFFFL,
            (b.getInt(92) and 0x4) != 0
        )
    }

    private fun guest(context: Context, file: File): String = GUEST_ROOT + file.relativeTo(FileTools.root(context)).path.replace(File.separatorChar, '/')

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun ensureTools(context: Context, needed: List<String>): String? {
        require(LinuxEnv.ready(context)) { "فعّل بيئة Linux من «البيئة المحلية» أولاً" }
        fun missing() = needed.filter { !LinuxEnv.run(context, "command -v $it", 30).ok }
        if (missing().isEmpty()) return null
        val install = LinuxEnv.run(context, "apk add --no-cache e2fsprogs e2fsprogs-extra erofs-utils", 900)
        val still = missing()
        return if (still.isEmpty()) null else "تعذّر تثبيت الأدوات: ${still.joinToString()}\n" + install.output.takeLast(600)
    }

    private fun raw(context: Context, file: File): Pair<File, Boolean> {
        if (!isSparse(file)) return file to false
        val out = File(file.parentFile, file.nameWithoutExtension + ".raw.img")
        sparseToRaw(file, out)
        return out to true
    }

    fun info(context: Context, path: String): String {
        val file = FileTools.file(context, path)
        require(file.isFile) { "غير موجود: $path" }
        val sparse = isSparse(file)
        val (rawFile, converted) = raw(context, file)
        val format = detect(rawFile)
        val rel = FileTools.relative(context, file)
        return buildString {
            append("💽 ").append(rel).append(" (").append(FileTools.formatSize(file.length())).append(")\n")
            append("الصيغة: ").append(
                when (format) { Format.EXT4 -> "EXT4"; Format.EROFS -> "EROFS (للقراءة فقط)"; Format.UNKNOWN -> "غير معروفة" }
            )
            if (sparse) append(" — sparse (الحجم بعد التحويل ${FileTools.formatSize(rawFile.length())})")
            if (format == Format.EXT4) {
                val e = ext4Info(rawFile)
                append("\nالتسمية: ").append(e.label.ifBlank { "—" }).append("، الكتلة ").append(e.blockSize)
                    .append("، عدد الكتل ").append(e.blocks).append(if (e.journal) "، فيها journal" else "")
            }
            if (format == Format.UNKNOWN) append("\nقد تكون super.img (أقسام ديناميكية) أو مضغوطة أو مشفّرة؛ غير مدعومة هنا.")
            if (converted) rawFile.delete()
        }
    }

    fun unpack(context: Context, path: String, outDirPath: String): String {
        val file = FileTools.file(context, path)
        require(file.isFile) { "غير موجود: $path" }
        val (rawFile, converted) = raw(context, file)
        try {
            val format = detect(rawFile)
            require(format != Format.UNKNOWN) { "صيغة غير مدعومة: ليست EXT4 ولا EROFS (جرّب «تحليل» أولاً)" }
            val tool = if (format == Format.EXT4) "debugfs" else "fsck.erofs"
            ensureTools(context, listOf(tool))?.let { error(it) }
            val dir = FileTools.file(context, outDirPath.ifBlank { "fs_unpacked/" + file.nameWithoutExtension })
            dir.deleteRecursively(); dir.mkdirs()
            val script = if (format == Format.EXT4) {
                "debugfs -R ${q("rdump / " + guest(context, dir))} ${q(guest(context, rawFile))} 2>&1 | tail -n 20"
            } else {
                "fsck.erofs --extract=${q(guest(context, dir))} ${q(guest(context, rawFile))} 2>&1 | tail -n 20"
            }
            val run = LinuxEnv.run(context, script, 3000)
            val count = dir.walkTopDown().count { it.isFile }
            require(count > 0) { "لم يُستخرج أي ملف.\n" + run.output.takeLast(800) }
            val meta = JSONObject().put("format", format.name).put("original_size", rawFile.length()).put("was_sparse", converted)
            if (format == Format.EXT4) {
                val e = ext4Info(rawFile)
                meta.put("label", e.label).put("block_size", e.blockSize)
            }
            File(dir, META).writeText(meta.toString(2))
            return "✅ فُكّت ${FileTools.relative(context, file)} (${format.name}) إلى ${FileTools.relative(context, dir)}\n" +
                "$count ملفاً.\n⚠️ لم تُنسخ وسوم SELinux ولا المالك الحقيقي؛ عدّل ما تريد ثم استدعِ fs_image_repack."
        } finally {
            if (converted) rawFile.delete()
        }
    }

    fun repack(context: Context, dirPath: String, targetPath: String, sizeMb: Int): String {
        val dir = FileTools.file(context, dirPath)
        val metaFile = File(dir, META)
        require(dir.isDirectory && metaFile.isFile) { "مجلد الفك غير صالح (لا يوجد $META). فك الصورة بأداة fs_image_unpack أولاً" }
        val meta = JSONObject(metaFile.readText())
        val format = Format.valueOf(meta.getString("format"))
        val target = FileTools.file(context, targetPath.ifBlank { "deliverables/" + dir.name + "-new.img" })
        target.parentFile?.mkdirs()
        val contentBytes = dir.walkTopDown().filter { it.isFile && it.name != META }.sumOf { it.length() }
        val original = meta.optLong("original_size", 0)
        val tool = if (format == Format.EXT4) "mke2fs" else "mkfs.erofs"
        ensureTools(context, listOf(tool))?.let { error(it) }
        // نسخة نظيفة بدون ملف الوصف داخل الصورة.
        val stage = File(dir.parentFile, dir.name + ".stage")
        stage.deleteRecursively()
        dir.copyRecursively(stage, overwrite = true)
        File(stage, META).delete()
        try {
            val script: String
            var size = 0L
            if (format == Format.EXT4) {
                val blockSize = meta.optLong("block_size", 4096).coerceAtLeast(1024)
                size = if (sizeMb > 0) sizeMb * 1024L * 1024L else maxOf(original, contentBytes * 115 / 100 + 16L * 1024 * 1024)
                size = (size + blockSize - 1) / blockSize * blockSize
                val label = meta.optString("label", "")
                script = "mke2fs -q -t ext4 -b $blockSize -m 0 -O ^has_journal -E root_owner=0:0 " +
                    (if (label.isNotBlank()) "-L ${q(label.take(16))} " else "") +
                    "-d ${q(guest(context, stage))} ${q(guest(context, target))} ${size / blockSize} 2>&1 | tail -n 15; " +
                    "e2fsck -fn ${q(guest(context, target))} 2>&1 | tail -n 5"
            } else {
                script = "mkfs.erofs ${q(guest(context, target))} ${q(guest(context, stage))} 2>&1 | tail -n 15"
            }
            val run = LinuxEnv.run(context, script, 3000)
            require(target.isFile && target.length() > 0) { "فشل التركيب:\n" + run.output.takeLast(900) }
            val grew = format == Format.EXT4 && original > 0 && size > original
            return "✅ رُكّبت ${FileTools.relative(context, target)} (${FileTools.formatSize(target.length())}، ${format.name})\n" +
                (if (grew) "⚠️ الصورة أكبر من الأصلية؛ تأكد أنها تناسب حجم القسم.\n" else "") +
                "⚠️ بلا وسوم SELinux وبلا المالك الحقيقي، وغير موقّعة (AVB): للفحص والتجارب على جهاز بوتلودره مفتوح. احتفظ بالأصلية.\n" +
                run.output.takeLast(400)
        } finally {
            stage.deleteRecursively()
        }
    }
}
