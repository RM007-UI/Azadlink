package com.azadlink.vpn.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.bepass.oblivion.vpn.AetherVpnService
import org.bepass.oblivion.vpn.TunnelConfig

/**
 * Starts the Psiphon-only mode supported by the bundled upstream Aether runtime.
 * No user-supplied JSON or server profile is needed; the core supplies its own
 * Psiphon configuration and can refresh its signed server list.
 */
object PsiphonAutoConnector {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun connect(context: Context, onStatus: (String) -> Unit) {
        try {
            val settings = mapOf<String, Any?>(
                "psiphon" to "only",
                "psiphonMode" to "auto",
                "psiphonConduitPeers" to "auto",
                "psiphonRejectCensoredPeers" to true,
                "protocol" to "masque",
                "modeLabel" to "Psiphon",
                "transport" to "h3",
                "scanMode" to "balanced",
                "obfuscation" to "balanced",
                "noizeProfile" to "balanced",
                "ipVersion" to "both",
                "logLevel" to "info",
                "endpoint" to "",
                "socksPort" to 1819,
                "tunnelMtu" to 1500,
                "coreMtu" to 0,
                "allowLan" to false,
                "proxyOnly" to false,
                "quickReconnect" to true,
                "dnsPrimary" to "1.1.1.1",
                "dnsSecondary" to "1.0.0.1",
                "splitTunnelMode" to "disabled",
                "bypassedApps" to emptyList<String>()
            )
            AetherVpnService.start(
                context.applicationContext,
                TunnelConfig.fromMap(settings, emptyList())
            )
            mainHandler.post { onStatus("درخواست اتصال مستقیم Psiphon ارسال شد؛ در انتظار تأیید تونل…") }
        } catch (error: Throwable) {
            mainHandler.post {
                onStatus("خطای Psiphon: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun disconnect(context: Context) {
        AetherVpnService.stop(context.applicationContext)
    }
}
