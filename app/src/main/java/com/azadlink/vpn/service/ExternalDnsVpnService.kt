package com.azadlink.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.azadlink.vpn.R
import com.azadlink.vpn.core.Protocol
import org.bepass.oblivion.vpn.TProxyService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the VPN interface for the bundled MasterDNS, StormDNS and DNSTT clients.
 * DNSTT's remote endpoint must be a SOCKS5 server without authentication if it
 * is used for whole-device tunnelling or the built-in TUN-to-SOCKS bridge.
 */
class ExternalDnsVpnService : VpnService() {
    private val generation = AtomicInteger(0)
    private val lock = Any()
    @Volatile private var child: Process? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var label = "DNS tunnel"

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = generation.incrementAndGet()
        if (intent?.action == ACTION_CONNECT) {
            val raw = intent.getStringExtra(EXTRA_PROFILE).orEmpty()
            val protocol = runCatching { Protocol.valueOf(intent.getStringExtra(EXTRA_PROTOCOL).orEmpty()) }
                .getOrDefault(Protocol.DNSTT)
            label = intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: protocol.displayName
            foreground("در حال راه‌اندازی " + label)
            Thread({ connect(requestId, raw, protocol) }, "azadlink-dns-connect").start()
        } else {
            foreground("در حال قطع تونل")
            Thread({
                stopResources()
                status("اتصال قطع شد")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }, "azadlink-dns-disconnect").start()
        }
        return START_NOT_STICKY
    }

    private data class EngineSpec(val command: List<String>, val port: Int, val timeoutMs: Long)
    private data class DnsttSpec(val domain: String, val pubkey: String, val resolver: String, val port: Int)

    private fun connect(id: Int, raw: String, protocol: Protocol) {
        try {
            stopResources()
            checkCurrent(id)
            require(protocol in setOf(Protocol.DNSTT, Protocol.MASTER_DNS, Protocol.STORM_DNS)) {
                "این سرویس فقط برای DNSTT، MasterDNS و StormDNS است."
            }
            val folder = File(filesDir, "dns-engines/" + protocol.name.lowercase(Locale.ROOT)).apply { mkdirs() }
            val log = File(folder, "engine-output.log")
            val spec = createSpec(protocol, raw, folder)
            status("در حال اجرای " + protocol.displayName + " و بررسی اتصال SOCKS5…")

            val builder = ProcessBuilder(spec.command)
                .directory(folder)
                .redirectErrorStream(true)
                .redirectOutput(log)
            builder.environment()["HOME"] = folder.absolutePath
            builder.environment()["TMPDIR"] = cacheDir.absolutePath
            val process = builder.start()
            synchronized(lock) {
                if (generation.get() != id) {
                    process.destroyForcibly()
                    return
                }
                child = process
            }

            awaitSocks5(id, process, log, spec.port, spec.timeoutMs)
            checkCurrent(id)
            val prefs = getSharedPreferences("azadlink_routing", MODE_PRIVATE)
            val proxyOnly = prefs.getBoolean("proxy_only", false)
            if (!proxyOnly) {
                status("SOCKS5 پاسخ داد؛ در حال ایجاد رابط VPN…")
                val descriptor = establishTun(protocol, prefs)
                synchronized(lock) {
                    if (generation.get() != id) {
                        runCatching { descriptor.close() }
                        return
                    }
                    tun = descriptor
                }
                val config = File(folder, "hev-socks5-tunnel.yaml")
                config.writeText(hevConfig(spec.port, File(folder, "hev.log")))
                check(TProxyService.start(config.absolutePath, descriptor.fd)) {
                    "موتور پل TUN به SOCKS5 راه‌اندازی نشد."
                }
                if (generation.get() != id) {
                    stopResources()
                    return
                }
            }
            status(
                if (proxyOnly) "پراکسی محلی فعال شد · SOCKS5 127.0.0.1:" + spec.port
                else "موتور SOCKS5 آماده است؛ پل VPN فعال شد · " + protocol.displayName"
            )
            updateNotification(if (proxyOnly) "پراکسی محلی فعال است" else "تونل VPN فعال است")
        } catch (error: Throwable) {
            if (generation.get() == id) {
                stopResources()
                val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                status("خطای اتصال " + protocol.displayName + ": " + reason)
                updateNotification("خطای اتصال")
            }
        }
    }

    private fun createSpec(protocol: Protocol, raw: String, folder: File): EngineSpec {
        val binaryName = when (protocol) {
            Protocol.MASTER_DNS -> "libazadlink_masterdns.so"
            Protocol.STORM_DNS -> "libazadlink_stormdns.so"
            Protocol.DNSTT -> "libazadlink_dnstt.so"
            else -> error("پروتکل DNS پشتیبانی نشده است.")
        }
        val binary = File(applicationInfo.nativeLibraryDir, binaryName)
        check(binary.isFile) { "فایل موتور پیدا نشد: " + binaryName }
        if (!binary.canExecute()) runCatching { binary.setExecutable(true, false) }
        check(binary.canExecute()) { "فایل موتور قابل اجرا نیست: " + binaryName }

        if (protocol == Protocol.DNSTT) {
            val data = parseDnstt(raw)
            return EngineSpec(
                listOf(binary.absolutePath, "-udp", data.resolver, "-pubkey", data.pubkey,
                    data.domain, "127.0.0.1:" + data.port),
                data.port,
                75_000L
            )
        }

        val json = JSONObject(raw)
        val declared = json.optString("protocol", "").lowercase(Locale.ROOT)
        val expected = if (protocol == Protocol.MASTER_DNS) "masterdns" else "stormdns"
        require(declared == expected || declared == protocol.name.lowercase(Locale.ROOT)) {
            "پروفایل باید JSON کامل " + expected + " باشد."
        }
        val domains = strings(json, "DOMAINS", "domains").ifEmpty {
            json.optString("domain", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        require(domains.isNotEmpty() && domains.all { it.matches(Regex("^[A-Za-z0-9.-]+$")) }) {
            "فیلد DOMAINS باید حداقل یک دامنهٔ معتبر داشته باشد."
        }
        val key = firstString(json, "ENCRYPTION_KEY", "encryptionKey", "key")
        require(key.isNotBlank()) { "فیلد ENCRYPTION_KEY خالی است." }
        val resolverList = resolvers(json)
        require(resolverList.isNotEmpty()) {
            "فیلد RESOLVERS باید شامل IPهای DNS مثل 1.1.1.1 و 8.8.8.8 باشد."
        }
        require(resolverList.all { it.isNotBlank() && !it.any(Char::isWhitespace) && !it.contains("://") }) {
            "مقادیر RESOLVERS باید IP یا IP:port باشند."
        }
        val port = json.optInt("LISTEN_PORT", json.optInt("listenPort", 18000))
        require(port in 1024..65535) { "LISTEN_PORT باید بین 1024 تا 65535 باشد." }

        json.put("DOMAINS", JSONArray(domains))
        json.put("ENCRYPTION_KEY", key)
        json.put("PROTOCOL_TYPE", "SOCKS5")
        json.put("LISTEN_IP", "127.0.0.1")
        json.put("LISTEN_PORT", port)
        json.put("SOCKS5_AUTH", false)
        json.put("RESOLVERS", JSONArray(resolverList))
        json.put("LOG_TO_FILE", false)
        json.put("LOG_DIR", File(folder, "logs").absolutePath)
        if (protocol == Protocol.STORM_DNS) json.put("STARTUP_MODE", "resolvers")

        File(folder, "client_config.json").writeText(json.toString())
        val resolverFile = File(folder, "client_resolvers.txt")
        resolverFile.writeText(resolverList.joinToString("\n") + "\n")
        return EngineSpec(
            listOf(binary.absolutePath, "-config", File(folder, "client_config.json").absolutePath,
                "-resolvers", resolverFile.absolutePath),
            port,
            180_000L
        )
    }

    private fun parseDnstt(raw: String): DnsttSpec {
        val text = raw.trim()
        if (text.startsWith("{")) {
            val json = JSONObject(text)
            val declared = firstString(json, "protocol").lowercase(Locale.ROOT)
            require(declared == "dnstt") { "فیلد protocol در JSON باید dnstt باشد." }
            val domain = firstString(json, "domain", "DOMAIN", "server")
            val key = firstString(json, "pubkey", "PUBKEY", "publicKey")
            val resolver = firstString(json, "resolver", "UDP_RESOLVER", "udp").ifBlank { "1.1.1.1:53" }
            val port = json.optInt("localPort", json.optInt("LISTEN_PORT", 18080))
            validateDnstt(domain, key, resolver, port)
            return DnsttSpec(domain, key, resolver, port)
        }
        val uri = Uri.parse(text)
        val domain = uri.host ?: uri.getQueryParameter("domain").orEmpty()
        val key = uri.getQueryParameter("pubkey") ?: uri.getQueryParameter("PUBKEY").orEmpty()
        val resolver = uri.getQueryParameter("resolver") ?: "1.1.1.1:53"
        val port = uri.getQueryParameter("localPort")?.toIntOrNull() ?: 18080
        validateDnstt(domain, key, resolver, port)
        return DnsttSpec(domain, key, resolver, port)
    }

    private fun validateDnstt(domain: String, key: String, resolver: String, port: Int) {
        require(domain.matches(Regex("^[A-Za-z0-9.-]+$"))) { "دامنهٔ DNSTT معتبر نیست." }
        require(key.matches(Regex("^[0-9a-fA-F]{64}$"))) { "کلید عمومی DNSTT باید 64 رقم hex باشد." }
        require(resolver.matches(Regex("^[A-Za-z0-9.\\[\\]:-]+:[0-9]{1,5}$"))) {
            "resolver باید مانند 1.1.1.1:53 باشد."
        }
        require(port in 1024..65535) { "localPort باید بین 1024 تا 65535 باشد." }
    }

    private fun firstString(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (json.has(key) && !json.isNull(key)) {
                val result = json.optString(key, "").trim()
                if (result.isNotBlank()) return result
            }
        }
        return ""
    }

    private fun strings(json: JSONObject, vararg keys: String): List<String> {
        keys.forEach { key ->
            val array = json.optJSONArray(key) ?: return@forEach
            val values = (0 until array.length()).map { array.optString(it, "").trim() }.filter { it.isNotBlank() }
            if (values.isNotEmpty()) return values
        }
        return emptyList()
    }

    private fun resolvers(json: JSONObject): List<String> {
        val arrayValues = strings(json, "RESOLVERS", "resolvers", "CLIENT_RESOLVERS")
        if (arrayValues.isNotEmpty()) return arrayValues
        return firstString(json, "RESOLVERS_TEXT", "resolversText", "CLIENT_RESOLVERS_TEXT")
            .split('\n', ',', ';').map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
    }

    private fun awaitSocks5(id: Int, process: Process, log: File, port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            checkCurrent(id)
            if (!process.isAlive) error("موتور DNS پیش از آماده‌شدن خارج شد. " + logTail(log))
            if (probeSocks5(port)) return
            Thread.sleep(1_300L)
        }
        error("پراکسی SOCKS5 آماده نشد. " + logTail(log))
    }

    private fun probeSocks5(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_300)
            socket.soTimeout = 2_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 0)); output.flush()
            if (input.read() != 5 || input.read() != 0) return@use false
            output.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 1, 0xBB.toByte())); output.flush()
            val version = input.read()
            val reply = input.read()
            val reserved = input.read()
            val type = input.read()
            version == 5 && reply == 0 && reserved == 0 && type in 1..4
        }
    }.getOrDefault(false)

    private fun establishTun(protocol: Protocol, prefs: android.content.SharedPreferences): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("AzadLink · " + protocol.displayName)
            .setMtu(1500)
            .addAddress("198.18.0.1", 30)
            .addAddress("fc00::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
            .addDnsServer("2606:4700:4700::1111")
        val mode = prefs.getString("split_mode", "disabled") ?: "disabled"
        val selected = prefs.getStringSet("selected_apps", emptySet())?.toList() ?: emptyList()
        if (mode == "onlySelected") {
            var count = 0
            selected.filter { it != packageName }.forEach { app ->
                try { builder.addAllowedApplication(app); count++ }
                catch (_: PackageManager.NameNotFoundException) { status("برنامه نصب نیست: " + app) }
            }
            check(count > 0) { "برای حالت فقط برنامه‌های انتخاب‌شده، حداقل یک برنامه انتخاب کن." }
        } else {
            runCatching { builder.addDisallowedApplication(packageName) }
            if (mode == "bypassSelected") selected.filter { it != packageName }.forEach { app ->
                try { builder.addDisallowedApplication(app) }
                catch (_: PackageManager.NameNotFoundException) { status("برنامه نصب نیست: " + app) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return builder.establish() ?: error("Android رابط VPN را ایجاد نکرد.")
    }

    private fun hevConfig(port: Int, file: File): String {
        val safePath = file.absolutePath.replace("'", "")
        return """
            tunnel:
              mtu: 1500
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
              post-up-script: ''
            socks5:
              port: $port
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 20480
              tcp-buffer-size: 8192
              connect-timeout: 10000
              read-write-timeout: 60000
              log-level: 'warn'
              log-file: '$safePath'
        """.trimIndent() + "\n"
    }

    private fun checkCurrent(id: Int) { check(generation.get() == id) { "درخواست اتصال لغو شد." } }

    private fun logTail(file: File): String = runCatching {
        if (!file.isFile) return@runCatching ""
        file.readLines().takeLast(5).joinToString(" | ").take(700)
    }.getOrDefault("")

    private fun stopResources() {
        synchronized(lock) {
            runCatching { TProxyService.stop() }
            val descriptor = tun
            tun = null
            runCatching { descriptor?.close() }
            val process = child
            child = null
            if (process != null) {
                runCatching { process.destroy() }
                runCatching { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly() }
            }
        }
    }

    private fun status(message: String) {
        getSharedPreferences(AzadLinkVpnService.PREFS, MODE_PRIVATE)
            .edit().putString(AzadLinkVpnService.KEY_MESSAGE, message).apply()
        sendBroadcast(Intent(AzadLinkVpnService.ACTION_STATUS).setPackage(packageName)
            .putExtra(AzadLinkVpnService.EXTRA_MESSAGE, message))
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AzadLink DNS tunnel", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String) = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("AzadLink · " + label)
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun foreground(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    override fun onRevoke() {
        generation.incrementAndGet()
        stopResources()
        status("دسترسی VPN لغو شد")
        super.onRevoke()
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        stopResources()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "azadlink_dns_tunnel"
        private const val NOTIFICATION_ID = 1101
        const val ACTION_CONNECT = "com.azadlink.vpn.DNS_CONNECT"
        const val ACTION_DISCONNECT = "com.azadlink.vpn.DNS_DISCONNECT"
        const val EXTRA_PROFILE = "dns_profile"
        const val EXTRA_PROTOCOL = "dns_protocol"
        const val EXTRA_LABEL = "dns_label"
    }
}
