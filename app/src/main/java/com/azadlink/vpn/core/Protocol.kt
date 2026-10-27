package com.azadlink.vpn.core

enum class Protocol(
    val displayName: String,
    val scheme: String,
    val engineFamily: EngineFamily,
    val note: String,
) {
    MASQUE("MASQUE", "masque", EngineFamily.AETHER, "نیازمند هستهٔ Aether و پل واقعی ترافیک VPN"),
    PSIPHON("Psiphon", "psiphon", EngineFamily.PSIPHON, "نیازمند موتور رسمی Psiphon و تنظیمات معتبر"),
    WIREGUARD("WireGuard", "wireguard", EngineFamily.WIREGUARD, "کانفیگ استاندارد به WireGuard outbound هستهٔ Xray تبدیل می‌شود"),
    MASTER_DNS("MasterDNS", "masterdns", EngineFamily.MASTER_DNS, "نیازمند موتور MasterDnsVPN و سرور اختصاصی"),
    STORM_DNS("StormDNS", "stormdns", EngineFamily.STORM_DNS, "نیازمند موتور StormDNS و سرور/DNS zone"),
    DNSTT("DNSTT", "dnstt", EngineFamily.DNSTT, "نیازمند کلاینت DNSTT و سرور راه‌دور که TCP را به SOCKS5 فوروارد کند"),
    VLESS("VLESS", "vless", EngineFamily.XRAY_CORE, "نیازمند core سازگار با Xray"),
    VMESS("VMess", "vmess", EngineFamily.XRAY_CORE, "نیازمند core سازگار با Xray"),
    TROJAN("Trojan", "trojan", EngineFamily.XRAY_CORE, "نیازمند core سازگار با Xray"),
    SHADOWSOCKS("Shadowsocks", "ss", EngineFamily.XRAY_CORE, "نیازمند core سازگار با Xray"),
    XVPN("X-VPN", "xvpn", EngineFamily.EXTERNAL_APP, "اتصال از طریق اپ رسمی X-VPN؛ وضعیت و قطع‌و‌وصل در همان اپ مدیریت می‌شود");

    companion object {
        fun fromScheme(scheme: String): Protocol? {
            if (scheme.equals("shadowsocks", ignoreCase = true)) return SHADOWSOCKS
            return entries.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) }
        }
    }
}

enum class EngineFamily { AETHER, XRAY_CORE, PSIPHON, WIREGUARD, MASTER_DNS, STORM_DNS, DNSTT, EXTERNAL_APP }
