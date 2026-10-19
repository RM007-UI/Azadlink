package com.azadlink.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
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
        TunnelBus.addStatusListener(tunnelStatusListener)
        refreshState()
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
        topStatus.text = "وضعیت: درخواست قطع اتصال ارسال شد"
    }
    private fun buildUi() {
        val bg = Color.rgb(10, 13, 24)
        val panel = Color.rgb(20, 25, 42)
        val muted = Color.rgb(154, 166, 192)
        val accent = Color.rgb(126, 102, 255)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        scroll.addView(content)
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandMark = TextView(this).apply {
            text = "A"
            textSize = 23f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(accent)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        brandText.addView(text("AzadLink", 24, Color.WHITE, true))
        brandText.addView(text("PRIVATE CONNECTION", 10, muted, true))
        brandRow.addView(brandMark)
        brandRow.addView(brandText)
        content.addView(brandRow)
        content.addView(space(26))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            setBackgroundColor(panel)
        }
        hero.addView(text("اتصال امن، ساده و خودکار", 21, Color.WHITE, true))
        hero.addView(space(6))
        hero.addView(text("وضعیت واقعی اتصال روی همین گوشی نمایش داده می‌شود.", 13, muted))
        hero.addView(space(20))
        topStatus = text("●  آمادهٔ اتصال", 14, Color.rgb(255, 207, 112), true)
        topStatus.setPadding(dp(13), dp(13), dp(13), dp(13))
        topStatus.setBackgroundColor(Color.rgb(44, 36, 28))
        hero.addView(topStatus)
        hero.addView(space(14))
        val autoButton = button("اتصال خودکار · MASQUE") {
            requestAutoConnect()
        }.apply { setBackgroundColor(accent); textSize = 16f }
        hero.addView(autoButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        hero.addView(space(8))
        hero.addView(button("قطع اتصال") { disconnectVpn() })
        content.addView(hero)
        content.addView(space(22))

        content.addView(text("پروتکل‌ها", 19, Color.WHITE, true))
        content.addView(space(4))
        content.addView(text("نمایش پروتکل به معنی آماده‌بودن موتور آن نیست.", 12, muted))
        content.addView(space(10))
        val masqueCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            setBackgroundColor(Color.rgb(31, 30, 58))
        }
        masqueCard.addView(text("MASQUE / Aether", 16, Color.WHITE, true))
        masqueCard.addView(space(3))
        masqueCard.addView(text("هدف نسخهٔ بعدی: کشف خودکار endpoint، انتخاب مسیر سالم و اتصال بدون واردکردن کانفیگ.", 12, muted))
        content.addView(masqueCard)
        content.addView(space(8))
        Protocol.entries.filter { it != Protocol.MASQUE }.forEach { protocol ->
            val engine = EngineRegistry.forProtocol(protocol)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(panel)
            }
            card.addView(text(protocol.displayName, 15, Color.WHITE, true))
            card.addView(text(engine.detail, 12, muted))
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(7)
            content.addView(card, params)
        }

        content.addView(space(18))
        content.addView(text("افزودن پروفایل", 19, Color.WHITE, true))
        content.addView(space(4))
        content.addView(text("برای پروتکل‌هایی که به کانفیگ نیاز دارند؛ اطلاعات خصوصی را با کسی به اشتراک نگذار.", 12, muted))
        profileInput = EditText(this).apply {
            hint = "vless://…  یا  wireguard://…"
            setHintTextColor(Color.rgb(111, 123, 149))
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 3
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setBackgroundColor(panel)
        }
        content.addView(space(10))
        content.addView(profileInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(space(10))
        content.addView(button("تشخیص و افزودن پروفایل") { importProfiles() })
        content.addView(space(18))
        content.addView(text("پروفایل‌های شما", 19, Color.WHITE, true))
        content.addView(space(9))
        profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(profileList)
        content.addView(space(14))
        content.addView(text("نسخهٔ فعلی رابط کاربری بازطراحی شده است؛ موتور Aether و مسیردهی TUN هنوز باید جداگانه یکپارچه و روی دستگاه آزمایش شوند.", 12, Color.rgb(255, 196, 116)))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        renderProfiles()
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
