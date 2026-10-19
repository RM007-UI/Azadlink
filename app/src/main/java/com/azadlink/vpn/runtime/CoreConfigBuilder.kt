package com.azadlink.vpn.runtime

import com.azadlink.vpn.core.Protocol
import com.azadlink.vpn.core.ProtocolProfile

/**
 * Validation placeholder for the first UI-preview build.
 * This does not start a tunnel; real engine integration must replace it.
 */
object CoreConfigBuilder {
    fun build(profile: ProtocolProfile): String {
        require(profile.protocol in setOf(Protocol.MASQUE, Protocol.WIREGUARD, Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS)) {
            "این پروتکل از مسیر Xray استفاده نمی‌کند"
        }
        require(profile.rawConfig.isNotBlank()) { "کانفیگ خالی است" }
        return profile.rawConfig
    }
}
