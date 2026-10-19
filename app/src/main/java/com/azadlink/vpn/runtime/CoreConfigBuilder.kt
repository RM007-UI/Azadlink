package com.azadlink.vpn.runtime

import android.util.Base64
import com.azadlink.vpn.core.Protocol
import com.azadlink.vpn.core.ProtocolProfile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Turns share links into Xray JSON. This builds a configuration only; the
 * foreground VPN service is responsible for starting the core and TUN.
 */
object CoreConfigBuilder {
    fun build(profile: ProtocolProfile): String {
        require(profile.protocol in setOf(Protocol.WIREGUARD, Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS)) {
            "این پروتکل از مسیر Xray استفاده نمی‌کند"
        }
        require(profile.rawConfig.isNotBlank()) { "کانفیگ خالی است" }
        val outbound = when (profile.protocol) {
            Protocol.VLESS -> vless(profile.rawConfig)
            Protocol.VMESS -> vmess(profile.rawConfig)
            Protocol.TROJAN -> trojan(profile.rawConfig)
            Protocol.SHADOWSOCKS -> shadowsocks(profile.rawConfig)
            Protocol.WIREGUARD -> wireguard(profile.rawConfig)
            else -> error("unsupported protocol")
        }
        return fullConfig(outbound).toString()
    }

    private fun fullConfig(proxy: JsonObject): JsonObject {
        val tunSettings = JsonObject().apply {
            addProperty("name", "azadlink0")
            addProperty("mtu", 1500)
        }
        val tun = JsonObject().apply {
            addProperty("tag", "tun-in")
            addProperty("port", 0)
            addProperty("protocol", "tun")
            add("settings", tunSettings)
            add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", JsonArray().apply {
                    add("http")
                    add("tls")
                    add("quic")
                })
            })
        }
        val localSocks = JsonObject().apply {
            addProperty("tag", "socks-in")
            addProperty("listen", "127.0.0.1")
            addProperty("port", 10808)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", true)
                addProperty("userLevel", 0)
            })
        }
        val direct = JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
            add("settings", JsonObject())
        }
        val block = JsonObject().apply {
            addProperty("tag", "block")
            addProperty("protocol", "blackhole")
            add("settings", JsonObject())
        }
        val rules = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { add("tun-in"); add("socks-in") })
                addProperty("outboundTag", "proxy")
            })
        }
        return JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("inbounds", JsonArray().apply { add(tun); add(localSocks) })
            add("outbounds", JsonArray().apply { add(proxy); add(direct); add(block) })
            add("routing", JsonObject().apply {
                addProperty("domainStrategy", "IPIfNonMatch")
                add("rules", rules)
            })
        }
    }

    private fun vless(raw: String): JsonObject {
        val uri = URI(raw.trim())
        val uuid = uri.rawUserInfo?.substringBefore(':')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("UUID لینک VLESS پیدا نشد")
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("آدرس سرور VLESS نامعتبر است")
        require(uri.port in 1..65535) { "پورت سرور VLESS نامعتبر است" }
        val q = query(uri.rawQuery)
        val user = JsonObject().apply {
            addProperty("id", decode(uuid))
            addProperty("encryption", q["encryption"] ?: "none")
            q["flow"]?.takeIf { it.isNotBlank() }?.let { addProperty("flow", it) }
        }
        val settings = JsonObject().apply {
            add("vnext", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", host)
                    addProperty("port", uri.port)
                    add("users", JsonArray().apply { add(user) })
                })
            })
        }
        val stream = streamSettings(q, host)
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "vless")
            add("settings", settings)
            add("streamSettings", stream)
        }
    }

    private fun vmess(raw: String): JsonObject {
        val encoded = raw.trim().substringAfter("vmess://", "")
        val decoded = decodeBase64(encoded)
        val input = JsonParser.parseString(decoded).asJsonObject
        val host = input.string("add").ifBlank { input.string("host") }
        val port = input.string("port").toIntOrNull() ?: 0
        val id = input.string("id")
        require(host.isNotBlank() && port in 1..65535 && id.isNotBlank()) { "لینک VMess ناقص است" }
        val network = input.string("net").ifBlank { "tcp" }
        val security = input.string("tls").let { if (it.isBlank() || it == "none") "none" else "tls" }
        val q = mutableMapOf("type" to network, "security" to security)
        input.string("sni").ifBlank { input.string("host") }.takeIf { it.isNotBlank() }?.let { q["sni"] = it }
        input.string("path").takeIf { it.isNotBlank() }?.let { q["path"] = it }
        val user = JsonObject().apply {
            addProperty("id", id)
            addProperty("alterId", input.string("aid").toIntOrNull() ?: 0)
            addProperty("security", input.string("scy").ifBlank { "auto" })
        }
        val settings = JsonObject().apply {
            add("vnext", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", host)
                    addProperty("port", port)
                    add("users", JsonArray().apply { add(user) })
                })
            })
        }
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "vmess")
            add("settings", settings)
            add("streamSettings", streamSettings(q, host))
        }
    }

    private fun trojan(raw: String): JsonObject {
        val uri = URI(raw.trim())
        val password = uri.rawUserInfo?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("رمز عبور لینک Trojan پیدا نشد")
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("آدرس سرور Trojan نامعتبر است")
        require(uri.port in 1..65535) { "پورت سرور Trojan نامعتبر است" }
        val q = query(uri.rawQuery).toMutableMap()
        q.putIfAbsent("security", "tls")
        val settings = JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", host)
                    addProperty("port", uri.port)
                    addProperty("password", decode(password))
                })
            })
        }
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "trojan")
            add("settings", settings)
            add("streamSettings", streamSettings(q, host))
        }
    }

    private fun shadowsocks(raw: String): JsonObject {
        val uri = URI(raw.trim())
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("آدرس سرور Shadowsocks نامعتبر است")
        require(uri.port in 1..65535) { "پورت سرور Shadowsocks نامعتبر است" }
        val info = uri.rawUserInfo?.let { decodeBase64(it) } ?: ""
        val method = info.substringBefore(':')
        val password = info.substringAfter(':', "")
        require(method.isNotBlank() && password.isNotBlank()) { "روش رمزنگاری یا رمز عبور Shadowsocks پیدا نشد" }
        val settings = JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", host)
                    addProperty("port", uri.port)
                    addProperty("method", method)
                    addProperty("password", password)
                })
            })
        }
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "shadowsocks")
            add("settings", settings)
        }
    }

    private fun wireguard(raw: String): JsonObject {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current = ""
        raw.lineSequence().forEach { original ->
            val line = original.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length - 1).trim().lowercase()
                sections.putIfAbsent(current, linkedMapOf())
            } else if (current.isNotEmpty() && '=' in line) {
                val key = line.substringBefore('=').trim().lowercase()
                val value = line.substringAfter('=').trim()
                sections[current]?.set(key, value)
            }
        }
        val iface = sections["interface"] ?: throw IllegalArgumentException("بخش [Interface] در WireGuard پیدا نشد")
        val peer = sections["peer"] ?: throw IllegalArgumentException("بخش [Peer] در WireGuard پیدا نشد")
        val privateKey = iface["privatekey"].orEmpty()
        val publicKey = peer["publickey"].orEmpty()
        val endpoint = peer["endpoint"].orEmpty()
        require(privateKey.isNotBlank() && publicKey.isNotBlank() && endpoint.contains(':')) {
            "PrivateKey، PublicKey یا Endpoint در WireGuard ناقص است"
        }
        val addresses = iface["address"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val allowed = peer["allowedips"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val peerConfig = JsonObject().apply {
            addProperty("publicKey", publicKey)
            peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let { addProperty("preSharedKey", it) }
            addProperty("endpoint", endpoint)
            addProperty("keepAlive", peer["persistentkeepalive"]?.toIntOrNull() ?: 25)
            if (allowed.isNotEmpty()) add("allowedIPs", JsonArray().apply { allowed.forEach { add(it) } })
        }
        val settings = JsonObject().apply {
            addProperty("secretKey", privateKey)
            add("address", JsonArray().apply { addresses.forEach { add(it) } })
            add("peers", JsonArray().apply { add(peerConfig) })
            addProperty("mtu", 1420)
            addProperty("noKernelTun", true)
            add("IsClient", com.google.gson.JsonPrimitive(true))
            iface["dns"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { dns ->
                add("remoteDNS", JsonArray().apply { dns.forEach { add(it) } })
            }
        }
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "wireguard")
            add("settings", settings)
        }
    }

    private fun streamSettings(q: Map<String, String>, host: String): JsonObject {
        val network = when (q["type"] ?: q["network"] ?: "tcp") {
            "httpupgrade" -> "httpupgrade"
            "splithttp" -> "splithttp"
            else -> q["type"] ?: q["network"] ?: "tcp"
        }
        val security = q["security"] ?: "none"
        val result = JsonObject().apply {
            addProperty("network", network)
            addProperty("security", security)
        }
        if (security == "tls") {
            result.add("tlsSettings", JsonObject().apply {
                addProperty("serverName", q["sni"] ?: q["servername"] ?: q["host"] ?: host)
                addProperty("allowInsecure", false)
                q["fp"]?.takeIf { it.isNotBlank() }?.let { addProperty("fingerprint", it) }
                q["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                    add("alpn", JsonArray().apply { alpn.split(',').forEach { add(it.trim()) } })
                }
            })
        } else if (security == "reality") {
            result.add("realitySettings", JsonObject().apply {
                addProperty("serverName", q["sni"] ?: q["servername"] ?: host)
                addProperty("fingerprint", q["fp"] ?: "chrome")
                q["pbk"]?.let { addProperty("publicKey", it) }
                q["sid"]?.let { addProperty("shortId", it) }
                q["spx"]?.let { addProperty("spiderX", it) }
            })
        }
        when (network) {
            "ws" -> result.add("wsSettings", JsonObject().apply {
                addProperty("path", decode(q["path"] ?: "/"))
                val wsHost = q["host"] ?: q["authority"]
                if (!wsHost.isNullOrBlank()) add("headers", JsonObject().apply { addProperty("Host", wsHost) })
            })
            "grpc" -> result.add("grpcSettings", JsonObject().apply {
                addProperty("serviceName", decode(q["serviceName"] ?: q["service"] ?: ""))
                addProperty("multiMode", (q["mode"] == "multi"))
                q["authority"]?.let { addProperty("authority", it) }
            })
            "http" -> result.add("httpSettings", JsonObject().apply {
                addProperty("path", decode(q["path"] ?: "/"))
                val httpHost = q["host"] ?: q["authority"]
                if (!httpHost.isNullOrBlank()) add("host", JsonArray().apply { add(httpHost) })
            })
            "httpupgrade" -> result.add("httpupgradeSettings", JsonObject().apply {
                addProperty("path", decode(q["path"] ?: "/"))
                q["host"]?.let { addProperty("host", it) }
            })
            "tcp" -> {
                if (q["headerType"] == "http" || q["type"] == "http") {
                    result.add("tcpSettings", JsonObject().apply {
                        add("header", JsonObject().apply {
                            addProperty("type", "http")
                            add("request", JsonObject().apply {
                                add("headers", JsonObject().apply {
                                    add("Host", JsonArray().apply { add(q["host"] ?: host) })
                                    addProperty("User-Agent", "Mozilla/5.0")
                                })
                                addProperty("path", "/")
                            })
                        })
                    })
                }
            }
        }
        return result
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { item ->
            val key = item.substringBefore('=')
            if (key.isBlank()) null else decode(key) to decode(item.substringAfter('=', ""))
        }.toMap()
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun decodeBase64(value: String): String {
        val clean = value.substringBefore('#').trim()
        val decoded = runCatching { Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()
            ?: runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull()
            ?: throw IllegalArgumentException("Base64 کانفیگ نامعتبر است")
        return String(decoded, StandardCharsets.UTF_8)
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
}
