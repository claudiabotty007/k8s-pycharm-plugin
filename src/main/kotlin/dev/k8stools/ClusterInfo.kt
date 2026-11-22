package dev.k8stools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Duration
import java.time.Instant

data class ContainerInfo(
    val name: String,
    val image: String,
    val ready: Boolean,
    val restarts: Int,
    val state: String,
)

data class PodInfo(
    val name: String,
    val namespace: String,
    val status: String,
    val ready: String,
    val restarts: Int,
    val node: String,
    val ip: String,
    val age: String,
    val containers: List<ContainerInfo>,
)

data class NodeInfo(
    val name: String,
    val ready: Boolean,
    val roles: String,
    val version: String,
    val os: String,
    val kernel: String,
    val runtime: String,
    val arch: String,
    val ip: String,
    val cpu: String,
    val memory: String,
    val maxPods: String,
    val age: String,
    val unschedulable: Boolean,
)

class PodsResult(val pods: List<PodInfo>, val note: String?)

/** Reads nodes / pods / containers via kubectl JSON output. Parsing is pure and unit-testable. */
object ClusterInfo {
    private const val TIMEOUT = "--request-timeout=15s"

    fun nodes(): List<NodeInfo> =
        parseNodes(Cli.run(Tool.KUBECTL, listOf("get", "nodes", "-o", "json", TIMEOUT), timeoutMs = 30_000).orThrow().stdout)

    fun pods(): PodsResult {
        val all = Cli.run(Tool.KUBECTL, listOf("get", "pods", "-A", "-o", "json", TIMEOUT), timeoutMs = 30_000)
        if (all.ok) return PodsResult(parsePods(all.stdout), null)
        // Typically RBAC: no cluster-wide list permission. Fall back to the context's namespace.
        val ns = Cli.run(Tool.KUBECTL, listOf("get", "pods", "-o", "json", TIMEOUT), timeoutMs = 30_000)
        if (!ns.ok) throw K8sException(all.message)
        return PodsResult(parsePods(ns.stdout), "No cluster-wide access - showing the context's default namespace only.")
    }

    // ---- JSON helpers ----
    private fun JsonObject?.obj(k: String): JsonObject? = this?.get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject?.arr(k: String): JsonArray = this?.get(k)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject?.str(k: String): String = this?.get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    private fun JsonObject?.int(k: String): Int = this?.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    private fun JsonObject?.bool(k: String): Boolean = this?.get(k)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    fun age(timestamp: String, now: Instant = Instant.now()): String {
        if (timestamp.isBlank()) return ""
        val d = try {
            Duration.between(Instant.parse(timestamp), now)
        } catch (e: Exception) {
            return ""
        }
        return when {
            d.toDays() >= 2 -> "${d.toDays()}d"
            d.toHours() >= 1 -> "${d.toHours()}h"
            d.toMinutes() >= 1 -> "${d.toMinutes()}m"
            else -> "${maxOf(d.seconds, 0)}s"
        }
    }

    fun parsePods(json: String): List<PodInfo> {
        if (json.isBlank()) return emptyList()
        return JsonParser.parseString(json).asJsonObject.arr("items").map { it.asJsonObject }.map { p ->
            val meta = p.obj("metadata")
            val spec = p.obj("spec")
            val status = p.obj("status")
            val statuses = status.arr("containerStatuses").map { it.asJsonObject }.associateBy { it.str("name") }

            val containers = spec.arr("containers").map { it.asJsonObject }.map { c ->
                val cs = statuses[c.str("name")]
                val st = cs.obj("state")
                val state = when {
                    st == null -> "Pending"
                    st.obj("running") != null -> "Running"
                    st.obj("waiting") != null -> st.obj("waiting").str("reason").ifBlank { "Waiting" }
                    st.obj("terminated") != null -> st.obj("terminated").str("reason").ifBlank { "Terminated" }
                    else -> "Unknown"
                }
                ContainerInfo(c.str("name"), c.str("image"), cs.bool("ready"), cs.int("restartCount"), state)
            }

            val phase = status.str("phase")
            // e.g. CrashLoopBackOff / ImagePullBackOff, as `kubectl get pods` shows them.
            val waitingReason = statuses.values.firstNotNullOfOrNull {
                it.obj("state").obj("waiting")?.str("reason")?.takeIf { r -> r.isNotBlank() }
            }
            val shown = when {
                meta?.get("deletionTimestamp") != null -> "Terminating"
                status.str("reason").isNotBlank() -> status.str("reason")
                waitingReason != null -> waitingReason
                else -> phase.ifBlank { "Unknown" }
            }
            PodInfo(
                name = meta.str("name"),
                namespace = meta.str("namespace"),
                status = shown,
                ready = "${containers.count { it.ready }}/${containers.size}",
                restarts = containers.sumOf { it.restarts },
                node = spec.str("nodeName"),
                ip = status.str("podIP"),
                age = age(meta.str("creationTimestamp")),
                containers = containers,
            )
        }.sortedWith(compareBy({ it.namespace }, { it.name }))
    }

    fun parseNodes(json: String): List<NodeInfo> {
        if (json.isBlank()) return emptyList()
        return JsonParser.parseString(json).asJsonObject.arr("items").map { it.asJsonObject }.map { n ->
            val meta = n.obj("metadata")
            val status = n.obj("status")
            val info = status.obj("nodeInfo")
            val cap = status.obj("capacity")
            val ready = status.arr("conditions").map { it.asJsonObject }.any { it.str("type") == "Ready" && it.str("status") == "True" }
            val roles = meta.obj("labels")?.keySet().orEmpty()
                .filter { it.startsWith("node-role.kubernetes.io/") }.map { it.substringAfter('/') }.sorted()
            val ip = status.arr("addresses").map { it.asJsonObject }.firstOrNull { it.str("type") == "InternalIP" }.str("address")
            NodeInfo(
                name = meta.str("name"),
                ready = ready,
                roles = roles.joinToString(",").ifBlank { "<none>" },
                version = info.str("kubeletVersion"),
                os = info.str("osImage"),
                kernel = info.str("kernelVersion"),
                runtime = info.str("containerRuntimeVersion"),
                arch = info.str("architecture"),
                ip = ip,
                cpu = cap.str("cpu"),
                memory = cap.str("memory"),
                maxPods = cap.str("pods"),
                age = age(meta.str("creationTimestamp")),
                unschedulable = n.obj("spec").bool("unschedulable"),
            )
        }.sortedBy { it.name }
    }
}
