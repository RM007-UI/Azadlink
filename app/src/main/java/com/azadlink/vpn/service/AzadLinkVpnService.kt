package com.azadlink.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.VpnService
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * UI-preview placeholder. It intentionally does not establish a VPN tunnel yet.
 * A foreground-service start must be acknowledged immediately, even in preview builds.
 */
class AzadLinkVpnService : VpnService() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground() shortly after startForegroundService().
        ensureNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val action = intent?.action
        val message = when (action) {
            ACTION_DISCONNECT -> "اتصال فعالی برای قطع‌کردن وجود ندارد"
            ACTION_CONNECT -> "نسخهٔ نمایشی: موتور اتصال هنوز یکپارچه نشده"
            else -> "آمادهٔ اتصال آزمایشی"
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MESSAGE, message).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        return START_NOT_STICKY
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

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("AzadLink")
            .setContentText("در حال آماده‌سازی اتصال آزمایشی")
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
