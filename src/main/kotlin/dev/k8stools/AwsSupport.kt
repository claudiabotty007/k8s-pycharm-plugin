package dev.k8stools

import com.google.gson.JsonParser
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable

data class EksCluster(
    val name: String,
    val region: String,
    val arn: String,
    val status: String,
    val version: String,
    val endpoint: String,
)

class ClusterScan(val clusters: List<EksCluster>, val errors: List<String>)

/** Thin wrapper over the AWS CLI. Profile / region are passed through env vars so every command honours them. */
object AwsSupport {
    fun env(profile: String, region: String): Map<String, String> = buildMap {
        put("AWS_PAGER", "") // never page: there is no terminal
        if (profile.isNotBlank()) put("AWS_PROFILE", profile)
        if (region.isNotBlank()) {
            put("AWS_REGION", region)
            put("AWS_DEFAULT_REGION", region)
        }
    }

    fun profiles(): List<String> {
        val r = Cli.run(Tool.AWS, listOf("configure", "list-profiles"), env = env("", ""))
        return if (r.ok) r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }

    fun defaultRegion(profile: String): String {
        val r = Cli.run(Tool.AWS, listOf("configure", "get", "region"), env = env(profile, ""))
        return if (r.ok) r.stdout.trim() else ""
    }

    /** "<account> - <arn>" of the credentials currently in use. */
    fun identity(env: Map<String, String>): String {
        val json = Cli.run(Tool.AWS, listOf("sts", "get-caller-identity", "--output", "json"), env = env, timeoutMs = 30_000).orThrow().stdout
        val o = JsonParser.parseString(json).asJsonObject
        return "${o.get("Account")?.asString ?: "?"} - ${o.get("Arn")?.asString ?: "?"}"
    }

    /** Regions enabled for the account (opt-in regions such as il-central-1 are included once enabled). */
    fun regions(env: Map<String, String>): List<String> =
        parseStringArray(
            Cli.run(Tool.AWS, listOf("ec2", "describe-regions", "--query", "Regions[].RegionName", "--output", "json"), env = env, timeoutMs = 30_000)
                .orThrow().stdout
        ).sorted()

    fun scan(env: Map<String, String>, regions: List<String>): ClusterScan {
        val pool = AppExecutorUtil.createBoundedApplicationPoolExecutor("K8sToolsAwsScan", 6)
        val futures = regions.map { r -> pool.submit(Callable { scanRegion(env, r) }) }
        val clusters = mutableListOf<EksCluster>()
        val errors = mutableListOf<String>()
        futures.forEach { f ->
            val (found, err) = f.get()
            clusters += found
            err?.let { errors += it }
        }
        return ClusterScan(clusters.sortedWith(compareBy({ it.region }, { it.name })), errors)
    }

    private fun scanRegion(env: Map<String, String>, region: String): Pair<List<EksCluster>, String?> {
        val list = Cli.run(Tool.AWS, listOf("eks", "list-clusters", "--region", region, "--output", "json"), env = env, timeoutMs = 60_000)
        if (!list.ok) return emptyList<EksCluster>() to "$region: ${list.message.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()}"
        val found = parseClusterNames(list.stdout).mapNotNull { name ->
            val d = Cli.run(
                Tool.AWS, listOf("eks", "describe-cluster", "--name", name, "--region", region, "--output", "json"),
                env = env, timeoutMs = 60_000,
            )
            if (d.ok) parseCluster(d.stdout, region) else EksCluster(name, region, "", "?", "", "")
        }
        return found to null
    }

    // ---- pure parsing (unit-tested) ----

    fun parseStringArray(json: String): List<String> =
        if (json.isBlank()) emptyList()
        else JsonParser.parseString(json).asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }

    fun parseClusterNames(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        val arr = JsonParser.parseString(json).asJsonObject.get("clusters")?.takeIf { it.isJsonArray } ?: return emptyList()
        return arr.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
    }

    fun parseCluster(json: String, region: String): EksCluster? {
        if (json.isBlank()) return null
        val c = JsonParser.parseString(json).asJsonObject.get("cluster")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        fun s(k: String) = c.get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        return EksCluster(s("name"), region, s("arn"), s("status"), s("version"), s("endpoint"))
    }
}
