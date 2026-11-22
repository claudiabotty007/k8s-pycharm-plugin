package dev.k8stools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date

data class KubeContext(
    val name: String,
    val cluster: String,
    val user: String,
    val namespace: String,
    val current: Boolean,
)

data class NewContext(
    val name: String,
    val server: String,
    val namespace: String = "",
    val user: String = "",
    val token: String = "",
    val caFile: String = "",
    val insecure: Boolean = false,
)

object Kube {
    private fun JsonObject?.str(key: String): String =
        this?.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    fun contexts(): List<KubeContext> {
        val json = Cli.run(Tool.KUBECTL, listOf("config", "view", "-o", "json")).orThrow().stdout
        if (json.isBlank()) return emptyList()
        val root = JsonParser.parseString(json).asJsonObject
        val current = root.str("current-context")
        val arr = root.get("contexts")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.map { e ->
            val o = e.asJsonObject
            val c = o.get("context")?.takeIf { it.isJsonObject }?.asJsonObject
            val name = o.str("name")
            KubeContext(name, c.str("cluster"), c.str("user"), c.str("namespace"), name == current)
        }
    }

    /** Empty string when no context is selected. */
    fun currentContext(): String {
        val r = Cli.run(Tool.KUBECTL, listOf("config", "current-context"))
        return if (r.ok) r.stdout.trim() else ""
    }

    fun useContext(name: String) {
        Cli.run(Tool.KUBECTL, listOf("config", "use-context", name)).orThrow()
    }

    fun deleteContext(name: String) {
        Cli.run(Tool.KUBECTL, listOf("config", "delete-context", name)).orThrow()
    }

    fun addContext(c: NewContext) {
        val cluster = mutableListOf("config", "set-cluster", c.name, "--server=${c.server}")
        if (c.insecure) cluster += "--insecure-skip-tls-verify=true"
        else if (c.caFile.isNotBlank()) cluster += listOf("--certificate-authority=${c.caFile}", "--embed-certs=true")
        Cli.run(Tool.KUBECTL, cluster).orThrow()

        var userName = c.user
        if (c.token.isNotBlank()) {
            if (userName.isBlank()) userName = c.name
            Cli.run(Tool.KUBECTL, listOf("config", "set-credentials", userName, "--token=${c.token}")).orThrow()
        }

        val ctx = mutableListOf("config", "set-context", c.name, "--cluster=${c.name}")
        if (userName.isNotBlank()) ctx += "--user=$userName"
        if (c.namespace.isNotBlank()) ctx += "--namespace=${c.namespace}"
        Cli.run(Tool.KUBECTL, ctx).orThrow()
    }

    /** Merges another kubeconfig file into the default one (a timestamped backup is written first). */
    fun importKubeconfig(path: String) {
        val envVal = System.getenv("KUBECONFIG").orEmpty()
        val entries = envVal.split(File.pathSeparator).filter { it.isNotBlank() }
        if (entries.size > 1) throw K8sException("KUBECONFIG lists several files; import is only supported with a single kubeconfig.")
        val target = File(entries.firstOrNull() ?: File(System.getProperty("user.home"), ".kube/config").path)
        val merged = Cli.run(
            Tool.KUBECTL, listOf("config", "view", "--flatten"),
            env = mapOf("KUBECONFIG" to listOfNotNull(target.takeIf { it.isFile }?.path, path).joinToString(File.pathSeparator))
        ).orThrow().stdout
        target.parentFile?.mkdirs()
        if (target.isFile) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            Files.copy(target.toPath(), File(target.parentFile, "${target.name}.bak-$stamp").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        target.writeText(merged)
    }

    fun applyArgs(paths: List<String>, namespace: String, dryRun: Boolean): List<String> {
        val args = mutableListOf("apply")
        paths.forEach { args += listOf("-f", it) }
        if (namespace.isNotBlank()) args += listOf("-n", namespace)
        if (dryRun) args += "--dry-run=server"
        return args
    }
}
