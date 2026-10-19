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

class MainActivity : Activity() {
    private val profiles = mutableListOf<ProtocolProfile>()
    private lateinit var profileInput: EditText
    private lateinit var profileList: LinearLayout
    private lateinit var topStatus: TextView
    private var pendingConnectProfile: ProtocolProfile? = null
    private var receiverRegistered = false
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
    }
    override fun onStop() {
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
                        throw IllegalArgumentException("تنظیمات JSON رسمی Psiphon را با دکمهٔ بالای کادر وارد کن")
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
    private fun disconnectVpn() {
        startService(Intent(this, AzadLinkVpnService::class.java).setAction(AzadLinkVpnService.ACTION_DISCONNECT))
        topStatus.text = "وضعیت: در حال قطع اتصال"
    }
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12,17,28)); setPadding(dp(18),dp(18),dp(18),dp(18)) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        content.addView(text("AzadLink",30,Color.WHITE,true))
        content.addView(text("Multi-core VPN · نسخهٔ آزمایشی 0.3",14,Color.rgb(155,174,200)))
        content.addView(space(14))
        topStatus = text("وضعیت: آمادهٔ واردکردن کانفیگ",14,Color.rgb(255,213,128))
        topStatus.setPadding(dp(12),dp(12),dp(12),dp(12)); topStatus.setBackgroundColor(Color.rgb(44,38,26)); content.addView(topStatus)
        content.addView(space(6)); content.addView(button("قطع اتصال") { disconnectVpn() }); content.addView(space(16))
        content.addView(text("موتورهای موجود در همین نسخه",20,Color.WHITE,true))
        Protocol.entries.forEach { protocol ->
            val engine = EngineRegistry.forProtocol(protocol)
            val label = TextView(this).apply {
                text = "${protocol.displayName}  •  ${engine.name}\n${engine.detail}"
                setTextColor(Color.rgb(213,224,239)); textSize = 13f
                setPadding(dp(12),dp(10),dp(12),dp(10)); setBackgroundColor(Color.rgb(24,33,49))
            }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); params.bottomMargin=dp(7); content.addView(label,params)
        }
        content.addView(space(10)); content.addView(text("واردکردن کانفیگ",20,Color.WHITE,true))
        content.addView(text("هر خط یک پروفایل؛ اطلاعات محرمانهٔ کانفیگ را در چت یا لاگ منتشر نکن.",13,Color.rgb(155,174,200)))
        content.addView(space(6)); content.addView(button("واردکردن تنظیمات JSON رسمی Psiphon") { importPsiphonConfig() })
        content.addView(text("MasterDNS/StormDNS: لینک JSON-base64 با configToml و resolvers؛ DNSTT: dnstt://دامنه?pubkey=کلید۶۴رقمی&udp=IP:53&listenPort=18082",12,Color.rgb(155,174,200)))
        profileInput = EditText(this).apply {
            hint="vless://… یا masque://… یا psiphon://… یا [Interface]…"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            textSize=14f; minLines=3; maxLines=7; gravity=Gravity.TOP or Gravity.START
            setPadding(dp(12),dp(12),dp(12),dp(12)); setBackgroundColor(Color.rgb(24,33,49))
        }
        content.addView(space(8)); content.addView(profileInput,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(space(8)); content.addView(button("تشخیص و افزودن کانفیگ") { importProfiles() }); content.addView(space(14))
        content.addView(text("کانفیگ‌های شناسایی‌شده",20,Color.WHITE,true))
        profileList = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; content.addView(profileList); content.addView(space(20))
        content.addView(text("پیش‌نمایش اولیهٔ رابط کاربری است؛ موتورهای اتصال هنوز یکپارچه و روی گوشی آزمایش نشده‌اند. فعلاً اتصال واقعی برقرار نمی‌شود.",13,Color.rgb(255,213,128)))
        root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f)); setContentView(root)
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
