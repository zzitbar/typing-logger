package dev.local.typinglog

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var toggle: Switch

    private lateinit var swFeishu: Switch
    private lateinit var etWebhook: EditText
    private lateinit var etSecret: EditText
    private lateinit var tvPush: TextView

    private var unlocked = false
    private var pausedAt = 0L
    private var lockDialog: AlertDialog? = null
    private var suppressToggle = false

    private val prefs
        get() = getSharedPreferences(Keys.PREFS, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.tvStatus)
        logView = findViewById(R.id.tvLog)
        toggle = findViewById(R.id.swEnabled)
        swFeishu = findViewById(R.id.swFeishu)
        etWebhook = findViewById(R.id.etWebhook)
        etSecret = findViewById(R.id.etSecret)
        tvPush = findViewById(R.id.tvPush)

        toggle.isChecked = prefs.getBoolean(Keys.ENABLED, true)
        toggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Keys.ENABLED, checked).apply()
            toast(if (checked) "已开始记录" else "已暂停记录")
        }

        etWebhook.setText(prefs.getString(Keys.FEISHU_WEBHOOK, ""))
        etSecret.setText(prefs.getString(Keys.FEISHU_SECRET, ""))
        swFeishu.isChecked = prefs.getBoolean(Keys.FEISHU_ENABLED, false)
        swFeishu.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            if (checked && etWebhook.text.toString().trim().isEmpty()) {
                toast("先填好 Webhook，再打开推送")
                suppressToggle = true
                swFeishu.isChecked = false
                suppressToggle = false
                return@setOnCheckedChangeListener
            }
            if (checked) saveFeishu(false)
            prefs.edit().putBoolean(Keys.FEISHU_ENABLED, checked).apply()
        }

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnToday).setOnClickListener { loadToday() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { exportAndShare() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.btnSaveFeishu).setOnClickListener { saveFeishu(true) }
        findViewById<Button>(R.id.btnTestFeishu).setOnClickListener { testPush() }
        findViewById<Button>(R.id.btnPassword).setOnClickListener { changePassword() }
    }

    override fun onStart() {
        super.onStart()
        val away = System.currentTimeMillis() - pausedAt
        if (!unlocked || (pausedAt > 0 && away > RELOCK_MS)) showLock()
    }

    override fun onResume() {
        super.onResume()
        status.text = buildStatus()
        tvPush.text = "推送状态：" + FeishuPusher.lastStatus(this)
    }

    override fun onStop() {
        super.onStop()
        pausedAt = System.currentTimeMillis()
        logView.text = ""
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 密码锁

    private fun showLock() {
        if (lockDialog?.isShowing == true) return
        logView.text = ""

        val input = EditText(this).apply {
            hint = "请输入密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 24, 64, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("数据记录")
            .setMessage("请输入访问密码")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("进入", null)
            .setNegativeButton("退出", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (Passwords.matches(this, input.text.toString())) {
                    unlocked = true
                    pausedAt = 0
                    dialog.dismiss()
                } else {
                    input.setText("")
                    toast("密码错误")
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                finish()
            }
        }

        dialog.show()
        lockDialog = dialog
    }

    private fun changePassword() {
        val oldInput = EditText(this).apply {
            hint = "原密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val newInput = EditText(this).apply {
            hint = "新密码（至少 4 位）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 16, 64, 0)
            addView(oldInput)
            addView(newInput)
        }

        AlertDialog.Builder(this)
            .setTitle("修改密码")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val old = oldInput.text.toString()
                val new = newInput.text.toString()
                if (Passwords.change(this, old, new)) {
                    toast("密码已修改")
                } else {
                    toast("原密码错误，或新密码不足 4 位")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 飞书推送

    private fun saveFeishu(showToast: Boolean): Boolean {
        val url = etWebhook.text.toString().trim()
        val secret = etSecret.text.toString().trim()
        if (url.isNotEmpty() && !url.startsWith("https://")) {
            toast("Webhook 必须以 https:// 开头")
            return false
        }
        prefs.edit()
            .putString(Keys.FEISHU_WEBHOOK, url)
            .putString(Keys.FEISHU_SECRET, secret)
            .apply()
        if (showToast) toast("已保存")
        return true
    }

    private fun testPush() {
        if (!saveFeishu(false)) return
        if (etWebhook.text.toString().trim().isEmpty()) {
            toast("请先填写 Webhook")
            return
        }
        toast("正在推送…")
        FeishuPusher.test(this) { ok, msg ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast(if (ok) "推送成功" else "推送失败：$msg")
                tvPush.text = "推送状态：" + FeishuPusher.lastStatus(this)
            }
        }
    }

    // ------------------------------------------------------------ 记录

    private fun buildStatus(): String {
        val am = getSystemService(AccessibilityManager::class.java)
        val enabled = am
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo?.serviceInfo?.packageName == packageName } == true
        val recording = prefs.getBoolean(Keys.ENABLED, true)
        return buildString {
            append("无障碍服务：")
            append(if (enabled) "已开启 ✓" else "未开启（点下方按钮去开启）")
            append("\n记录开关：")
            append(if (recording) "开" else "关")
            append("\n数据位置：应用私有目录 logs/；开启飞书推送后才会联网发送")
        }
    }

    private fun loadToday() {
        io.execute {
            val text = LogStore.todayPreview(this)
            runOnUiThread { logView.text = text }
        }
    }

    private fun exportAndShare() {
        io.execute {
            try {
                val zip = LogStore.exportZip(this)
                runOnUiThread { share(zip) }
            } catch (e: Exception) {
                runOnUiThread { toast("导出失败：${e.message}") }
            }
        }
    }

    private fun share(file: File) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "导出打字记录"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空全部记录")
            .setMessage("删除本机所有打字记录，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                io.execute {
                    LogStore.clear(this)
                    runOnUiThread {
                        logView.text = ""
                        toast("已清空")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val RELOCK_MS = 60_000L
    }
}
