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
        stopTunnel("در حال جایگزینی اتصال قبلی")
        try {
            val protocol = runCatching { Protocol.valueOf(protocolName) }
                .getOrElse { throw IllegalArgumentException("پروتکل ناشناخته است: $protocolName") }
            require(protocol in setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS)) {
                "موتور Xray برای $protocolName انتخاب نشده است."
            }
            publishStatus("در حال ساخت کانفیگ $protocolName…")
            val profile = ProtocolProfile(protocol = protocol, label = currentLabel, rawConfig = raw)
            val config = CoreConfigBuilder.build(profile)

            publishStatus("در حال ایجاد رابط VPN…")
            val tun = Builder()
                .setSession("AzadLink · $currentLabel")
                .setMtu(1500)
                .addAddress("10.0.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addAddress("fd00:1:fd00:1::2", 126)
                .addRoute("::", 0)
                .addDnsServer("2606:4700:4700::1111")
                .establish() ?: throw IllegalStateException("Android رابط VPN را ایجاد نکرد")
            vpnInterface = tun

            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            val controller = Libv2ray.newCoreController(this)
            coreController = controller
            publishStatus("در حال راه‌اندازی موتور Xray…")
            controller.startLoop(config, tun.fd)
            check(controller.isRunning) { "هستهٔ Xray اجرا نشد" }
            publishStatus("درخواست اتصال به موتور ارسال شد؛ در حال بررسی تونل")
        } catch (error: Throwable) {
            stopTunnel("خطای اتصال: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun stopTunnel(message: String) {
        runCatching { coreController?.stopLoop() }
        coreController = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
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
        message?.takeIf { it.isNotBlank() }?.let { publishStatus("Xray: $it") }
        return 0
    }

    override fun onRevoke() {
        worker.execute { stopTunnel("دسترسی VPN لغو شد") }
        super.onRevoke()
    }

    override fun onDestroy() {
        worker.execute { stopTunnel("سرویس متوقف شد") }
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
            .setSmallIcon(android.R.drawable.stat_sys_vpn_ic)
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
