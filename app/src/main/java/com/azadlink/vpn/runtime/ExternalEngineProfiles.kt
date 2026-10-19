package com.azadlink.vpn.runtime

import com.azadlink.vpn.core.Protocol
import com.azadlink.vpn.core.ProtocolProfile

/**
 * Input validation placeholders for the UI preview. No external tunnel is started here.
 */
object ExternalEngineProfiles {
    fun embeddedPsiphonConfig(raw: String): String? =
        raw.takeIf { it.trim().startsWith("{") && it.contains("PropagationChannelId") }

    fun parseDnsTunnel(profile: ProtocolProfile): Any {
        require(profile.protocol == Protocol.MASTER_DNS || profile.protocol == Protocol.STORM_DNS) {
            "پروفایل DNS tunnel نامعتبر است"
        }
        require(profile.rawConfig.isNotBlank()) { "کانفیگ خالی است" }
        return profile.rawConfig
    }

    fun parseDnstt(raw: String): Any {
        require(raw.trim().startsWith("dnstt://", ignoreCase = true)) { "فرمت DNSTT باید dnstt:// باشد" }
        return raw
    }
}
