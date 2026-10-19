package com.azadlink.vpn.core

enum class EngineReadiness { READY, CORE_RUNTIME_REQUIRED, NEEDS_INTEGRATION, NEEDS_SERVER_CONFIG }
data class EngineDescriptor(val family: EngineFamily, val name: String, val readiness: EngineReadiness, val detail: String, val upstream: String)
object EngineRegistry {
    private val engines = mapOf(
        EngineFamily.AETHER to EngineDescriptor(EngineFamily.AETHER, "Aether native core", EngineReadiness.NEEDS_INTEGRATION, "موتور Rust، کشف خودکار endpoint و پل TUN/SOCKS5 هنوز در این شاخه یکپارچه نشده‌اند.", "https://github.com/CluvexStudio/Aether"),
        EngineFamily.XRAY_CORE to EngineDescriptor(EngineFamily.XRAY_CORE, "Xray-core / gomobile", EngineReadiness.CORE_RUNTIME_REQUIRED, "هسته باید در برنامه راه‌اندازی و روی دستگاه آزمایش شود.", "https://github.com/2dust/AndroidLibXrayLite"),
        EngineFamily.PSIPHON to EngineDescriptor(EngineFamily.PSIPHON, "Psiphon ConsoleClient", EngineReadiness.NEEDS_SERVER_CONFIG, "فایل JSON معتبر لازم است.", "https://github.com/Psiphon-Labs/psiphon-tunnel-core"),
        EngineFamily.WIREGUARD to EngineDescriptor(EngineFamily.WIREGUARD, "WireGuard outbound (Xray)", EngineReadiness.CORE_RUNTIME_REQUIRED, "کانفیگ و کلیدهای واقعی لازم‌اند.", "https://github.com/XTLS/Xray-core"),
        EngineFamily.MASTER_DNS to EngineDescriptor(EngineFamily.MASTER_DNS, "MasterDnsVPN Go client", EngineReadiness.NEEDS_SERVER_CONFIG, "پروفایل و سرور اختصاصی لازم است.", "https://github.com/masterking32/MasterDnsVPN"),
        EngineFamily.STORM_DNS to EngineDescriptor(EngineFamily.STORM_DNS, "StormDNS Go client", EngineReadiness.NEEDS_SERVER_CONFIG, "پروفایل و سرور/DNS zone لازم است.", "https://github.com/nullroute1970/StormDNS"),
        EngineFamily.DNSTT to EngineDescriptor(EngineFamily.DNSTT, "DNSTT Go client", EngineReadiness.NEEDS_SERVER_CONFIG, "دامنه، کلید و سرور راه‌دور لازم است.", "https://github.com/mahsanet/dnstt")
    )
    fun descriptor(family: EngineFamily): EngineDescriptor = engines.getValue(family)
    fun forProtocol(protocol: Protocol): EngineDescriptor = descriptor(protocol.engineFamily)
    fun canAttemptConnection(protocol: Protocol): Boolean = protocol in Protocol.entries
}
