package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryAuditTest {

    @Test
    fun comparesVersionsNumerically() {
        assertEquals(-1, LibraryAudit.compareVersions("1.13.1", "1.14.0"))
        assertEquals(0, LibraryAudit.compareVersions("1.2", "1.2.0"))
        assertEquals(1, LibraryAudit.compareVersions("2.0.0", "1.99.99"))
        assertNull(LibraryAudit.compareVersions("^1.2.3", "1.3.0"))
    }

    @Test
    fun classifiesUpgradeLevel() {
        assertEquals("major", LibraryAudit.levelOf("18.2.0", "19.3.1"))
        assertEquals("minor", LibraryAudit.levelOf("1.13.1", "1.14.0"))
        assertEquals("patch", LibraryAudit.levelOf("1.13.1", "1.13.2"))
        assertEquals("same", LibraryAudit.levelOf("1.13.1", "1.13.1"))
        assertEquals("unknown", LibraryAudit.levelOf("^1.0", "1.2.0"))
    }

    @Test
    fun normalizesEcosystemAliases() {
        assertEquals("Maven", LibraryAudit.normalizeEcosystem("gradle"))
        assertEquals("npm", LibraryAudit.normalizeEcosystem("node"))
        assertEquals("PyPI", LibraryAudit.normalizeEcosystem("pip"))
        assertEquals("crates.io", LibraryAudit.normalizeEcosystem("cargo"))
        assertEquals("Go", LibraryAudit.normalizeEcosystem("golang"))
    }

    @Test
    fun offlineLookupIsReportedWithoutNetwork() {
        val info = LibraryAudit.lookup("npm", "react", online = false)
        assertEquals("react", info.name)
        assertEquals(false, info.error.isNullOrBlank())
    }
}
