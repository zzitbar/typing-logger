package dev.local.typinglog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 5 分钟一次的闹钟兜底。
 *
 * 只能做“检查 + 提醒”，不能也不该尝试重新启用无障碍服务：
 * 无障碍服务由系统绑定，应用没有接口能代用户打开；强行停止还会把闹钟一起清掉。
 */
object Watchdog {

    const val ACTION_HEARTBEAT = "dev.local.typinglog.action.HEARTBEAT"

    private const val REQUEST_CODE = 1001
    private const val INTERVAL_MS = 5 * 60 * 1000L

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx)
        val at = System.currentTimeMillis() + INTERVAL_MS

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 没给“闹钟和提醒”权限时退回非精确闹钟，仍会触发，只是可能有延迟
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: Exception) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Exception) {
            }
        }
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQUEST_CODE,
        Intent(ctx, HeartbeatReceiver::class.java).setAction(ACTION_HEARTBEAT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
