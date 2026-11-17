package com.azadlink.vpn.core

import java.net.URI
import com.google.gson.JsonParser
import java.util.Locale

object ProtocolParser {
    private val schemeRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):\\/\\/", RegexOption.IGNORE_CASE)
    fun parse(input: String): ParseResult {
        val whole = input.trim()
        if (whole.startsWith("{") && whole.endsWith("}")) {
            val single = parseOne(whole)
            return if (single != null) ParseResult(listOf(single), emptyList()) else ParseResult(emptyList(), listOf("JSON profile (unsupported format)"))
        }
        val profiles = mutableListOf<ProtocolProfile>()
        val rejected = mutableListOf<String>()
        val normalLines = mutableListOf<String>()
        val wireGuardBlock = StringBuilder()
        var inWireGuard = false
        fun flushNormal() {
            normalLines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) return@forEach
                val profile = parseOne(trimmed)
                if (profile != null) profiles += profile else rejected += safePreview(trimmed)
            }
            normalLines.clear()
        }
        fun flushWireGuard() {
            val raw = wireGuardBlock.toString().trim()
            if (raw.isNotEmpty()) {
                val profile = parseOne(raw)
                if (profile != null) profiles += profile else rejected += "WireGuard config (invalid format)"
            }
            wireGuardBlock.setLength(0)
            inWireGuard = false
        }
        input.lineSequence().forEach { original ->
            val line = original.trim()
            val startsLink = schemeRegex.containsMatchIn(line)
            val startsInterface = line.equals("[Interface]", ignoreCase = true)
            if (startsInterface) {
                flushNormal()
                if (inWireGuard) flushWireGuard()
                inWireGuard = true
                wireGuardBlock.append(original).append('\n')
            } else if (inWireGuard && startsLink) {
                flushWireGuard()
                normalLines += original
            } else if (inWireGuard) wireGuardBlock.append(original).append('\n')
            else normalLines += original
        }
        flushWireGuard()
        flushNormal()
        return ParseResult(profiles.distinctBy { it.rawConfig }, rejected)
    }
    fun parseOne(raw: String): ProtocolProfile? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        if (line.startsWith("{") && line.endsWith("}")) {
            val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
            val declared = obj.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val protocol = Protocol.fromScheme(declared) ?: runCatching { Protocol.valueOf(declared.uppercase(Locale.ROOT)) }.getOrNull() ?: return null
            if (protocol !in setOf(Protocol.MASQUE, Protocol.PSIPHON, Protocol.DNSTT, Protocol.MASTER_DNS, Protocol.STORM_DNS)) return null
            return ProtocolProfile(protocol = protocol, label = "${protocol.displayName} profile", rawConfig = line)
        }
        if (line.contains("[Interface]", true) && line.contains("[Peer]", true))
            return ProtocolProfile(protocol = Protocol.WIREGUARD, label = "WireGuard config", rawConfig = line)
        val scheme = schemeRegex.find(line)?.groupValues?.getOrNull(1) ?: return null
        val protocol = Protocol.fromScheme(scheme.lowercase(Locale.ROOT)) ?: return null
        val uri = runCatching { URI(line) }.getOrNull()
        val opaque = protocol in setOf(Protocol.PSIPHON, Protocol.MASTER_DNS, Protocol.STORM_DNS)
        val host = if (opaque) null else uri?.host?.takeIf { it.isNotBlank() }
        val label = host ?: "${protocol.displayName} profile"
        return ProtocolProfile(protocol = protocol, label = label, rawConfig = line, serverHost = host)
    }
    private fun safePreview(line: String): String {
        val scheme = schemeRegex.find(line)?.groupValues?.getOrNull(1)
        return if (scheme != null) "$scheme://… (unsupported format)" else "Unknown configuration format"
    }
}
