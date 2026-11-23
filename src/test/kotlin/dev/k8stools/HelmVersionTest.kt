package dev.k8stools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelmVersionTest {
    @Test
    fun parsesVersions() {
        assertEquals(4 to 3, HelmVersion.parse("v4.3.2+g8346aaa"))
        assertEquals(3 to 18, HelmVersion.parse("v3.18.4+gabc123\n"))
        assertNull(HelmVersion.parse("not a version"))
    }

    @Test
    fun choosesDryRunFlag() {
        assertEquals("--dry-run=client", HelmVersion.dryRunFlag("v4.3.2+g8346aaa"))
        assertEquals("--dry-run=client", HelmVersion.dryRunFlag("v3.13.0+gabc"))
        assertEquals("--dry-run=client", HelmVersion.dryRunFlag("v3.18.4+gabc"))
        assertEquals("--dry-run", HelmVersion.dryRunFlag("v3.12.3+gabc")) // valued form not supported yet
        assertEquals("--dry-run", HelmVersion.dryRunFlag("v2.17.0"))
        assertEquals("--dry-run", HelmVersion.dryRunFlag("garbage"))
        assertEquals("--dry-run", HelmVersion.dryRunFlag(null))
    }

    @Test
    fun installArgsUseTheChosenFlag() {
        assertEquals(
            listOf("upgrade", "--install", "rel", "/c", "--namespace", "default", "--dry-run=client"),
            LocalChart.installArgs("/c", "rel", "", false, "", true, "--dry-run=client")
        )
        val chart = HelmChart("nginx", "bitnami", "https://charts.example.com", "1.2.3", "", "", 0)
        assertEquals(
            "--dry-run=client",
            HelmHub.installArgs(chart, "rel", "", false, "", true, "--dry-run=client").last()
        )
        // Not a dry run: the flag must not appear at all.
        assertEquals(false, HelmHub.installArgs(chart, "rel", "", false, "", false, "--dry-run=client").contains("--dry-run=client"))
    }
}
