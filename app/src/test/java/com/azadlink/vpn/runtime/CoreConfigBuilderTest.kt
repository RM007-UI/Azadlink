package com.azadlink.vpn.runtime

import com.azadlink.vpn.core.Protocol
import com.azadlink.vpn.core.ProtocolProfile
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreConfigBuilderTest {
    @Test
    fun buildsVlessWebSocketTlsConfigWithProxyRouting() {
        val profile = ProtocolProfile(
            protocol = Protocol.VLESS,
            label = "test",
            rawConfig = "vless://11111111-1111-4111-8111-111111111111@edge.example:443?type=ws&security=tls&sni=cdn.example&host=cdn.example&path=%2Fws"
        )

        val root = JsonParser.parseString(CoreConfigBuilder.build(profile)).asJsonObject
        val outbound = root.getAsJsonArray("outbounds").first().asJsonObject
        val routingRule = root.getAsJsonObject("routing").getAsJsonArray("rules").first().asJsonObject

        assertEquals("vless", outbound.get("protocol").asString)
        assertEquals("cdn.example", outbound.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings").get("serverName").asString)
        assertEquals("/ws", outbound.getAsJsonObject("streamSettings").getAsJsonObject("wsSettings").get("path").asString)
        assertEquals("proxy", routingRule.get("outboundTag").asString)
        assertTrue(root.getAsJsonArray("inbounds").size() >= 2)
    }

    @Test
    fun rejectsInvalidPortAndUnsupportedProtocol() {
        val invalidPort = ProtocolProfile(
            protocol = Protocol.VLESS,
            label = "bad",
            rawConfig = "vless://11111111-1111-4111-8111-111111111111@edge.example:99999?security=tls"
        )
        val unsupported = ProtocolProfile(protocol = Protocol.MASQUE, label = "masque", rawConfig = "masque://")

        assertFalse(runCatching { CoreConfigBuilder.build(invalidPort) }.isSuccess)
        assertFalse(runCatching { CoreConfigBuilder.build(unsupported) }.isSuccess)
    }
}
