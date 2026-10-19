package com.azadlink.vpn.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * UI-preview placeholder. It intentionally does not establish a VPN tunnel yet.
 */
class AzadLinkVpnService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val message = when (action) {
            ACTION_DISCONNECT -> "اتصال فعالی برای قطع‌کردن وجود ندارد"
            ACTION_CONNECT -> "نسخهٔ نمایشی: موتور اتصال هنوز یکپارچه نشده"
            else -> "آمادهٔ اتصال آزمایشی"
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MESSAGE, message).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
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
