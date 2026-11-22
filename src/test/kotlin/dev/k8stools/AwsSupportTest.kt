package dev.k8stools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwsSupportTest {
    @Test
    fun parsesClusterNamesAndRegions() {
        assertEquals(listOf("prod", "dev"), AwsSupport.parseClusterNames("""{"clusters":["prod","dev"]}"""))
        assertTrue(AwsSupport.parseClusterNames("""{"clusters":[]}""").isEmpty())
        assertTrue(AwsSupport.parseClusterNames("").isEmpty())
        assertEquals(listOf("eu-west-1", "il-central-1"), AwsSupport.parseStringArray("""["eu-west-1","il-central-1"]"""))
    }

    @Test
    fun parsesDescribeCluster() {
        val json = """{"cluster":{"name":"prod","arn":"arn:aws:eks:il-central-1:123456789012:cluster/prod",
            "status":"ACTIVE","version":"1.31","endpoint":"https://ABC.yl4.il-central-1.eks.amazonaws.com"}}"""
        val c = AwsSupport.parseCluster(json, "il-central-1")!!
        assertEquals("prod", c.name)
        assertEquals("il-central-1", c.region)
        assertEquals("arn:aws:eks:il-central-1:123456789012:cluster/prod", c.arn)
        assertEquals("ACTIVE", c.status)
        assertEquals("1.31", c.version)
        assertNull(AwsSupport.parseCluster("""{"other":1}""", "x"))
    }

    @Test
    fun envCarriesProfileRegionAndDisablesPager() {
        val e = AwsSupport.env("dev", "eu-west-1")
        assertEquals("dev", e["AWS_PROFILE"])
        assertEquals("eu-west-1", e["AWS_REGION"])
        assertEquals("eu-west-1", e["AWS_DEFAULT_REGION"])
        assertEquals("", e["AWS_PAGER"])
        val bare = AwsSupport.env("", "")
        assertEquals(setOf("AWS_PAGER"), bare.keys)
    }
}
