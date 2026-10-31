package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyAuditTest {

    @Test
    fun parsesGradleDependencies() {
        val text = """
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
                testImplementation("junit:junit:4.13.2")
                implementation(platform("androidx.compose:compose-bom:2024.10.01"))
            }
        """.trimIndent()
        val deps = DependencyAudit.parseGradle(text, "app/build.gradle.kts")
        assertEquals(2, deps.size)
        assertTrue(deps.any { it.name == "androidx.core:core-ktx" && it.version == "1.13.1" })
        assertTrue(deps.any { it.name == "junit:junit" && it.scope == "testImplementation" })
    }

    @Test
    fun parsesPackageJsonScopes() {
        val text = """
            {"dependencies":{"react":"^18.2.0"},"devDependencies":{"vite":"5.0.0"}}
        """.trimIndent()
        val deps = DependencyAudit.parsePackageJson(text, "package.json")
        assertEquals(2, deps.size)
        assertTrue(deps.any { it.name == "react" && it.ecosystem == "npm" })
        assertTrue(deps.any { it.name == "vite" && it.scope == "devDependencies" })
    }

    @Test
    fun parsesRequirementsWithOperators() {
        val text = """
            # comment
            requests==2.31.0
            flask>=3.0
            -r other.txt
        """.trimIndent()
        val deps = DependencyAudit.parseRequirements(text, "requirements.txt")
        assertEquals(2, deps.size)
        assertTrue(deps.any { it.name == "requests" && it.version == "==2.31.0" })
        assertTrue(deps.any { it.name == "flask" && it.version == ">=3.0" })
    }

    @Test
    fun exactVersionRejectsRangesAndAcceptsPins() {
        assertEquals("1.2.3", DependencyAudit.exactVersion("1.2.3"))
        assertEquals("2.31.0", DependencyAudit.exactVersion("==2.31.0"))
        assertNull(DependencyAudit.exactVersion("^1.2.3"))
        assertNull(DependencyAudit.exactVersion(">=1.0"))
        assertNull(DependencyAudit.exactVersion("${'$'}kotlinVersion"))
        assertNull(DependencyAudit.exactVersion("1.2.+"))
        assertNull(DependencyAudit.exactVersion("1.0 || 2.0"))
    }
}
