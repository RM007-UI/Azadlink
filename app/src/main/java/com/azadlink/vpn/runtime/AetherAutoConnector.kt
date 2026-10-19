package com.azadlink.vpn.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.bepass.oblivion.vpn.AetherVpnService
import org.bepass.oblivion.vpn.TunnelConfig
import org.bepass.oblivion.vpn.TunnelBus
import org.bepass.oblivion.vpn.TunnelStage
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Starts the pinned upstream Aether MASQUE runtime.
 *
 * Registration is done before the VPN service starts because Aether requires a
 * MASQUE identity file. The UI must still wait for TunnelBus.CONNECTED before
 * presenting a successful connection.
 */
object AetherAutoConnector {
    private const val SOCKS_PORT = 1819
    private const val HTTP_PORT = 1820
    private const val REGISTER_TIMEOUT_MINUTES = 4L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun connect(context: Context, onStatus: (String) -> Unit) {
        val appContext = context.applicationContext
        Thread({
            try {
                onStatusOnMain(onStatus, "در حال بررسی موتور Aether…")
                val binary = File(appContext.applicationInfo.nativeLibraryDir, "libaether.so")
                check(binary.isFile) {
                    "فایل موتور Aether در APK پیدا نشد؛ این ساخت هنوز موتور بومی را بسته‌بندی نکرده است."
                }

                val workDir = File(appContext.filesDir, "aether").apply { mkdirs() }
                val masqueIdentity = File(workDir, "aether-masque.toml")
                val envVars = mapOf(
                    "HOME" to workDir.absolutePath,
                    "TMPDIR" to appContext.cacheDir.absolutePath,
                    "SSL_CERT_DIR" to "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts",
                    "AETHER_CONFIG" to File(workDir, "aether.toml").absolutePath,
                    "AETHER_WG_CONFIG" to File(workDir, "aether-wg.toml").absolutePath,
                    "AETHER_MASQUE_CONFIG" to masqueIdentity.absolutePath,
                    "OBLIVION_CORE_OWNER" to Process.myPid().toString()
                )

                if (!hasIdentity(masqueIdentity)) {
                    onStatusOnMain(onStatus, "در حال آماده‌سازی هویت اتصال امن…")
                    val registrationLog = File(appContext.cacheDir, "aether-register.log")
                    val process = ProcessBuilder(
                        binary.absolutePath,
                        "--register", "masque",
                        "--log-level", "info",
                        "--ip", "both"
                    ).directory(workDir).redirectErrorStream(true).redirectOutput(registrationLog).apply {
                        environment().putAll(envVars)
                    }.start()

                    if (!process.waitFor(REGISTER_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                        process.destroyForcibly()
                        error("ثبت هویت Aether بیش از حد طول کشید.")
                    }
                    check(process.exitValue() == 0 && hasIdentity(masqueIdentity)) {
                        val detail = runCatching { registrationLog.readLines().takeLast(8).joinToString(" | ") }
                            .getOrDefault("")
                        "ثبت هویت MASQUE ناموفق بود. $detail"
                    }
                }

                onStatusOnMain(onStatus, "هویت آماده است؛ در حال راه‌اندازی تونل MASQUE…")
                val masquePrefs = appContext.getSharedPreferences("azadlink_masque", Context.MODE_PRIVATE)
                val scanMode = "balanced"
                val ipVersion = masquePrefs.getString("ip_version", "both") ?: "both"
                val obfuscation = masquePrefs.getString("obfuscation", "firewall") ?: "firewall"
                val transport = masquePrefs.getString("transport", "h2") ?: "h2"
                val masqueSni = masquePrefs.getString("sni", "www.cloudflare.com")
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: "www.cloudflare.com"
                val arguments = mutableListOf(
                    "--bind", "127.0.0.1:$SOCKS_PORT",
                    "--http-proxy", "127.0.0.1:$HTTP_PORT",
                    "--scan", scanMode,
                    "--log-level", "info",
                    "--ip", ipVersion,
                    "--validate-secs", "10",
                    "--reconnect-secs", "2",
                    "--noize", obfuscation,
                    "--masque",
                    "--masque-sni", masqueSni
                )
                if (transport == "h2") arguments.add("--h2")
                val settings = mapOf<String, Any?>(
                    "protocol" to "masque",
                    "modeLabel" to "MASQUE ${transport.uppercase()}",
                    "transport" to transport,
                    "scanMode" to scanMode,
                    "obfuscation" to obfuscation,
                    "noizeProfile" to obfuscation,
                    "ipVersion" to ipVersion,
                    "logLevel" to "info",
                    "endpoint" to "",
                    "socksPort" to SOCKS_PORT,
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
                lateinit var statusListener: (org.bepass.oblivion.vpn.TunnelSnapshot) -> Unit
                statusListener = { snapshot ->
                    when (snapshot.stage) {
                        TunnelStage.CONNECTED -> {
                            TunnelBus.removeStatusListener(statusListener)
                            onStatusOnMain(onStatus, "تونل MASQUE با HTTP/2 برقرار شد.")
                        }
                        TunnelStage.FAILED -> {
                            TunnelBus.removeStatusListener(statusListener)
                            onStatusOnMain(onStatus, "اتصال MASQUE با HTTP/2 ناموفق بود؛ گزارش اتصال را برای بررسی خطای موتور باز کن.")
                        }
                        else -> Unit
                    }
                }
                AetherVpnService.start(appContext, TunnelConfig.fromMap(settings, arguments))
                TunnelBus.addStatusListener(statusListener)
            } catch (error: Throwable) {
                onStatusOnMain(onStatus, "خطای اتصال: ${error.message ?: error.javaClass.simpleName}")
            }
        }, "azadlink-aether-connect").start()
    }

    fun disconnect(context: Context) {
        AetherVpnService.stop(context.applicationContext)
    }

    private fun hasIdentity(file: File): Boolean = runCatching {
        file.isFile && Regex("^device_id\\s*=", RegexOption.MULTILINE).containsMatchIn(file.readText())
    }.getOrDefault(false)

    private fun onStatusOnMain(callback: (String) -> Unit, message: String) {
        mainHandler.post { callback(message) }
    }
}
