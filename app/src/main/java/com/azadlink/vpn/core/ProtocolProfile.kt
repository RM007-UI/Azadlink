package com.azadlink.vpn.core

import java.util.UUID

data class ProtocolProfile(
    val id: String = UUID.randomUUID().toString(),
    val protocol: Protocol,
    val label: String,
    val rawConfig: String,
    val serverHost: String? = null,
) {
    override fun toString(): String = "$label (${protocol.displayName})"
}
data class ParseResult(val profiles: List<ProtocolProfile>, val rejectedLines: List<String>)
