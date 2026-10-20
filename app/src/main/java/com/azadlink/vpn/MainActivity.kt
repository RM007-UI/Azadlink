package com.azadlink.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.net.VpnService
import android.content.ActivityNotFoundException
import com.azadlink.vpn.runtime.ExternalEngineProfiles
import com.azadlink.vpn.runtime.PsiphonAutoConnector
import com.azadlink.vpn.runtime.PsiphonTunnelRuntime
import com.azadlink.vpn.service.AzadLinkVpnService
import com.azadlink.vpn.core.EngineRegistry
import com.azadlink.vpn.core.Protocol
import com.azadlink.vpn.core.ProtocolParser
import com.azadlink.vpn.core.ProtocolProfile
import com.azadlink.vpn.runtime.CoreConfigBuilder
import com.azadlink.vpn.runtime.AetherAutoConnector
import org.bepass.oblivion.vpn.TunnelBus
import org.bepass.oblivion.vpn.TunnelSnapshot
import org.bepass.oblivion.vpn.TunnelStage

class MainActivity : Activity() {
    private val profiles = mutableListOf<ProtocolProfile>()
    private lateinit var profileInput: EditText
    private lateinit var profileList: LinearLayout
    private lateinit var topStatus: TextView
    private var pendingConnectProfile: ProtocolProfile? = null
    private var pendingAutoConnect = false
    private var pendingPsiphonAuto = false
    private var receiverRegistered = false
    private val tunnelStatusListener: (TunnelSnapshot) -> Unit = { snapshot ->
        runOnUiThread {
            if (::topStatus.isInitialized) {
                val state = when (snapshot.stage) {
                    TunnelStage.DISCONNECTED -> "قطع"
                    TunnelStage.CONNECTING -> "در حال اتصال"
                    TunnelStage.VALIDATING -> "در حال بررسی تونل"
                    TunnelStage.CONNECTED -> "متصل"
                    TunnelStage.DISCONNECTING -> "در حال قطع"
                    TunnelStage.FAILED -> "اتصال ناموفق"
                }
                val detail = snapshot.message?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                val gateway = snapshot.gateway?.let { " · $it" } ?: ""
                topStatus.text = "وضعیت واقعی: $state$gateway$detail"
            }
        }
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(AzadLinkVpnService.EXTRA_MESSAGE) ?: return
            topStatus.text = "وضعیت: $message"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 17, 28)
        window.navigationBarColor = Color.rgb(12, 17, 28)
        window.decorView.systemUiVisibility = 0
        buildUi()
        refreshState()
    }
    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(AzadLinkVpnService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else { @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter) }
            receiverRegistered = true
        }
        refreshState()
        TunnelBus.addStatusListener(tunnelStatusListener)
    }
    override fun onStop() {
        TunnelBus.removeStatusListener(tunnelStatusListener)
        if (receiverRegistered) { runCatching { unregisterReceiver(statusReceiver) }; receiverRegistered = false }
        super.onStop()
    }
    @Deprecated("Deprecated in Android API; kept for VPN consent compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_PSIPHON) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri == null) Toast.makeText(this, "فایلی انتخاب نشد", Toast.LENGTH_LONG).show()
                else runCatching {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalArgumentException("خواندن فایل ناموفق بود")
                    PsiphonTunnelRuntime.validateConfigFile(this, json)
                }.onSuccess {
                    Toast.makeText(this, "تنظیمات Psiphon در حافظهٔ خصوصی اپ ذخیره شد", Toast.LENGTH_LONG).show()
                    renderProfiles()
                }.onFailure {
                    Toast.makeText(this, "تنظیمات Psiphon پذیرفته نشد: ${it.message ?: "JSON نامعتبر"}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        if (requestCode != REQUEST_VPN_PERMISSION) return
        if (pendingPsiphonAuto) {
            pendingPsiphonAuto = false
            if (resultCode == RESULT_OK) startPsiphonAutoConnect()
            else Toast.makeText(this, "اجازهٔ VPN داده نشد", Toast.LENGTH_LONG).show()
            return
        }
        if (pendingAutoConnect) {
            pendingAutoConnect = false
            if (resultCode == RESULT_OK) startAutoConnect()
            else Toast.makeText(this, "اجازهٔ VPN داده نشد", Toast.LENGTH_LONG).show()
            return
        }
        val profile = pendingConnectProfile
        pendingConnectProfile = null
        if (resultCode == RESULT_OK && profile != null) startVpn(profile)
        else Toast.makeText(this, "اجازهٔ VPN داده نشد", Toast.LENGTH_LONG).show()
    }
    private fun refreshState() {
        val prefs = getSharedPreferences(AzadLinkVpnService.PREFS, MODE_PRIVATE)
        topStatus.text = "وضعیت: " + (prefs.getString(AzadLinkVpnService.KEY_MESSAGE, "آمادهٔ اتصال") ?: "آمادهٔ اتصال")
    }
    private fun requestConnect(profile: ProtocolProfile) {
        if (profile.protocol == Protocol.PSIPHON) {
            requestPsiphonAutoConnect()
            return
        }
        val validation = runCatching {
            when (profile.protocol) {
                Protocol.MASQUE, Protocol.WIREGUARD, Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS -> CoreConfigBuilder.build(profile)
                Protocol.PSIPHON -> {
                    val embedded = ExternalEngineProfiles.embeddedPsiphonConfig(profile.rawConfig)
                    if (embedded == null && !PsiphonTunnelRuntime.canLoad(profile.rawConfig, this))
                        throw IllegalArgumentException("اتصال خودکار Psiphon هنوز به موتور رسمی وصل نشده؛ برای Psiphon کانفیگ دستی لازم نیست.")
                }
                Protocol.MASTER_DNS, Protocol.STORM_DNS -> ExternalEngineProfiles.parseDnsTunnel(profile)
                Protocol.DNSTT -> ExternalEngineProfiles.parseDnstt(profile.rawConfig)
            }
        }
        validation.exceptionOrNull()?.let { Toast.makeText(this, "کانفیگ آمادهٔ اجرا نیست: ${it.message ?: "فرمت پشتیبانی‌نشده"}", Toast.LENGTH_LONG).show(); return }
        pendingConnectProfile = profile
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
        else { pendingConnectProfile = null; startVpn(profile) }
    }
    private fun importPsiphonConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(intent, REQUEST_IMPORT_PSIPHON) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "مدیر فایل سازگار پیدا نشد", Toast.LENGTH_LONG).show() }
    }
    private fun startVpn(profile: ProtocolProfile) {
        val serviceIntent = Intent(this, AzadLinkVpnService::class.java).apply {
            action = AzadLinkVpnService.ACTION_CONNECT
            putExtra(AzadLinkVpnService.EXTRA_PROFILE, profile.rawConfig)
            putExtra(AzadLinkVpnService.EXTRA_PROTOCOL, profile.protocol.name)
            putExtra(AzadLinkVpnService.EXTRA_LABEL, profile.label)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        topStatus.text = "وضعیت: درخواست اتصال ارسال شد"
    }
    private fun requestPsiphonAutoConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingPsiphonAuto = true
            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
        } else startPsiphonAutoConnect()
    }

    private fun startPsiphonAutoConnect() {
        topStatus.text = "وضعیت: در حال راه‌اندازی Psiphon…"
        PsiphonAutoConnector.connect(this) { message ->
            if (::topStatus.isInitialized) topStatus.text = "وضعیت: $message"
            if (message.startsWith("خطای Psiphon:")) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestAutoConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingAutoConnect = true
            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
        } else {
            startAutoConnect()
        }
    }

    private fun startAutoConnect() {
        topStatus.text = "وضعیت: در حال آماده‌سازی موتور Aether…"
        AetherAutoConnector.connect(this) { message ->
            topStatus.text = "وضعیت: $message"
            if (message.startsWith("خطای اتصال:")) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun disconnectVpn() {
        AetherAutoConnector.disconnect(this)
        val stopIntent = Intent(this, AzadLinkVpnService::class.java).apply {
            action = AzadLinkVpnService.ACTION_DISCONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(stopIntent) else startService(stopIntent)
        topStatus.text = "وضعیت: درخواست قطع اتصال ارسال شد"
    }
    private fun buildUi() {
        val bg = Color.rgb(8, 11, 20)
        val surface = Color.rgb(17, 23, 38)
        val surfaceRaised = Color.rgb(24, 31, 50)
        val accent = Color.rgb(137, 116, 255)
        val muted = Color.rgb(151, 164, 190)
        val mint = Color.rgb(104, 225, 190)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(12))
        }
        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_azadlink)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        brand.addView(text("AzadLink", 21, Color.WHITE, true))
        brand.addView(text("اتصال، با انتخاب خودت", 11, muted))
        header.addView(logo)
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val routingButton = TextView(this).apply {
            text = "⚙"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(surfaceRaised, 15)
            isClickable = true
            isFocusable = true
            setOnClickListener { showRoutingSettings() }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        header.addView(routingButton)
        root.addView(header)

        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(surface, 17)
        }
        val statusDot = TextView(this).apply {
            text = "●"
            textSize = 12f
            setTextColor(mint)
            setPadding(0, 0, dp(9), 0)
        }
        topStatus = text("آمادهٔ اتصال", 13, Color.WHITE, true)
        statusPanel.addView(statusDot)
        statusPanel.addView(topStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val logsButton = TextView(this).apply {
            text = "گزارش"
            textSize = 12f
            setTextColor(Color.rgb(198, 188, 255))
            setPadding(dp(8), dp(5), 0, dp(5))
            isClickable = true
            setOnClickListener { showTunnelLogs() }
        }
        statusPanel.addView(logsButton)
        val statusParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusParams.setMargins(dp(16), 0, dp(16), dp(12))
        root.addView(statusPanel, statusParams)

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(24))
        }
        scroll.addView(content)
        val pages = mutableListOf<LinearLayout>()

        fun heading(title: String, subtitle: String) {
            content.addView(text(title, 25, Color.WHITE, true))
            content.addView(space(5))
            content.addView(text(subtitle, 13, muted))
            content.addView(space(18))
        }
        fun sectionLabel(value: String) {
            content.addView(text(value, 14, Color.rgb(198, 188, 255), true))
            content.addView(space(9))
        }
        fun actionCard(symbol: String, title: String, subtitle: String, action: () -> Unit, tint: Int = surfaceRaised) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(13), dp(13), dp(13))
                background = rounded(tint, 18)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            val mark = TextView(this).apply {
                text = symbol
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(48, 43, 82), 14)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            }
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(5), 0)
            }
            copy.addView(text(title, 15, Color.WHITE, true))
            copy.addView(space(3))
            copy.addView(text(subtitle, 11, muted))
            card.addView(mark)
            card.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(text("‹", 23, muted, true))
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(9)
            content.addView(card, params)
        }
        fun page(build: () -> Unit) {
            val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            content.addView(page)
            // Build each tab into its own container; only one page is visible at a time.
            val savedParent = page.parent
            pages.add(page)
            val currentContent = content
            // Temporarily direct additions to the page by moving it to the end and swapping the target.
            val childStart = currentContent.childCount
            build()
            val created = (childStart until currentContent.childCount).map { currentContent.getChildAt(it) }
            created.forEach { currentContent.removeView(it); page.addView(it) }
        }

        page {
            heading("پروتکل‌های سروری", "کانفیگ‌ها و اتصال‌های مبتنی بر سرور")
            sectionLabel("انتخاب پروتکل")
            actionCard("V", "VLESS", "سبک و مناسب کانفیگ‌های مدرن", { showProtocolDetail(Protocol.VLESS) })
            actionCard("M", "VMess", "پروتکل مبتنی بر پروفایل", { showProtocolDetail(Protocol.VMESS) })
            actionCard("T", "Trojan", "اتصال با پیکربندی TLS", { showProtocolDetail(Protocol.TROJAN) })
            actionCard("S", "Shadowsocks", "پروکسی رمزگذاری‌شده", { showProtocolDetail(Protocol.SHADOWSOCKS) })
            actionCard("W", "WireGuard", "تونل VPN مبتنی بر کلید", { showProtocolDetail(Protocol.WIREGUARD) })
            content.addView(space(10))
            sectionLabel("افزودن کانفیگ")
            profileInput = EditText(this).apply {
                hint = "لینک vless:// یا vmess:// یا کانفیگ WireGuard"
                setHintTextColor(Color.rgb(104, 117, 145))
                setTextColor(Color.WHITE)
                textSize = 13f
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP or Gravity.START
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = rounded(surface, 16)
            }
            content.addView(profileInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(space(9))
            content.addView(button("تشخیص و افزودن کانفیگ") { importProfiles() }.apply {
                background = rounded(accent, 15)
                minHeight = dp(49)
            })
            content.addView(space(16))
            sectionLabel("پروفایل‌های اضافه‌شده")
            profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(profileList)
        }

        page {
            heading("تونل‌های DNS", "ابزارهای DNS در یک بخش مستقل")
            actionCard("D", "DNSTT", "تونل DNS؛ نیازمند تنظیمات سرور", { showProtocolDetail(Protocol.DNSTT) })
            actionCard("M", "Master DNS", "موتور مستقل MasterDNS", { showProtocolDetail(Protocol.MASTER_DNS) })
            actionCard("ϟ", "Storm DNS", "موتور مستقل StormDNS", { showProtocolDetail(Protocol.STORM_DNS) })
            content.addView(space(12))
            content.addView(text("وضعیت هر موتور پس از شروع اتصال باید از خود سرویس تأیید شود؛ نمایش این کارت‌ها به‌تنهایی به معنی آماده‌بودن اتصال نیست.", 12, muted))
        }

        page {
            heading("Aether و MASQUE", "تنظیمات اتصال را با شبکهٔ خودت هماهنگ کن")
            val hero = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(17), dp(18), dp(17), dp(17))
                background = rounded(Color.rgb(30, 27, 57), 22)
            }
            hero.addView(text("اتصال خودکار MASQUE", 20, Color.WHITE, true))
            hero.addView(space(6))
            hero.addView(text("حالت HTTP/2 و پروفایل firewall به‌صورت پیش‌فرض انتخاب شده‌اند؛ این ترکیب در گزارش دستگاهت تونل را تأیید کرده بود.", 12, Color.rgb(193, 188, 226)))
            hero.addView(space(14))
            hero.addView(button("اتصال به MASQUE") { requestAutoConnect() }.apply {
                background = rounded(accent, 15)
                minHeight = dp(51)
            })
            hero.addView(space(8))
            hero.addView(button("تنظیمات پیشرفته") { showMasqueAdvancedSettings() }.apply {
                background = rounded(Color.rgb(49, 46, 75), 15)
                minHeight = dp(47)
            })
            hero.addView(space(8))
            hero.addView(button("قطع اتصال") { disconnectVpn() }.apply {
                background = rounded(Color.rgb(63, 38, 53), 15)
                minHeight = dp(45)
            })
            content.addView(hero)
            content.addView(space(17))
            sectionLabel("نکتهٔ اتصال")
            content.addView(text("اگر اسکن پیش‌فرض gateway پیدا نکرد، ابتدا HTTP/2 را نگه دار و گزارش موتور را بررسی کن. نتیجه به شبکه و دسترسی درگاه‌ها وابسته است.", 13, muted))
        }

        page {
            heading("Psiphon", "اتصال مستقیم؛ بدون واردکردن فایل JSON")
            val psiphonCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(19), dp(18), dp(18))
                background = rounded(Color.rgb(26, 31, 48), 22)
            }
            val emblem = TextView(this).apply {
                text = "✦"
                textSize = 31f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(63, 47, 105), 18)
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
            }
            psiphonCard.addView(emblem)
            psiphonCard.addView(space(12))
            psiphonCard.addView(text("اتصال خودکار Psiphon", 20, Color.WHITE, true))
            psiphonCard.addView(space(6))
            psiphonCard.addView(text("نسخهٔ جدید باید با هستهٔ Psiphon سازگار ساخته شود. اگر خطا رخ دهد، از گزارش اتصال می‌توان علت دقیق را دید.", 12, muted))
            psiphonCard.addView(space(15))
            psiphonCard.addView(button("اتصال به Psiphon") { requestPsiphonAutoConnect() }.apply {
                background = rounded(accent, 15)
                minHeight = dp(51)
            })
            psiphonCard.addView(space(8))
            psiphonCard.addView(button("قطع اتصال") { disconnectVpn() }.apply {
                background = rounded(Color.rgb(63, 38, 53), 15)
                minHeight = dp(45)
            })
            content.addView(psiphonCard)
            content.addView(space(14))
            content.addView(button("نمایش گزارش خطا") { showTunnelLogs() }.apply {
                background = rounded(surfaceRaised, 15)
                minHeight = dp(45)
            })
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = rounded(Color.rgb(15, 20, 33), 21)
        }
        val tabs = listOf("سرورها", "DNS", "Aether", "Psiphon")
        val icons = listOf("⌘", "⌁", "◈", "✦")
        val tabButtons = mutableListOf<LinearLayout>()
        fun selectTab(index: Int) {
            pages.forEachIndexed { i, target ->
                if (i == index) {
                    target.visibility = View.VISIBLE
                    target.alpha = 0f
                    target.translationY = dp(7).toFloat()
                    target.animate().alpha(1f).translationY(0f).setDuration(190).start()
                } else {
                    target.animate().cancel()
                    target.visibility = View.GONE
                }
            }
            tabButtons.forEachIndexed { i, item ->
                item.background = rounded(if (i == index) Color.rgb(64, 53, 105) else Color.TRANSPARENT, 15)
                item.animate().scaleX(if (i == index) 1.04f else 1f).scaleY(if (i == index) 1.04f else 1f).setDuration(150).start()
                val icon = item.getChildAt(0) as TextView
                val label = item.getChildAt(1) as TextView
                icon.setTextColor(if (i == index) Color.WHITE else muted)
                label.setTextColor(if (i == index) Color.WHITE else muted)
            }
        }
        tabs.forEachIndexed { index, label ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(7), dp(3), dp(7))
                background = rounded(if (index == 0) Color.rgb(64, 53, 105) else Color.TRANSPARENT, 15)
                isClickable = true
                isFocusable = true
                addView(TextView(this@MainActivity).apply {
                    text = icons[index]
                    textSize = 17f
                    gravity = Gravity.CENTER
                    setTextColor(if (index == 0) Color.WHITE else muted)
                })
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(if (index == 0) Color.WHITE else muted)
                })
                setOnClickListener { selectTab(index) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            tabButtons.add(tab)
            bottomBar.addView(tab)
        }

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val navParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        navParams.setMargins(dp(10), dp(5), dp(10), dp(7))
        root.addView(bottomBar, navParams)
        setContentView(root)
        selectTab(0)
        renderProfiles()
    }

    private fun showTunnelLogs() {
        val logs = TunnelBus.readLogs(this).trim().ifBlank { "هنوز گزارشی از موتور Aether ثبت نشده است." }
        val logView = TextView(this).apply {
            text = logs.takeLast(14000)
            textSize = 12f
            setTextColor(Color.rgb(222, 228, 242))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        AlertDialog.Builder(this)
            .setTitle("گزارش اتصال")
            .setView(scroll)
            .setPositiveButton("بستن", null)
            .setNeutralButton("کپی", null)
            .show()
            .getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AzadLink logs", logs))
                Toast.makeText(this, "گزارش کپی شد.", Toast.LENGTH_LONG).show()
            }
    }

    private fun showRoutingSettings() {
        val prefs = getSharedPreferences("azadlink_routing", MODE_PRIVATE)
        val currentMode = prefs.getString("split_mode", "disabled") ?: "disabled"
        val selected = prefs.getStringSet("selected_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        panel.addView(text("انتخاب کن کدام برنامه‌ها از تونل عبور کنند.", 14, Color.WHITE, true))
        panel.addView(space(8))
        val modeGroup = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val modeOptions = listOf(
            "disabled" to "همهٔ برنامه‌ها از تونل عبور کنند",
            "bypassSelected" to "همه به‌جز برنامه‌های انتخاب‌شده",
            "onlySelected" to "فقط برنامه‌های انتخاب‌شده"
        )
        val radios = modeOptions.map { (value, label) ->
            android.widget.RadioButton(this).apply {
                text = label
                tag = value
                textSize = 13f
                setTextColor(Color.WHITE)
                isChecked = currentMode == value
                modeGroup.addView(this)
            }
        }
        panel.addView(modeGroup)
        panel.addView(space(8))
        val proxyOnly = android.widget.CheckBox(this).apply {
            text = "حالت فقط پراکسی (بدون مسیریابی سراسری)"
            textSize = 13f
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("proxy_only", false)
        }
        panel.addView(proxyOnly)
        panel.addView(text(
            "حالت فقط پراکسی برای Aether/Psiphon و پروتکل‌های Xray فعال است؛ موتورهای DNS هنوز به یکپارچه‌سازی جداگانه نیاز دارند. در این حالت، برنامه‌های دلخواه باید دستی به پراکسی محلی وصل شوند (Xray: SOCKS5 روی 127.0.0.1:10808؛ Aether: پورت SOCKS انتخاب‌شده).",
            11, Color.rgb(255, 205, 115)
        ))
        panel.addView(space(10))
        panel.addView(text("برنامه‌ها", 15, Color.rgb(207, 199, 255), true))
        panel.addView(space(5))

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (appInfo.packageName == packageName) return@mapNotNull null
                val label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(appInfo.packageName)
                Triple(label, appInfo.packageName, info.loadIcon(packageManager))
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        val appChecks = mutableMapOf<String, android.widget.CheckBox>()
        val appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apps.forEach { (label, pkg, icon) ->
            val check = android.widget.CheckBox(this).apply {
                text = "$label  ·  $pkg"
                textSize = 12f
                setTextColor(Color.WHITE)
                isChecked = selected.contains(pkg)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(132, 103, 255))
                setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
                compoundDrawablePadding = dp(8)
            }
            appChecks[pkg] = check
            appList.addView(check)
        }
        val appScroll = ScrollView(this).apply {
            addView(appList)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240))
        }
        panel.addView(appScroll)
        AlertDialog.Builder(this)
            .setTitle("مسیریابی و حالت پراکسی")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val mode = radios.firstOrNull { it.isChecked }?.tag as? String ?: "disabled"
                val chosen = appChecks.filterValues { it.isChecked }.keys
                prefs.edit()
                    .putString("split_mode", mode)
                    .putStringSet("selected_apps", chosen.toSet())
                    .putBoolean("proxy_only", proxyOnly.isChecked)
                    .apply()
                Toast.makeText(this, "تنظیمات ذخیره شد؛ اتصال را قطع و دوباره برقرار کن.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun showMasqueAdvancedSettings() {
        val prefs = getSharedPreferences("azadlink_masque", MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(8))
        }
        fun addLabel(value: String) {
            panel.addView(text(value, 13, Color.rgb(186, 196, 218), true))
            panel.addView(space(4))
        }
        fun spinner(items: List<String>, selected: Int): android.widget.Spinner {
            return android.widget.Spinner(this).apply {
                adapter = android.widget.ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    items
                )
                setSelection(selected)
            }
        }
        addLabel("انتقال")
        val transportSpinner = spinner(
            listOf("HTTP/2 · پیشنهادشده برای شبکهٔ من", "حالت پیش‌فرض موتور"),
            if ((prefs.getString("transport", "h2") ?: "h2") == "h2") 0 else 1
        )
        panel.addView(transportSpinner)
        panel.addView(space(10))
        addLabel("الگوی عبور از محدودیت")
        val noiseSpinner = spinner(
            listOf("firewall", "balanced"),
            if ((prefs.getString("obfuscation", "firewall") ?: "firewall") == "firewall") 0 else 1
        )
        panel.addView(noiseSpinner)
        panel.addView(space(10))
        addLabel("نسخهٔ IP")
        val ipSpinner = spinner(
            listOf("دوگانه · IPv4 + IPv6", "فقط IPv4", "فقط IPv6"),
            when (prefs.getString("ip_version", "both")) { "v4" -> 1; "v6" -> 2; else -> 0 }
        )
        panel.addView(ipSpinner)
        panel.addView(space(10))
        addLabel("نام سرور TLS (SNI)")
        val sniInput = EditText(this).apply {
            setSingleLine(true)
            setText(prefs.getString("sni", "www.cloudflare.com") ?: "www.cloudflare.com")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(111, 123, 149))
            hint = "www.cloudflare.com"
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(23, 29, 48), 12)
        }
        panel.addView(sniInput)
        panel.addView(space(8))
        panel.addView(text(
            "تنظیمات پس از ذخیره‌شدن روی اتصال بعدی اعمال می‌شوند. موفقیت اتصال به شبکه و درگاه قابل‌دسترسی هم بستگی دارد.",
            12, Color.rgb(159, 171, 197)
        ))
        AlertDialog.Builder(this)
            .setTitle("تنظیمات پیشرفتهٔ MASQUE")
            .setView(panel)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val ip = when (ipSpinner.selectedItemPosition) { 1 -> "v4"; 2 -> "v6"; else -> "both" }
                prefs.edit()
                    .putString("transport", if (transportSpinner.selectedItemPosition == 0) "h2" else "default")
                    .putString("obfuscation", if (noiseSpinner.selectedItemPosition == 0) "firewall" else "balanced")
                    .putString("ip_version", ip)
                    .putString("sni", sniInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "www.cloudflare.com")
                    .apply()
                Toast.makeText(this, "تنظیمات MASQUE ذخیره شد؛ برای اعمال، دوباره وصل شو.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun showProtocolDetail(protocol: Protocol) {
        val engine = EngineRegistry.forProtocol(protocol)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        panel.addView(text(protocol.displayName, 22, Color.WHITE, true))
        panel.addView(space(6))
        panel.addView(text(protocol.note, 13, Color.rgb(172, 182, 205)))
        panel.addView(space(14))
        panel.addView(text("موتور", 12, Color.rgb(172, 182, 205), true))
        panel.addView(text(engine.name, 15, Color.WHITE, true))
        panel.addView(space(5))
        panel.addView(text(engine.detail, 12, Color.rgb(172, 182, 205)))
        panel.addView(space(16))

        when (protocol) {
            Protocol.MASQUE -> {
                panel.addView(button("اتصال خودکار") { requestAutoConnect() }.apply {
                    background = rounded(Color.rgb(132, 103, 255), 14)
                })
                panel.addView(space(7))
                panel.addView(button("تنظیمات پیشرفتهٔ MASQUE") { showMasqueAdvancedSettings() }.apply {
                    background = rounded(Color.rgb(37, 48, 70), 14)
                })
                panel.addView(space(7))
                panel.addView(button("قطع اتصال") { disconnectVpn() })
            }
            Protocol.PSIPHON -> {
                panel.addView(text("اتصال مستقیم با موتور Psiphon بسته‌بندی‌شده؛ بدون فایل JSON یا کانفیگ دستی. نتیجه فقط پس از گزارش وضعیت تونل مشخص می‌شود.", 12, Color.rgb(215, 208, 245)))
                panel.addView(space(9))
                panel.addView(button("اتصال مستقیم Psiphon") { requestPsiphonAutoConnect() }.apply {
                    background = rounded(Color.rgb(132, 103, 255), 14)
                })
            }
            else -> {
                val matching = profiles.filter { it.protocol == protocol }
                if (matching.isEmpty()) {
                    panel.addView(text("برای این پروتکل پروفایل سرور اضافه نشده.", 13, Color.rgb(255, 198, 111)))
                    panel.addView(space(8))
                    panel.addView(button("بستن و افزودن پروفایل") {
                        profileInput.requestFocus()
                        Toast.makeText(this, "پروفایل را در بخش افزودن پروفایل وارد کن.", Toast.LENGTH_LONG).show()
                    })
                } else {
                    matching.forEach { profile ->
                        panel.addView(text(profile.label, 14, Color.WHITE, true))
                        panel.addView(space(5))
                        panel.addView(button("اتصال با این پروفایل") { requestConnect(profile) })
                        panel.addView(space(7))
                    }
                }
            }
        }

        AlertDialog.Builder(this)
            .setView(panel)
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun importProfiles() {
        val result=ProtocolParser.parse(profileInput.text?.toString().orEmpty()); var added=0
        result.profiles.forEach { if (profiles.none { p -> p.rawConfig==it.rawConfig }) { profiles+=it; added++ } }
        renderProfiles()
        Toast.makeText(this,"شناسایی شد: ${result.profiles.size} · جدید: $added · ناشناخته: ${result.rejectedLines.size}",Toast.LENGTH_LONG).show()
    }
    private fun renderProfiles() {
        profileList.removeAllViews()
        if (profiles.isEmpty()) { profileList.addView(text("هنوز کانفیگی اضافه نشده.",13,Color.rgb(155,174,200))); return }
        profiles.forEach { profile ->
            val engine=EngineRegistry.forProtocol(profile.protocol)
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(10),dp(12),dp(10)); setBackgroundColor(Color.rgb(24,33,49)) }
            card.addView(text(profile.label,16,Color.WHITE,true))
            card.addView(text("${profile.protocol.displayName} · شناسایی موفق",12,Color.rgb(129,212,170)))
            card.addView(text("موتور لازم: ${engine.name}",12,Color.rgb(155,174,200)))
            card.addView(text("${engine.detail}\nکانفیگ فقط در حافظهٔ همین صفحه نگه داشته می‌شود.",12,Color.rgb(255,213,128)))
            card.addView(button("آماده‌سازی اتصال ${profile.protocol.displayName}") { requestConnect(profile) })
            val params=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); params.bottomMargin=dp(8); profileList.addView(card,params)
        }
    }
    private fun button(title:String, action:()->Unit):Button=Button(this).apply { text=title; isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(53,112,196)); setOnClickListener { action() } }
    private fun text(value:String,size:Int,color:Int,bold:Boolean=false):TextView=TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD); setPadding(0,dp(3),0,dp(3)) }
    private fun space(heightDp:Int):View=View(this).apply { layoutParams=LinearLayout.LayoutParams(1,dp(heightDp)) }
    private fun dp(value:Int):Int=(value*resources.displayMetrics.density).toInt()
    companion object { private const val REQUEST_VPN_PERMISSION=7102; private const val REQUEST_IMPORT_PSIPHON=7103 }
}
