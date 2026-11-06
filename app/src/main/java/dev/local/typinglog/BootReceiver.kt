package dev.local.typinglog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 重启后把闹钟续上（无障碍服务本身由系统恢复）。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Watchdog.schedule(context)
        }
    }
}
