package de.mario.noipduc

import de.mario.noipduc.NoIpProtocol.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoIpProtocolTest {

    @Test fun publicIpv4() {
        assertTrue(NoIpProtocol.isPublicIpv4("93.184.216.34"))
        assertTrue(NoIpProtocol.isPublicIpv4("8.8.8.8"))
        // private, lokale, CGNAT und ungültige Adressen werden abgelehnt
        listOf(
            "10.0.0.1", "192.168.1.1", "172.16.0.1", "172.31.255.255", "127.0.0.1",
            "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255",
            "256.1.1.1", "1.2.3", "1.2.3.4.5", "01.2.3.4", "1.2.3.4\n", "<script>", "",
            "1.2.3.4; rm -rf /", "::1",
        ).forEach { assertFalse(it, NoIpProtocol.isPublicIpv4(it)) }
    }

    @Test fun hostnames() {
        val (ok, err) = NoIpProtocol.parseHostnames(" MeinHost.ddns.net, zwei.hopto.org ;meinhost.ddns.net ")
        assertNull(err)
        assertEquals(listOf("meinhost.ddns.net", "zwei.hopto.org"), ok)
        assertEquals(listOf("all.ddnskey.com"), NoIpProtocol.parseHostnames("all.ddnskey.com").first)

        listOf("", "localhost", "a..b.com", "-x.ddns.net", "x-.ddns.net", "evil.com&myip=1.1.1.1",
            "host.ddns.net/..", "hö.ddns.net", "a b").forEach {
            assertNotNull(it, NoIpProtocol.parseHostnames(it).second)
        }
        val many = (1..21).joinToString(",") { "h$it.ddns.net" }
        assertNotNull(NoIpProtocol.parseHostnames(many).second)
    }

    @Test fun responses() {
        val ip = "93.184.216.34"
        assertTrue(NoIpProtocol.parseResponse(200, "good $ip", ip) is Result.Ok)
        assertTrue(NoIpProtocol.parseResponse(200, "nochg $ip\r\n", ip) is Result.Ok)
        assertTrue(NoIpProtocol.parseResponse(200, "good $ip\nnochg $ip", ip) is Result.Ok)
        assertTrue(NoIpProtocol.parseResponse(200, "good $ip\nnohost", ip) is Result.Fatal)
        listOf("nohost", "badauth", "badagent", "!donator", "abuse").forEach {
            assertTrue(it, NoIpProtocol.parseResponse(200, it, ip) is Result.Fatal)
        }
        assertTrue(NoIpProtocol.parseResponse(401, "", ip) is Result.Fatal)
        val r911 = NoIpProtocol.parseResponse(200, "911", ip)
        assertTrue(r911 is Result.Retry && r911.waitMinutes == 30)
        assertTrue(NoIpProtocol.parseResponse(503, "", ip) is Result.Retry)
        assertTrue(NoIpProtocol.parseResponse(302, "", ip) is Result.Retry)
        assertTrue(NoIpProtocol.parseResponse(200, "", ip) is Result.Retry)
        // "goodbye" darf nicht als "good" durchgehen
        assertTrue(NoIpProtocol.parseResponse(200, "goodbye", ip) is Result.Retry)
    }

    @Test fun sanitizeUnknownServerText() {
        val r = NoIpProtocol.parseResponse(200, "<html>\u001b[31m" + "x".repeat(500), "1.1.1.1")
        assertTrue(r is Result.Retry)
        assertFalse(r.message.contains('\u001b'))
        assertTrue(r.message.length < 120)
    }
}
