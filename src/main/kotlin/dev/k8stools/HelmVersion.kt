package dev.k8stools

/**
 * Helm 4 deprecates the bare `--dry-run` in favour of `--dry-run=client`. The valued form exists since Helm 3.13,
 * so older Helm keeps the plain flag.
 */
object HelmVersion {
    private val versionRe = Regex("""v?(\d+)\.(\d+)""")

    /** Parses the output of `helm version --short` (e.g. `v4.3.2+g8346aaa`) into (major, minor). */
    fun parse(output: String): Pair<Int, Int>? {
        val m = versionRe.find(output) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    fun dryRunFlag(versionOutput: String?): String {
        val (major, minor) = parse(versionOutput.orEmpty()) ?: return "--dry-run"
        return if (major > 3 || (major == 3 && minor >= 13)) "--dry-run=client" else "--dry-run"
    }

    /** Asks the installed helm; falls back to the plain flag when it cannot be run or parsed. */
    fun detectDryRunFlag(): String {
        val r = Cli.run(Tool.HELM, listOf("version", "--short"), timeoutMs = 15_000)
        return dryRunFlag(if (r.ok) r.stdout else null)
    }
}
