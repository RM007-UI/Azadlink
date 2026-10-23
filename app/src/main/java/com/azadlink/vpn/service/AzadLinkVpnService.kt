package com.azadlink.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.widget.Toast
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import com.azadlink.vpn.R
import com.azadlink.vpn.core.Protocol
import com.azadlink.vpn.runtime.CoreConfigBuilder
import com.azadlink.vpn.core.ProtocolProfile
import java.util.concurrent.Executors

/**
 * Owns the Xray-backed VPN session for share-link protocols. MASQUE and
 * Psiphon-only sessions are owned by the upstream AetherVpnService.
 */
class AzadLinkVpnService : VpnService(), CoreCallbackHandler {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var coreController: CoreController? = null
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var currentLabel: String = "AzadLink"

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی تونل"))
        when (intent?.action) {
            ACTION_DISCONNECT -> worker.execute { stopTunnel("اتصال قطع شد") }
            ACTION_CONNECT -> {
                val raw = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
                val protocolName = intent.getStringExtra(EXTRA_PROTOCOL).orEmpty()
                currentLabel = intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: protocolName
                worker.execute { startTunnel(raw, protocolName) }
            }
            else -> {
                publishStatus("سرویس آماده است")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(raw: String, protocolName: String) {
        releaseTunnel()
        try {
            val protocol = runCatching { Protocol.valueOf(protocolName) }
                .getOrElse { throw IllegalArgumentException("پروتکل ناشناخته است: $protocolName") }
            require(protocol in setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS, Protocol.WIREGUARD)) {
                "موتور Xray برای $protocolName انتخاب نشده است."
            }
            publishStatus("در حال ساخت کانفیگ $protocolName…")
            val profile = ProtocolProfile(protocol = protocol, label = currentLabel, rawConfig = raw)
            val config = CoreConfigBuilder.build(profile)

            val routingPrefs = getSharedPreferences("azadlink_routing", MODE_PRIVATE)
            val splitMode = routingPrefs.getString("split_mode", "disabled") ?: "disabled"
            val selectedApps = routingPrefs.getStringSet("selected_apps", emptySet())?.toList() ?: emptyList()
            val proxyOnly = routingPrefs.getBoolean("proxy_only", false)
            val tun: ParcelFileDescriptor? = if (proxyOnly) {
                publishStatus("حالت پراکسی: موتور محلی در حال راه‌اندازی است…")
                null
            } else {
                publishStatus("در حال ایجاد رابط VPN…")
                val builder = Builder()
                    .setSession("AzadLink · $currentLabel")
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addAddress("fd00:1:fd00:1::2", 126)
                    .addRoute("::", 0)
                    .addDnsServer("2606:4700:4700::1111")
                if (splitMode == "onlySelected") {
                    var allowedCount = 0
                    selectedApps.filter { it != packageName }.forEach { app ->
                        try {
                            builder.addAllowedApplication(app)
                            allowedCount++
                        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                            publishStatus("برنامهٔ انتخاب‌شده نصب نیست: $app")
                        }
                    }
                    check(allowedCount > 0) { "برای حالت «فقط برنامه‌های انتخاب‌شده» حداقل یک برنامهٔ نصب‌شده انتخاب کن." }
                } else {
                    builder.addDisallowedApplication(packageName)
                    if (splitMode == "bypassSelected") {
                        selectedApps.filter { it != packageName }.forEach { app ->
                            try {
                                builder.addDisallowedApplication(app)
                            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                                publishStatus("برنامهٔ انتخاب‌شده نصب نیست: $app")
                            }
                        }
                    }
                }
                builder.establish() ?: throw IllegalStateException("Android رابط VPN را ایجاد نکرد")
            }
            vpnInterface = tun

            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            val controller = Libv2ray.newCoreController(this)
            coreController = controller
            publishStatus("در حال راه‌اندازی موتور Xray…")
            // AndroidLibXrayLite documents fd=0 as proxy-only mode without a TUN interface.
            controller.startLoop(config, tun?.fd ?: 0)
            check(controller.isRunning) { "هستهٔ Xray اجرا نشد" }
            publishStatus(if (proxyOnly) "موتور اجرا شد؛ در حال آزمون پراکسی محلی…" else "موتور اجرا شد؛ در حال آزمون عبور ترافیک…")
            verifyTrafficThroughTunnel(proxyOnly)

        } catch (error: Throwable) {
            stopTunnel("خطای اتصال: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun releaseTunnel() {
        runCatching { coreController?.stopLoop() }
        coreController = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun stopTunnel(message: String) {
        releaseTunnel()
        publishStatus(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishStatus(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MESSAGE, message).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
    }

    override fun startup(): Long {
        publishStatus("موتور Xray راه‌اندازی شد")
        return 0
    }

    override fun shutdown(): Long = 0

    override fun onEmitStatus(code: Long, message: String?): Long {
        val value = message?.trim().orEmpty()
        if (value.contains("error", true) || value.contains("failed", true)) {
            publishStatus("خطای Xray: $value")
        }
        return 0
    }

    private fun verifyTrafficThroughTunnel(proxyOnly: Boolean = false) {
        try {
            val socksProxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", 10808)
            )
            val connection = (java.net.URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection(socksProxy)
                as javax.net.ssl.HttpsURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            val code = try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
            if (code !in 200..399) throw IllegalStateException("آزمون اینترنت پاسخ HTTP $code داد")
            publishStatus(if (proxyOnly)
                "پراکسی محلی فعال و آزموده شد · SOCKS5 127.0.0.1:10808"
            else
                "موتور و خروجی پراکسی آزموده شدند؛ وضعیت VPN فعال است")
        } catch (error: Throwable) {
            stopTunnel("اتصال تونل تأیید نشد: ${error.message ?: "آزمون اینترنت ناموفق بود"}")
        }
    }

    override fun onRevoke() {
        worker.execute { stopTunnel("دسترسی VPN لغو شد") }
        super.onRevoke()
    }

    override fun onDestroy() {
        worker.execute { releaseTunnel() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "AzadLink connection", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AzadLink · $currentLabel")
            .setContentText(message)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "azadlink_connection"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STATUS = "com.azadlink.vpn.ACTION_STATUS"
        const val ACTION_CONNECT = "com.azadlink.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.azadlink.vpn.ACTION_DISCONNECT"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_PROFILE = "extra_profile"
        const val EXTRA_PROTOCOL = "extra_protocol"
        const val EXTRA_LABEL = "extra_label"
        const val PREFS = "azadlink_state"
        const val KEY_MESSAGE = "message"
    }
}
