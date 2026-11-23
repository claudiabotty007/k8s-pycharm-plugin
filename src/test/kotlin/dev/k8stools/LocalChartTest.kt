package dev.k8stools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChartTest {
    @Test
    fun parsesChartYaml() {
        val m = LocalChart.parseMeta(
            """
            # a comment
            apiVersion: v2
            name: my-app
            description: "A chart: with a colon"
            type: application
            version: 0.1.0
            appVersion: '1.16.0'
            dependencies:
              - name: redis
                version: 1.2.3
            """.trimIndent()
        )!!
        assertEquals("my-app", m.name) // not the dependency's name (indented)
        assertEquals("0.1.0", m.version)
        assertEquals("1.16.0", m.appVersion)
        assertEquals("application", m.type)
        assertEquals("A chart: with a colon", m.description)
        assertNull(LocalChart.parseMeta("apiVersion: v2\nversion: 1.0.0"))
        assertEquals("1.0.0", LocalChart.parseMeta("name: x\nversion: 1.0.0 # pinned")!!.version)
    }

    @Test
    fun validatesNames() {
        assertTrue(LocalChart.validName("my-app"))
        assertTrue(LocalChart.validName("a1"))
        assertFalse(LocalChart.validName("My-App"))
        assertFalse(LocalChart.validName("-bad"))
        assertFalse(LocalChart.validName("bad-"))
        assertFalse(LocalChart.validName("has_underscore"))
        assertFalse(LocalChart.validName(""))
        assertFalse(LocalChart.validName("a".repeat(54)))
    }

    @Test
    fun buildsCommands() {
        assertEquals(listOf("lint", "/c"), LocalChart.lintArgs("/c", ""))
        assertEquals(listOf("lint", "/c", "-f", "v.yaml"), LocalChart.lintArgs("/c", "v.yaml"))
        assertEquals(listOf("template", "rel", "/c", "--namespace", "default"), LocalChart.templateArgs("rel", "/c", "", ""))
        assertEquals(listOf("create", "x"), LocalChart.createArgs("x"))
        assertEquals(listOf("package", "/c", "--destination", "/out"), LocalChart.packageArgs("/c", "/out"))
        assertEquals(
            listOf("upgrade", "--install", "rel", "/c", "--namespace", "prod", "--create-namespace", "-f", "v.yaml", "--dry-run"),
            LocalChart.installArgs("/c", "rel", "prod", true, "v.yaml", true)
        )
        assertEquals(
            listOf("upgrade", "--install", "rel", "/c", "--namespace", "default"),
            LocalChart.installArgs("/c", "rel", "", false, "", false)
        )
    }
}
