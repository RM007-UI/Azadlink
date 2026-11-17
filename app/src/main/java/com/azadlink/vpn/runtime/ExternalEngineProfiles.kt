package com.azadlink.vpn.runtime

import com.azadlink.vpn.core.Protocol
import com.azadlink.vpn.core.ProtocolProfile
import com.google.gson.JsonParser
import java.util.Locale

/** Validates the bundled DNS-tunnel configuration formats. */
object ExternalEngineProfiles {
    fun embeddedPsiphonConfig(raw: String): String? =
        raw.takeIf { it.trim().startsWith("{") && it.contains("PropagationChannelId") }

    fun parseDnsTunnel(profile: ProtocolProfile): Any {
        require(profile.protocol == Protocol.MASTER_DNS || profile.protocol == Protocol.STORM_DNS) {
            "پروفایل DNS tunnel نامعتبر است"
        }
        val trimmed = profile.rawConfig.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
            "برای MasterDNS و StormDNS، فایل JSON کامل موتور را همراه فیلد protocol وارد کن."
        }
        val json = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
            ?: throw IllegalArgumentException("فرمت JSON پروفایل DNS معتبر نیست.")
        val declared = json.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase(Locale.ROOT)
        val expected = if (profile.protocol == Protocol.MASTER_DNS) "masterdns" else "stormdns"
        require(declared == expected) { "فیلد protocol باید " + expected + " باشد." }
        require(json.has("ENCRYPTION_KEY") || json.has("encryptionKey") || json.has("key")) {
            "فیلد ENCRYPTION_KEY در کانفیگ موجود نیست."
        }
        require(json.has("DOMAINS") || json.has("domains") || json.has("domain")) {
            "فیلد DOMAINS در کانفیگ موجود نیست."
        }
        require(json.has("RESOLVERS") || json.has("resolvers") || json.has("RESOLVERS_TEXT") ||
            json.has("CLIENT_RESOLVERS") || json.has("CLIENT_RESOLVERS_TEXT")) {
            "فیلد RESOLVERS باید شامل IPهای DNS باشد."
        }
        return trimmed
    }

    fun parseDnstt(raw: String): Any {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val json = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
                ?: throw IllegalArgumentException("فرمت JSON پروفایل DNSTT معتبر نیست.")
            val declared = json.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase(Locale.ROOT)
            require(declared == "dnstt") { "فیلد protocol در JSON باید dnstt باشد." }
            require(json.has("domain") || json.has("DOMAIN") || json.has("server")) {
                "دامنهٔ تونل DNSTT در کانفیگ موجود نیست."
            }
            require(json.has("pubkey") || json.has("PUBKEY") || json.has("publicKey")) {
                "کلید عمومی DNSTT در کانفیگ موجود نیست."
            }
            return trimmed
        }
        require(trimmed.startsWith("dnstt://", ignoreCase = true)) {
            "فرمت DNSTT باید dnstt://domain?pubkey=...&resolver=1.1.1.1:53 باشد."
        }
        return trimmed
    }
}
