package dev.local.typinglog

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
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

    private val prefs
        get() = getSharedPreferences("config", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.tvStatus)
        logView = findViewById(R.id.tvLog)
        toggle = findViewById(R.id.swEnabled)

        toggle.isChecked = prefs.getBoolean("enabled", true)
        toggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("enabled", checked).apply()
            toast(if (checked) "已开始记录" else "已暂停记录")
        }

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnToday).setOnClickListener { loadToday() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { exportAndShare() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        status.text = buildStatus()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun buildStatus(): String {
        val am = getSystemService(AccessibilityManager::class.java)
        val enabled = am
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo?.serviceInfo?.packageName == packageName } == true
        val recording = prefs.getBoolean("enabled", true)
        return buildString {
            append("无障碍服务：")
            append(if (enabled) "已开启 ✓" else "未开启（点下方按钮去开启）")
            append("\n记录开关：")
            append(if (recording) "开" else "关")
            append("\n存放位置：应用私有目录 logs/，不联网、不上传")
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
}
