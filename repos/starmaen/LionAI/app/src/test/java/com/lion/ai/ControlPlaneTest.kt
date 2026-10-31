package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPlaneTest {

    @Test
    fun balanceIgnoresStringsAndComments() {
        assertNull(ControlPlane.balance("fun main() { println(\"}\") // }\n}"))
        assertNull(ControlPlane.balance("val x = \"\"\"}\"\"\"\nval y = ')'"))
        val bad = ControlPlane.balance("fun main() { if (true) { }")
        assertNotNull(bad)
        assertTrue(bad!!.first.contains("غير مغلقة"))
    }

    @Test
    fun mismatchReportsLine() {
        val bad = ControlPlane.balance("val a = (1]\n")!!
        assertTrue(bad.first.contains("غير متطابق"))
        assertEquals(1, bad.second)
    }

    @Test
    fun tamperedTestIsDetectedByHmac() {
        val key = "unit-test-key"
        val snapshot = ProjectSnapshot(
            files = mapOf(
                "src/main/kotlin/Main.kt" to "fun main() = Unit",
                "src/test/kotlin/MainTest.kt" to "@Test fun ok() {}"
            )
        )
        val signatures = TestIntegrity.signAll(snapshot, key)
        assertTrue(ControlPlane.verifySignatures(snapshot, signatures, key).isEmpty())

        val tampered = ProjectSnapshot(snapshot.files + ("src/test/kotlin/MainTest.kt" to "@Test fun cheated() {}"))
        val issues = ControlPlane.verifySignatures(tampered, signatures, key)
        assertEquals(1, issues.size)
        assertEquals("TEST_TAMPERED", issues.first().code)
    }

    @Test
    fun missingSignedTestIsAnError() {
        val key = "unit-test-key"
        val snapshot = ProjectSnapshot(mapOf("src/main/kotlin/Main.kt" to "fun main() = Unit"))
        val signatures = mapOf("src/test/kotlin/GoneTest.kt" to "deadbeef")
        val issues = ControlPlane.verifySignatures(snapshot, signatures, key)
        assertEquals("TEST_MISSING", issues.first().code)
    }

    @Test
    fun manifestValidationRejectsBrokenDagAndUnsafePaths() {
        val manifest = BuildManifest(
            project = "demo",
            files = listOf(
                ManifestFile("src/Main.kt"),
                ManifestFile("src/Main.kt"),
                ManifestFile("../escape.kt")
            ),
            tasks = listOf(
                ManifestTask("a", "implementer", "A", dependsOn = listOf("b"), files = listOf("src/Main.kt")),
                ManifestTask("b", "reviewer", "B", dependsOn = listOf("a"), files = listOf("src/Main.kt"))
            )
        )
        val report = ControlPlane.validateManifest(manifest)
        assertFalse(report.ok)
        val codes = report.issues.map { it.code }.toSet()
        assertTrue(codes.contains("MANIFEST_DUPLICATE_FILE"))
        assertTrue(codes.contains("MANIFEST_BAD_PATH"))
        assertTrue(codes.contains("MANIFEST_CYCLE"))
    }

    @Test
    fun validManifestNeedsNoFilesOnDiskYet() {
        val manifest = BuildManifest(
            project = "demo",
            entryPoints = listOf("src/Main.kt"),
            files = listOf(ManifestFile("src/Main.kt", entry = true), ManifestFile("README.md")),
            tasks = listOf(ManifestTask("t1", "implementer", "Implement", files = listOf("src/Main.kt")))
        )
        assertTrue(ControlPlane.validateManifest(manifest).ok)
    }

    @Test
    fun ignoredDirectoriesDoNotBecomeOrphans() {
        val snapshot = ProjectSnapshot(
            files = mapOf(
                "build/Generated.kt" to "fun generated() = Unit",
                "src/main/kotlin/Main.kt" to "fun main() = Unit"
            )
        )
        val result = ControlPlane.reachability(snapshot, setOf("src/main/kotlin/Main.kt"))
        assertFalse(result.orphans.contains("build/Generated.kt"))
    }
}
