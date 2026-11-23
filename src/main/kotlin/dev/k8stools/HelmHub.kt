package dev.k8stools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.io.HttpRequests
import java.net.URLEncoder

data class HelmChart(
    val name: String,
    val repoName: String,
    val repoUrl: String,
    val version: String,
    val appVersion: String,
    val description: String,
    val stars: Int,
)

/** Chart search against Artifact Hub, the public Helm hub (uses the IDE's proxy settings). */
object HelmHub {
    private fun JsonObject?.str(key: String): String =
        this?.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    fun search(query: String, limit: Int = 40): List<HelmChart> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://artifacthub.io/api/v1/packages/search?ts_query_web=$q&kind=0&limit=$limit&offset=0"
        val body = try {
            HttpRequests.request(url).accept("application/json").connectTimeout(10_000).readTimeout(20_000).readString(null)
        } catch (e: Exception) {
            throw K8sException("Artifact Hub request failed: ${e.message}")
        }
        val pkgs = JsonParser.parseString(body).asJsonObject.get("packages")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return pkgs.map { it.asJsonObject }.map { p ->
            val repo = p.get("repository")?.takeIf { it.isJsonObject }?.asJsonObject
            HelmChart(
                name = p.str("name"),
                repoName = repo.str("name"),
                repoUrl = repo.str("url"),
                version = p.str("version"),
                appVersion = p.str("app_version"),
                description = p.str("description"),
                stars = p.get("stars")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            )
        }.filter { it.repoUrl.isNotBlank() }
    }

    fun installArgs(
        chart: HelmChart, release: String, namespace: String, createNamespace: Boolean,
        valuesFile: String, dryRun: Boolean, dryRunFlag: String = "--dry-run",
    ): List<String> {
        // --repo avoids touching the user's `helm repo` list.
        val args = mutableListOf(
            "upgrade", "--install", release, chart.name,
            "--repo", chart.repoUrl, "--version", chart.version,
            "--namespace", namespace.ifBlank { "default" },
        )
        if (createNamespace) args += "--create-namespace"
        if (valuesFile.isNotBlank()) args += listOf("-f", valuesFile)
        if (dryRun) args += dryRunFlag
        return args
    }
}
