package com.azadlink.vpn.runtime

import com.azadlink.vpn.core.Protocol
import com.azadlink.vpn.core.ProtocolProfile

/**
 * Configuration validation only. This function does not start a tunnel.
 * MASQUE is owned by the Aether runtime and must never be sent through Xray.
 */
object CoreConfigBuilder {
    fun build(profile: ProtocolProfile): String {
        require(profile.protocol in setOf(Protocol.WIREGUARD, Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS)) {
            "این پروتکل از مسیر Xray استفاده نمی‌کند"
        }
        require(profile.rawConfig.isNotBlank()) { "کانفیگ خالی است" }
        return profile.rawConfig
    }
}
