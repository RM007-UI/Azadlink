package com.azadlink.vpn.core

enum class EngineReadiness { READY, CORE_RUNTIME_REQUIRED, NEEDS_INTEGRATION, NEEDS_SERVER_CONFIG }
data class EngineDescriptor(val family: EngineFamily, val name: String, val readiness: EngineReadiness, val detail: String, val upstream: String)

object EngineRegistry {
    private val engines = mapOf(
        EngineFamily.AETHER to EngineDescriptor(
            EngineFamily.AETHER, "Aether native core", EngineReadiness.CORE_RUNTIME_REQUIRED,
            "هستهٔ Aether و پل TUN/SOCKS5 از upstream بسته‌بندی شده‌اند؛ اتصال MASQUE هنوز باید روی دستگاه تأیید شود.",
            "https://github.com/CluvexStudio/Aether"
        ),
        EngineFamily.XRAY_CORE to EngineDescriptor(
            EngineFamily.XRAY_CORE, "Xray-core / gomobile", EngineReadiness.CORE_RUNTIME_REQUIRED,
            "هستهٔ Xray و سرویس TUN در برنامه قرار گرفته‌اند؛ فقط بعد از آزمون واقعی ترافیک، اتصال موفق اعلام می‌شود.",
            "https://github.com/XTLS/Xray-core"
        ),
        EngineFamily.PSIPHON to EngineDescriptor(
            EngineFamily.PSIPHON, "Psiphon via bundled Aether runtime", EngineReadiness.CORE_RUNTIME_REQUIRED,
            "حالت Psiphon-only از موتور رسمی بسته‌بندی‌شده اجرا می‌شود؛ نیازی به فایل JSON از کاربر نیست.",
            "https://github.com/Psiphon-Labs/psiphon-tunnel-core"
        ),
        EngineFamily.WIREGUARD to EngineDescriptor(
            EngineFamily.WIREGUARD, "WireGuard outbound", EngineReadiness.CORE_RUNTIME_REQUIRED,
            "پروفایل استاندارد WireGuard به outbound هستهٔ Xray تبدیل می‌شود؛ اتصال واقعی به سرور باید روی دستگاه بررسی شود.",
            "https://github.com/XTLS/Xray-core"
        ),
        EngineFamily.MASTER_DNS to EngineDescriptor(
            EngineFamily.MASTER_DNS, "MasterDnsVPN Go client", EngineReadiness.NEEDS_SERVER_CONFIG,
            "به پروفایل و سرور معتبر نیاز دارد؛ سرویس داخلی از پل SOCKS5 به TUN استفاده می‌کند و اتصال به سرور باید بررسی شود.",
            "https://github.com/masterking32/MasterDnsVPN"
        ),
        EngineFamily.STORM_DNS to EngineDescriptor(
            EngineFamily.STORM_DNS, "StormDNS Go client", EngineReadiness.NEEDS_SERVER_CONFIG,
            "به سرور و DNS zone اختصاصی نیاز دارد؛ سرویس داخلی از پل SOCKS5 به TUN استفاده می‌کند.",
            "https://github.com/nullroute1970/StormDNS"
        ),
        EngineFamily.DNSTT to EngineDescriptor(
            EngineFamily.DNSTT, "DNSTT Go client", EngineReadiness.NEEDS_SERVER_CONFIG,
            "به دامنه، کلید عمومی، resolver و سرور راه‌دور نیاز دارد؛ پل SOCKS5 به TUN در برنامه قرار دارد.",
            "https://github.com/mahsanet/dnstt"
        )
    )

    fun descriptor(family: EngineFamily): EngineDescriptor = engines.getValue(family)
    fun forProtocol(protocol: Protocol): EngineDescriptor = descriptor(protocol.engineFamily)
    fun canAttemptConnection(protocol: Protocol): Boolean = protocol in Protocol.entries
}
