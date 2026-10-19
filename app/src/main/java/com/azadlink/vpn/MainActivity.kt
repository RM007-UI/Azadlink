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
        val bg = Color.rgb(10, 13, 25)
        val panel = Color.rgb(23, 29, 48)
        val muted = Color.rgb(159, 171, 197)
        val accent = Color.rgb(132, 103, 255)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(content)

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandMark = TextView(this).apply {
            text = "A"
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(accent, 16)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(text("AzadLink", 24, Color.WHITE, true))
        brandText.addView(text("اتصال آزاد، انتخاب خودت", 12, muted))
        brandRow.addView(brandMark)
        brandRow.addView(brandText)
        content.addView(brandRow)
        content.addView(space(22))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            background = rounded(Color.rgb(43, 36, 77), 24)
        }
        hero.addView(text("به اینترنتت برگرد", 24, Color.WHITE, true))
        hero.addView(space(5))
        hero.addView(text("موتور موردنظرت را انتخاب کن؛ وضعیت اتصال باید از خود موتور تأیید شود.", 13, Color.rgb(213, 207, 240)))
        hero.addView(space(16))
        topStatus = text("●  آمادهٔ اتصال", 14, Color.rgb(255, 205, 115), true)
        topStatus.setPadding(dp(12), dp(11), dp(12), dp(11))
        topStatus.background = rounded(Color.rgb(54, 45, 62), 13)
        hero.addView(topStatus)
        hero.addView(space(12))
        val autoButton = button("اتصال خودکار · MASQUE") { requestAutoConnect() }.apply {
            background = rounded(accent, 15)
            textSize = 15f
            minHeight = dp(52)
        }
        hero.addView(autoButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        hero.addView(space(8))
        hero.addView(button("قطع اتصال") { disconnectVpn() }.apply {
            background = rounded(Color.rgb(58, 43, 55), 14)
            minHeight = dp(46)
        })
        hero.addView(space(7))
        hero.addView(button("گزارش اتصال و خطاها") { showTunnelLogs() }.apply {
            background = rounded(Color.rgb(37, 43, 65), 14)
            minHeight = dp(44)
        })
        content.addView(hero)
        content.addView(space(22))

        content.addView(text("پروتکل‌ها", 20, Color.WHITE, true))
        content.addView(space(3))
        content.addView(text("هر کارت بخش مستقل تنظیمات و وضعیت خودش را دارد.", 12, muted))
        content.addView(space(11))

        val groups = listOf(
            "اتصال خودکار" to listOf(Protocol.MASQUE, Protocol.PSIPHON),
            "پروتکل‌های سروری" to listOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS, Protocol.WIREGUARD),
            "تونل‌های DNS" to listOf(Protocol.MASTER_DNS, Protocol.STORM_DNS, Protocol.DNSTT)
        )
        groups.forEach { (heading, items) ->
            content.addView(text(heading, 15, Color.rgb(207, 199, 255), true))
            content.addView(space(7))
            items.forEach { protocol ->
                val engine = EngineRegistry.forProtocol(protocol)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                    background = rounded(panel, 17)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showProtocolDetail(protocol) }
                }
                val icon = TextView(this).apply {
                    text = when (protocol) {
                        Protocol.MASQUE -> "◈"
                        Protocol.PSIPHON -> "✦"
                        Protocol.VLESS -> "V"
                        Protocol.VMESS -> "M"
                        Protocol.TROJAN -> "T"
                        Protocol.SHADOWSOCKS -> "S"
                        Protocol.WIREGUARD -> "W"
                        Protocol.MASTER_DNS -> "D"
                        Protocol.STORM_DNS -> "ϟ"
                        Protocol.DNSTT -> "⌁"
                    }
                    textSize = 19f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(if (protocol == Protocol.MASQUE || protocol == Protocol.PSIPHON) Color.rgb(51, 42, 88) else Color.rgb(37, 44, 66), 13)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                }
                val labels = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(11), 0, dp(4), 0)
                }
                labels.addView(text(protocol.displayName, 15, Color.WHITE, true))
                labels.addView(text(engine.name, 11, muted))
                card.addView(icon)
                card.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(text("›", 24, muted, true))
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.bottomMargin = dp(7)
                content.addView(card, params)
            }
            content.addView(space(8))
        }

        content.addView(space(4))
        content.addView(text("افزودن پروفایل", 19, Color.WHITE, true))
        content.addView(space(4))
        content.addView(text("برای VLESS، VMess، Trojan، Shadowsocks و WireGuard", 12, muted))
        profileInput = EditText(this).apply {
            hint = "vless://… یا [Interface]…"
            setHintTextColor(Color.rgb(111, 123, 149))
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 3
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(panel, 16)
        }
        content.addView(space(9))
        content.addView(profileInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(space(9))
        content.addView(button("تشخیص و افزودن پروفایل") { importProfiles() }.apply {
            background = rounded(accent, 15)
            minHeight = dp(48)
        })
        content.addView(space(17))
        content.addView(text("پروفایل‌های این نشست", 19, Color.WHITE, true))
        content.addView(space(8))
        profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(profileList)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
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
