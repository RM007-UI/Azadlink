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
import java.util.concurrent.atomic.AtomicInteger

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

    private val connectionGeneration = AtomicInteger(0)

    fun connect(context: Context, onStatus: (String) -> Unit) {
        val generation = connectionGeneration.incrementAndGet()
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
                        "ثبت هویت MASQUE ناموفق بود. " + detail
                    }
                }

                if (connectionGeneration.get() != generation) return@Thread
                onStatusOnMain(onStatus, "هویت آماده است؛ در حال راه‌اندازی تونل MASQUE…")
                val masquePrefs = appContext.getSharedPreferences("azadlink_masque", Context.MODE_PRIVATE)
                val routingPrefs = appContext.getSharedPreferences("azadlink_routing", Context.MODE_PRIVATE)
                // New installs and Auto/Default presets prefer the known-good HTTP/2 path.
                // Respect explicit advanced choices only when the user selected Manual mode.
                val connectionMode = masquePrefs.getString("connection_mode", "auto") ?: "auto"
                val automatic = connectionMode != "manual"
                val scanMode = if (automatic) "balanced" else
                    (masquePrefs.getString("scan_mode", "balanced") ?: "balanced")
                        .lowercase().let { if (it in setOf("turbo", "balanced", "thorough", "stealth", "ironclad")) it else "balanced" }
                val firstScanMode = if (scanMode in setOf("thorough", "stealth", "ironclad")) "turbo" else scanMode
                val fallbackScanMode = if (firstScanMode != scanMode) scanMode else when (scanMode) {
                    "turbo" -> "balanced"
                    "balanced" -> "turbo"
                    else -> "balanced"
                }
                val ipVersion = if (automatic) "both" else (masquePrefs.getString("ip_version", "both") ?: "both")
                val obfuscation = if (automatic) "firewall" else (masquePrefs.getString("obfuscation", "firewall") ?: "firewall")
                val transport = if (automatic) "h2" else if ((masquePrefs.getString("transport", "h2") ?: "h2") == "h2") "h2" else "h3"
                val fragment = !automatic && masquePrefs.getBoolean("fragment", false) && transport == "h2"
                val autoFallback = connectionMode == "auto" || masquePrefs.getBoolean("auto_fallback", true)
                val masqueSni = if (automatic) "www.cloudflare.com" else
                    (masquePrefs.getString("sni", "www.cloudflare.com")?.trim()?.takeIf { it.isNotEmpty() } ?: "www.cloudflare.com")
                val manualEndpoint = if (automatic) "" else masquePrefs.getString("endpoint", "")?.trim().orEmpty()

                fun startAttempt(attemptScanMode: String, fallbackAttempt: Boolean, attemptTransport: String = transport) {
                    if (connectionGeneration.get() != generation) return
                    val attemptFragment = fragment && attemptTransport == "h2"
                    val arguments = mutableListOf(
                        "--bind", "127.0.0.1:" + SOCKS_PORT,
                        "--http-proxy", "127.0.0.1:" + HTTP_PORT,
                        "--scan", attemptScanMode,
                        "--log-level", "info",
                        "--ip", ipVersion,
                        "--validate-secs", "10",
                        "--reconnect-secs", "2",
                        "--noize", obfuscation,
                        "--masque",
                        "--masque-sni", masqueSni
                    )
                    if (attemptTransport == "h2") arguments.add("--h2")
                    if (attemptFragment) arguments.add("--fragment")

                    val settings = mapOf<String, Any?>(
                        "protocol" to "masque",
                        "modeLabel" to ("MASQUE " + attemptTransport.uppercase()),
                        "transport" to attemptTransport,
                        "scanMode" to attemptScanMode,
                        "obfuscation" to obfuscation,
                        "noizeProfile" to obfuscation,
                        "ipVersion" to ipVersion,
                        "logLevel" to "info",
                        "endpoint" to if (fallbackAttempt) "" else manualEndpoint,
                        "socksPort" to SOCKS_PORT,
                        "tunnelMtu" to 1500,
                        "coreMtu" to 0,
                        "fragment" to attemptFragment,
                        "allowLan" to false,
                        "proxyOnly" to routingPrefs.getBoolean("proxy_only", false),
                        "quickReconnect" to true,
                        "dnsPrimary" to "1.1.1.1",
                        "dnsSecondary" to "1.0.0.1",
                        "splitTunnelMode" to (routingPrefs.getString("split_mode", "disabled") ?: "disabled"),
                        "bypassedApps" to (routingPrefs.getStringSet("selected_apps", emptySet())?.toList() ?: emptyList<String>())
                    )

                    var sawConnecting = false
                    val attemptFinished = AtomicInteger(0)
                    lateinit var statusListener: (org.bepass.oblivion.vpn.TunnelSnapshot) -> Unit
                    lateinit var watchdog: Runnable

                    fun finishFailure(reason: String) {
                        if (connectionGeneration.get() != generation || !attemptFinished.compareAndSet(0, 1)) return
                        mainHandler.removeCallbacks(watchdog)
                        TunnelBus.removeStatusListener(statusListener)
                        if (!fallbackAttempt && autoFallback) {
                            // A gateway may pass TCP/TLS probing but reject HTTP/2 CONNECT-IP.
                            // In automatic mode, retry with HTTP/3 instead of repeating H2.
                            val nextTransport = if (automatic && attemptTransport == "h2") "h3" else attemptTransport
                            val nextScanMode = if (automatic && attemptTransport == "h2") attemptScanMode else fallbackScanMode
                            val fallbackDescription = if (nextTransport != attemptTransport) {
                                "انتقال جایگزین ${nextTransport.uppercase()} با اسکن «$nextScanMode»"
                            } else {
                                "اسکن جایگزین «$nextScanMode»"
                            }
                            onStatusOnMain(
                                onStatus,
                                "$reason؛ در حال توقف تلاش فعلی و شروع $fallbackDescription…"
                            )
                            AetherVpnService.stop(appContext)
                            mainHandler.postDelayed({
                                if (connectionGeneration.get() == generation) {
                                    startAttempt(nextScanMode, true, nextTransport)
                                }
                            }, 2200L)
                        } else {
                            AetherVpnService.stop(appContext)
                            onStatusOnMain(
                                onStatus,
                                "$reason اتصال MASQUE برقرار نشد. حالت اسکن: $attemptScanMode؛ گزارش موتور را بررسی کن."
                            )
                        }
                    }

                    statusListener = { snapshot ->
                        if (connectionGeneration.get() != generation) {
                            mainHandler.removeCallbacks(watchdog)
                            TunnelBus.removeStatusListener(statusListener)
                        } else {
                            if (snapshot.stage == TunnelStage.CONNECTING || snapshot.stage == TunnelStage.VALIDATING) {
                                sawConnecting = true
                            }
                            if (sawConnecting) when (snapshot.stage) {
                                TunnelStage.CONNECTED -> {
                                    if (attemptFinished.compareAndSet(0, 1)) {
                                        mainHandler.removeCallbacks(watchdog)
                                        TunnelBus.removeStatusListener(statusListener)
                                        val prefix = if (fallbackAttempt) "تونل MASQUE در تلاش جایگزین برقرار شد." else "تونل MASQUE برقرار شد."
                                        onStatusOnMain(onStatus, prefix + " انتقال " + attemptTransport.uppercase() + "؛ پراکسی موتور پاسخ داد و رابط VPN برقرار شد.")
                                    }
                                }
                                TunnelStage.FAILED -> {
                                    val detail = snapshot.message?.trim()?.takeIf { it.isNotEmpty() }
                                        ?: "هسته پس از اسکن $attemptScanMode اتصال قابل‌استفاده‌ای پیدا نکرد"
                                    finishFailure(detail)
                                }
                                else -> Unit
                            }
                        }
                    }
                    watchdog = Runnable {
                        val timeoutSeconds = attemptTimeoutMs(attemptScanMode, fallbackAttempt, automatic) / 1000L
                        finishFailure("مهلت تلاش اسکن $attemptScanMode پس از $timeoutSeconds ثانیه تمام شد")
                    }
                    TunnelBus.addStatusListener(statusListener)
                    try {
                        AetherVpnService.start(appContext, TunnelConfig.fromMap(settings, arguments))
                        mainHandler.postDelayed(watchdog, attemptTimeoutMs(attemptScanMode, fallbackAttempt, automatic))
                    } catch (error: Throwable) {
                        if (attemptFinished.compareAndSet(0, 1)) {
                            mainHandler.removeCallbacks(watchdog)
                            TunnelBus.removeStatusListener(statusListener)
                            onStatusOnMain(onStatus, "خطای اتصال: " + (error.message ?: error.javaClass.simpleName))
                        }
                    }
                }

                startAttempt(firstScanMode, false)
            } catch (error: Throwable) {
                onStatusOnMain(onStatus, "خطای اتصال: " + (error.message ?: error.javaClass.simpleName))
            }
        }, "azadlink-aether-connect").start()
    }

    fun disconnect(context: Context) {
        connectionGeneration.incrementAndGet()
        AetherVpnService.stop(context.applicationContext)
    }

    private fun attemptTimeoutMs(scanMode: String, fallbackAttempt: Boolean, automatic: Boolean): Long {
        // Let the first automatic balanced scan fail fast, but don't interrupt a user's
        // explicitly selected scan or the thorough fallback before Aether's own budget ends.
        if (automatic && !fallbackAttempt && scanMode.equals("balanced", ignoreCase = true)) {
            return 155_000L
        }
        return when (scanMode.trim().lowercase()) {
            "turbo", "fast" -> 115_000L
            "thorough", "deep", "pro" -> 370_000L
            "stealth", "quiet", "ironclad", "real", "verify", "guaranteed" -> 250_000L
            else -> 190_000L
        }
    }

    private fun hasIdentity(file: File): Boolean = runCatching {
        file.isFile && Regex("^device_id\\s*=", RegexOption.MULTILINE).containsMatchIn(file.readText())
    }.getOrDefault(false)

    private fun onStatusOnMain(callback: (String) -> Unit, message: String) {
        mainHandler.post { callback(message) }
    }
}
