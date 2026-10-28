package dev.local.typinglog

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

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
    private val worker = Executors.newSingleThreadExecutor()

    /** key = 包名 + 窗口 id */
    private val pending = HashMap<String, Pending>()
    private val lastCommitted = HashMap<String, Pair<String, Long>>()
    private val labels = HashMap<String, String>()

    private val prefs: SharedPreferences
        get() = getSharedPreferences(PREFS, MODE_PRIVATE)

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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
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
        worker.execute {
            try {
                LogStore.append(this, record)
            } catch (_: Exception) {
                // 存储失败不影响输入
            }
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

    override fun onDestroy() {
        flushAll()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "config"
        private const val KEY_ENABLED = "enabled"
        private const val IDLE_MS = 800L
        private const val SESSION_GAP_MS = 5 * 60 * 1000L
        private const val DEDUPE_MS = 3_000L
    }
}
