package dev.k8stools

import java.io.File

data class ChartMeta(
    val name: String,
    val version: String,
    val appVersion: String,
    val type: String,
    val description: String,
)

/** Helpers for working on a Helm chart that lives in a local folder. */
object LocalChart {
    private val nameRe = Regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")

    /** Chart / release names must be lowercase DNS-style labels (Helm limits release names to 53 chars). */
    fun validName(n: String): Boolean = n.length <= 53 && nameRe.matches(n)

    fun isChartDir(dir: String): Boolean = dir.isNotBlank() && File(dir, "Chart.yaml").isFile

    fun readMeta(dir: String): ChartMeta? =
        File(dir, "Chart.yaml").takeIf { it.isFile }?.let { parseMeta(it.readText()) }

    /** Reads the top-level scalar keys of Chart.yaml (no YAML library needed for these). */
    fun parseMeta(yaml: String): ChartMeta? {
        val top = yaml.lineSequence()
            .filter { it.isNotEmpty() && !it[0].isWhitespace() && !it.startsWith("#") && !it.startsWith("-") }
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to unquote(line.substring(i + 1))
            }
            .toMap()
        val name = top["name"]?.takeIf { it.isNotBlank() } ?: return null
        return ChartMeta(name, top["version"].orEmpty(), top["appVersion"].orEmpty(), top["type"] ?: "application", top["description"].orEmpty())
    }

    private fun unquote(raw: String): String {
        var v = raw.trim()
        if (v.length >= 2 && (v[0] == '"' || v[0] == '\'')) {
            val end = v.indexOf(v[0], 1)
            return if (end > 0) v.substring(1, end) else v.substring(1)
        }
        val hash = v.indexOf(" #")
        if (hash >= 0) v = v.substring(0, hash)
        return v.trim()
    }

    private fun withValues(args: MutableList<String>, valuesFile: String) {
        if (valuesFile.isNotBlank()) args += listOf("-f", valuesFile)
    }

    fun lintArgs(dir: String, valuesFile: String): List<String> = mutableListOf("lint", dir).also { withValues(it, valuesFile) }

    fun templateArgs(release: String, dir: String, namespace: String, valuesFile: String): List<String> =
        mutableListOf("template", release, dir, "--namespace", namespace.ifBlank { "default" }).also { withValues(it, valuesFile) }

    fun dependencyUpdateArgs(dir: String): List<String> = listOf("dependency", "update", dir)

    fun packageArgs(dir: String, destination: String): List<String> = listOf("package", dir, "--destination", destination)

    fun createArgs(name: String): List<String> = listOf("create", name)

    fun installArgs(dir: String, release: String, namespace: String, createNamespace: Boolean, valuesFile: String, dryRun: Boolean): List<String> {
        val args = mutableListOf("upgrade", "--install", release, dir, "--namespace", namespace.ifBlank { "default" })
        if (createNamespace) args += "--create-namespace"
        withValues(args, valuesFile)
        if (dryRun) args += "--dry-run"
        return args
    }
}
