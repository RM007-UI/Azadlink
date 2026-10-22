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
                topStatus.text = "وضعیت واقعی: $state$gateway$detail"
                when (snapshot.stage) {
                    TunnelStage.CONNECTED -> setConnectionTimerActive(true)
                    TunnelStage.DISCONNECTED, TunnelStage.FAILED -> setConnectionTimerActive(false)
                    else -> Unit
                }
            }
        }
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(AzadLinkVpnService.EXTRA_MESSAGE) ?: return
            topStatus.text = "وضعیت: $message"
            when {
                message.contains("متصل ·", true) || message.contains("Psiphon متصل شد", true) || message.contains("تونل MASQUE برقرار شد", true) -> setConnectionTimerActive(true)
                message.contains("اتصال قطع شد", true) || message.contains("خطای اتصال:", true) || message.contains("اتصال تونل تأیید نشد", true) -> setConnectionTimerActive(false)
            }
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
            else Toast.makeText(this, "اجازهٔ VPN داده نشد", Toast.LENGTH_LONG).show()
            return
        }
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
        val country = getSharedPreferences("azadlink_psiphon_settings", MODE_PRIVATE)
            .getString("country", "") ?: ""
        PsiphonAutoConnector.connect(this, { message ->
            if (::topStatus.isInitialized) topStatus.text = "وضعیت: $message"
            if (message.startsWith("خطای Psiphon:")) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }, country, false)
    }

    private fun requestPsiphonChainConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingPsiphonChain = true
            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
        } else startPsiphonChainConnect()
    }

    private fun startPsiphonChainConnect() {
        topStatus.text = "وضعیت: در حال راه‌اندازی زنجیرهٔ MASQUE → Psiphon…"
        val country = getSharedPreferences("azadlink_psiphon_settings", MODE_PRIVATE)
            .getString("country", "") ?: ""
        PsiphonAutoConnector.connect(this, { message ->
            if (::topStatus.isInitialized) topStatus.text = "وضعیت: $message"
            if (message.startsWith("خطای Psiphon:")) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }, country, true)
    }

    private fun showPsiphonSettings() {
        val prefs = getSharedPreferences("azadlink_psiphon_settings", MODE_PRIVATE)
        val countries = listOf(
            "سریع‌ترین / خودکار" to "",
            "آمریکا" to "US",
            "کانادا" to "CA",
            "بریتانیا" to "GB",
            "آلمان" to "DE",
            "هلند" to "NL",
            "فرانسه" to "FR",
            "ژاپن" to "JP",
            "سنگاپور" to "SG",
            "هند" to "IN"
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
            addView(text("موقعیت خروجی Psiphon", 15, Color.WHITE, true))
            addView(space(7))
            addView(picker)
            addView(space(8))
            addView(text(
                "سریع‌ترین / خودکار یعنی انتخاب کشور را به Psiphon می‌سپارد؛ کشور انتخابی تضمین‌کنندهٔ سرعت یا دسترس‌پذیری نیست.",
                12, Color.rgb(159, 171, 197)
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("موقعیت Psiphon")
            .setView(panel)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره") { _, _ ->
                prefs.edit().putString("country", countries[picker.selectedItemPosition].second).apply()
                Toast.makeText(this, "موقعیت Psiphon ذخیره شد.", Toast.LENGTH_SHORT).show()
            }
            .show()
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
        val lightTheme = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("theme", "dark") == "light"
        val bg = if (lightTheme) Color.rgb(242, 245, 251) else Color.rgb(7, 10, 19)
        val surface = if (lightTheme) Color.WHITE else Color.rgb(17, 23, 38)
        val raised = if (lightTheme) Color.rgb(232, 237, 247) else Color.rgb(23, 31, 49)
        val elevated = if (lightTheme) Color.rgb(222, 229, 243) else Color.rgb(29, 38, 59)
        val accent = Color.rgb(109, 84, 235)
        val accent2 = Color.rgb(48, 155, 181)
        val mint = Color.rgb(38, 163, 125)
        val muted = if (lightTheme) Color.rgb(87, 101, 127) else Color.rgb(152, 166, 192)
        val stroke = if (lightTheme) Color.rgb(205, 214, 231) else Color.rgb(44, 55, 78)
        val mainText = if (lightTheme) Color.rgb(24, 31, 48) else Color.WHITE

        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        fun panelBackground(color: Int, radius: Int, border: Int = stroke): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), border)
            }

        fun gradientBackground(radius: Int): GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(51, 40, 99), Color.rgb(25, 54, 78))
            ).apply {
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), Color.rgb(79, 76, 132))
            }

        fun spaceInto(target: LinearLayout, height: Int) {
            target.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(1, dp(height))
            })
        }

        fun labelInto(target: LinearLayout, value: String, size: Int = 13, color: Int = muted, bold: Boolean = false) {
            val label = TextView(this).apply {
                text = value
                textSize = size.toFloat()
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                includeFontPadding = false
            }
            target.addView(label)
        }

        fun pageHeading(target: LinearLayout, title: String, subtitle: String, tag: String? = null) {
            if (!tag.isNullOrBlank()) {
                target.addView(TextView(this).apply {
                    text = tag.uppercase()
                    textSize = 10f
                    letterSpacing = 0.12f
                    setTextColor(accent2)
                    setTypeface(typeface, Typeface.BOLD)
                    includeFontPadding = false
                })
                spaceInto(target, 7)
            }
            labelInto(target, title, 26, Color.WHITE, true)
            spaceInto(target, 5)
            labelInto(target, subtitle, 13, muted)
            spaceInto(target, 21)
        }

        fun sectionTitle(target: LinearLayout, title: String, subtitle: String? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(2).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(18))
            })
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), 0, 0, 0)
            }
            labelInto(copy, title, 15, mainText, true)
            if (!subtitle.isNullOrBlank()) {
                spaceInto(copy, 3)
                labelInto(copy, subtitle, 11, muted)
            }
            row.addView(copy)
            target.addView(row)
            spaceInto(target, 11)
        }

        fun actionCard(
            target: LinearLayout,
            symbol: String,
            title: String,
            subtitle: String,
            action: () -> Unit,
            iconColor: Int = accent
        ) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(13), dp(13), dp(13))
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.rgb(55, 65, 91)),
                    panelBackground(raised, 20),
                    null
                )
                isClickable = true
                isFocusable = true
                elevation = dp(2).toFloat()
                setOnClickListener {
                    animate().cancel()
                    animate().scaleX(0.985f).scaleY(0.985f).setDuration(70).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }.start()
                    action()
                }
            }
            val iconTile = TextView(this).apply {
                text = symbol
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(iconColor)
                    cornerRadius = dp(15).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            }
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(4), 0)
            }
            labelInto(copy, title, 15, Color.WHITE, true)
            spaceInto(copy, 4)
            labelInto(copy, subtitle, 11, muted)
            card.addView(iconTile)
            card.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(TextView(this).apply {
                text = "›"
                textSize = 27f
                gravity = Gravity.CENTER
                setTextColor(muted)
                includeFontPadding = false
            })
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(9)
            target.addView(card, params)
        }

        fun actionButton(target: LinearLayout, title: String, fill: Int, action: () -> Unit, height: Int = 48) {
            val item = button(title, action).apply {
                background = GradientDrawable().apply {
                    setColor(fill)
                    cornerRadius = dp(15).toFloat()
                }
                backgroundTintList = null
                minHeight = dp(height)
                minimumHeight = dp(height)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                textSize = 14f
                elevation = dp(1).toFloat()
            }
            target.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(16), dp(12))
        }
        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_azadlink)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        labelInto(brand, "AzadLink", 21, Color.WHITE, true)
        spaceInto(brand, 3)
        labelInto(brand, "SMART TUNNEL CLIENT", 9, accent2, true)
        header.addView(logo)
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(stroke),
                panelBackground(raised, 16),
                null
            )
            isClickable = true
            isFocusable = true
            text = "☰"
            setOnClickListener { showAppDrawer() }
            contentDescription = "منوی برنامه و مسیریابی"
            layoutParams = LinearLayout.LayoutParams(dp(45), dp(45))
        }
        header.addView(settingsButton)
        root.addView(header)

        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = panelBackground(surface, 17)
        }
        val dot = TextView(this).apply {
            text = "●"
            textSize = 11f
            setTextColor(mint)
            setPadding(0, 0, dp(8), 0)
        }
        topStatus = TextView(this).apply {
            text = "آمادهٔ اتصال"
            textSize = 12f
            setTextColor(Color.rgb(232, 238, 249))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        statusPanel.addView(dot)
        statusPanel.addView(topStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val logAction = TextView(this).apply {
            text = "گزارش"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(190, 177, 255))
            setPadding(dp(10), dp(7), 0, dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { showTunnelLogs() }
        }
        statusPanel.addView(logAction)
        val statusParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusParams.setMargins(dp(15), 0, dp(15), dp(10))
        root.addView(statusPanel, statusParams)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(8), dp(15), dp(20))
        }
        scroll.addView(content)
        val pages = mutableListOf<LinearLayout>()

        fun addPage(build: (LinearLayout) -> Unit) {
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                alpha = 1f
            }
            content.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            pages.add(page)
            build(page)
        }

        addPage { page ->
            pageHeading(page, "اتصال", "پروتکل را انتخاب کن و دکمهٔ بزرگ اتصال را بزن", "AZADLINK · SMART TUNNEL")
            val connectHero = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    if (lightTheme) intArrayOf(Color.rgb(224, 226, 255), Color.rgb(218, 243, 248))
                    else intArrayOf(Color.rgb(45, 37, 91), Color.rgb(20, 58, 76))
                ).apply {
                    cornerRadius = dp(26).toFloat()
                    setStroke(dp(1), if (lightTheme) Color.rgb(191, 196, 245) else Color.rgb(72, 76, 133))
                }
                elevation = dp(3).toFloat()
            }
            selectedProtocolLabel = TextView(this).apply {
                text = selectedProtocolName()
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(mainText)
                setTypeface(typeface, Typeface.BOLD)
                includeFontPadding = false
            }
            selectedProtocolHint = TextView(this).apply {
                text = "پروتکل انتخاب‌شده"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(muted)
                setPadding(0, dp(4), 0, dp(10))
            }
            val connectOrb = TextView(this).apply {
                text = "⟡\nاتصال"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(126, 88, 255), Color.rgb(48, 185, 206))
                ).apply { shape = GradientDrawable.OVAL; setStroke(dp(3), Color.argb(80, 255, 255, 255)) }
                elevation = dp(9).toFloat()
                layoutParams = LinearLayout.LayoutParams(dp(128), dp(128)).apply { gravity = Gravity.CENTER }
                isClickable = true
                isFocusable = true
                contentDescription = "اتصال با پروتکل انتخاب‌شده"
                setOnClickListener {
                    animate().cancel()
                    animate().scaleX(0.94f).scaleY(0.94f).setDuration(85).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                    }.start()
                    connectSelectedProtocol()
                }
            }
            ObjectAnimator.ofPropertyValuesHolder(
                connectOrb,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.035f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.035f)
            ).apply { duration = 1500; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE; start() }
            connectHero.addView(selectedProtocolLabel)
            connectHero.addView(selectedProtocolHint)
            connectHero.addView(connectOrb)
            val chooseProtocol = TextView(this).apply {
                text = "☷   انتخاب پروتکل"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(if (lightTheme) Color.rgb(84, 77, 180) else Color.rgb(67, 57, 126))
                    cornerRadius = dp(15).toFloat()
                }
                setPadding(dp(20), dp(12), dp(20), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { showProtocolDrawer() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                }
            }
            connectHero.addView(chooseProtocol)
            val connectionHelp = TextView(this).apply {
                text = "اگر هنوز کانفیگی نداری، پروتکل را انتخاب کن تا راهنمای افزودن آن باز شود."
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(muted)
                setPadding(0, dp(12), 0, 0)
            }
            connectHero.addView(connectionHelp)
            spaceInto(connectHero, 14)
            connectionTimerText = TextView(this).apply {
                text = "مدت اتصال  ·  ۰۰:۰۰:۰۰"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(210, 224, 245))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.argb(45, 8, 13, 28))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.argb(90, 165, 174, 255))
                }
            }
            connectHero.addView(connectionTimerText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            page.addView(connectHero)
            spaceInto(page, 20)
            sectionTitle(page, "پروتکل‌های سروری", "VLESS، VMess، Trojan و Shadowsocks")
            actionCard(page, "V", "VLESS", "پشتیبانی از لینک‌های VLESS و Reality/TLS", { showProtocolDetail(Protocol.VLESS) }, Color.rgb(105, 81, 223))
            actionCard(page, "M", "VMess", "ورود لینک و اجرای کانفیگ با Xray", { showProtocolDetail(Protocol.VMESS) }, Color.rgb(56, 130, 211))
            actionCard(page, "T", "Trojan", "اتصال مبتنی بر TLS", { showProtocolDetail(Protocol.TROJAN) }, Color.rgb(35, 150, 135))
            actionCard(page, "S", "Shadowsocks", "پروکسی رمزگذاری‌شده", { showProtocolDetail(Protocol.SHADOWSOCKS) }, Color.rgb(184, 104, 66))
            actionCard(page, "W", "WireGuard", "تونل مبتنی بر کلید", { showProtocolDetail(Protocol.WIREGUARD) }, Color.rgb(68, 131, 181))
            actionCard(page, "A", "Aether · MASQUE", "اتصال خودکار؛ تنظیمات پیشرفته و HTTP/2", { showProtocolDetail(Protocol.MASQUE) }, Color.rgb(114, 84, 224))
            spaceInto(page, 9)
            sectionTitle(page, "افزودن پروفایل", "لینک را وارد کن؛ تشخیص پروتکل خودکار است")
            val inputShell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(11))
                background = panelBackground(surface, 18)
            }
            profileInput = EditText(this).apply {
                hint = "vless://  ·  vmess://  ·  trojan://  ·  ss://"
                setHintTextColor(Color.rgb(104, 119, 145))
                setTextColor(Color.WHITE)
                textSize = 13f
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP or Gravity.START
                setPadding(dp(4), dp(7), dp(4), dp(7))
                background = null
                includeFontPadding = false
            }
            inputShell.addView(profileInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            page.addView(inputShell)
            spaceInto(page, 9)
            actionButton(page, "＋   تشخیص و افزودن کانفیگ", accent, { importProfiles() }, 49)
            spaceInto(page, 20)
            sectionTitle(page, "پروفایل‌های من", "پروفایل‌های ذخیره‌شده روی همین دستگاه")
            profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            page.addView(profileList)
            spaceInto(page, 10)
            labelInto(page, "نکته: افزودن موفق کانفیگ به معنی تأییدشدن اتصال سرور نیست؛ وضعیت موتور بعد از اتصال بررسی می‌شود.", 11, muted)
        }

        addPage { page ->
            pageHeading(page, "تونل‌های DNS", "سه موتور مستقل در یک فضای مرتب", "DNS TUNNELS")
            actionCard(page, "D", "DNSTT", "تونل DNS؛ به آدرس و مشخصات سرور نیاز دارد", { showProtocolDetail(Protocol.DNSTT) }, Color.rgb(69, 135, 201))
            actionCard(page, "M", "Master DNS", "موتور MasterDnsVPN", { showProtocolDetail(Protocol.MASTER_DNS) }, Color.rgb(40, 155, 143))
            actionCard(page, "ϟ", "Storm DNS", "موتور StormDNS", { showProtocolDetail(Protocol.STORM_DNS) }, Color.rgb(121, 91, 218))
            spaceInto(page, 13)
            val note = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = panelBackground(Color.rgb(29, 33, 48), 16, Color.rgb(66, 58, 80))
            }
            labelInto(note, "ⓘ", 17, Color.rgb(255, 199, 112), true)
            val noteText = TextView(this).apply {
                text = "هر موتور باید از گزارش خودش آماده‌بودن تونل را تأیید کند؛ کارت پروتکل به‌تنهایی وضعیت اتصال را ثابت نمی‌کند."
                textSize = 12f
                setTextColor(Color.rgb(199, 204, 219))
                setPadding(dp(9), 0, 0, 0)
            }
            note.addView(noteText)
            page.addView(note)
        }

        addPage { page ->
            pageHeading(page, "Aether / MASQUE", "برای شبکهٔ خودت، مسیر اتصال را تنظیم کن", "AETHER CORE")
            val hero = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(17))
                background = gradientBackground(24)
                elevation = dp(3).toFloat()
            }
            val heroTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = TextView(this).apply {
                text = "A"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(104, 77, 203))
                    cornerRadius = dp(11).toFloat()
                }
                setPadding(dp(12), dp(7), dp(12), dp(7))
            }
            val miniCopy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, 0, 0)
            }
            labelInto(miniCopy, "AETHER ENGINE", 10, Color.rgb(189, 184, 238), true)
            spaceInto(miniCopy, 3)
            labelInto(miniCopy, "MASQUE · AETHER ENGINE", 13, Color.WHITE, true)
            heroTop.addView(badge)
            heroTop.addView(miniCopy)
            hero.addView(heroTop)
            spaceInto(hero, 19)
            labelInto(hero, "اتصال امن، با تنظیمات خودت", 21, mainText, true)
            spaceInto(hero, 6)
            labelInto(hero, "اسکن gateway، نسخهٔ IP، SNI و پروفایل استتار را از تنظیمات پیشرفته کنترل کن.", 12, Color.rgb(206, 209, 234))
            spaceInto(hero, 17)
            actionButton(hero, "اتصال به MASQUE", accent, { requestAutoConnect() }, 51)
            spaceInto(hero, 8)
            actionButton(hero, "تنظیمات پیشرفته", Color.rgb(51, 51, 91), { showMasqueAdvancedSettings() }, 47)
            spaceInto(hero, 8)
            actionButton(hero, "تنظیمات پیش‌فرض", Color.rgb(42, 62, 82), {
                getSharedPreferences("azadlink_masque", MODE_PRIVATE).edit()
                    .putString("connection_mode", "default")
                    .putString("transport", "h2")
                    .putString("scan_mode", "balanced")
                    .putString("obfuscation", "firewall")
                    .putString("ip_version", "both")
                    .putString("sni", "www.cloudflare.com")
                    .putString("endpoint", "")
                    .putBoolean("fragment", false)
                    .putBoolean("auto_fallback", true)
                    .apply()
                Toast.makeText(this, "تنظیمات پیش‌فرض MASQUE فعال شد (HTTP/2 + تلاش جایگزین).", Toast.LENGTH_LONG).show()
            }, 43)
            spaceInto(hero, 7)
            actionButton(hero, "تنظیم خودکار", Color.rgb(44, 105, 105), {
                getSharedPreferences("azadlink_masque", MODE_PRIVATE).edit()
                    .putString("connection_mode", "auto")
                    .putString("transport", "h2")
                    .putString("scan_mode", "balanced")
                    .putString("obfuscation", "firewall")
                    .putString("ip_version", "both")
                    .putString("sni", "www.cloudflare.com")
                    .putString("endpoint", "")
                    .putBoolean("fragment", false)
                    .putBoolean("auto_fallback", true)
                    .apply()
                Toast.makeText(this, "حالت خودکار فعال شد؛ در صورت شکست، اسکن عمیق‌تر امتحان می‌شود.", Toast.LENGTH_LONG).show()
            }, 43)
            spaceInto(hero, 8)
            labelInto(hero, "اتصال زنجیره‌ای از این بخش موقتاً برداشته شده؛ برای استفاده از زنجیرهٔ سالم، وارد تب Psiphon شو.", 12, Color.rgb(206, 209, 234))
            spaceInto(hero, 8)
            actionButton(hero, "قطع اتصال", Color.rgb(75, 42, 59), { disconnectVpn() }, 44)
            page.addView(hero)
            spaceInto(page, 17)
            sectionTitle(page, "پیشنهاد برای عیب‌یابی", "تنظیمات یکسان همیشه روی همهٔ شبکه‌ها جواب نمی‌دهند")
            val retryCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = panelBackground(surface, 18)
            }
            labelInto(retryCard, "اسکن عمیق‌تر در تلاش دوم", 14, Color.WHITE, true)
            spaceInto(retryCard, 5)
            labelInto(retryCard, "اگر اسکن اولیه gateway قابل‌استفاده پیدا نکند، برنامه می‌تواند یک‌بار با حالت thorough دوباره امتحان کند. ممکن است بیشتر طول بکشد و اتصال را تضمین نمی‌کند.", 11, muted)
            page.addView(retryCard)
            spaceInto(page, 10)
            actionButton(page, "نمایش گزارش موتور", elevated, { showTunnelLogs() }, 45)
        }

        addPage { page ->
            pageHeading(page, "Psiphon", "اتصال مستقیم، بدون واردکردن JSON", "PSIPHON")
            val hero = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(19), dp(18), dp(18))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(39, 45, 75), Color.rgb(27, 54, 63))
                ).apply {
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), Color.rgb(62, 85, 104))
                }
                elevation = dp(3).toFloat()
            }
            val emblem = TextView(this).apply {
                text = "✦"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(81, 68, 145))
                    cornerRadius = dp(18).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
            }
            hero.addView(emblem)
            spaceInto(hero, 14)
            labelInto(hero, "اتصال خودکار Psiphon", 21, mainText, true)
            spaceInto(hero, 6)
            labelInto(hero, "هستهٔ Psiphon همراه برنامه اجرا می‌شود. بعد از شروع، وضعیت آمادگی تونل و آزمون پراکسی در گزارش ثبت می‌شود.", 12, Color.rgb(198, 209, 222))
            spaceInto(hero, 17)
            actionButton(hero, "انتخاب موقعیت خروجی", Color.rgb(51, 51, 91), { showPsiphonSettings() }, 45)
            spaceInto(hero, 8)
            val selectedCountry = getSharedPreferences("azadlink_psiphon_settings", MODE_PRIVATE).getString("country", "").orEmpty()
            labelInto(hero, "موقعیت فعلی: " + (selectedCountry.ifBlank { "سریع‌ترین / خودکار" }), 12, Color.rgb(198, 209, 222))
            spaceInto(hero, 12)
            actionButton(hero, "اتصال به Psiphon", Color.rgb(39, 165, 143), { requestPsiphonAutoConnect() }, 51)
            spaceInto(hero, 8)
            actionButton(hero, "اتصال زنجیره‌ای با MASQUE", Color.rgb(44, 105, 105), { requestPsiphonChainConnect() }, 47)
            spaceInto(hero, 8)
            actionButton(hero, "قطع اتصال", Color.rgb(75, 42, 59), { disconnectVpn() }, 44)
            page.addView(hero)
            spaceInto(page, 14)
            actionButton(page, "نمایش گزارش خطا و اتصال", elevated, { showTunnelLogs() }, 45)
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = panelBackground(Color.rgb(14, 19, 31), 22, Color.rgb(31, 40, 60))
            elevation = dp(6).toFloat()
        }
        val tabNames = listOf("پروتکل‌ها", "DNS", "Aether", "Psiphon")
        val tabIcons = listOf("⌘", "⌁", "◈", "✦")
        val tabButtons = mutableListOf<LinearLayout>()
        fun selectTab(index: Int) {
            pages.forEachIndexed { i, page ->
                if (i == index) {
                    page.visibility = View.VISIBLE
                    page.alpha = 0f
                    page.translationY = dp(9).toFloat()
                    page.animate().alpha(1f).translationY(0f).setDuration(210).start()
                } else {
                    page.animate().cancel()
                    page.visibility = View.GONE
                }
            }
            tabButtons.forEachIndexed { i, tab ->
                val active = i == index
                tab.background = GradientDrawable().apply {
                    setColor(if (active) Color.rgb(61, 51, 102) else Color.TRANSPARENT)
                    cornerRadius = dp(15).toFloat()
                }
                tab.animate().scaleX(if (active) 1.035f else 1f).scaleY(if (active) 1.035f else 1f)
                    .setDuration(150).start()
                val icon = tab.getChildAt(0) as TextView
                val label = tab.getChildAt(1) as TextView
                icon.setTextColor(if (active) Color.WHITE else muted)
                label.setTextColor(if (active) Color.WHITE else muted)
                label.setTypeface(label.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            }
            scroll.scrollTo(0, 0)
        }
        tabNames.forEachIndexed { index, name ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(7), dp(3), dp(7))
                background = GradientDrawable().apply {
                    setColor(if (index == 0) Color.rgb(61, 51, 102) else Color.TRANSPARENT)
                    cornerRadius = dp(15).toFloat()
                }
                isClickable = true
                isFocusable = true
                addView(TextView(this@MainActivity).apply {
                    text = tabIcons[index]
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(if (index == 0) Color.WHITE else muted)
                    includeFontPadding = false
                })
                addView(TextView(this@MainActivity).apply {
                    text = name
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(if (index == 0) Color.WHITE else muted)
                    setPadding(0, dp(3), 0, 0)
                    includeFontPadding = false
                })
                setOnClickListener { selectTab(index) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            tabButtons.add(tab)
            bottomBar.addView(tab)
        }

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val navParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        navParams.setMargins(dp(12), dp(5), dp(12), dp(8))
        root.addView(bottomBar, navParams)
        setContentView(root)
        selectTab(0)
        renderProfiles()
    }

    private fun setConnectionTimerActive(active: Boolean) {
        val prefs = getSharedPreferences("azadlink_connection", MODE_PRIVATE)
        if (active) {
            if (prefs.getLong("connected_since_ms", 0L) <= 0L) {
                prefs.edit().putLong("connected_since_ms", System.currentTimeMillis()).apply()
            }
        } else {
            prefs.edit().remove("connected_since_ms").apply()
        }
        renderConnectionTimer()
    }

    private fun renderConnectionTimer() {
        val view = connectionTimerText ?: return
        val start = getSharedPreferences("azadlink_connection", MODE_PRIVATE).getLong("connected_since_ms", 0L)
        if (start <= 0L) {
            view.text = "مدت اتصال  ·  ۰۰:۰۰:۰۰"
            return
        }
        val seconds = ((System.currentTimeMillis() - start).coerceAtLeast(0L) / 1000L)
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val secs = seconds % 60L
        view.text = "مدت اتصال  ·  %02d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, secs)
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

    private fun selectedProtocol(): Protocol {
        val saved = getSharedPreferences("azadlink_ui", MODE_PRIVATE).getString("selected_protocol", "MASQUE") ?: "MASQUE"
        return runCatching { Protocol.valueOf(saved) }.getOrDefault(Protocol.MASQUE)
    }

    private fun selectedProtocolName(): String = when (selectedProtocol()) {
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

    private fun connectSelectedProtocol() {
        when (val protocol = selectedProtocol()) {
            Protocol.MASQUE -> requestAutoConnect()
            Protocol.PSIPHON -> requestPsiphonAutoConnect()
            else -> {
                val profile = profiles.firstOrNull { it.protocol == protocol }
                if (profile != null) requestConnect(profile) else showProtocolDetail(protocol)
            }
        }
    }

    private fun showProtocolDrawer() {
        val groups = listOf(
            "پروتکل‌های سروری" to listOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS, Protocol.WIREGUARD),
            "تونل‌های DNS" to listOf(Protocol.DNSTT, Protocol.MASTER_DNS, Protocol.STORM_DNS),
            "Aether و MASQUE" to listOf(Protocol.MASQUE),
            "اتصال مستقل" to listOf(Protocol.PSIPHON)
        )
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val dialog = AlertDialog.Builder(this).setTitle("انتخاب پروتکل").setNegativeButton("بستن", null).create()
        groups.forEach { (heading, protocols) ->
            panel.addView(text(heading, 14, Color.rgb(155, 137, 255), true))
            panel.addView(space(5))
            protocols.forEach { protocol ->
                val title = when (protocol) {
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
                val row = TextView(this).apply {
                    text = (if (selectedProtocol() == protocol) "✓   " else "◇   ") + title
                    textSize = 14f
                    setTextColor(if (selectedProtocol() == protocol) Color.rgb(135, 112, 255) else Color.WHITE)
                    setPadding(dp(13), dp(13), dp(13), dp(13))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(23, 30, 48))
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), Color.rgb(48, 58, 83))
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        getSharedPreferences("azadlink_ui", MODE_PRIVATE).edit().putString("selected_protocol", protocol.name).apply()
                        selectedProtocolLabel?.text = title
                        selectedProtocolHint?.text = "پروتکل انتخاب‌شده"
                        Toast.makeText(this@MainActivity, "پروتکل انتخاب شد: $title", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        if (protocol != Protocol.MASQUE && protocol != Protocol.PSIPHON && profiles.none { it.protocol == protocol }) {
                            showProtocolDetail(protocol)
                        }
                    }
                }
                panel.addView(row)
                panel.addView(space(6))
            }
            panel.addView(space(7))
        }
        dialog.setView(ScrollView(this).apply { addView(panel) })
        dialog.show()
    }

    private fun showAppSettings() {
        val prefs = getSharedPreferences("azadlink_ui", MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(10))
        }
        panel.addView(text("ظاهر برنامه", 14, Color.rgb(155, 137, 255), true))
        val themeSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("تاریک · مدرن", "روشن · ساده"))
            setSelection(if (prefs.getString("theme", "dark") == "light") 1 else 0)
        }
        panel.addView(themeSpinner)
        panel.addView(space(12))
        panel.addView(text("زبان رابط کاربری", 14, Color.rgb(155, 137, 255), true))
        val languageSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("فارسی", "English (آزمایشی)"))
            setSelection(if (prefs.getString("language", "fa") == "en") 1 else 0)
        }
        panel.addView(languageSpinner)
        panel.addView(space(8))
        panel.addView(text("زبان انگلیسی در این نسخه آزمایشی است؛ ممکن است بعضی متن‌های ثابت و گزارش موتور همچنان فارسی باشند.", 11, Color.rgb(159, 171, 197)))
        panel.addView(space(16))
        panel.addView(text("دربارهٔ سازنده", 15, Color.rgb(155, 137, 255), true))
        panel.addView(space(4))
        panel.addView(text("سازنده: رضا", 13, Color.WHITE, true))
        panel.addView(text("AzadLink یک کلاینت اندرویدی برای مدیریت و اجرای چند موتور تونل و پروکسی در یک رابط ساده است. پشتیبانی واقعی هر پروتکل به کانفیگ، شبکه و وضعیت موتور مربوط بستگی دارد.", 12, Color.rgb(177, 188, 207)))
        val repoLink = TextView(this).apply {
            text = "↗  github.com/RM007-UI/Azadlink"
            textSize = 13f
            setTextColor(Color.rgb(113, 190, 255))
            setPadding(0, dp(10), 0, dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/RM007-UI/Azadlink"))) }
                    .onFailure { Toast.makeText(this@MainActivity, "بازکردن GitHub ناموفق بود", Toast.LENGTH_SHORT).show() }
            }
        }
        panel.addView(repoLink)
        AlertDialog.Builder(this)
            .setTitle("تنظیمات AzadLink")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val newTheme = if (themeSpinner.selectedItemPosition == 1) "light" else "dark"
                val language = if (languageSpinner.selectedItemPosition == 1) "en" else "fa"
                prefs.edit().putString("theme", newTheme).putString("language", language).apply()
                Toast.makeText(this, "تنظیمات ذخیره شد؛ برای اعمال تم، صفحه دوباره ساخته می‌شود.", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .show()
    }

    private fun showAppDrawer() {
        val options = arrayOf(
            "مسیریابی برنامه‌ها  ·  انتخاب برنامه‌های عبوری از تونل",
            "حالت پراکسی  ·  تنظیم جداگانه",
            "انتخاب پروتکل اتصال",
            "تنظیمات و ظاهر برنامه",
            "گزارش موتور و خطاها"
        )
        AlertDialog.Builder(this)
            .setTitle("منوی AzadLink")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRoutingSettings()
                    1 -> showProxySettings()
                    2 -> showProtocolDrawer()
                    3 -> showAppSettings()
                    4 -> showTunnelLogs()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun showProxySettings() {
        val prefs = getSharedPreferences("azadlink_routing", MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        val enabled = android.widget.Switch(this).apply {
            text = "فقط حالت پراکسی (بدون VPN سراسری)"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("proxy_only", false)
        }
        panel.addView(enabled)
        panel.addView(space(8))
        panel.addView(text("در این حالت موتور به‌جای مسیریابی کل گوشی، پراکسی محلی را فراهم می‌کند. برای Xray پورت SOCKS برابر 127.0.0.1:10808 است؛ Aether از پورت خودش استفاده می‌کند. پشتیبانی از این حالت به موتور انتخابی بستگی دارد و برای موتورهای DNS هنوز یکسان نیست.", 12, Color.rgb(177, 188, 207)))
        AlertDialog.Builder(this)
            .setTitle("حالت پراکسی")
            .setView(panel)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره") { _, _ ->
                prefs.edit().putBoolean("proxy_only", enabled.isChecked).apply()
                Toast.makeText(this, "حالت پراکسی ذخیره شد؛ اتصال فعلی را قطع و دوباره برقرار کن.", Toast.LENGTH_LONG).show()
            }
            .show()
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
                setSelection(selected.coerceIn(0, items.lastIndex))
            }
        }

        addLabel("انتقال MASQUE")
        val transportSpinner = spinner(
            listOf("HTTP/2 · مناسب شبکه‌های محدود", "HTTP/3 · حالت سریع‌تر"),
            if ((prefs.getString("transport", "h2") ?: "h2") == "h2") 0 else 1
        )
        panel.addView(transportSpinner)
        panel.addView(space(10))

        addLabel("حالت اسکن gateway")
        val scanModes = listOf("turbo", "balanced", "thorough", "stealth", "ironclad")
        val currentScan = prefs.getString("scan_mode", "balanced") ?: "balanced"
        val scanSpinner = spinner(scanModes, scanModes.indexOf(currentScan))
        panel.addView(scanSpinner)
        panel.addView(text(
            "turbo سریع‌تر است؛ balanced متعادل است؛ thorough محدودهٔ بیشتری را بررسی می‌کند؛ stealth آهسته‌تر است؛ ironclad برای بررسی سخت‌گیرانه‌تر طراحی شده است.",
            11, Color.rgb(159, 171, 197)
        ))
        panel.addView(space(10))

        addLabel("پروفایل استتار")
        val noiseModes = listOf("firewall", "gfw", "off")
        val currentNoise = prefs.getString("obfuscation", "firewall") ?: "firewall"
        val noiseSpinner = spinner(noiseModes, noiseModes.indexOf(currentNoise))
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
        panel.addView(space(10))

        addLabel("درگاه دستی (اختیاری)")
        val endpointInput = EditText(this).apply {
            setSingleLine(true)
            setText(prefs.getString("endpoint", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(111, 123, 149))
            hint = "مثلاً 162.159.198.64:443"
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(23, 29, 48), 12)
        }
        panel.addView(endpointInput)
        panel.addView(text(
            "خالی بگذار تا اسکن خودکار انجام شود. درگاه دستی ممکن است تغییر کند؛ اگر جواب ندهد، تلاش جایگزین دوباره اسکن می‌کند.",
            11, Color.rgb(159, 171, 197)
        ))
        panel.addView(space(6))

        val fragmentCheck = android.widget.CheckBox(this).apply {
            text = "فرگمنت ClientHello (فقط در HTTP/2)"
            textSize = 12f
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(132, 103, 255))
            isChecked = prefs.getBoolean("fragment", false)
        }
        panel.addView(fragmentCheck)
        panel.addView(space(3))

        val fallbackCheck = android.widget.CheckBox(this).apply {
            text = "اگر اسکن شکست خورد، یک بار با اسکن عمیق‌تر تلاش کن"
            textSize = 12f
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(132, 103, 255))
            isChecked = prefs.getBoolean("auto_fallback", true)
        }
        panel.addView(fallbackCheck)
        panel.addView(space(8))
        panel.addView(text(
            "تلاش جایگزین ممکن است چند دقیقه طول بکشد و موفقیت اتصال را تضمین نمی‌کند. تنظیمات فقط روی اتصال بعدی اعمال می‌شوند.",
            11, Color.rgb(159, 171, 197)
        ))
        AlertDialog.Builder(this)
            .setTitle("تنظیمات پیشرفتهٔ MASQUE")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val endpointValue = endpointInput.text?.toString()?.trim().orEmpty()
                if (endpointValue.isNotBlank()) {
                    val endpointPort = endpointValue.substringAfterLast(':', "").toIntOrNull()
                    if (!endpointValue.contains(':') || endpointPort == null || endpointPort !in 1..65535 || endpointValue.any { it.isWhitespace() }) {
                        Toast.makeText(this, "درگاه باید به شکل host:port باشد؛ مثلاً 162.159.198.64:443", Toast.LENGTH_LONG).show()
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
        result.profiles.forEach { candidate ->
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
        profileList.removeAllViews()
        if (profiles.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(22), dp(16), dp(22))
                background = rounded(Color.rgb(17, 23, 38), 18)
            }
            empty.addView(text("هنوز پروفایلی اضافه نشده", 14, Color.WHITE, true))
            empty.addView(text("لینک سرور را بالا وارد کن.", 12, Color.rgb(152, 166, 192)))
            profileList.addView(empty)
            return
        }
        profiles.toList().forEach { profile ->
            val engine = EngineRegistry.forProtocol(profile.protocol)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(20, 28, 45))
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), Color.rgb(43, 54, 76))
                }
                elevation = dp(1).toFloat()
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val mark = TextView(this).apply {
                text = profile.protocol.displayName.take(1)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(75, 64, 134), 13)
                layoutParams = LinearLayout.LayoutParams(dp(41), dp(41))
            }
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            copy.addView(text(profile.label, 15, Color.WHITE, true))
            copy.addView(text(profile.protocol.displayName + " · پروفایل ذخیره‌شده", 11, Color.rgb(135, 218, 184)))
            top.addView(mark)
            top.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val delete = TextView(this).apply {
                text = "×"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(232, 151, 164))
                background = rounded(Color.rgb(55, 35, 50), 12)
                layoutParams = LinearLayout.LayoutParams(dp(39), dp(39))
                isClickable = true
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("حذف پروفایل")
                        .setMessage("پروفایل «" + profile.label + "» از حافظهٔ برنامه حذف شود؟")
                        .setNegativeButton("لغو", null)
                        .setPositiveButton("حذف") { _, _ ->
                            profiles.removeAll { it.id == profile.id || it.rawConfig == profile.rawConfig }
                            saveProfiles()
                            renderProfiles()
                        }
                        .show()
                }
            }
            top.addView(delete)
            card.addView(top)
            card.addView(space(8))
            card.addView(text("موتور: " + engine.name, 11, Color.rgb(152, 166, 192)))
            card.addView(space(10))
            val connectButton = button("اتصال با این پروفایل") { requestConnect(profile) }.apply {
                background = rounded(Color.rgb(81, 65, 164), 13)
                backgroundTintList = null
                minHeight = dp(44)
            }
            card.addView(connectButton)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(10)
            profileList.addView(card, params)
        }
    }
    private fun button(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
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
    private fun text(value:String,size:Int,color:Int,bold:Boolean=false):TextView=TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD); setPadding(0,dp(3),0,dp(3)) }
    private fun space(heightDp:Int):View=View(this).apply { layoutParams=LinearLayout.LayoutParams(1,dp(heightDp)) }
    private fun dp(value:Int):Int=(value*resources.displayMetrics.density).toInt()
    companion object { private const val REQUEST_VPN_PERMISSION=7102; private const val REQUEST_IMPORT_PSIPHON=7103 }
}
