package dev.local.typinglog

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 首页：微博热搜榜。
 * 右下角有个几乎看不见的小圆点，点它输入密码才进日志页。
 */
class HotSearchActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var listView: ListView
    private lateinit var tvUpdated: TextView
    private lateinit var btnRefresh: Button

    private val items = ArrayList<HotItem>()
    private val adapter = HotAdapter()

    private var loading = false
    private var lastLoadedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hot)

        listView = findViewById(R.id.lvHot)
        tvUpdated = findViewById(R.id.tvUpdated)
        btnRefresh = findViewById(R.id.btnRefresh)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> copyTitle(position) }

        btnRefresh.setOnClickListener { refresh() }
        findViewById<View>(R.id.btnHidden).setOnClickListener { askPassword() }

        restoreCache()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 放久了回来顺手刷一次
        if (!loading && lastLoadedAt > 0 &&
            System.currentTimeMillis() - lastLoadedAt > 5 * 60_000
        ) {
            refresh()
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 数据

    private fun restoreCache() {
        val cached = HotSearchRepository.loadCache(this) ?: return
        val (list, at) = cached
        if (list.isEmpty()) return
        items.clear()
        items.addAll(list)
        adapter.notifyDataSetChanged()
        tvUpdated.text = "缓存 " + timeText(at)
    }

    private fun refresh() {
        if (loading) return
        loading = true
        btnRefresh.isEnabled = false
        tvUpdated.text = "加载中…"

        io.execute {
            val list = try {
                HotSearchRepository.load()
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                btnRefresh.isEnabled = true

                if (list.isNotEmpty()) {
                    items.clear()
                    items.addAll(list)
                    adapter.notifyDataSetChanged()
                    lastLoadedAt = System.currentTimeMillis()
                    HotSearchRepository.saveCache(this, list)
                    tvUpdated.text = "更新于 " + timeText(lastLoadedAt)
                    listView.setSelection(0)
                } else {
                    tvUpdated.text =
                        if (items.isEmpty()) "加载失败，点刷新重试" else "刷新失败，显示缓存"
                }
            }
        }
    }

    private fun copyTitle(position: Int) {
        val item = items.getOrNull(position) ?: return
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("hot", item.title))
        toast("已复制：" + item.title)
    }

    // ------------------------------------------------------------ 隐藏入口

    private fun askPassword() {
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
            .setTitle("访问日志")
            .setView(box)
            .setPositiveButton("进入", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (Passwords.matches(this@HotSearchActivity, input.text.toString())) {
                    val nonce = Auth.grant()
                    startActivity(
                        Intent(this@HotSearchActivity, LogsActivity::class.java)
                            .putExtra(LogsActivity.EXTRA_AUTH, nonce)
                    )
                    dialog.dismiss()
                } else {
                    input.setText("")
                    toast("密码错误")
                }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------ 列表

    private inner class HotAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_hot, parent, false)
            val item = items[position]

            val rank = view.findViewById<TextView>(R.id.tvRank)
            rank.text = item.rank.toString()
            rank.setTextColor(
                when (item.rank) {
                    1 -> 0xFFE6162D.toInt()
                    2 -> 0xFFFF6A00.toInt()
                    3 -> 0xFFFFA000.toInt()
                    else -> 0xFF999999.toInt()
                }
            )

            view.findViewById<TextView>(R.id.tvTitle).text = item.title
            view.findViewById<TextView>(R.id.tvHot).text = item.hot
            return view
        }
    }

    private fun timeText(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
