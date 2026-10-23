package com.azadlink.vpn

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
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
import com.azadlink.vpn.service.ExternalDnsVpnService
import com.azadlink.vpn.core.EngineRegistry
import com.azadlink.vpn.core.Protocol
import com.azadlink.vpn.core.ProtocolParser
import com.azadlink.vpn.core.ProtocolProfile
import com.azadlink.vpn.runtime.CoreConfigBuilder
import com.azadlink.vpn.runtime.AetherAutoConnector
import org.bepass.oblivion.vpn.TunnelBus
import org.bepass.oblivion.vpn.TunnelSnapshot
import org.bepass.oblivion.vpn.TunnelStage
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val profiles = mutableListOf<ProtocolProfile>()
    private var activeProfileFilter: Protocol? = null
    private lateinit var profileInput: EditText
    private lateinit var profileList: LinearLayout
    private lateinit var topStatus: TextView
    private var pendingConnectProfile: ProtocolProfile? = null
    private var pendingAutoConnect = false
    private var pendingPsiphonAuto = false
    private var pendingPsiphonChain = false
    private var receiverRegistered = false
    private var selectedProtocolLabel: TextView? = null
    private var selectedProtocolHint: TextView? = null
    private var connectionTimerText: TextView? = null
    private enum class ConnectionUiState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, FAILED }
    private var connectionUiState = ConnectionUiState.DISCONNECTED
    private var connectionOrb: TextView? = null
    private var connectionPulse: ObjectAnimator? = null
    private val connectionFeedbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var connectionFeedbackRunnable: Runnable? = null
    private val connectionTimerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectionTimerTicker = object : Runnable {
        override fun run() {
            renderConnectionTimer()
            connectionTimerHandler.postDelayed(this, 1000L)
        }
    }
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
                topStatus.text = localizeUi("وضعیت واقعی: $state$gateway$detail")
                val showDisconnected = snapshot.stage == TunnelStage.DISCONNECTED &&
                    connectionUiState in setOf(ConnectionUiState.CONNECTING, ConnectionUiState.CONNECTED, ConnectionUiState.DISCONNECTING)
                setConnectionUiState(
                    when (snapshot.stage) {
                        TunnelStage.DISCONNECTED -> ConnectionUiState.DISCONNECTED
                        TunnelStage.CONNECTING, TunnelStage.VALIDATING -> ConnectionUiState.CONNECTING
                        TunnelStage.CONNECTED -> ConnectionUiState.CONNECTED
                        TunnelStage.DISCONNECTING -> ConnectionUiState.DISCONNECTING
                        TunnelStage.FAILED -> ConnectionUiState.FAILED
                    },
                    showDisconnectedFeedback = showDisconnected
                )
            }
        }
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(AzadLinkVpnService.EXTRA_MESSAGE) ?: return
            if (::topStatus.isInitialized) topStatus.text = localizeUi("وضعیت: $message")
            applyConnectionStateFromMessage(message, liveEvent = true)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 17, 28)
        window.navigationBarColor = Color.rgb(12, 17, 28)
        window.decorView.systemUiVisibility = 0
        loadProfiles()
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
        connectionTimerHandler.removeCallbacks(connectionTimerTicker)
        connectionTimerHandler.post(connectionTimerTicker)
    }
    override fun onStop() {
        connectionTimerHandler.removeCallbacks(connectionTimerTicker)
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
        if (pendingPsiphonChain) {
            pendingPsiphonChain = false
            if (resultCode == RESULT_OK) startPsiphonChainConnect()
            else { setConnectionUiState(ConnectionUiState.DISCONNECTED); Toast.makeText(this, "اجازهٔ VPN داده نشد", Toast.LENGTH_LONG).show() }
            return
        }
        if (pendingPsiphonAuto) {
            pendingPsiphonAuto = false
            if (resultCode == RESULT_OK) startPsiphonAutoConnect()
            else { setConnectionUiState(ConnectionUiState.DISCONNECTED); Toast.makeText(this, "اجازهٔ VPN داده نشد", Toast.LENGTH_LONG).show() }
            return
        }
        if (pendingAutoConnect) {
            pendingAutoConnect = false
            if (resultCode == RESULT_OK) startAutoConnect()
            else { setConnectionUiState(ConnectionUiState.DISCONNECTED); Toast.makeText(this, "اجازهٔ VPN داده نشد", Toast.LENGTH_LONG).show() }
            return
        }
        val profile = pendingConnectProfile
        pendingConnectProfile = null
        if (resultCode == RESULT_OK && profile != null) startVpn(profile)
        else { setConnectionUiState(ConnectionUiState.DISCONNECTED); Toast.makeText(this, "اجازهٔ VPN داده نشد", Toast.LENGTH_LONG).show() }
    }

    private var connectionTimerStartedAt: Long? = null

    private fun setConnectionTimerActive(active: Boolean) {
        if (active) {
            if (connectionTimerStartedAt == null) connectionTimerStartedAt = System.currentTimeMillis()
        } else {
            connectionTimerStartedAt = null
        }
        renderConnectionTimer()
    }

    private fun renderConnectionTimer() {
        val view = connectionTimerText ?: return
        val started = connectionTimerStartedAt
        if (started == null) {
            view.text = localizeUi("مدت اتصال: —")
            return
        }
        val totalSeconds = ((System.currentTimeMillis() - started).coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val elapsed = if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
            else "%02d:%02d".format(minutes, seconds)
        view.text = localizeUi("مدت اتصال: $elapsed")
    }

private fun buildUi() {
        val prefs = getSharedPreferences("azadlink_ui", MODE_PRIVATE)
        val light = prefs.getString("theme", "dark") == "light"
        val english = prefs.getString("language", "fa") == "en"
        fun tr(fa: String, en: String) = if (english) en else fa
        val bg = if (light) Color.rgb(246, 248, 253) else Color.rgb(7, 10, 19)
        val surface = if (light) Color.WHITE else Color.rgb(17, 23, 38)
        val raised = if (light) Color.rgb(239, 242, 250) else Color.rgb(23, 31, 49)
        val fg = if (light) Color.rgb(26, 33, 51) else Color.WHITE
        val muted = if (light) Color.rgb(93, 105, 128) else Color.rgb(152, 166, 192)
        val accent = Color.rgb(129, 100, 255); val cyan = Color.rgb(73, 199, 220)
        val stroke = if (light) Color.rgb(220, 225, 237) else Color.rgb(44, 55, 78)
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        fun panel(color: Int, radius: Int = 18): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(8), dp(17), dp(8)); setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(12)) }
        header.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_azadlink); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)) })
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        brand.addView(text("AzadLink", 21, fg, true)); brand.addView(text(tr("اتصال ساده، کنترل کامل", "Simple connection, full control"), 11, muted))
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply { text = "⚙"; textSize = 23f; gravity = Gravity.CENTER; setTextColor(fg); background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(stroke), panel(raised, 15), null); isClickable = true; isFocusable = true; contentDescription = tr("تنظیمات", "Settings"); layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)); setOnClickListener { showAppSettings() } })
        root.addView(header)
        val status = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(11), dp(13), dp(11)); background = panel(surface, 17) }
        status.addView(TextView(this).apply { text = "●"; textSize = 11f; setTextColor(cyan); setPadding(0, 0, dp(8), 0) })
        topStatus = TextView(this).apply { text = tr("آمادهٔ اتصال", "Ready to connect"); textSize = 12f; setTextColor(fg); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; includeFontPadding = false }
        status.addView(topStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        status.addView(TextView(this).apply { text = tr("گزارش", "Logs"); textSize = 12f; setTextColor(accent); setPadding(dp(8), dp(5), 0, dp(5)); isClickable = true; isFocusable = true; setOnClickListener { showTunnelLogs() } })
        val statusParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); statusParams.bottomMargin = dp(10); root.addView(status, statusParams)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(8), dp(2), dp(22)) }
        scroll.addView(body)
        body.addView(text(tr("همه‌چیز آماده است؟", "Ready when you are"), 23, fg, true).apply { gravity = Gravity.CENTER })
        body.addView(text(tr("پروتکل را انتخاب کن و برای شروع روی دکمه بزن.", "Choose a protocol, then tap connect."), 12, muted).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(5), dp(10), dp(8)) })
        val protocolCard = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(10), dp(13), dp(10)); background = panel(surface, 19); isClickable = true; isFocusable = true; setOnClickListener { showProtocolDrawer() } }
        protocolCard.addView(TextView(this).apply { text = "✧"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, cyan)).apply { cornerRadius = dp(14).toFloat() }; layoutParams = LinearLayout.LayoutParams(dp(43), dp(43)) })
        val protoCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, dp(8), 0) }
        protoCopy.addView(text(tr("پروتکل انتخاب‌شده", "Selected protocol"), 11, muted))
        selectedProtocolLabel = text(selectedProtocolName(), 15, fg, true); protoCopy.addView(selectedProtocolLabel)
        selectedProtocolHint = text(tr("برای تغییر، اینجا بزن", "Tap here to change"), 10, accent); protoCopy.addView(selectedProtocolHint)
        protocolCard.addView(protoCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); protocolCard.addView(text("⌄", 24, muted, true))
        val protoParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); protoParams.topMargin = dp(10); protoParams.bottomMargin = dp(7); body.addView(protocolCard, protoParams)
        val orbWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(7), 0, 0) }
        val orbFrame = android.widget.FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(232), dp(232)) }
        orbFrame.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(1), if (light) Color.rgb(213, 211, 255) else Color.rgb(47, 58, 87)) } }, android.widget.FrameLayout.LayoutParams(dp(222), dp(222), Gravity.CENTER))
        val orb = TextView(this).apply { gravity = Gravity.CENTER; includeFontPadding = false; textAlignment = View.TEXT_ALIGNMENT_CENTER; elevation = dp(12).toFloat(); isClickable = true; isFocusable = true; layoutParams = android.widget.FrameLayout.LayoutParams(dp(202), dp(202), Gravity.CENTER); contentDescription = tr("اتصال با پروتکل انتخاب‌شده", "Connect with selected protocol"); setOnClickListener { animate().scaleX(0.96f).scaleY(0.96f).setDuration(85).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(130).start() }.start(); onConnectionOrbTapped() } }
        connectionOrb = orb; orbFrame.addView(orb); orbWrap.addView(orbFrame); body.addView(orbWrap)
        body.addView(text(tr("برای شروع اتصال ضربه بزن", "Tap to start connecting"), 12, muted).apply { gravity = Gravity.CENTER })
        connectionTimerText = text(tr("مدت اتصال: —", "Connected time: —"), 12, cyan, true).apply { gravity = Gravity.CENTER }; body.addView(connectionTimerText)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(15), 0, 0) }
        fun actionRow(symbol: String, title: String, subtitle: String, action: () -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = panel(surface, 17); isClickable = true; isFocusable = true; setOnClickListener { action() } }
            row.addView(TextView(this).apply { text = symbol; textSize = 20f; gravity = Gravity.CENTER; setTextColor(cyan); background = panel(raised, 13); layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)) })
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }; copy.addView(text(title, 14, fg, true)); copy.addView(text(subtitle, 11, muted))
            row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); row.addView(text("›", 25, muted))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(8); actions.addView(row, lp)
        }
        actionRow("⌘", tr("سرورها و کانفیگ‌ها", "Servers & configurations"), tr("افزودن، مدیریت یا حذف پروفایل‌ها", "Add, manage or remove profiles")) { showServerProfilesDialog() }
        actionRow("⚙", tr("تنظیمات پروتکل", "Protocol settings"), tr("تنظیمات مخصوص اتصال انتخاب‌شده", "Settings for the selected connection")) { when (selectedProtocol()) { Protocol.MASQUE -> showMasqueAdvancedSettings(); Protocol.PSIPHON -> showPsiphonSettings(); else -> showServerProfilesDialog(selectedProtocol()) } }
        actionRow("☷", tr("تنظیمات و ظاهر", "Settings & appearance"), tr("زبان، تم و دربارهٔ سازنده", "Language, theme and author")) { showAppSettings() }
        body.addView(actions)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        renderConnectionOrb(connectionUiState)
        renderProfiles()
    }
private fun refreshState() {
        val prefs = getSharedPreferences(AzadLinkVpnService.PREFS, MODE_PRIVATE)
        val english = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("language", "fa") == "en"
        val saved = prefs.getString(AzadLinkVpnService.KEY_MESSAGE, if (english) "Ready to connect" else "آمادهٔ اتصال") ?: if (english) "Ready to connect" else "آمادهٔ اتصال"
        topStatus.text = if (english && saved == "آمادهٔ اتصال") "Ready to connect" else (if (english) saved.replace("وضعیت:", "Status:").replace("در حال اتصال", "Connecting").replace("متصل", "Connected").replace("قطع اتصال", "Disconnecting").replace("اتصال قطع شد", "Disconnected") else "وضعیت: " + saved)
        applyConnectionStateFromMessage(saved, liveEvent = false)
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
        validation.exceptionOrNull()?.let {
            Toast.makeText(this, "کانفیگ آمادهٔ اجرا نیست: " + (it.message ?: "فرمت پشتیبانی‌نشده"), Toast.LENGTH_LONG).show()
            return
        }
        setConnectionUiState(ConnectionUiState.CONNECTING)
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
        setConnectionUiState(ConnectionUiState.CONNECTING)
        val dnsProtocol = profile.protocol in setOf(Protocol.DNSTT, Protocol.MASTER_DNS, Protocol.STORM_DNS)
        stopOtherTunnelEngines(if (dnsProtocol) "dns" else "xray")
        val serviceIntent = if (dnsProtocol) {
            Intent(this, ExternalDnsVpnService::class.java).apply {
                action = ExternalDnsVpnService.ACTION_CONNECT
                putExtra(ExternalDnsVpnService.EXTRA_PROFILE, profile.rawConfig)
                putExtra(ExternalDnsVpnService.EXTRA_PROTOCOL, profile.protocol.name)
                putExtra(ExternalDnsVpnService.EXTRA_LABEL, profile.label)
            }
        } else {
            Intent(this, AzadLinkVpnService::class.java).apply {
                action = AzadLinkVpnService.ACTION_CONNECT
                putExtra(AzadLinkVpnService.EXTRA_PROFILE, profile.rawConfig)
                putExtra(AzadLinkVpnService.EXTRA_PROTOCOL, profile.protocol.name)
                putExtra(AzadLinkVpnService.EXTRA_LABEL, profile.label)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        topStatus.text = "وضعیت: درخواست اتصال ارسال شد"
    }

    private fun requestPsiphonAutoConnect() {
        setConnectionUiState(ConnectionUiState.CONNECTING)
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingPsiphonAuto = true
            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
        } else startPsiphonAutoConnect()
    }

    private fun startPsiphonAutoConnect() {
        stopOtherTunnelEngines("aether")
        setConnectionUiState(ConnectionUiState.CONNECTING)
        topStatus.text = "وضعیت: در حال راه‌اندازی Psiphon…"
        val country = getSharedPreferences("azadlink_psiphon_settings", MODE_PRIVATE)
            .getString("country", "") ?: ""
        PsiphonAutoConnector.connect(this, { message ->
            if (::topStatus.isInitialized) topStatus.text = "وضعیت: " + message
            applyConnectionStateFromMessage(message, liveEvent = true)
            when {
                message.contains("متصل شد", true) -> setConnectionTimerActive(true)
                message.contains("ناموفق", true) || message.contains("آماده نشد", true) -> setConnectionTimerActive(false)
            }
            if (message.startsWith("خطای Psiphon:")) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }, country, false)
    }

    private fun requestPsiphonChainConnect() {
        setConnectionUiState(ConnectionUiState.CONNECTING)
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingPsiphonChain = true
            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
        } else startPsiphonChainConnect()
    }

    private fun startPsiphonChainConnect() {
        stopOtherTunnelEngines("aether")
        setConnectionUiState(ConnectionUiState.CONNECTING)
        topStatus.text = "وضعیت: در حال راه‌اندازی زنجیرهٔ MASQUE → Psiphon…"
        val country = getSharedPreferences("azadlink_psiphon_settings", MODE_PRIVATE)
            .getString("country", "") ?: ""
        PsiphonAutoConnector.connect(this, { message ->
            if (::topStatus.isInitialized) topStatus.text = "وضعیت: " + message
            applyConnectionStateFromMessage(message, liveEvent = true)
            when {
                message.contains("متصل شد", true) -> setConnectionTimerActive(true)
                message.contains("ناموفق", true) || message.contains("آماده نشد", true) -> setConnectionTimerActive(false)
            }
            if (message.startsWith("خطای Psiphon:")) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }, country, true)
    }

    private fun showPsiphonSettings() {
        val prefs = getSharedPreferences("azadlink_psiphon_settings", MODE_PRIVATE)
        val english = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("language", "fa") == "en"
        val light = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("theme", "dark") == "light"
        fun tr(fa: String, en: String) = if (english) en else fa
        val fg = if (light) Color.rgb(26, 33, 51) else Color.WHITE
        val muted = if (light) Color.rgb(85, 98, 121) else Color.rgb(159, 171, 197)
        val countries = listOf(
            tr("سریع‌ترین / خودکار", "Fastest / automatic") to "",
            tr("آمریکا", "United States") to "US",
            tr("کانادا", "Canada") to "CA",
            tr("بریتانیا", "United Kingdom") to "GB",
            tr("آلمان", "Germany") to "DE",
            tr("هلند", "Netherlands") to "NL",
            tr("فرانسه", "France") to "FR",
            tr("ژاپن", "Japan") to "JP",
            tr("سنگاپور", "Singapore") to "SG",
            tr("هند", "India") to "IN"
        )
        val saved = prefs.getString("country", "") ?: ""
        val selected = countries.indexOfFirst { it.second == saved }.coerceAtLeast(0)
        val picker = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                countries.map { it.first }
            )
            setSelection(selected)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
            addView(text(tr("موقعیت خروجی Psiphon", "Psiphon exit location"), 15, fg, true))
            addView(space(7))
            addView(picker)
            addView(space(8))
            addView(text(
                tr("سریع‌ترین / خودکار یعنی انتخاب کشور را به Psiphon می‌سپارد؛ کشور انتخابی تضمین‌کنندهٔ سرعت یا دسترس‌پذیری نیست.", "Fastest / automatic lets Psiphon choose a location. A selected country does not guarantee speed or availability."),
                12, muted
            ))
        }
        AlertDialog.Builder(this)
            .setTitle(tr("موقعیت Psiphon", "Psiphon location"))
            .setView(panel)
            .setNegativeButton(tr("لغو", "Cancel"), null)
            .setPositiveButton(tr("ذخیره", "Save")) { _, _ ->
                prefs.edit().putString("country", countries[picker.selectedItemPosition].second).apply()
                Toast.makeText(this, tr("موقعیت Psiphon ذخیره شد.", "Psiphon location saved."), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun requestAutoConnect() {
        setConnectionUiState(ConnectionUiState.CONNECTING)
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingAutoConnect = true
            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
        } else {
            startAutoConnect()
        }
    }

    private fun startAutoConnect() {
        stopOtherTunnelEngines("aether")
        setConnectionUiState(ConnectionUiState.CONNECTING)
        topStatus.text = "وضعیت: در حال آماده‌سازی موتور Aether…"
        AetherAutoConnector.connect(this) { message ->
            topStatus.text = "وضعیت: " + message
            applyConnectionStateFromMessage(message, liveEvent = true)
            if (message.startsWith("خطای اتصال:") || message.contains("برقرار نشد")) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopOtherTunnelEngines(keep: String) {
        if (keep != "aether") AetherAutoConnector.disconnect(this)
        if (keep != "xray") stopService(Intent(this, AzadLinkVpnService::class.java))
        if (keep != "dns") stopService(Intent(this, ExternalDnsVpnService::class.java))
    }

    private fun disconnectVpn() {
        setConnectionUiState(ConnectionUiState.DISCONNECTING)
        stopOtherTunnelEngines("none")
        setConnectionUiState(ConnectionUiState.DISCONNECTED)
        topStatus.text = "وضعیت: اتصال قطع شد"
    }

    private fun applyConnectionStateFromMessage(message: String, liveEvent: Boolean = false) {
        val value = message.trim().lowercase()
        when {
            value.contains("در حال قطع") || value.contains("disconnecting") -> {
                setConnectionUiState(ConnectionUiState.DISCONNECTING)
            }
            value.contains("خطای اتصال") || value.contains("خطای psiphon") ||
                value.contains("ناموفق") || value.contains("تأیید نشد") ||
                value.contains("برقرار نشد") || value.contains("آماده نشد") ||
                value.contains("timeout") || value.contains("failed") -> {
                setConnectionUiState(ConnectionUiState.FAILED)
            }
            value.contains("در حال اتصال") || value.contains("در حال راه‌اندازی") ||
                value.contains("در حال آماده‌سازی") || value.contains("در حال ساخت") ||
                value.contains("در حال ایجاد") || value.contains("در حال بررسی") ||
                value.contains("در حال آزمون") || value.contains("در حال شروع") ||
                value.contains("request sent") || value.contains("connecting") -> {
                setConnectionUiState(ConnectionUiState.CONNECTING)
            }
            value.contains("psiphon متصل شد") || value.contains("متصل ·") ||
                value.contains("تونل masque برقرار شد") ||
                value.contains("عبور ترافیک از تونل تأیید شد") ||
                value.contains("پراکسی محلی فعال شد") ||
                value.contains("فعال و آزموده شد") || value.contains("tunnel validated") ||
                (value.contains("connected") && !value.contains("disconnected")) -> {
                setConnectionUiState(ConnectionUiState.CONNECTED)
            }
            value.contains("اتصال قطع شد") || value == "قطع" ||
                value.contains("disconnected") || value.contains("ready to connect") ||
                value.contains("آمادهٔ اتصال") -> {
                setConnectionUiState(
                    ConnectionUiState.DISCONNECTED,
                    showDisconnectedFeedback = liveEvent &&
                        (value.contains("اتصال قطع شد") || value.contains("disconnected"))
                )
            }
        }
    }

    private fun setConnectionUiState(
        state: ConnectionUiState,
        showDisconnectedFeedback: Boolean = false
    ) {
        connectionUiState = state
        when (state) {
            ConnectionUiState.CONNECTED -> setConnectionTimerActive(true)
            ConnectionUiState.DISCONNECTED, ConnectionUiState.FAILED -> setConnectionTimerActive(false)
            else -> Unit
        }

        connectionFeedbackRunnable?.let { connectionFeedbackHandler.removeCallbacks(it) }
        connectionFeedbackRunnable = null
        connectionPulse?.cancel()
        connectionPulse = null
        val orb = connectionOrb
        if (orb != null) {
            orb.alpha = 1f
            renderConnectionOrb(state, disconnectedFeedback = state == ConnectionUiState.DISCONNECTED && showDisconnectedFeedback)
            if (state == ConnectionUiState.CONNECTING) {
                connectionPulse = ObjectAnimator.ofFloat(orb, View.ALPHA, 1f, 0.68f).apply {
                    duration = 650L
                    repeatMode = ObjectAnimator.REVERSE
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            }
        }

        if (state == ConnectionUiState.DISCONNECTED && showDisconnectedFeedback) {
            val reset = Runnable {
                if (connectionUiState == ConnectionUiState.DISCONNECTED) {
                    renderConnectionOrb(ConnectionUiState.DISCONNECTED)
                }
                connectionFeedbackRunnable = null
            }
            connectionFeedbackRunnable = reset
            connectionFeedbackHandler.postDelayed(reset, 1900L)
        }
    }

    private fun renderConnectionOrb(
        state: ConnectionUiState,
        disconnectedFeedback: Boolean = false
    ) {
        val orb = connectionOrb ?: return
        val english = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("language", "fa") == "en"
        val light = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("theme", "dark") == "light"
        val (glyph, label, colors, description) = when {
            disconnectedFeedback -> Quadruple(
                "✓",
                if (english) "Disconnected" else "قطع شد",
                intArrayOf(Color.rgb(25, 180, 137), Color.rgb(58, 215, 179)),
                if (english) "VPN disconnected" else "اتصال قطع شد"
            )
            state == ConnectionUiState.CONNECTING -> Quadruple(
                "◌",
                if (english) "Connecting" else "در حال اتصال",
                intArrayOf(Color.rgb(126, 88, 255), Color.rgb(48, 185, 206)),
                if (english) "Connection in progress" else "در حال اتصال"
            )
            state == ConnectionUiState.CONNECTED -> Quadruple(
                "⏻",
                if (english) "Disconnect" else "قطع اتصال",
                intArrayOf(Color.rgb(238, 71, 123), Color.rgb(139, 62, 224)),
                if (english) "Tap to disconnect" else "برای قطع اتصال ضربه بزن"
            )
            state == ConnectionUiState.DISCONNECTING -> Quadruple(
                "◌",
                if (english) "Disconnecting" else "در حال قطع",
                intArrayOf(Color.rgb(91, 104, 132), Color.rgb(69, 82, 109)),
                if (english) "Disconnecting" else "در حال قطع اتصال"
            )
            state == ConnectionUiState.FAILED -> Quadruple(
                "↻",
                if (english) "Try again" else "تلاش دوباره",
                intArrayOf(Color.rgb(195, 69, 88), Color.rgb(118, 54, 139)),
                if (english) "Connection failed; tap to retry" else "اتصال ناموفق؛ برای تلاش دوباره بزن"
            )
            else -> Quadruple(
                "⟡",
                if (english) "Connect" else "اتصال",
                intArrayOf(Color.rgb(126, 88, 255), Color.rgb(48, 185, 206)),
                if (english) "Connect with selected protocol" else "اتصال با پروتکل انتخاب‌شده"
            )
        }
        orb.text = "$glyph\n$label"
        orb.textSize = if (label.length > 9) 14f else 19f
        orb.contentDescription = description
        val border = if (light) Color.argb(100, 55, 64, 89) else Color.argb(105, 255, 255, 255)
        orb.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(3), border)
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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

    private fun selectedProtocol(): Protocol {
        val saved = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("selected_protocol", "MASQUE") ?: "MASQUE"
        return runCatching { Protocol.valueOf(saved) }.getOrDefault(Protocol.MASQUE)
    }

    private fun isChainSelected(): Boolean =
        getSharedPreferences("azadlink_ui", MODE_PRIVATE)
            .getString("selected_connection_mode", "single") == "chain"

    private fun selectedProtocolName(): String {
        if (isChainSelected()) return "MASQUE → Psiphon"
        return when (selectedProtocol()) {
            Protocol.MASQUE -> "Aether · MASQUE"
            Protocol.PSIPHON -> "Psiphon"
            Protocol.VLESS -> "VLESS"
            Protocol.VMESS -> "VMess"
            Protocol.TROJAN -> "Trojan"
            Protocol.SHADOWSOCKS -> "Shadowsocks"
            Protocol.WIREGUARD -> "WireGuard"
            Protocol.DNSTT -> "DNSTT"
            Protocol.MASTER_DNS -> "Master DNS"
            Protocol.STORM_DNS -> "Storm DNS"
        }
    }

    private fun onConnectionOrbTapped() {
        when (connectionUiState) {
            ConnectionUiState.CONNECTING, ConnectionUiState.CONNECTED -> disconnectVpn()
            ConnectionUiState.DISCONNECTING -> Unit
            ConnectionUiState.DISCONNECTED, ConnectionUiState.FAILED -> connectSelectedProtocol()
        }
    }

    private fun connectSelectedProtocol() {
        // Selection only changes the mode. The chain starts only after the user
        // taps the central connection orb.
        if (isChainSelected()) {
            requestPsiphonChainConnect()
            return
        }
        when (val protocol = selectedProtocol()) {
            Protocol.MASQUE -> requestAutoConnect()
            Protocol.PSIPHON -> requestPsiphonAutoConnect()
            else -> {
                val profile = profiles.firstOrNull { it.protocol == protocol }
                if (profile != null) {
                    requestConnect(profile)
                } else {
                    val english = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("language", "fa") == "en"
                    val message = if (english) "Choose or add a server profile for ${protocol.displayName} before connecting." else
                        "برای اتصال با ${protocol.displayName} ابتدا کانفیگ آن را اضافه کن."
                    topStatus.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    showServerProfilesDialog(protocol)
                }
            }
        }
    }

    private fun showProtocolDrawer() {
        val prefs = getSharedPreferences("azadlink_ui", MODE_PRIVATE)
        val light = prefs.getString("theme", "dark") == "light"
        val english = prefs.getString("language", "fa") == "en"
        fun tr(fa: String, en: String) = if (english) en else fa
        val bg = if (light) Color.rgb(248, 250, 255) else Color.rgb(10, 14, 25)
        val surface = if (light) Color.WHITE else Color.rgb(19, 26, 42)
        val fg = if (light) Color.rgb(26, 33, 51) else Color.WHITE
        val muted = if (light) Color.rgb(89, 101, 123) else Color.rgb(157, 170, 194)
        val accent = Color.rgb(133, 109, 255)
        val dialog = android.app.Dialog(this)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)); setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(text(tr("پروتکل‌ها و تنظیمات", "Protocols & settings"), 20, fg, true))
        titleBox.addView(text(tr("کشوی AzadLink", "AzadLink navigation"), 11, muted))
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button(tr("بستن", "Close")) { dialog.dismiss() })
        outer.addView(header); outer.addView(space(12))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun heading(fa: String, en: String) { body.addView(text(tr(fa, en), 12, accent, true)); body.addView(space(5)) }
        fun item(fa: String, en: String, subFa: String, subEn: String, selected: Boolean = false, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (selected) (if (light) Color.rgb(237, 233, 255) else Color.rgb(40, 34, 72)) else surface)
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), if (selected) accent else if (light) Color.rgb(215, 221, 235) else Color.rgb(45, 55, 77))
                }
                isClickable = true; isFocusable = true; setOnClickListener { action() }
            }
            row.addView(text((if (selected) "✓  " else "") + tr(fa, en), 14, fg, true))
            row.addView(text(tr(subFa, subEn), 11, muted))
            body.addView(row); body.addView(space(5))
        }
        fun choose(protocol: Protocol, title: String) {
            prefs.edit()
                .putString("selected_protocol", protocol.name)
                .putString("selected_connection_mode", "single")
                .apply()
            selectedProtocolLabel?.text = title
            selectedProtocolHint?.text = tr(
                "انتخاب شد · برای اتصال روی دایره بزن",
                "Selected · tap the circle to connect"
            )
            dialog.dismiss()
            Toast.makeText(
                this@MainActivity,
                tr("$title انتخاب شد؛ برای اتصال روی دایرهٔ وسط بزن.", "$title selected; tap the center circle to connect."),
                Toast.LENGTH_SHORT
            ).show()
        }
        heading("سرور و پراکسی", "Server protocols")
        item("VLESS", "VLESS", "کانفیگ VLESS / Reality / TLS", "VLESS / Reality / TLS profiles", !isChainSelected() && selectedProtocol() == Protocol.VLESS) { choose(Protocol.VLESS, "VLESS") }
        item("VMess", "VMess", "کانفیگ VMess", "VMess profiles", !isChainSelected() && selectedProtocol() == Protocol.VMESS) { choose(Protocol.VMESS, "VMess") }
        item("Trojan", "Trojan", "اتصال مبتنی بر TLS", "TLS-based connection", !isChainSelected() && selectedProtocol() == Protocol.TROJAN) { choose(Protocol.TROJAN, "Trojan") }
        item("Shadowsocks", "Shadowsocks", "پروکسی رمزگذاری‌شده", "Encrypted proxy", !isChainSelected() && selectedProtocol() == Protocol.SHADOWSOCKS) { choose(Protocol.SHADOWSOCKS, "Shadowsocks") }
        item("WireGuard", "WireGuard", "تونل مبتنی بر کلید", "Key-based tunnel", !isChainSelected() && selectedProtocol() == Protocol.WIREGUARD) { choose(Protocol.WIREGUARD, "WireGuard") }
        item("اتصال زنجیره‌ای · MASQUE → Psiphon", "Chain connection · MASQUE → Psiphon", "اول انتخاب کن؛ سپس دکمهٔ وسط را بزن", "Select first, then tap the center button", isChainSelected()) {
            prefs.edit().putString("selected_connection_mode", "chain").apply()
            selectedProtocolLabel?.text = tr("اتصال زنجیره‌ای · MASQUE → Psiphon", "Chain · MASQUE → Psiphon")
            selectedProtocolHint?.text = tr("انتخاب شد · برای شروع روی دایره بزن", "Selected · tap the circle to start")
            dialog.dismiss()
            Toast.makeText(
                this@MainActivity,
                tr("اتصال زنجیره‌ای انتخاب شد؛ برای اتصال روی دایرهٔ وسط بزن.", "Chain selected. Tap the center circle to connect."),
                Toast.LENGTH_SHORT
            ).show()
        }
        heading("تونل‌های DNS", "DNS tunnels")
        item("DNSTT", "DNSTT", "تونل DNS", "DNS tunnel", !isChainSelected() && selectedProtocol() == Protocol.DNSTT) { choose(Protocol.DNSTT, "DNSTT") }
        item("Master DNS", "Master DNS", "موتور MasterDNS", "MasterDNS engine", !isChainSelected() && selectedProtocol() == Protocol.MASTER_DNS) { choose(Protocol.MASTER_DNS, "Master DNS") }
        item("Storm DNS", "Storm DNS", "موتور StormDNS", "StormDNS engine", !isChainSelected() && selectedProtocol() == Protocol.STORM_DNS) { choose(Protocol.STORM_DNS, "Storm DNS") }
        heading("Aether / MASQUE", "Aether / MASQUE")
        item("Aether · MASQUE", "Aether · MASQUE", "اتصال خودکار و تنظیمات پیشرفته", "Automatic connection and advanced options", !isChainSelected() && selectedProtocol() == Protocol.MASQUE) { choose(Protocol.MASQUE, "Aether · MASQUE") }
        heading("Psiphon", "Psiphon")
        item("Psiphon", "Psiphon", "اتصال مستقیم با موتور همراه برنامه", "Direct connection using the bundled engine", !isChainSelected() && selectedProtocol() == Protocol.PSIPHON) { choose(Protocol.PSIPHON, "Psiphon") }
        heading("مدیریت", "Manage")
        item("کانفیگ‌ها و سرورها", "Server configurations", "افزودن، اتصال و حذف پروفایل‌ها", "Add, connect and remove profiles") { dialog.dismiss(); showServerProfilesDialog() }
        item("حالت پروکسی", "Proxy mode", "تنظیم پراکسی محلی", "Configure local proxy mode") { dialog.dismiss(); showProxySettings() }
        item("انتخاب برنامه‌های تونل‌شونده", "App routing", "همه یا فقط برنامه‌های انتخابی", "All apps or selected apps only") { dialog.dismiss(); showRoutingSettings() }
        item("تنظیمات پیشرفتهٔ MASQUE", "Advanced MASQUE settings", "HTTP/2، اسکن، SNI و fallback", "HTTP/2, scan, SNI and fallback") { dialog.dismiss(); showMasqueAdvancedSettings() }
        item("تنظیمات و زبان", "Settings & language", "تم، زبان و نام نویسنده", "Theme, language and author") { dialog.dismiss(); showAppSettings() }
        item("گزارش اتصال", "Connection logs", "بررسی خطاهای موتور", "Inspect engine diagnostics") { dialog.dismiss(); showTunnelLogs() }
        scroll.addView(body)
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(outer)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.45f); addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.START or Gravity.TOP)
            setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.setOnShowListener {
            outer.translationX = (if (english) -dp(28) else dp(28)).toFloat(); outer.alpha = 0.92f
            outer.animate().translationX(0f).alpha(1f).setDuration(190).start()
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun showServerProfilesDialog(filter: Protocol? = null) {
        activeProfileFilter = filter
        val prefs = getSharedPreferences("azadlink_ui", MODE_PRIVATE)
        val english = prefs.getString("language", "fa") == "en"
        fun tr(fa: String, en: String) = if (english) en else fa
        val light = prefs.getString("theme", "dark") == "light"
        val fg = if (light) Color.rgb(26, 33, 51) else Color.WHITE
        val muted = if (light) Color.rgb(85, 98, 121) else Color.rgb(157, 170, 194)
        val fill = if (light) Color.rgb(246, 248, 253) else Color.rgb(16, 22, 36)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(12)) }
        val profileHelp = when (filter) {
            Protocol.MASTER_DNS -> tr("JSON کامل با protocol=masterdns، DOMAINS، ENCRYPTION_KEY و RESOLVERS وارد کن.",
                "Paste full JSON containing protocol=masterdns, DOMAINS, ENCRYPTION_KEY and RESOLVERS.")
            Protocol.STORM_DNS -> tr("JSON کامل با protocol=stormdns، DOMAINS، ENCRYPTION_KEY و RESOLVERS وارد کن.",
                "Paste full JSON containing protocol=stormdns, DOMAINS, ENCRYPTION_KEY and RESOLVERS.")
            Protocol.DNSTT -> tr("JSON شامل protocol=dnstt، domain، pubkey و resolver یا لینک dnstt:// را وارد کن. برای تونل کامل، مقصد سرور باید SOCKS5 بدون رمز باشد.",
                "Paste JSON with protocol=dnstt, domain, pubkey and resolver, or dnstt:// URI. Whole-device mode requires a no-auth SOCKS5 endpoint server-side.")
            else -> if (filter == null) tr("لینک کانفیگ را وارد کن؛ نوع پروتکل از روی فرمت تشخیص داده می‌شود.", "Paste a server configuration link; its protocol is detected from the format.")
                else tr("پروفایل مخصوص " + filter.displayName + " را وارد کن.", "Add a profile for " + filter.displayName + ".")
        }
        panel.addView(text(profileHelp, 12, muted))
        panel.addView(space(8))
        profileInput = EditText(this).apply {
            hint = when (filter) {
                Protocol.MASTER_DNS -> """{"protocol":"masterdns","DOMAINS":["t.example.com"],"ENCRYPTION_KEY":"…","RESOLVERS":["1.1.1.1","8.8.8.8"]}"""
                Protocol.STORM_DNS -> """{"protocol":"stormdns","DOMAINS":["t.example.com"],"ENCRYPTION_KEY":"…","RESOLVERS":["1.1.1.1","8.8.8.8"]}"""
                Protocol.DNSTT -> """{"protocol":"dnstt","domain":"t.example.com","pubkey":"64-hex-digits","resolver":"1.1.1.1:53","localPort":18080}"""
                else -> if (filter == null) "vless:// · vmess:// · trojan:// · ss://" else filter.displayName
            }
            setTextColor(fg); setHintTextColor(muted); textSize = 13f; minLines = 3; maxLines = 7
            gravity = Gravity.TOP or Gravity.START; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(13).toFloat(); setStroke(dp(1), if (light) Color.rgb(210, 218, 232) else Color.rgb(48, 58, 81)) }
        }
        panel.addView(profileInput); panel.addView(space(8))
        panel.addView(button(tr("تشخیص و افزودن", "Parse and add")) { importProfiles() })
        panel.addView(space(12)); panel.addView(text(tr("پروفایل‌های ذخیره‌شده", "Saved profiles"), 15, fg, true)); panel.addView(space(5))
        profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(profileList); renderProfiles()
        AlertDialog.Builder(this)
            .setTitle(if (filter == null) tr("کانفیگ‌ها و سرورها", "Server configurations") else tr("افزودن " + filter.displayName, "Add " + filter.displayName))
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton(tr("بستن", "Close"), null)
            .show()
    }

    private fun showAppSettings() {
        val prefs = getSharedPreferences("azadlink_ui", MODE_PRIVATE)
        val english = prefs.getString("language", "fa") == "en"
        fun tr(fa: String, en: String) = if (english) en else fa
        val light = prefs.getString("theme", "dark") == "light"
        val fg = if (light) Color.rgb(26, 33, 51) else Color.WHITE
        val muted = if (light) Color.rgb(85, 98, 121) else Color.rgb(177, 188, 207)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(10)) }
        panel.addView(text(tr("ظاهر برنامه", "Appearance"), 14, Color.rgb(133, 109, 255), true))
        val themeSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(tr("تاریک · مدرن", "Dark · Modern"), tr("روشن · ساده", "Light · Clean")))
            setSelection(if (prefs.getString("theme", "dark") == "light") 1 else 0)
        }
        panel.addView(themeSpinner); panel.addView(space(12))
        panel.addView(text(tr("زبان رابط کاربری", "Interface language"), 14, Color.rgb(133, 109, 255), true))
        val languageSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("فارسی", "English"))
            setSelection(if (english) 1 else 0)
        }
        panel.addView(languageSpinner); panel.addView(space(15))
        panel.addView(text(tr("دربارهٔ برنامه", "About AzadLink"), 15, Color.rgb(133, 109, 255), true))
        panel.addView(space(4)); panel.addView(text(tr("سازنده: رضا · RM007", "Creator: Reza · RM007"), 14, fg, true))
        panel.addView(text(tr("AzadLink کلاینت اندرویدی برای مدیریت چند موتور تونل و پروکسی است. کارکرد هر پروتکل به نسخهٔ موتور، کانفیگ و شبکه بستگی دارد.",
            "AzadLink is an Android client for managing tunnel and proxy engines. Each protocol depends on engine compatibility, configuration and network conditions."), 12, muted))
        val repoLink = TextView(this).apply {
            text = "↗  github.com/RM007-UI/Azadlink"; textSize = 13f; setTextColor(Color.rgb(53, 132, 218))
            setPadding(0, dp(10), 0, dp(6)); isClickable = true; isFocusable = true
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/RM007-UI/Azadlink"))) }
                    .onFailure { Toast.makeText(this@MainActivity, tr("بازکردن GitHub ناموفق بود", "Could not open GitHub"), Toast.LENGTH_SHORT).show() }
            }
        }
        panel.addView(repoLink)
        AlertDialog.Builder(this).setTitle(tr("تنظیمات AzadLink", "AzadLink settings"))
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton(tr("لغو", "Cancel"), null)
            .setPositiveButton(tr("ذخیره", "Save")) { _, _ ->
                prefs.edit().putString("theme", if (themeSpinner.selectedItemPosition == 1) "light" else "dark")
                    .putString("language", if (languageSpinner.selectedItemPosition == 1) "en" else "fa").apply()
                recreate()
            }.show()
    }

    private fun showAppDrawer() {
        val prefs = getSharedPreferences("azadlink_routing", MODE_PRIVATE)
        val currentMode = prefs.getString("split_mode", "disabled") ?: "disabled"
        val selected = prefs.getStringSet("selected_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }
        panel.addView(text("برنامه‌هایی را که از تونل عبور می‌کنند انتخاب کن.", 14, Color.WHITE, true))
        panel.addView(space(8))
        val modeGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
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
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(132, 103, 255))
                isChecked = currentMode == value
                modeGroup.addView(this)
            }
        }
        panel.addView(modeGroup)
        panel.addView(space(8))
        panel.addView(text("برنامه‌های نصب‌شده", 15, Color.rgb(207, 199, 255), true))
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
        val bulkActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bulkActions.addView(button("انتخاب همه") { appChecks.values.forEach { it.isChecked = true } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        bulkActions.addView(button("پاک‌کردن انتخاب‌ها") { appChecks.values.forEach { it.isChecked = false } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
        panel.addView(bulkActions)
        panel.addView(space(5))
        val appScroll = ScrollView(this).apply {
            addView(appList)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(290))
        }
        panel.addView(appScroll)
        panel.addView(space(10))
        panel.addView(button("تنظیم جداگانهٔ حالت پراکسی") { showProxySettings() })
        panel.addView(space(5))
        panel.addView(button("تنظیمات و ظاهر برنامه") { showAppSettings() })

        AlertDialog.Builder(this)
            .setTitle("کشوی برنامه‌ها")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیرهٔ مسیریابی") { _, _ ->
                val mode = radios.firstOrNull { it.isChecked }?.tag as? String ?: "disabled"
                val chosen = appChecks.filterValues { it.isChecked }.keys.toSet()
                if (mode == "onlySelected" && chosen.isEmpty()) {
                    Toast.makeText(this, "برای حالت فقط برنامه‌های انتخاب‌شده، حداقل یک برنامه را انتخاب کن.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                prefs.edit()
                    .putString("split_mode", mode)
                    .putStringSet("selected_apps", chosen)
                    .apply()
                Toast.makeText(this, "انتخاب برنامه‌ها ذخیره شد؛ اتصال را قطع و دوباره برقرار کن.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun showProxySettings() {
        val prefs = getSharedPreferences("azadlink_routing", MODE_PRIVATE)
        val english = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("language", "fa") == "en"
        val light = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("theme", "dark") == "light"
        fun tr(fa: String, en: String) = if (english) en else fa
        val fg = if (light) Color.rgb(26, 33, 51) else Color.WHITE
        val muted = if (light) Color.rgb(85, 98, 121) else Color.rgb(177, 188, 207)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(8)) }
        val enabled = android.widget.Switch(this).apply {
            text = tr("فقط حالت پراکسی (بدون VPN سراسری)", "Proxy-only mode (no system-wide VPN)")
            textSize = 14f; setTextColor(fg); isChecked = prefs.getBoolean("proxy_only", false)
        }
        panel.addView(enabled); panel.addView(space(8))
        panel.addView(text(tr("این گزینه برای موتورهایی کاربرد دارد که SOCKS محلی می‌سازند. Xray، Aether/Psiphon و MasterDNS/StormDNS از آن پشتیبانی می‌کنند؛ DNSTT برای عبور ترافیک به مقصد سرورِ SOCKS5 بدون احراز هویت نیاز دارد.",
            "Proxy-only mode requires a local SOCKS listener. Xray, Aether/Psiphon and MasterDNS/StormDNS support it; DNSTT needs a no-auth SOCKS5 endpoint behind the remote tunnel server."), 12, muted))
        AlertDialog.Builder(this).setTitle(tr("حالت پروکسی", "Proxy mode")).setView(panel)
            .setNegativeButton(tr("لغو", "Cancel"), null)
            .setPositiveButton(tr("ذخیره", "Save")) { _, _ ->
                prefs.edit().putBoolean("proxy_only", enabled.isChecked).apply()
                Toast.makeText(this, tr("تنظیمات ذخیره شد؛ اتصال را دوباره برقرار کن.", "Saved. Reconnect for the change to take effect."), Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun showRoutingSettings() {
        val prefs = getSharedPreferences("azadlink_routing", MODE_PRIVATE)
        val english = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("language", "fa") == "en"
        val light = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("theme", "dark") == "light"
        fun tr(fa: String, en: String) = if (english) en else fa
        val fg = if (light) Color.rgb(26, 33, 51) else Color.WHITE
        val muted = if (light) Color.rgb(85, 98, 121) else Color.rgb(177, 188, 207)
        val currentMode = prefs.getString("split_mode", "disabled") ?: "disabled"
        val selected = prefs.getStringSet("selected_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        panel.addView(text(tr("انتخاب کن کدام برنامه‌ها از تونل عبور کنند.", "Choose which apps should use the tunnel."), 14, fg, true))
        panel.addView(space(8))
        val modeGroup = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val options = listOf(
            "disabled" to tr("همهٔ برنامه‌ها از تونل عبور کنند", "Route all apps through the tunnel"),
            "bypassSelected" to tr("همه به‌جز برنامه‌های انتخاب‌شده", "All apps except selected ones"),
            "onlySelected" to tr("فقط برنامه‌های انتخاب‌شده", "Only selected apps")
        )
        val radios = options.map { (value, label) ->
            android.widget.RadioButton(this).apply {
                text = label; tag = value; textSize = 13f; setTextColor(fg)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(132, 103, 255))
                isChecked = currentMode == value; modeGroup.addView(this)
            }
        }
        panel.addView(modeGroup); panel.addView(space(8))
        panel.addView(text(tr("برنامه‌های نصب‌شده", "Installed apps"), 15, fg, true)); panel.addView(space(5))
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0).mapNotNull { info ->
            val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            if (appInfo.packageName == packageName) return@mapNotNull null
            val label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }.getOrDefault(appInfo.packageName)
            Triple(label, appInfo.packageName, info.loadIcon(packageManager))
        }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
        val appChecks = mutableMapOf<String, android.widget.CheckBox>()
        val appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apps.forEach { (label, pkg, icon) ->
            val check = android.widget.CheckBox(this).apply {
                text = "$label · $pkg"; textSize = 12f; setTextColor(fg); isChecked = selected.contains(pkg)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(132, 103, 255))
                setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null); compoundDrawablePadding = dp(8)
            }
            appChecks[pkg] = check; appList.addView(check)
        }
        val bulk = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bulk.addView(button(tr("انتخاب همه", "Select all")) { appChecks.values.forEach { it.isChecked = true } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        bulk.addView(button(tr("پاک‌کردن انتخاب‌ها", "Clear selection")) { appChecks.values.forEach { it.isChecked = false } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
        panel.addView(bulk); panel.addView(space(6))
        panel.addView(ScrollView(this).apply { addView(appList); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270)) })
        AlertDialog.Builder(this).setTitle(tr("مسیریابی برنامه‌ها", "App routing")).setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton(tr("لغو", "Cancel"), null)
            .setPositiveButton(tr("ذخیره", "Save")) { _, _ ->
                val mode = radios.firstOrNull { it.isChecked }?.tag as? String ?: "disabled"
                val chosen = appChecks.filterValues { it.isChecked }.keys.toSet()
                if (mode == "onlySelected" && chosen.isEmpty()) {
                    Toast.makeText(this, tr("برای این حالت حداقل یک برنامه انتخاب کن.", "Select at least one app for this mode."), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                prefs.edit().putString("split_mode", mode).putStringSet("selected_apps", chosen).apply()
                Toast.makeText(this, tr("ذخیره شد؛ اتصال را قطع و دوباره برقرار کن.", "Saved. Reconnect for the change to take effect."), Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun showMasqueAdvancedSettings() {
        val prefs = getSharedPreferences("azadlink_masque", MODE_PRIVATE)
        val english = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("language", "fa") == "en"
        val light = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("theme", "dark") == "light"
        fun tr(fa: String, en: String) = if (english) en else fa
        val fg = if (light) Color.rgb(26, 33, 51) else Color.WHITE
        val muted = if (light) Color.rgb(85, 98, 121) else Color.rgb(159, 171, 197)
        val fieldBg = if (light) Color.rgb(246, 248, 253) else Color.rgb(23, 29, 48)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(8))
        }
        fun addLabel(value: String) {
            panel.addView(text(value, 13, fg, true))
            panel.addView(space(4))
        }
        fun spinner(items: List<String>, selected: Int): android.widget.Spinner {
            return android.widget.Spinner(this).apply {
                adapter = android.widget.ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    items
                )
                setSelection(selected.coerceIn(0, items.lastIndex))
            }
        }

        addLabel(tr("انتقال MASQUE", "MASQUE transport"))
        val transportSpinner = spinner(
            listOf(tr("HTTP/2 · مناسب شبکه‌های محدود", "HTTP/2 · better for restricted networks"), tr("HTTP/3 · حالت سریع‌تر", "HTTP/3 · faster mode")),
            if ((prefs.getString("transport", "h2") ?: "h2") == "h2") 0 else 1
        )
        panel.addView(transportSpinner)
        panel.addView(space(10))

        addLabel(tr("حالت اسکن gateway", "Gateway scan mode"))
        val scanModes = listOf("turbo", "balanced", "thorough", "stealth", "ironclad")
        val currentScan = prefs.getString("scan_mode", "balanced") ?: "balanced"
        val scanSpinner = spinner(scanModes, scanModes.indexOf(currentScan))
        panel.addView(scanSpinner)
        panel.addView(text(
            tr("turbo سریع‌تر است؛ balanced متعادل است؛ thorough محدودهٔ بیشتری را بررسی می‌کند؛ stealth آهسته‌تر است؛ ironclad برای بررسی سخت‌گیرانه‌تر طراحی شده است.", "turbo is faster; balanced is the default compromise; thorough checks more candidates; stealth is slower; ironclad is stricter."),
            11, muted
        ))
        panel.addView(space(10))

        addLabel(tr("پروفایل استتار", "Obfuscation profile"))
        val noiseModes = listOf("firewall", "gfw", "off")
        val currentNoise = prefs.getString("obfuscation", "firewall") ?: "firewall"
        val noiseSpinner = spinner(noiseModes, noiseModes.indexOf(currentNoise))
        panel.addView(noiseSpinner)
        panel.addView(space(10))

        addLabel(tr("نسخهٔ IP", "IP version"))
        val ipSpinner = spinner(
            listOf(tr("دوگانه · IPv4 + IPv6", "Dual · IPv4 + IPv6"), tr("فقط IPv4", "IPv4 only"), tr("فقط IPv6", "IPv6 only")),
            when (prefs.getString("ip_version", "both")) { "v4" -> 1; "v6" -> 2; else -> 0 }
        )
        panel.addView(ipSpinner)
        panel.addView(space(10))

        addLabel(tr("نام سرور TLS (SNI)", "TLS server name (SNI)"))
        val sniInput = EditText(this).apply {
            setSingleLine(true)
            setText(prefs.getString("sni", "www.cloudflare.com") ?: "www.cloudflare.com")
            setTextColor(fg)
            setHintTextColor(muted)
            hint = "www.cloudflare.com"
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(fieldBg, 12)
        }
        panel.addView(sniInput)
        panel.addView(space(10))

        addLabel(tr("درگاه دستی (اختیاری)", "Manual endpoint (optional)"))
        val endpointInput = EditText(this).apply {
            setSingleLine(true)
            setText(prefs.getString("endpoint", "") ?: "")
            setTextColor(fg)
            setHintTextColor(muted)
            hint = if (english) "e.g. 162.159.198.64:443" else "مثلاً 162.159.198.64:443"
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(fieldBg, 12)
        }
        panel.addView(endpointInput)
        panel.addView(text(
            tr("خالی بگذار تا اسکن خودکار انجام شود. درگاه دستی ممکن است تغییر کند؛ اگر جواب ندهد، تلاش جایگزین دوباره اسکن می‌کند.", "Leave blank for automatic scanning. Manual endpoints can change; fallback may rescan if one fails."),
            11, Color.rgb(159, 171, 197)
        ))
        panel.addView(space(6))

        val fragmentCheck = android.widget.CheckBox(this).apply {
            text = tr("فرگمنت ClientHello (فقط در HTTP/2)", "ClientHello fragmentation (HTTP/2 only)")
            textSize = 12f
            setTextColor(fg)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(132, 103, 255))
            isChecked = prefs.getBoolean("fragment", false)
        }
        panel.addView(fragmentCheck)
        panel.addView(space(3))

        val fallbackCheck = android.widget.CheckBox(this).apply {
            text = tr("اگر اسکن شکست خورد، یک بار با اسکن عمیق‌تر تلاش کن", "Retry once with a deeper scan if the first scan fails")
            textSize = 12f
            setTextColor(fg)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(132, 103, 255))
            isChecked = prefs.getBoolean("auto_fallback", true)
        }
        panel.addView(fallbackCheck)
        panel.addView(space(8))
        panel.addView(text(
            tr("تلاش جایگزین ممکن است چند دقیقه طول بکشد و موفقیت اتصال را تضمین نمی‌کند. تنظیمات فقط روی اتصال بعدی اعمال می‌شوند.", "Fallback may take several minutes and cannot guarantee a connection. Settings apply to the next connection."),
            11, Color.rgb(159, 171, 197)
        ))
        AlertDialog.Builder(this)
            .setTitle(tr("تنظیمات پیشرفتهٔ MASQUE", "Advanced MASQUE settings"))
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton(tr("لغو", "Cancel"), null)
            .setPositiveButton(tr("ذخیره", "Save")) { _, _ ->
                val endpointValue = endpointInput.text?.toString()?.trim().orEmpty()
                if (endpointValue.isNotBlank()) {
                    val endpointPort = endpointValue.substringAfterLast(':', "").toIntOrNull()
                    if (!endpointValue.contains(':') || endpointPort == null || endpointPort !in 1..65535 || endpointValue.any { it.isWhitespace() }) {
                        Toast.makeText(this, tr("درگاه باید به شکل host:port باشد؛ مثلاً 162.159.198.64:443", "Endpoint must be host:port, e.g. 162.159.198.64:443"), Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                }
                val ip = when (ipSpinner.selectedItemPosition) { 1 -> "v4"; 2 -> "v6"; else -> "both" }
                prefs.edit()
                    .putString("connection_mode", "manual")
                    .putString("transport", if (transportSpinner.selectedItemPosition == 0) "h2" else "h3")
                    .putString("scan_mode", scanModes[scanSpinner.selectedItemPosition])
                    .putString("obfuscation", noiseModes[noiseSpinner.selectedItemPosition])
                    .putString("ip_version", ip)
                    .putString("sni", sniInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "www.cloudflare.com")
                    .putString("endpoint", endpointValue)
                    .putBoolean("fragment", fragmentCheck.isChecked)
                    .putBoolean("auto_fallback", fallbackCheck.isChecked)
                    .apply()
                Toast.makeText(this, tr("تنظیمات MASQUE ذخیره شد؛ برای اعمال، دوباره وصل شو.", "MASQUE settings saved. Reconnect to apply them."), Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun showProtocolDetail(protocol: Protocol) {
        val prefs = getSharedPreferences("azadlink_ui", MODE_PRIVATE)
        val english = prefs.getString("language", "fa") == "en"
        val light = prefs.getString("theme", "dark") == "light"
        fun tr(fa: String, en: String) = if (english) en else fa
        val fg = if (light) Color.rgb(25, 32, 49) else Color.WHITE
        val muted = if (light) Color.rgb(85, 98, 121) else Color.rgb(172, 182, 205)
        val engine = EngineRegistry.forProtocol(protocol)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
        panel.addView(text(protocol.displayName, 22, fg, true))
        panel.addView(space(6))
        panel.addView(text(protocol.note, 13, muted))
        panel.addView(space(14))
        panel.addView(text(tr("موتور", "Engine"), 12, muted, true))
        panel.addView(text(engine.name, 15, fg, true))
        panel.addView(space(5))
        panel.addView(text(engine.detail, 12, muted))
        panel.addView(space(16))
        when (protocol) {
            Protocol.MASQUE -> {
                panel.addView(button(tr("اتصال خودکار", "Connect automatically")) { requestAutoConnect() })
                panel.addView(space(7))
                panel.addView(button(tr("تنظیمات پیشرفتهٔ MASQUE", "Advanced MASQUE settings")) { showMasqueAdvancedSettings() })
                panel.addView(space(7))
                panel.addView(button(tr("قطع اتصال", "Disconnect")) { disconnectVpn() })
            }
            Protocol.PSIPHON -> {
                panel.addView(text(tr("اتصال مستقیم با موتور Psiphon همراه برنامه؛ وضعیت واقعی بعد از آماده‌شدن تونل گزارش می‌شود.",
                    "Direct connection using the bundled Psiphon engine. The real tunnel status is reported after startup."), 12, muted))
                panel.addView(space(9))
                panel.addView(button(tr("اتصال مستقیم Psiphon", "Connect Psiphon")) { requestPsiphonAutoConnect() })
                panel.addView(space(7))
                panel.addView(button(tr("موقعیت خروجی", "Exit location")) { showPsiphonSettings() })
            }
            else -> {
                val matching = profiles.filter { it.protocol == protocol }
                if (matching.isEmpty()) {
                    panel.addView(text(tr("برای این پروتکل هنوز پروفایلی ذخیره نشده.", "No saved profile exists for this protocol yet."), 13, if (light) Color.rgb(170, 99, 18) else Color.rgb(255, 198, 111)))
                    panel.addView(space(8))
                    panel.addView(button(tr("افزودن کانفیگ این پروتکل", "Add this protocol's configuration")) { showServerProfilesDialog(protocol) })
                } else {
                    matching.forEach { profile ->
                        panel.addView(text(profile.label, 14, fg, true))
                        panel.addView(space(5))
                        panel.addView(button(tr("اتصال با این پروفایل", "Connect with this profile")) { requestConnect(profile) })
                        panel.addView(space(7))
                    }
                    panel.addView(button(tr("افزودن پروفایل دیگر", "Add another profile")) { showServerProfilesDialog(protocol) })
                }
            }
        }
        AlertDialog.Builder(this).setView(panel).setNegativeButton(tr("بستن", "Close"), null).show()
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun loadProfiles() {
        profiles.clear()
        val saved = getSharedPreferences("azadlink_profiles", MODE_PRIVATE)
            .getString("items", "[]").orEmpty()
        runCatching {
            val array = JSONArray(saved)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val protocol = runCatching { Protocol.valueOf(item.optString("protocol")) }.getOrNull() ?: continue
                val raw = item.optString("rawConfig")
                if (raw.isBlank()) continue
                val label = item.optString("label").ifBlank { protocol.displayName }
                profiles.add(ProtocolProfile(protocol = protocol, label = label, rawConfig = raw))
            }
        }.onFailure {
            profiles.clear()
        }
    }

    private fun saveProfiles() {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("protocol", profile.protocol.name)
                    .put("label", profile.label)
                    .put("rawConfig", profile.rawConfig)
            )
        }
        getSharedPreferences("azadlink_profiles", MODE_PRIVATE)
            .edit().putString("items", array.toString()).apply()
    }

    private fun importProfiles() {
        val result = ProtocolParser.parse(profileInput.text?.toString().orEmpty())
        var added = 0
        result.profiles.filter { activeProfileFilter == null || it.protocol == activeProfileFilter }.forEach { candidate ->
            if (profiles.none { existing -> existing.rawConfig == candidate.rawConfig }) {
                profiles.add(candidate)
                added++
            }
        }
        if (added > 0) saveProfiles()
        renderProfiles()
        Toast.makeText(
            this,
            "شناسایی شد: " + result.profiles.size + " · جدید: " + added + " · ناشناخته: " + result.rejectedLines.size,
            Toast.LENGTH_LONG
        ).show()
    }
    private fun renderProfiles() {
        if (!::profileList.isInitialized) return
        profileList.removeAllViews()
        val prefs = getSharedPreferences("azadlink_ui", MODE_PRIVATE)
        val light = prefs.getString("theme", "dark") == "light"
        val english = prefs.getString("language", "fa") == "en"
        fun tr(fa: String, en: String) = if (english) en else fa
        val fg = if (light) Color.rgb(26, 33, 51) else Color.WHITE
        val muted = if (light) Color.rgb(85, 98, 121) else Color.rgb(152, 166, 192)
        if (profiles.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(18), dp(16), dp(18))
                background = rounded(if (light) Color.rgb(244, 247, 252) else Color.rgb(17, 23, 38), 15)
            }
            empty.addView(text(tr("هنوز پروفایلی اضافه نشده", "No profiles added yet"), 14, fg, true))
            empty.addView(text(tr("لینک سرور را در کادر بالا وارد کن.", "Paste a server link above."), 12, muted))
            profileList.addView(empty); return
        }
        profiles.toList().forEach { profile ->
            val engine = EngineRegistry.forProtocol(profile.protocol)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11))
                background = GradientDrawable().apply {
                    setColor(if (light) Color.WHITE else Color.rgb(20, 28, 45)); cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), if (light) Color.rgb(213, 221, 235) else Color.rgb(43, 54, 76))
                }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
            copy.addView(text(profile.label, 14, fg, true))
            copy.addView(text(profile.protocol.displayName + tr(" · پروفایل ذخیره‌شده", " · saved profile"), 11,
                if (light) Color.rgb(28, 129, 102) else Color.rgb(135, 218, 184)))
            top.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val delete = TextView(this).apply {
                text = "×"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.rgb(210, 77, 103))
                background = rounded(if (light) Color.rgb(255, 236, 240) else Color.rgb(55, 35, 50), 11)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)); isClickable = true
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(tr("حذف پروفایل", "Delete profile"))
                        .setMessage(tr("پروفایل «" + profile.label + "» حذف شود؟", "Delete profile “" + profile.label + "”?"))
                        .setNegativeButton(tr("لغو", "Cancel"), null)
                        .setPositiveButton(tr("حذف", "Delete")) { _, _ ->
                            profiles.removeAll { it.id == profile.id || it.rawConfig == profile.rawConfig }; saveProfiles(); renderProfiles()
                        }.show()
                }
            }
            top.addView(delete); card.addView(top); card.addView(space(7))
            card.addView(text(tr("موتور: ", "Engine: ") + engine.name, 11, muted)); card.addView(space(8))
            card.addView(button(tr("اتصال با این پروفایل", "Connect with this profile")) { requestConnect(profile) })
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(8); profileList.addView(card, params)
        }
    }

    private fun localizeUi(value: String): String {
        val english = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("language", "fa") == "en"
        if (!english) return value
        val exact = mapOf(
            "بستن" to "Close", "لغو" to "Cancel", "ذخیره" to "Save", "حذف" to "Delete",
            "اتصال" to "Connect", "قطع اتصال" to "Disconnect", "وضعیت اتصال" to "Connection status",
            "آمادهٔ اتصال" to "Ready to connect", "پروتکل انتخاب‌شده" to "Selected protocol",
            "هنوز پروفایلی اضافه نشده" to "No profiles added yet", "گزارش اتصال" to "Connection logs",
            "گزارش کپی شد." to "Logs copied.", "موقعیت خروجی Psiphon" to "Psiphon exit location",
            "تنظیمات پیشرفتهٔ MASQUE" to "Advanced MASQUE settings",
            "اتصال زنجیره‌ای" to "Chain connection", "انتخاب پروتکل" to "Choose protocol"
        )
        exact[value]?.let { return it }
        return when {
            value.startsWith("وضعیت: ") -> "Status: " + translateStatus(value.removePrefix("وضعیت: "))
            value.startsWith("وضعیت واقعی: ") -> "Live status: " + translateStatus(value.removePrefix("وضعیت واقعی: "))
            value.startsWith("موتور: ") -> "Engine: " + value.removePrefix("موتور: ")
            value.startsWith("مدت اتصال") -> value.replace("مدت اتصال", "Connected")
            value.startsWith("نویسنده: ") -> "Author: " + value.removePrefix("نویسنده: ")
            value == "در حال اتصال" -> "Connecting"
            value == "در حال بررسی تونل" -> "Validating tunnel"
            value == "متصل" -> "Connected"
            value == "در حال قطع" -> "Disconnecting"
            value == "قطع" -> "Disconnected"
            value.startsWith("اتصال ناموفق") -> "Connection failed"
            else -> value
        }
    }

    private fun translateStatus(value: String): String {
        val replacements = listOf(
            "در حال آماده‌سازی موتور Aether" to "Preparing Aether engine",
            "در حال راه‌اندازی زنجیرهٔ MASQUE → Psiphon" to "Starting MASQUE → Psiphon chain",
            "در حال راه‌اندازی Psiphon" to "Starting Psiphon",
            "هویت آماده است" to "Identity ready",
            "تونل MASQUE برقرار شد" to "MASQUE tunnel established",
            "تونل MASQUE در تلاش جایگزین برقرار شد" to "MASQUE tunnel established on fallback",
            "اتصال قطع شد" to "Disconnected",
            "در حال اتصال" to "Connecting",
            "متصل" to "Connected",
            "ناموفق" to "Failed",
            "قطع" to "Disconnected"
        )
        var result = value
        replacements.forEach { (fa, en) -> result = result.replace(fa, en) }
        return result
    }

    private fun button(title: String, action: () -> Unit): Button = Button(this).apply {
        text = localizeUi(title)
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(Color.rgb(77, 63, 155), 14)
        backgroundTintList = null
        setOnClickListener { action() }
    }
    private fun text(value:String,size:Int,color:Int,bold:Boolean=false):TextView=TextView(this).apply { text=localizeUi(value); textSize=size.toFloat(); setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD); setPadding(0,dp(3),0,dp(3)) }
    private fun space(heightDp:Int):View=View(this).apply { layoutParams=LinearLayout.LayoutParams(1,dp(heightDp)) }
    private fun dp(value:Int):Int=(value*resources.displayMetrics.density).toInt()
    companion object { private const val REQUEST_VPN_PERMISSION=7102; private const val REQUEST_IMPORT_PSIPHON=7103 }
}
