package com.azadlink.vpn.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.bepass.oblivion.vpn.AetherVpnService
import org.bepass.oblivion.vpn.TunnelBus
import org.bepass.oblivion.vpn.TunnelConfig
import org.bepass.oblivion.vpn.TunnelSnapshot
import org.bepass.oblivion.vpn.TunnelStage
import java.util.concurrent.atomic.AtomicInteger

/** Starts Psiphon directly or chains Psiphon over Aether/MASQUE. */
object PsiphonAutoConnector {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)

    fun connect(
        context: Context,
        onStatus: (String) -> Unit,
        country: String = "",
        chainOverMasque: Boolean = false
    ) {
        val run = generation.incrementAndGet()
        try {
            val app = context.applicationContext
            val routingPrefs = app.getSharedPreferences("azadlink_routing", Context.MODE_PRIVATE)
            val countryCode = country.trim().uppercase().takeIf {
                it.isEmpty() || it.matches(Regex("[A-Z]{2}"))
            } ?: ""
            val settings = mapOf<String, Any?>(
                "psiphon" to if (chainOverMasque) "chain" else "only",
                "psiphonCountry" to countryCode,
                "psiphonMode" to "auto",
                "psiphonConduitPeers" to "auto",
                "psiphonRejectCensoredPeers" to true,
                "protocol" to "masque",
                "modeLabel" to if (chainOverMasque) "MASQUE → Psiphon" else "Psiphon",
                "transport" to "h2",
                "scanMode" to "balanced",
                "obfuscation" to "firewall",
                "noizeProfile" to "firewall",
                "ipVersion" to "both",
                "logLevel" to "info",
                "endpoint" to "",
                "socksPort" to 1819,
                "tunnelMtu" to 1500,
                "coreMtu" to 0,
                "fragment" to false,
                "allowLan" to false,
                "proxyOnly" to routingPrefs.getBoolean("proxy_only", false),
                "quickReconnect" to true,
                "dnsPrimary" to "1.1.1.1",
                "dnsSecondary" to "1.0.0.1",
                "splitTunnelMode" to (routingPrefs.getString("split_mode", "disabled") ?: "disabled"),
                "bypassedApps" to (routingPrefs.getStringSet("selected_apps", emptySet())?.toList() ?: emptyList<String>())
            )
            // Chain mode reserves 1829/1830 for the inner Aether SOCKS/HTTP proxy;
            // Psiphon remains exposed on the final local SOCKS port 1819.
            val args = if (chainOverMasque) listOf(
                "--bind", "127.0.0.1:1829",
                "--http-proxy", "127.0.0.1:1830",
                "--scan", "balanced",
                "--log-level", "info",
                "--ip", "both",
                "--validate-secs", "10",
                "--reconnect-secs", "2",
                "--noize", "firewall",
                "--masque",
                "--masque-sni", "www.cloudflare.com",
                "--h2"
            ) else emptyList()

            onStatusMain(onStatus, if (chainOverMasque)
                "در حال راه‌اندازی زنجیرهٔ MASQUE → Psiphon…"
            else "در حال راه‌اندازی Psiphon با موقعیت ${countryCode.ifBlank { "سریع‌ترین/خودکار" }}…")

            var sawConnecting = false
            lateinit var listener: (TunnelSnapshot) -> Unit
            listener = { snapshot ->
                if (generation.get() != run) {
                    TunnelBus.removeStatusListener(listener)
                } else {
                    if (snapshot.stage == TunnelStage.CONNECTING || snapshot.stage == TunnelStage.VALIDATING) {
                        sawConnecting = true
                    }
                    if (sawConnecting) when (snapshot.stage) {
                        TunnelStage.CONNECTED -> {
                            TunnelBus.removeStatusListener(listener)
                            onStatusMain(onStatus, if (chainOverMasque)
                                "متصل: MASQUE → Psiphon؛ وضعیت تونل تأیید شد."
                            else "Psiphon متصل شد؛ وضعیت تونل تأیید شد.")
                        }
                        TunnelStage.FAILED -> {
                            TunnelBus.removeStatusListener(listener)
                            onStatusMain(onStatus, "اتصال ${if (chainOverMasque) "MASQUE → Psiphon" else "Psiphon"} ناموفق شد؛ گزارش موتور را بررسی کن.")
                        }
                        else -> Unit
                    }
                }
            }
            TunnelBus.addStatusListener(listener)
            try {
                AetherVpnService.start(app, TunnelConfig.fromMap(settings, args))
            } catch (error: Throwable) {
                TunnelBus.removeStatusListener(listener)
                throw error
            }
        } catch (error: Throwable) {
            onStatusMain(onStatus, "خطای Psiphon: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun disconnect(context: Context) {
        generation.incrementAndGet()
        AetherVpnService.stop(context.applicationContext)
    }

    private fun onStatusMain(callback: (String) -> Unit, message: String) {
        mainHandler.post { callback(message) }
    }
}
