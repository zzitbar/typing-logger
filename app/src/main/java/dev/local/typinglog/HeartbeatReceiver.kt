package dev.local.typinglog

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 闹钟每 5 分钟醒一次：记心跳、续下一次闹钟，
 * 发现无障碍服务被关了就发一条通知，让用户自己去开。
 */
class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Keys.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(Keys.HEARTBEAT_AT, System.currentTimeMillis()).apply()

        Watchdog.schedule(context)

        // 用户自己关了记录开关，就别再打扰
        if (!prefs.getBoolean(Keys.ENABLED, true)) return
        if (isServiceEnabled(context)) return

        val now = System.currentTimeMillis()
        val lastNotice = prefs.getLong(Keys.WATCHDOG_NOTICE_AT, 0L)
        if (now - lastNotice < NOTICE_GAP_MS) return
        prefs.edit().putLong(Keys.WATCHDOG_NOTICE_AT, now).apply()

        notifyDisabled(context)
    }

    private fun isServiceEnabled(ctx: Context): Boolean {
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == ctx.packageName }
    }

    private fun notifyDisabled(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "记录服务", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val pi = PendingIntent.getActivity(
            ctx,
            0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("记录服务未开启")
            .setContentText("点这里去开启无障碍服务")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            nm.notify(NOTICE_ID, notification)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL = "watchdog"
        private const val NOTICE_ID = 1001
        private const val NOTICE_GAP_MS = 30 * 60 * 1000L
    }
}
