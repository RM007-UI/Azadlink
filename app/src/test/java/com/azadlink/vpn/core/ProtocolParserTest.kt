package com.azadlink.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolParserTest {
    @Test
    fun parsesMultipleShareLinksAndIgnoresComments() {
        val input = """
            # saved profiles
            vless://11111111-1111-4111-8111-111111111111@edge.example:443?security=tls
            trojan://password@trojan.example:443?security=tls
        """.trimIndent()

        val result = ProtocolParser.parse(input)

        assertEquals(listOf(Protocol.VLESS, Protocol.TROJAN), result.profiles.map { it.protocol })
        assertTrue(result.rejectedLines.isEmpty())
    }

    @Test
    fun parsesWireGuardBlockAsOneProfile() {
        val input = """
            [Interface]
            PrivateKey = private
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = public
            Endpoint = peer.example:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val result = ProtocolParser.parse(input)

        assertEquals(1, result.profiles.size)
        assertEquals(Protocol.WIREGUARD, result.profiles.single().protocol)
    }

    @Test
    fun rejectsUnsupportedOrMalformedLinesWithoutLeakingRawSecrets() {
        val result = ProtocolParser.parse("not-a-link\nvless://bad")

        assertTrue(result.profiles.isEmpty())
        assertEquals(2, result.rejectedLines.size)
        assertTrue(result.rejectedLines.none { it.contains("not-a-link") || it.contains("vless://bad") })
    }
}
