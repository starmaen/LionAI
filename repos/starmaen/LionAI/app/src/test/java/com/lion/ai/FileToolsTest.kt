package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileToolsTest {

    @Test
    fun resolveUnderRejectsTraversal() {
        val root = Files.createTempDirectory("lion-root").toFile()
        assertThrows(IllegalArgumentException::class.java) {
            FileTools.resolveUnder(root, "../escape.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileTools.resolveUnder(root, "a/../../escape.txt")
        }
    }

    @Test
    fun resolveUnderKeepsNestedPathInsideRoot() {
        val root = Files.createTempDirectory("lion-root").toFile()
        val file = FileTools.resolveUnder(root, "src/Main.kt")
        assertTrue(file.path.startsWith(root.canonicalPath + File.separator))
        assertEquals("Main.kt", file.name)
    }

    @Test
    fun resolveUnderRejectsReservedNames() {
        val root = Files.createTempDirectory("lion-root").toFile()
        assertThrows(IllegalArgumentException::class.java) {
            FileTools.resolveUnder(root, ".lion-write-1.tmp")
        }
    }

    @Test
    fun atomicWriteCreatesAndReplacesContent() {
        val dir = Files.createTempDirectory("lion-atomic").toFile()
        val target = File(dir, "notes.txt")
        FileTools.writeAtomically(target, "first")
        assertEquals("first", target.readText())
        FileTools.writeAtomically(target, "second")
        assertEquals("second", target.readText())
        val leftovers = dir.listFiles()?.filter { it.name.startsWith(".lion-write-") }.orEmpty()
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun fileTypeRecognizesCommonExtensions() {
        assertEquals("code", FileTools.fileType("Main.kt"))
        assertEquals("json", FileTools.fileType("data.JSON"))
        assertEquals("archive", FileTools.fileType("build.zip"))
        assertEquals("unknown", FileTools.fileType("noext"))
    }

    @Test
    fun formatSizeUsesReadableUnits() {
        assertEquals("512 B", FileTools.formatSize(512))
        assertEquals("1.0 KB", FileTools.formatSize(1024))
        assertFalse(FileTools.formatSize(2 * 1024 * 1024).isBlank())
    }
}
