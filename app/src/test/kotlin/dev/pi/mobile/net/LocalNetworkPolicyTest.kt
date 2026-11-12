package dev.pi.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPolicyTest {
    @Test
    fun addsDefaultPiPortWhenMissing() {
        assertEquals("http://192.168.1.50:7690", LocalNetworkPolicy.normalize("192.168.1.50").baseUrl)
        assertEquals("http://192.168.1.50:7690", LocalNetworkPolicy.normalize("192.168.1.50:7690").baseUrl)
        assertEquals("http://192.168.1.50:7690", LocalNetworkPolicy.normalize(" http://192.168.1.50/ ").baseUrl)
    }

    @Test
    fun keepsExplicitPortsAndScheme() {
        assertEquals("https://10.0.0.5:7690", LocalNetworkPolicy.normalize("https://10.0.0.5").baseUrl)
        assertEquals("http://172.20.1.2:9000", LocalNetworkPolicy.normalize("http://172.20.1.2:9000").baseUrl)
        assertEquals("http://192.168.1.50:80", LocalNetworkPolicy.normalize("192.168.1.50:80").baseUrl)
    }

    @Test
    fun dropsPathQueryAndFragment() {
        assertEquals(
            "http://192.168.1.50:7690",
            LocalNetworkPolicy.normalize("192.168.1.50:7690/some/path?x=1#frag").baseUrl,
        )
    }

    @Test
    fun acceptsPrivateRangesAndLocalNames() {
        assertTrue(LocalNetworkPolicy.normalize("10.1.2.3").ok)
        assertTrue(LocalNetworkPolicy.normalize("172.16.0.9").ok)
        assertTrue(LocalNetworkPolicy.normalize("172.31.255.254").ok)
        assertTrue(LocalNetworkPolicy.normalize("192.168.1.1").ok)
        assertTrue(LocalNetworkPolicy.normalize("169.254.10.20").ok)
        assertTrue(LocalNetworkPolicy.normalize("pi.local").ok)
        assertTrue(LocalNetworkPolicy.normalize("my-pc").ok)
        assertTrue(LocalNetworkPolicy.normalize("[fd00::1]").ok)
        assertTrue(LocalNetworkPolicy.normalize("[fe80::1]").ok)
        assertTrue(LocalNetworkPolicy.normalize("localhost").ok)
    }

    @Test
    fun rejectsPublicAndMalformedTargets() {
        assertFalse(LocalNetworkPolicy.normalize("https://example.com").ok)
        assertFalse(LocalNetworkPolicy.normalize("http://8.8.8.8:7690").ok)
        assertFalse(LocalNetworkPolicy.normalize("100.64.0.1").ok)
        assertFalse(LocalNetworkPolicy.normalize("172.32.0.1").ok)
        assertFalse(LocalNetworkPolicy.normalize("http://192.168.0.1:99999").ok)
        assertFalse(LocalNetworkPolicy.normalize("http://user:pass@192.168.0.1:7690").ok)
        assertFalse(LocalNetworkPolicy.normalize("ftp://192.168.0.1").ok)
        assertFalse(LocalNetworkPolicy.normalize("javascript:alert(1)").ok)
        assertFalse(LocalNetworkPolicy.normalize("").ok)
        assertFalse(LocalNetworkPolicy.normalize("http://192.168.1.50:7690/\nInjected").ok)
    }

    @Test
    fun hostClassification() {
        assertTrue(LocalNetworkPolicy.isPrivateHost("192.168.0.1"))
        assertTrue(LocalNetworkPolicy.isPrivateHost("::ffff:192.168.0.1"))
        assertTrue(LocalNetworkPolicy.isPrivateHost("fd12:3456::1"))
        assertTrue(LocalNetworkPolicy.isPrivateHost("fe80::1"))
        assertTrue(LocalNetworkPolicy.isPrivateHost("::1"))
        assertTrue(LocalNetworkPolicy.isPrivateHost("desktop.local"))
        assertFalse(LocalNetworkPolicy.isPrivateHost("fd00::1.example.com"))
        assertFalse(LocalNetworkPolicy.isPrivateHost("1.1.1.1"))
        assertFalse(LocalNetworkPolicy.isPrivateHost("pi.example.com"))
        assertNull(LocalNetworkPolicy.normalize("192.168.1.50").error)
    }
}
