package com.azadlink.vpn.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.bepass.oblivion.vpn.AetherVpnService
import org.bepass.oblivion.vpn.TunnelConfig
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
                val environment = mapOf(
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
                        environment().putAll(environment)
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
                val arguments = listOf(
                    "--bind", "127.0.0.1:$SOCKS_PORT",
                    "--http-proxy", "127.0.0.1:$HTTP_PORT",
                    "--scan", "balanced",
                    "--log-level", "info",
                    "--ip", "both",
                    "--validate-secs", "10",
                    "--reconnect-secs", "2",
                    "--noize", "balanced",
                    "--masque",
                    "--masque-sni", "www.cloudflare.com"
                )
                val settings = mapOf<String, Any?>(
                    "protocol" to "masque",
                    "modeLabel" to "MASQUE H3",
                    "transport" to "h3",
                    "scanMode" to "balanced",
                    "obfuscation" to "balanced",
                    "noizeProfile" to "balanced",
                    "ipVersion" to "both",
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
                AetherVpnService.start(appContext, TunnelConfig.fromMap(settings, arguments))
                onStatusOnMain(onStatus, "درخواست تونل ارسال شد؛ منتظر تأیید اتصال واقعی…")
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
