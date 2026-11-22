package dev.k8stools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClusterInfoTest {
    private val podsJson = """
    {"items":[
      {"metadata":{"name":"web-1","namespace":"prod","creationTimestamp":"2026-11-20T10:00:00Z"},
       "spec":{"nodeName":"node-a","containers":[{"name":"app","image":"nginx:1.27"},{"name":"sidecar","image":"envoy:1"}]},
       "status":{"phase":"Running","podIP":"10.1.0.5","containerStatuses":[
         {"name":"app","ready":true,"restartCount":0,"state":{"running":{}}},
         {"name":"sidecar","ready":true,"restartCount":2,"state":{"running":{}}}]}},
      {"metadata":{"name":"crash-1","namespace":"dev","creationTimestamp":"2026-11-22T11:00:00Z"},
       "spec":{"nodeName":"node-b","containers":[{"name":"app","image":"bad:1"}]},
       "status":{"phase":"Running","containerStatuses":[
         {"name":"app","ready":false,"restartCount":7,"state":{"waiting":{"reason":"CrashLoopBackOff"}}}]}},
      {"metadata":{"name":"pending-1","namespace":"dev","creationTimestamp":"2026-11-22T11:59:00Z"},
       "spec":{"containers":[{"name":"app","image":"x:1"}]},
       "status":{"phase":"Pending"}},
      {"metadata":{"name":"going-1","namespace":"dev","deletionTimestamp":"2026-11-22T12:00:00Z"},
       "spec":{"containers":[{"name":"app","image":"x:1"}]},
       "status":{"phase":"Running","containerStatuses":[{"name":"app","ready":true,"restartCount":0,"state":{"running":{}}}]}}
    ]}"""

    private val nodesJson = """
    {"items":[{"metadata":{"name":"cp-1","creationTimestamp":"2026-01-01T00:00:00Z",
       "labels":{"node-role.kubernetes.io/control-plane":"","kubernetes.io/os":"linux"}},
      "spec":{"unschedulable":true},
      "status":{"conditions":[{"type":"MemoryPressure","status":"False"},{"type":"Ready","status":"True"}],
        "addresses":[{"type":"Hostname","address":"cp-1"},{"type":"InternalIP","address":"10.0.0.7"}],
        "capacity":{"cpu":"4","memory":"16Gi","pods":"110"},
        "nodeInfo":{"kubeletVersion":"v1.31.2","osImage":"Ubuntu 24.04","kernelVersion":"6.8","containerRuntimeVersion":"containerd://1.7","architecture":"amd64"}}},
     {"metadata":{"name":"w-1"},"status":{"conditions":[{"type":"Ready","status":"False"}]}}]}"""

    @Test
    fun parsesPods() {
        val pods = ClusterInfo.parsePods(podsJson)
        assertEquals(listOf("crash-1", "going-1", "pending-1", "web-1"), pods.map { it.name }) // sorted by ns, name
        val web = pods.first { it.name == "web-1" }
        assertEquals("Running", web.status)
        assertEquals("2/2", web.ready)
        assertEquals(2, web.restarts)
        assertEquals("node-a", web.node)
        assertEquals(listOf("app", "sidecar"), web.containers.map { it.name })
        assertEquals("CrashLoopBackOff", pods.first { it.name == "crash-1" }.status)
        assertEquals("Terminating", pods.first { it.name == "going-1" }.status)
        val pending = pods.first { it.name == "pending-1" }
        assertEquals("Pending", pending.status)
        assertEquals("0/1", pending.ready)
        assertEquals("Pending", pending.containers.single().state) // no containerStatuses yet
    }

    @Test
    fun parsesNodes() {
        val nodes = ClusterInfo.parseNodes(nodesJson)
        val cp = nodes.first { it.name == "cp-1" }
        assertTrue(cp.ready)
        assertTrue(cp.unschedulable)
        assertEquals("control-plane", cp.roles)
        assertEquals("10.0.0.7", cp.ip)
        assertEquals("v1.31.2", cp.version)
        assertEquals("16Gi", cp.memory)
        val w = nodes.first { it.name == "w-1" }
        assertFalse(w.ready)
        assertEquals("<none>", w.roles)
    }

    @Test
    fun handlesEmptyAndAge() {
        assertTrue(ClusterInfo.parsePods("").isEmpty())
        assertTrue(ClusterInfo.parseNodes("""{"items":[]}""").isEmpty())
        val now = Instant.parse("2026-11-22T12:00:00Z")
        assertEquals("3d", ClusterInfo.age("2026-11-19T11:00:00Z", now))
        assertEquals("5h", ClusterInfo.age("2026-11-22T07:00:00Z", now))
        assertEquals("1m", ClusterInfo.age("2026-11-22T11:58:59Z", now))
        assertEquals("", ClusterInfo.age("garbage", now))
    }
}
