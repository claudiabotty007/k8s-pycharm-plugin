package dev.k8stools

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.util.Base64

data class TfOutput(val name: String, val value: String)

data class TfClusterGuess(val endpoint: TfOutput?, val caData: TfOutput?, val clusterName: TfOutput?)

object TerraformSupport {
    /** Runs `terraform output -json` and flattens nested values to dotted keys. */
    fun outputs(dir: String): List<TfOutput> {
        if (!File(dir).isDirectory) throw K8sException("Not a directory: $dir")
        val json = Cli.run(Tool.TERRAFORM, listOf("output", "-json", "-no-color"), workDir = dir).orThrow().stdout
        if (json.isBlank()) return emptyList()
        val root = JsonParser.parseString(json).asJsonObject
        val result = mutableListOf<TfOutput>()
        for ((name, wrapper) in root.entrySet()) {
            val value = wrapper.asJsonObject.get("value") ?: continue
            flatten(name, value, result)
        }
        return result
    }

    private fun flatten(key: String, el: JsonElement, out: MutableList<TfOutput>) {
        when {
            el.isJsonNull -> {}
            el.isJsonPrimitive -> out += TfOutput(key, el.asString)
            el.isJsonObject -> el.asJsonObject.entrySet().forEach { (k, v) -> flatten("$key.$k", v, out) }
            el.isJsonArray -> el.asJsonArray.forEachIndexed { i, v -> flatten("$key[$i]", v, out) }
        }
    }

    private val hostLike = Regex("""^(https?://)?[A-Za-z0-9.-]+\.[A-Za-z]{2,}(:\d+)?(/.*)?$|^https?://.+""")

    fun looksLikeEndpoint(o: TfOutput): Boolean = !o.value.contains('\n') && hostLike.matches(o.value.trim())

    private fun endpointScore(o: TfOutput): Int {
        if (!looksLikeEndpoint(o)) return -100
        val k = o.name.lowercase()
        var s = 0
        if (o.value.startsWith("http")) s += 2
        if (listOf("endpoint", "url", "server", "host", "api").any { k.contains(it) }) s += 2
        if (listOf("cluster", "k8s", "kube", "eks", "gke", "aks").any { k.contains(it) }) s += 2
        if (listOf("ca_", "cert", "token", "oidc", "registry", "ecr").any { k.contains(it) }) s -= 5
        return s
    }

    fun guess(outputs: List<TfOutput>): TfClusterGuess {
        val endpoint = outputs.maxByOrNull { endpointScore(it) }?.takeIf { endpointScore(it) >= 2 }
        val ca = outputs.firstOrNull {
            val k = it.name.lowercase()
            (k.contains("certificate_authority") || k.contains("cluster_ca") || k.contains("ca_cert") || k.endsWith("ca_data")) &&
                    it.value.length > 50
        }
        val name = outputs.firstOrNull { it.name.lowercase().let { k -> k.contains("cluster_name") || k == "cluster_id" } }
        return TfClusterGuess(endpoint, ca, name)
    }

    fun normalizeEndpoint(v: String): String {
        val t = v.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    /** Writes the CA (PEM or base64-encoded PEM) to ~/.kube/k8s-tools/<name>-ca.crt and returns the file path. */
    fun writeCaFile(contextName: String, caData: String): String {
        val trimmed = caData.trim()
        val pem = if (trimmed.startsWith("-----BEGIN")) trimmed
        else try {
            String(Base64.getMimeDecoder().decode(trimmed), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw K8sException("CA data is neither PEM nor valid base64.")
        }
        if (!pem.contains("BEGIN CERTIFICATE")) throw K8sException("CA data does not contain a certificate.")
        val safe = contextName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(System.getProperty("user.home"), ".kube/k8s-tools").apply { mkdirs() }
        return File(dir, "$safe-ca.crt").apply { writeText(pem.trim() + "\n") }.absolutePath
    }
}
